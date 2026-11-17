package com.hospio.platform.service;

import com.hospio.common.billing.FeeDebitDtos;
import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.platform.client.FeeDebitClient;
import com.hospio.platform.client.PlatformPaystackClient;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.entity.DeviceInvoice;
import com.hospio.platform.entity.DevicePackage;
import com.hospio.platform.entity.DevicePayment;
import com.hospio.platform.entity.FeeCollection;
import com.hospio.platform.repository.DeviceInvoiceRepository;
import com.hospio.platform.repository.DevicePackageRepository;
import com.hospio.platform.repository.DevicePaymentRepository;
import com.hospio.platform.repository.FeeCollectionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Monthly device invoices (docs/plan-device-packages.md): a MONTHLY package's instalments and the data plan. Each is issued on its
 * due day (the install day, then the same day each month), debited from the hotel's Moniepoint/OPay account once a day until paid,
 * or paid by Paystack or by hand. Overdue: a reminder at 3 days, an alert at 7, POS payments paused at 14 until it is paid.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceInvoiceService {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    static final int REMIND_AFTER = 3;
    static final int FLAG_AFTER = 7;
    static final int PAUSE_AFTER = 14;
    static final String ALERT = "DEVICE_INVOICE_OVERDUE";
    static final String WAITING = "Waiting for the provider to confirm the debit";
    static final List<String> OPEN = List.of(DeviceInvoice.DUE, DeviceInvoice.OVERDUE);
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);

    private final DeviceInvoiceRepository invoices;
    private final DevicePackageRepository packages;
    private final DevicePaymentRepository payments;
    private final DevicePackageService devices;
    private final FeeCollectionRepository collections;
    private final FeeDebitClient debits;
    private final PlatformPaystackClient paystack;
    private final TenantClient tenant;
    private final DeliveryService deliveries;
    private final AlertService alerts;
    private final TransactionTemplate tx;

    public record InvoiceView(String id, String packageId, String businessId, String hotelId, String hotelName, String number, String kind,
                              int sequence, Integer ofCount, BigDecimal amount, LocalDate dueOn, String status, BigDecimal paidAmount, Instant paidAt,
                              String paidVia, String paidReference, String lastError, int daysOverdue, boolean posPaused) {
    }

    public record RunResult(int issued, int paid, int failed, int overdue, int reminded, int flagged, int paused, int voided) {
    }

    // ---- the daily run ---------------------------------------------------------------------------------

    /** Issues what is due, debits what is open, then moves the overdue along. Runs as the system (07:00 daily, or by a Super admin). */
    public RunResult run(LocalDate today) {
        return TenantContext.runWith(Tenant.system("device-invoices"), () -> {
            int[] issued = issue(today);
            int[] collected = collect(today);
            int[] late = overdue(today);
            log.info("Device invoices {}: {} issued, {} voided, {} paid, {} failed, {} overdue, {} reminded, {} flagged, {} paused", today,
                    issued[0], issued[1], collected[0], collected[1], late[0], late[1], late[2], late[3]);
            return new RunResult(issued[0], collected[0], collected[1], late[0], late[1], late[2], late[3], issued[1]);
        });
    }

    /** [issued, voided]: the instalments and data-plan months due by today; a paid-off package's open instalments are voided. */
    int[] issue(LocalDate today) {
        int issued = 0;
        int voided = 0;
        for (DevicePackage p : packages.findByStatusInOrderByUpdatedAtDesc(List.of(DevicePackage.INSTALLED, DevicePackage.PAID_OFF))) {
            if (p.getInstalledAt() == null) {
                continue;
            }
            try {
                if (DevicePackage.MONTHLY.equals(p.getPaymentOption()) && p.getMonths() != null) {
                    if (DevicePackage.PAID_OFF.equals(p.getStatus())) {
                        voided += voidOpenInstalments(p);
                    } else {
                        for (int k = 1; k <= p.getMonths(); k++) {
                            LocalDate due = p.getInstalledAt().plusMonths(k - 1);
                            if (due.isAfter(today)) {
                                break;
                            }
                            if (create(p, DeviceInvoice.INSTALMENT, k, p.getMonths(), instalment(p, k), due)) {
                                issued++;
                            }
                        }
                    }
                }
                if (p.getDataPlanMonthly().signum() > 0) {
                    for (int m = 0; ; m++) {
                        LocalDate due = p.getInstalledAt().plusMonths(m);
                        if (due.isAfter(today)) {
                            break;
                        }
                        if (create(p, DeviceInvoice.DATA_PLAN, m + 1, null, p.getDataPlanMonthly(), due)) {
                            issued++;
                        }
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Device invoices for package {} not issued: {}", p.getId(), e.getMessage());
            }
        }
        return new int[] {issued, voided};
    }

    /** Instalment {@code k} of the package: the total split evenly (kobo rounded up), the last taking what is left. */
    static BigDecimal instalment(DevicePackage p, int k) {
        BigDecimal each = p.getTotal().divide(BigDecimal.valueOf(p.getMonths()), 2, RoundingMode.UP);
        if (k < p.getMonths()) {
            return each;
        }
        return Money.of(p.getTotal().subtract(each.multiply(BigDecimal.valueOf(p.getMonths() - 1L))).max(BigDecimal.ZERO));
    }

    private boolean create(DevicePackage p, String kind, int sequence, Integer ofCount, BigDecimal planned, LocalDate due) {
        if (invoices.existsByPackageIdAndKindAndSequence(p.getId(), kind, sequence)) {
            return false;
        }
        BigDecimal amount = planned;
        if (DeviceInvoice.INSTALMENT.equals(kind)) {
            // Never ask for more than the package has left once the other open instalments are counted (payments by hand count).
            BigDecimal open = invoices.findByHotelIdAndStatusIn(p.getHotelId(), OPEN).stream()
                    .filter(i -> i.getPackageId().equals(p.getId()) && DeviceInvoice.INSTALMENT.equals(i.getKind()))
                    .map(i -> i.getAmount().subtract(i.getPaidAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
            amount = planned.min(p.remaining().subtract(open)).max(BigDecimal.ZERO);
            if (amount.signum() <= 0) {
                return false;
            }
        }
        BigDecimal fAmount = Money.of(amount);
        DeviceInvoice saved = TenantContext.runWith(Tenant.systemFor(p.getBusinessId(), p.getHotelId(), null, "device-invoices"), () -> tx.execute(st -> {
            String prefix = "DEV-" + due.getYear() + String.format("%02d", due.getMonthValue()) + "-";
            String number = prefix + String.format("%04d", invoices.countByNumberPrefix(prefix) + 1);
            return invoices.save(DeviceInvoice.builder().businessId(p.getBusinessId()).hotelId(p.getHotelId()).packageId(p.getId())
                    .hotelName(p.getHotelName()).number(number).kind(kind).sequence(sequence).ofCount(ofCount).amount(fAmount).dueOn(due)
                    .status(DeviceInvoice.DUE).build());
        }));
        notifyOwner(saved, "Hospio360 · device invoice " + saved.getNumber(), "Here is a device invoice for " + nz(saved.getHotelName()) + ".\n\n"
                + describe(saved) + ": NGN " + naira(saved.getAmount()) + ", due " + DAY.format(saved.getDueOn()) + ".\n\n"
                + "It is debited from the hotel's Moniepoint or OPay account. If that doesn't go through, pay it online under Hospio fees → Devices.");
        return true;
    }

    private int voidOpenInstalments(DevicePackage p) {
        int n = 0;
        for (DeviceInvoice i : invoices.findByHotelIdAndStatusIn(p.getHotelId(), OPEN)) {
            if (i.getPackageId().equals(p.getId()) && DeviceInvoice.INSTALMENT.equals(i.getKind()) && i.getPaidAmount().signum() == 0) {
                save(i, x -> {
                    x.setStatus(DeviceInvoice.VOID);
                    x.setLastError("The devices were paid off another way");
                });
                n++;
            }
        }
        return n;
    }

    /** [paid, failed]: each open invoice due by today is debited once a day (a pending debit is asked about instead). */
    int[] collect(LocalDate today) {
        int paid = 0;
        int failed = 0;
        for (DeviceInvoice i : invoices.findByStatusInAndDueOnLessThanEqualOrderByDueOnAsc(OPEN, today)) {
            try {
                String after = debit(i, today);
                if ("PAID".equals(after)) {
                    paid++;
                } else if ("FAILED".equals(after)) {
                    failed++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Device invoice {} not debited: {}", i.getNumber(), e.getMessage());
            }
        }
        return new int[] {paid, failed};
    }

    /** One invoice's debit step; PAID, FAILED, PENDING or null when nothing was tried (debit off, already tried today). */
    String debit(DeviceInvoice i, LocalDate today) {
        FeeCollection c = collections.findByHotelId(i.getHotelId()).orElse(null);
        if (c == null || !c.isDebitEnabled() || c.getProvider() == null) {
            return null;
        }
        FeeDebitDtos.DebitResult r;
        if (i.getDebitRef() != null && WAITING.equals(i.getLastError())) {
            r = debits.status(i.getHotelId(), c.getProvider(), i.getDebitRef());
        } else {
            if (today.equals(i.getLastDebitOn())) {
                return null;
            }
            String ref = "DV-" + i.getNumber().replace("DEV-", "") + "-" + (i.getDebitAttempts() + 1);
            BigDecimal amount = Money.of(i.getAmount().subtract(i.getPaidAmount()));
            save(i, x -> {
                x.setDebitAttempts(x.getDebitAttempts() + 1);
                x.setLastDebitOn(today);
                x.setDebitRef(ref);
            });
            r = debits.debit(i.getHotelId(), c.getProvider(), amount, ref, "Hospio360 " + describe(i));
        }
        switch (r.status()) {
            case "APPROVED" -> {
                BigDecimal got = r.collected() == null || r.collected().signum() <= 0 ? i.getAmount().subtract(i.getPaidAmount()) : r.collected();
                pay(i.getId(), "DEBIT", i.getDebitRef(), got, null);
                return "PAID";
            }
            case "FAILED" -> {
                save(i, x -> x.setLastError(r.message() == null ? "The debit didn't go through" : r.message()));
                return "FAILED";
            }
            default -> {
                save(i, x -> x.setLastError(WAITING));
                return "PENDING";
            }
        }
    }

    /** [overdue, reminded, flagged, paused]. */
    int[] overdue(LocalDate today) {
        int overdue = 0;
        int reminded = 0;
        int flagged = 0;
        int paused = 0;
        for (DeviceInvoice i : invoices.findByStatusInAndDueOnLessThanEqualOrderByDueOnAsc(OPEN, today.minusDays(1))) {
            long days = ChronoUnit.DAYS.between(i.getDueOn(), today);
            if (DeviceInvoice.DUE.equals(i.getStatus())) {
                save(i, x -> x.setStatus(DeviceInvoice.OVERDUE));
                overdue++;
            }
            if (days >= REMIND_AFTER && i.getRemindedAt() == null) {
                notifyOwner(i, "Hospio360 · device invoice " + i.getNumber() + " is overdue", describe(i) + " for " + nz(i.getHotelName())
                        + " (NGN " + naira(i.getAmount().subtract(i.getPaidAmount())) + ") was due on " + DAY.format(i.getDueOn())
                        + ". Pay it online under Hospio fees → Devices, or make sure the hotel's Moniepoint/OPay account can be debited. POS payments pause "
                        + PAUSE_AFTER + " days after the due date.");
                save(i, x -> x.setRemindedAt(Instant.now()));
                reminded++;
            }
            if (days >= FLAG_AFTER && i.getFlaggedAt() == null) {
                TenantContext.runWith(Tenant.system("device-invoices"), () -> alerts.raise(ALERT, i.getBusinessId(), i.getHotelId(),
                        "Device invoice overdue · " + nz(i.getHotelName()), i.getNumber() + " (" + describe(i) + ", NGN " + naira(i.getAmount())
                                + ") has been overdue since " + DAY.format(i.getDueOn()), "danger"));
                save(i, x -> x.setFlaggedAt(Instant.now()));
                flagged++;
            }
            if (days >= PAUSE_AFTER && i.getPausedAt() == null) {
                pausePos(i.getBusinessId(), i.getHotelId(), true);
                save(i, x -> x.setPausedAt(Instant.now()));
                paused++;
            }
        }
        return new int[] {overdue, reminded, flagged, paused};
    }

    // ---- paying -----------------------------------------------------------------------------------------

    /**
     * Marks an invoice paid (debit, Paystack or by hand), once: an instalment is credited to its package, and the hotel's POS pause
     * and alert are lifted when nothing it owes is still past the limits.
     */
    InvoiceView pay(String invoiceId, String via, String reference, BigDecimal amount, String recordedBy) {
        DeviceInvoice done = TenantContext.runWith(Tenant.system("device-invoices"), () -> tx.execute(st -> {
            DeviceInvoice i = invoices.lockById(invoiceId).orElseThrow(() -> new ResourceNotFoundException("Device invoice not found"));
            if (!i.open()) {
                return i;
            }
            BigDecimal owed = i.getAmount().subtract(i.getPaidAmount());
            BigDecimal got = Money.of(amount == null ? owed : amount.min(owed));
            i.setPaidAmount(Money.of(i.getPaidAmount().add(got)));
            i.setPaidVia(via);
            i.setPaidReference(reference);
            i.setLastError(null);
            if (i.getPaidAmount().compareTo(i.getAmount()) >= 0) {
                i.setStatus(DeviceInvoice.PAID);
                i.setPaidAt(Instant.now());
            }
            invoices.save(i);
            if (DeviceInvoice.INSTALMENT.equals(i.getKind()) && got.signum() > 0) {
                DevicePackage p = packages.lockById(i.getPackageId()).orElse(null);
                String ref = i.getNumber() + (reference == null ? "" : ":" + reference);
                if (p != null && !payments.existsByPackageIdAndSourceAndReference(p.getId(), DevicePayment.INVOICE, ref) && p.remaining().signum() > 0) {
                    BigDecimal credit = Money.of(got.min(p.remaining()));
                    payments.save(DevicePayment.builder().businessId(p.getBusinessId()).hotelId(p.getHotelId()).packageId(p.getId())
                            .source(DevicePayment.INVOICE).amount(credit).reference(ref).invoiceId(i.getId()).paidOn(LocalDate.now(LAGOS))
                            .note(describe(i) + " · " + via.toLowerCase(Locale.ROOT)).recordedByName(recordedBy).build());
                    devices.credit(p, credit);
                }
            }
            return i;
        }));
        if (DeviceInvoice.PAID.equals(done.getStatus())) {
            lift(done.getBusinessId(), done.getHotelId());
            log.info("Device invoice {} paid via {}", done.getNumber(), via);
        }
        return view(done);
    }

    /** Lifts the POS pause and the alert when the hotel has no open invoice past those limits. */
    private void lift(String businessId, String hotelId) {
        LocalDate today = LocalDate.now(LAGOS);
        List<DeviceInvoice> open = invoices.findByHotelIdAndStatusIn(hotelId, OPEN);
        boolean stillPaused = open.stream().anyMatch(i -> ChronoUnit.DAYS.between(i.getDueOn(), today) >= PAUSE_AFTER);
        boolean stillFlagged = open.stream().anyMatch(i -> ChronoUnit.DAYS.between(i.getDueOn(), today) >= FLAG_AFTER);
        if (!stillPaused) {
            pausePos(businessId, hotelId, false);
        }
        if (!stillFlagged) {
            TenantContext.runWith(Tenant.system("device-invoices"), () -> {
                alerts.clear(ALERT, businessId);
                return null;
            });
        }
    }

    /**
     * The device pause, kept apart from the daily fee's: pausing always pauses POS; lifting resumes POS only when the daily fee
     * hasn't paused it too.
     */
    void pausePos(String businessId, String hotelId, boolean paused) {
        FeeCollection c = TenantContext.runWith(Tenant.system("device-invoices"), () -> tx.execute(st -> {
            FeeCollection x = collections.findByHotelId(hotelId).orElseGet(() -> FeeCollection.builder().businessId(businessId).hotelId(hotelId).build());
            if (x.isDevicePosPaused() == paused) {
                return null;
            }
            x.setDevicePosPaused(paused);
            return collections.save(x);
        }));
        if (c == null) {
            return;
        }
        if (paused || !c.isPosPaused()) {
            debits.pausePos(businessId, hotelId, paused);
        }
        log.info("POS payments {} for hotel {} (device invoice)", paused ? "paused" : "resumed", hotelId);
    }

    /** An admin marks an invoice paid (cash or transfer received). */
    public InvoiceView markPaid(String invoiceId, String reference, String note) {
        if (!StringUtils.hasText(reference)) {
            throw new BadRequestException("Enter the payment reference (bank reference or receipt number)");
        }
        DeviceInvoice i = invoices.findById(invoiceId).orElseThrow(() -> new ResourceNotFoundException("Device invoice not found"));
        if (!i.open()) {
            throw new BadRequestException("This invoice is " + i.getStatus().toLowerCase(Locale.ROOT) + ": nothing is owed on it");
        }
        HospioUserDetails user = CurrentUser.require();
        return pay(invoiceId, "MANUAL", reference.trim(), null, user.getFullName());
    }

    /** The owner or manager pays online: a Paystack checkout for what is still owed; they come back to {@code callbackUrl}. */
    public PlatformPaystackClient.Checkout payOnline(String invoiceId, String callbackUrl) {
        HospioUserDetails user = CurrentUser.require();
        DeviceInvoice i = invoices.findById(invoiceId).orElseThrow(() -> new ResourceNotFoundException("Device invoice not found"));
        if (!user.isPlatform() && (!i.getBusinessId().equals(user.getBusinessId())
                || (!user.has(com.hospio.common.enums.Role.OWNER) && !user.getHotelIds().contains(i.getHotelId())))) {
            throw new ResourceNotFoundException("Device invoice not found");
        }
        if (!i.open()) {
            throw new BadRequestException("This invoice is already " + i.getStatus().toLowerCase(Locale.ROOT));
        }
        String reference = i.getNumber() + "-" + (System.currentTimeMillis() / 1000);
        PlatformPaystackClient.Checkout c = paystack.initialize(user.getEmail(), i.getAmount().subtract(i.getPaidAmount()), reference, callbackUrl);
        save(i, x -> x.setPaystackRef(c.reference()));
        return c;
    }

    /** Back from Paystack (or its webhook): the payment is checked with Paystack and the invoice settled once. */
    public InvoiceView confirm(String reference) {
        if (!StringUtils.hasText(reference) || !reference.startsWith("DEV-")) {
            throw new BadRequestException("That isn't a device invoice payment");
        }
        DeviceInvoice i = TenantContext.runWith(Tenant.system("device-invoices"), () -> invoices.findByPaystackRef(reference).or(() -> {
            // An older checkout (a second tab): the invoice number is the reference without its time.
            String number = reference.substring(0, reference.lastIndexOf('-'));
            return invoices.findAll().stream().filter(x -> x.getNumber().equals(number)).findFirst();
        }).orElseThrow(() -> new ResourceNotFoundException("Device invoice not found")));
        if (!i.open()) {
            return view(i);
        }
        PlatformPaystackClient.Verified v = paystack.verify(reference);
        if (!v.success()) {
            throw new BadRequestException("Paystack hasn't confirmed this payment" + (v.message() == null ? "" : ": " + v.message()));
        }
        return pay(i.getId(), "PAYSTACK", reference, v.amount(), null);
    }

    // ---- reading ----------------------------------------------------------------------------------------

    public List<InvoiceView> forPackage(String packageId) {
        return invoices.findByPackageIdOrderByDueOnDescKindAsc(packageId).stream().map(this::view).toList();
    }

    /** The invoices of the given packages (the owner's business, a manager's hotel), newest first. */
    public List<InvoiceView> forPackages(List<String> packageIds) {
        List<InvoiceView> out = new ArrayList<>();
        packageIds.forEach(id -> out.addAll(forPackage(id)));
        out.sort((a, b) -> b.dueOn().compareTo(a.dueOn()));
        return out;
    }

    public List<InvoiceView> all(List<String> statuses) {
        List<String> s = statuses == null || statuses.isEmpty() ? List.of(DeviceInvoice.DUE, DeviceInvoice.OVERDUE, DeviceInvoice.PAID) : statuses;
        return invoices.findByStatusInAndDueOnLessThanEqualOrderByDueOnAsc(s, LocalDate.now(LAGOS).plusYears(1)).stream().map(this::view).toList();
    }

    InvoiceView view(DeviceInvoice i) {
        int days = i.open() ? (int) Math.max(0, ChronoUnit.DAYS.between(i.getDueOn(), LocalDate.now(LAGOS))) : 0;
        return new InvoiceView(i.getId(), i.getPackageId(), i.getBusinessId(), i.getHotelId(), i.getHotelName(), i.getNumber(), i.getKind(),
                i.getSequence(), i.getOfCount(), i.getAmount(), i.getDueOn(), i.getStatus(), i.getPaidAmount(), i.getPaidAt(), i.getPaidVia(),
                i.getPaidReference(), WAITING.equals(i.getLastError()) ? null : i.getLastError(), days, i.getPausedAt() != null && i.open());
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    static String describe(DeviceInvoice i) {
        return DeviceInvoice.DATA_PLAN.equals(i.getKind()) ? "Data plan for " + DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK).format(i.getDueOn())
                : "Devices instalment " + i.getSequence() + (i.getOfCount() == null ? "" : " of " + i.getOfCount());
    }

    private void notifyOwner(DeviceInvoice i, String subject, String body) {
        try {
            TenantClient.Business b = tenant.business(i.getBusinessId()).orElse(null);
            if (b == null || !StringUtils.hasText(b.ownerEmail())) {
                return;
            }
            deliveries.send(Delivery.email(b.ownerEmail(), subject, "Hello " + (StringUtils.hasText(b.ownerName()) ? b.ownerName() : "there") + ",\n\n"
                    + body + "\n\nHospio360\n", Delivery.Kind.PAYMENT_REMINDER, i.getId() + ":" + subject.hashCode()));
        } catch (RuntimeException e) {
            log.warn("Device invoice {} email not sent: {}", i.getNumber(), e.getMessage());
        }
    }

    private void save(DeviceInvoice i, java.util.function.Consumer<DeviceInvoice> change) {
        TenantContext.runWith(Tenant.system("device-invoices"), () -> tx.executeWithoutResult(st -> {
            DeviceInvoice fresh = invoices.findById(i.getId()).orElse(i);
            change.accept(fresh);
            invoices.save(fresh);
            change.accept(i);
        }));
    }

    static String naira(BigDecimal v) {
        java.text.NumberFormat n = java.text.NumberFormat.getNumberInstance(Locale.UK);
        n.setMinimumFractionDigits(2);
        n.setMaximumFractionDigits(2);
        return n.format(v);
    }

    private static String nz(String s) {
        return s == null ? "the hotel" : s;
    }
}
