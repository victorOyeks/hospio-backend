package com.hospio.platform.service;

import com.hospio.common.billing.BillingHold;
import com.hospio.common.billing.MessageBillingDtos;
import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.platform.client.FeeDebitClient;
import com.hospio.platform.client.MessageBillingClient;
import com.hospio.platform.client.PlatformPaystackClient;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.entity.DailyFee;
import com.hospio.platform.entity.DeviceInvoice;
import com.hospio.platform.entity.FeeCollection;
import com.hospio.platform.entity.MonthlyInvoice;
import com.hospio.platform.entity.MonthlyInvoiceEvent;
import com.hospio.platform.entity.MonthlyInvoiceLine;
import com.hospio.platform.entity.MonthlyInvoicePayment;
import com.hospio.platform.repository.DailyFeeRepository;
import com.hospio.platform.repository.DeviceInvoiceRepository;
import com.hospio.platform.repository.FeeCollectionRepository;
import com.hospio.platform.repository.MonthlyInvoiceEventRepository;
import com.hospio.platform.repository.MonthlyInvoiceLineRepository;
import com.hospio.platform.repository.MonthlyInvoicePaymentRepository;
import com.hospio.platform.repository.MonthlyInvoiceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Hospio360's monthly invoice (docs/plan-monthly-invoices.md): one per business per month, issued on the 1st at 07:00 Lagos for the
 * month before. It bills the platform fee per location (the daily fees, day by day), the guest-message invoices messaging-service made
 * at 00:30, the device schedule items due in the month and the from-sales device share, plus what earlier months carried in (a total
 * under the minimum, or a credit). Due 7 days after issue, 3 days' grace; then a soft restriction (POS push, new sales, new bookings)
 * until it is paid through Hospio360's Paystack link or recorded by an admin.
 *
 * <p>Timeline (issued on the 1st): reminders on the 6th and 7th, "due today" on the 8th, overdue on the 9th and 10th, the final warning
 * on the 11th, the restriction from 06:00 on the 12th. A one-time extension moves the last day; the final warning goes on it. Every notice
 * is an email to the owner and a bell notice to owners and managers, logged once per kind and day.
 *
 * <p>On staging ({@code hospio.billing.qa-only=true}) only QA businesses are invoiced at all (name starting "QA", an owner at
 * {@code @hospio-qa.test}, or an id in {@code hospio.billing.qa-business-ids}).
 */
@Slf4j
@Service
public class MonthlyInvoiceService {

    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    public static final int DUE_DAYS = 7;
    public static final int GRACE_DAYS = 3;
    /** The restriction starts at this time the morning after the last day. */
    public static final LocalTime RESTRICT_AT = LocalTime.of(6, 0);
    static final Set<String> STAGES = Set.of("LIVE", "AT_RISK");
    static final List<String> OPEN = List.of(MonthlyInvoice.DUE, MonthlyInvoice.OVERDUE);
    static final List<String> BILLED_DAY_STATUSES = List.of(DailyFee.ACCRUED, DailyFee.SHADOW, DailyFee.NO_FEE, DailyFee.SCHEDULED);
    static final List<String> OFFLINE_VIA = List.of("BANK_TRANSFER", "CASH", "CHEQUE", "OTHER");
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.UK);
    static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMMM", Locale.UK);
    static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK);
    static final String NOTICE_KIND = "HOSPIO_INVOICE";
    public static final String COMPANY_LINE = "Oyeks Global Ltd · RC 10001123 · Lagos, Nigeria · hello@oyeksglobal.com";

    private final MonthlyInvoiceRepository invoices;
    private final MonthlyInvoiceLineRepository lines;
    private final MonthlyInvoicePaymentRepository payments;
    private final MonthlyInvoiceEventRepository events;
    private final DailyFeeRepository fees;
    private final DeviceInvoiceRepository deviceItems;
    private final DeviceInvoiceService deviceInvoices;
    private final DevicePackageService devices;
    private final FeeCollectionRepository collections;
    private final FeeDebitClient pos;
    private final MessageBillingClient messages;
    private final TenantClient tenant;
    private final PlatformSettingsService settings;
    private final PlatformPaystackClient paystack;
    private final DeliveryService deliveries;
    private final ObjectProvider<EventPublisher> publisher;
    private final CommissionService commission;
    private final BillingHold holds;
    private final TransactionTemplate tx;
    private final String hotelAppUrl;
    private final boolean qaOnly;
    private final Set<String> qaBusinessIds;

    public MonthlyInvoiceService(MonthlyInvoiceRepository invoices, MonthlyInvoiceLineRepository lines, MonthlyInvoicePaymentRepository payments,
                                 MonthlyInvoiceEventRepository events, DailyFeeRepository fees, DeviceInvoiceRepository deviceItems,
                                 DeviceInvoiceService deviceInvoices, DevicePackageService devices, FeeCollectionRepository collections, FeeDebitClient pos,
                                 MessageBillingClient messages, TenantClient tenant, PlatformSettingsService settings, PlatformPaystackClient paystack,
                                 DeliveryService deliveries, ObjectProvider<EventPublisher> publisher, CommissionService commission, BillingHold holds,
                                 TransactionTemplate tx, @Value("${hospio.apps.hotel-url:https://web-staging.hospio360.com}") String hotelAppUrl,
                                 @Value("${hospio.billing.qa-only:false}") boolean qaOnly,
                                 @Value("${hospio.billing.qa-business-ids:}") String qaBusinessIds) {
        this.invoices = invoices;
        this.lines = lines;
        this.payments = payments;
        this.events = events;
        this.fees = fees;
        this.deviceItems = deviceItems;
        this.deviceInvoices = deviceInvoices;
        this.devices = devices;
        this.collections = collections;
        this.pos = pos;
        this.messages = messages;
        this.tenant = tenant;
        this.settings = settings;
        this.paystack = paystack;
        this.deliveries = deliveries;
        this.publisher = publisher;
        this.commission = commission;
        this.holds = holds;
        this.tx = tx;
        this.hotelAppUrl = hotelAppUrl.replaceAll("/+$", "");
        this.qaOnly = qaOnly;
        this.qaBusinessIds = Arrays.stream(qaBusinessIds == null ? new String[0] : qaBusinessIds.split(","))
                .map(String::trim).filter(StringUtils::hasText).collect(Collectors.toSet());
    }

    // ---- views ------------------------------------------------------------------------------------------

    public record LineView(String id, String section, String hotelId, String hotelName, String label, String detail, BigDecimal baseAmount,
                           BigDecimal rate, BigDecimal amount, String sourceType, String sourceId, String createdByName, Instant createdAt) {
    }

    public record PaymentView(String id, String receiptNumber, BigDecimal amount, String via, String reference, LocalDate paidOn,
                              String recordedByName, String note, Instant createdAt) {
    }

    public record EventView(String id, String kind, LocalDate onDate, String recipient, String outcome, String note, String actorName,
                            Instant createdAt) {
    }

    public record SectionTotals(BigDecimal platform, BigDecimal messages, BigDecimal devices, BigDecimal carriedIn, BigDecimal adjustments) {
    }

    /**
     * {@code displayStatus}: DUE, DUE_SOON (the 3 days up to the due date), OVERDUE, RESTRICTED, PAID, CARRIED_FORWARD, VOID.
     * {@code restrictFrom}: when the restriction starts if it is still unpaid (06:00 the morning after {@code lastDay}).
     */
    public record InvoiceView(String id, String number, String businessId, String businessName, String month, String monthLabel,
                              LocalDate issuedOn, LocalDate dueOn, LocalDate graceUntil, LocalDate lastDay, Instant restrictFrom, String status,
                              String displayStatus, SectionTotals sections, BigDecimal vatPct, BigDecimal vatAmount, BigDecimal total,
                              BigDecimal paidAmount, BigDecimal outstanding, Instant paidAt, String paidVia, LocalDate extendedUntil,
                              String extensionReason, String extendedByName, boolean restricted, Instant restrictedAt, String carriedIntoNumber,
                              String voidReason, String reissueOfNumber, String replacedByNumber, boolean canPay, List<LineView> lines,
                              List<PaymentView> payments, List<EventView> events) {
    }

    public record InvoiceRow(String id, String number, String businessId, String businessName, String month, String monthLabel, LocalDate issuedOn,
                             LocalDate dueOn, LocalDate lastDay, String status, String displayStatus, SectionTotals sections, BigDecimal total,
                             BigDecimal paidAmount, BigDecimal outstanding, boolean restricted, LocalDate extendedUntil) {
    }

    public record InvoiceList(List<InvoiceRow> invoices, Map<String, Long> counts, Map<String, BigDecimal> totals) {
    }

    /**
     * The banner in the hotel app. {@code state}: NONE, DUE, DUE_SOON, DUE_TODAY, OVERDUE, FINAL_WARNING, RESTRICTED. Staff below manager
     * only get RESTRICTED (with its message), never amounts.
     */
    public record BillingStatus(String state, String invoiceId, String number, String monthLabel, BigDecimal outstanding, LocalDate dueOn,
                                LocalDate lastDay, Instant restrictFrom, boolean restricted, boolean canPay, String title, String message) {
        static final BillingStatus NONE = new BillingStatus("NONE", null, null, null, null, null, null, null, false, false, null, null);
    }

    // ---- issuing ----------------------------------------------------------------------------------------

    public record IssueRun(String month, int issued, int carriedForward, int skipped, List<String> notes) {
    }

    /** Every LIVE / AT_RISK business (QA ones only on staging): its invoice for {@code month}, issued {@code issuedOn}. */
    public IssueRun issueAll(YearMonth month, LocalDate issuedOn) {
        return asSystem(() -> {
            int issued = 0;
            int carried = 0;
            int skipped = 0;
            List<String> notes = new ArrayList<>();
            for (String stage : STAGES) {
                for (TenantClient.Business row : tenant.businesses(stage, null)) {
                    if (!billable(row)) {
                        skipped++;
                        continue;
                    }
                    try {
                        TenantClient.Business b = tenant.business(row.id()).orElse(row);
                        MonthlyInvoice inv = issue(b, month, issuedOn, null);
                        if (inv == null) {
                            skipped++;
                        } else if (MonthlyInvoice.CARRIED_FORWARD.equals(inv.getStatus())) {
                            carried++;
                        } else {
                            issued++;
                        }
                    } catch (RuntimeException e) {
                        skipped++;
                        notes.add(row.name() + ": not issued (" + e.getMessage() + ")");
                        log.error("Monthly invoice {} for {} ({}) not issued: {}", month, row.name(), row.id(), e.getMessage(), e);
                    }
                }
            }
            log.info("Monthly invoices {}: {} issued, {} carried forward, {} skipped {}", month, issued, carried, skipped, notes);
            return new IssueRun(month.toString(), issued, carried, skipped, notes);
        });
    }

    /** One business (an admin's run, QA, or a reissue). Refused on staging for a business that isn't QA. */
    public InvoiceView issueOne(String businessId, YearMonth month, LocalDate issuedOn) {
        return asSystem(() -> {
            TenantClient.Business b = tenant.business(businessId).orElseThrow(() -> new ResourceNotFoundException("Business not found"));
            if (!billable(b)) {
                throw new BadRequestException("Staging only invoices QA businesses (hospio.billing.qa-only): " + b.name() + " isn't one");
            }
            MonthlyInvoice inv = issue(b, month, issuedOn, null);
            if (inv == null) {
                throw new BadRequestException("Nothing to invoice for " + b.name() + " in " + MONTH.format(month)
                        + " (already invoiced, or no charges)");
            }
            return view(inv, true);
        });
    }

    /** The staging guard: on staging only QA businesses are billed. Production bills everyone. */
    boolean billable(TenantClient.Business b) {
        if (!qaOnly) {
            return true;
        }
        return qaBusinessIds.contains(b.id()) || qaName(b.name()) || (b.ownerEmail() != null && b.ownerEmail().toLowerCase(Locale.ROOT).endsWith("@hospio-qa.test"));
    }

    static boolean qaName(String name) {
        return name != null && name.trim().toUpperCase(Locale.ROOT).startsWith("QA");
    }

    private boolean billable(MonthlyInvoice i) {
        return !qaOnly || qaBusinessIds.contains(i.getBusinessId()) || qaName(i.getBusinessName());
    }

    /**
     * The business's invoice for {@code month}; null when it already has one or there is nothing to bill. Reads messaging first (over HTTP,
     * outside the transaction; a failure stops the invoice so it is never issued without its message charges), then builds and saves it
     * in one transaction, then links the message invoices and tells the owner.
     */
    MonthlyInvoice issue(TenantClient.Business b, YearMonth month, LocalDate issuedOn, String reissueOfId) {
        if (invoices.findFirstByBusinessIdAndMonthAndStatusNot(b.id(), month.toString(), MonthlyInvoice.VOID).isPresent()) {
            return null;
        }
        List<MessageBillingDtos.ToBill> msgs = messages.toBill(b.id(), month.toString());
        Map<String, String> hotelNames = new LinkedHashMap<>();
        b.hotels().forEach(h -> hotelNames.put(h.id(), h.name()));
        BigDecimal vatPct = nz(settings.fees().invoiceVatPct());
        BigDecimal minimum = nz(settings.fees().invoiceMinimum());
        LocalDate monthEnd = month.atEndOfMonth();
        MonthlyInvoice made = tx.execute(st -> {
            invoices.lock("monthly-invoice:" + b.id());
            if (invoices.findFirstByBusinessIdAndMonthAndStatusNot(b.id(), month.toString(), MonthlyInvoice.VOID).isPresent()) {
                return null;
            }
            List<MonthlyInvoiceLine> out = new ArrayList<>();
            int[] order = {0};
            // Platform fee and the from-sales device share, per location.
            List<DailyFee> days = fees.findByBusinessIdAndMonthlyInvoiceIdIsNullAndStatusInAndFeeDateLessThanEqualOrderByHotelNameAscFeeDateAsc(b.id(),
                    BILLED_DAY_STATUSES, monthEnd);
            Map<String, List<DailyFee>> byHotel = new LinkedHashMap<>();
            days.forEach(d -> byHotel.computeIfAbsent(d.getHotelId(), k -> new ArrayList<>()).add(d));
            List<MonthlyInvoiceLine> deviceShare = new ArrayList<>();
            for (Map.Entry<String, List<DailyFee>> e : byHotel.entrySet()) {
                List<DailyFee> ds = e.getValue();
                String hotelName = Objects.requireNonNullElse(ds.get(0).getHotelName(), hotelNames.getOrDefault(e.getKey(), "Location"));
                BigDecimal sales = Money.of(ds.stream().map(DailyFee::getSalesTotal).reduce(BigDecimal.ZERO, BigDecimal::add));
                BigDecimal fee = Money.of(ds.stream().map(DailyFee::getFee).reduce(BigDecimal.ZERO, BigDecimal::add));
                Set<BigDecimal> rates = ds.stream().map(d -> d.getRate().stripTrailingZeros()).collect(Collectors.toSet());
                BigDecimal rate = rates.size() == 1 ? rates.iterator().next() : null;
                LocalDate first = ds.get(0).getFeeDate();
                LocalDate last = ds.get(ds.size() - 1).getFeeDate();
                out.add(line(b.id(), e.getKey(), MonthlyInvoiceLine.PLATFORM, hotelName, "Platform fee · " + hotelName,
                        ds.size() + " sale day" + (ds.size() == 1 ? "" : "s") + ", " + SHORT.format(first) + " to " + SHORT.format(last) + " · NGN "
                                + naira(sales) + (rate == null ? " at the rates of each day" : " × " + pct(rate) + "%"),
                        sales, rate, fee, MonthlyInvoiceLine.DAILY_FEES, null, order[0]++));
                BigDecimal share = Money.of(ds.stream().map(DailyFee::getDeviceFee).reduce(BigDecimal.ZERO, BigDecimal::add));
                if (share.signum() > 0) {
                    BigDecimal shareSales = Money.of(ds.stream().filter(d -> d.getDeviceFee().signum() > 0).map(DailyFee::getSalesTotal)
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
                    BigDecimal shareRate = ds.stream().filter(d -> d.getDeviceFee().signum() > 0).map(DailyFee::getDeviceRate).findFirst().orElse(null);
                    deviceShare.add(line(b.id(), e.getKey(), MonthlyInvoiceLine.DEVICES, hotelName, "Devices from sales · " + hotelName,
                            (shareRate == null ? "" : pct(shareRate) + "% of NGN " + naira(shareSales) + " sales, ") + "until the device balance is paid",
                            shareSales, shareRate, share, MonthlyInvoiceLine.DEVICE_SHARE, null, 0));
                }
            }
            // Guest messages: one line per location's MSG- invoice.
            for (MessageBillingDtos.ToBill m : msgs) {
                String hotelName = Objects.requireNonNullElse(m.hotelName(), hotelNames.getOrDefault(m.hotelId(), "Location"));
                out.add(line(b.id(), m.hotelId(), MonthlyInvoiceLine.MESSAGES, hotelName, "Guest messages · " + hotelName + " · "
                                + MONTH.format(YearMonth.parse(m.month())),
                        m.emailCount() + " email" + (m.emailCount() == 1 ? "" : "s") + ", " + m.smsCount() + " SMS, " + m.whatsappCount() + " WhatsApp ("
                                + m.number() + ")", null, null, Money.of(m.total()), MonthlyInvoiceLine.COMM_INVOICE, m.id(), order[0]++));
            }
            // Devices & internet: schedule items due in or before the month, then the from-sales share.
            List<DeviceInvoice> items = deviceItems.findByBusinessIdAndMonthlyInvoiceIdIsNullAndStatusInAndDueOnLessThanEqualOrderByDueOnAsc(b.id(),
                    List.of(DeviceInvoice.DUE, DeviceInvoice.OVERDUE), monthEnd);
            for (DeviceInvoice i : items) {
                BigDecimal owed = Money.of(i.getAmount().subtract(i.getPaidAmount()));
                if (owed.signum() <= 0) {
                    continue;
                }
                String hotelName = Objects.requireNonNullElse(i.getHotelName(), hotelNames.getOrDefault(i.getHotelId(), "Location"));
                out.add(line(b.id(), i.getHotelId(), MonthlyInvoiceLine.DEVICES, hotelName, DeviceInvoiceService.describe(i) + " · " + hotelName,
                        "Scheduled " + DAY.format(i.getDueOn()) + " (" + i.getNumber() + ")", null, null, owed, MonthlyInvoiceLine.DEVICE_INVOICE, i.getId(),
                        order[0]++));
            }
            for (MonthlyInvoiceLine l : deviceShare) {
                l.setSortOrder(order[0]++);
                out.add(l);
            }
            // What earlier months carried in: a total under the minimum, or a credit left on a paid invoice.
            List<MonthlyInvoice> carriedIn = new ArrayList<>(invoices.findByBusinessIdAndStatusAndCarriedIntoIdIsNull(b.id(), MonthlyInvoice.CARRIED_FORWARD));
            invoices.findByBusinessIdAndStatusAndCarriedIntoIdIsNull(b.id(), MonthlyInvoice.PAID).stream()
                    .filter(p -> p.getPaidAmount().compareTo(p.getTotal()) > 0).forEach(carriedIn::add);
            carriedIn.removeIf(c -> c.getMonth().compareTo(month.toString()) > 0);
            carriedIn.sort(Comparator.comparing(MonthlyInvoice::getMonth));
            for (MonthlyInvoice c : carriedIn) {
                boolean credit = MonthlyInvoice.PAID.equals(c.getStatus());
                BigDecimal amount = credit ? Money.of(c.getTotal().subtract(c.getPaidAmount())) : Money.of(c.getTotal());
                out.add(line(b.id(), null, MonthlyInvoiceLine.CARRIED, null, (credit ? "Credit from " : "Carried forward from ")
                                + MONTH.format(YearMonth.parse(c.getMonth())) + " (" + c.getNumber() + ")",
                        credit ? "Overpaid after a credit note" : "Under NGN " + naira(minimum) + ", so it wasn't due on its own", null, null, amount,
                        MonthlyInvoiceLine.MONTHLY_INVOICE, c.getId(), order[0]++));
            }
            boolean anything = out.stream().anyMatch(l -> l.getAmount().signum() != 0);
            if (!anything) {
                return null;
            }
            BigDecimal platform = sum(out, MonthlyInvoiceLine.PLATFORM);
            BigDecimal msg = sum(out, MonthlyInvoiceLine.MESSAGES);
            BigDecimal dev = sum(out, MonthlyInvoiceLine.DEVICES);
            BigDecimal carried = sum(out, MonthlyInvoiceLine.CARRIED);
            BigDecimal vat = vatPct.signum() > 0 ? Money.percent(platform.add(msg).add(dev), vatPct) : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            BigDecimal total = Money.of(platform.add(msg).add(dev).add(carried).add(vat));
            String status = total.compareTo(minimum) < 0 ? MonthlyInvoice.CARRIED_FORWARD : MonthlyInvoice.DUE;
            String prefix = "HIN-" + month.toString().replace("-", "") + "-";
            invoices.lock("monthly-invoice-number:" + month);
            String number = prefix + String.format("%04d", invoices.lastNumber(prefix + "%") + 1);
            LocalDate due = issuedOn.plusDays(DUE_DAYS);
            MonthlyInvoice inv = invoices.save(MonthlyInvoice.builder().businessId(b.id()).businessName(b.name()).number(number).month(month.toString())
                    .issuedOn(issuedOn).issuedAt(Instant.now()).dueOn(due).graceUntil(due.plusDays(GRACE_DAYS)).platformTotal(platform).messagesTotal(msg)
                    .devicesTotal(dev).carriedInTotal(carried).vatPct(vatPct).vatAmount(vat).total(total).status(status).reissueOfId(reissueOfId).build());
            for (MonthlyInvoiceLine l : out) {
                l.setInvoiceId(inv.getId());
                lines.save(l);
            }
            for (DailyFee d : days) {
                d.setMonthlyInvoiceId(inv.getId());
                if (d.getDue().signum() > 0) {
                    d.setStatus(DailyFee.INVOICED);
                }
                fees.save(d);
            }
            for (DeviceInvoice i : items) {
                i.setMonthlyInvoiceId(inv.getId());
                i.setMonthlyInvoiceNumber(number);
                deviceItems.save(i);
            }
            for (MonthlyInvoice c : carriedIn) {
                c.setCarriedIntoId(inv.getId());
                invoices.save(c);
            }
            return inv;
        });
        if (made == null) {
            return null;
        }
        List<String> msgIds = msgs.stream().map(MessageBillingDtos.ToBill::id).toList();
        if (!msgIds.isEmpty()) {
            messages.link(new MessageBillingDtos.Link(made.getId(), made.getNumber(), msgIds));
        }
        log.info("Monthly invoice {} for {}: NGN {} ({})", made.getNumber(), b.name(), made.getTotal(), made.getStatus());
        try {
            tellIssued(made, b, issuedOn);
        } catch (RuntimeException e) {
            log.warn("Invoice {} issued but the owner could not be told: {}", made.getNumber(), e.getMessage());
        }
        return made;
    }

    private static MonthlyInvoiceLine line(String businessId, String hotelId, String section, String hotelName, String label, String detail,
                                           BigDecimal base, BigDecimal rate, BigDecimal amount, String sourceType, String sourceId, int order) {
        return MonthlyInvoiceLine.builder().businessId(businessId).hotelId(hotelId).section(section).hotelName(hotelName).label(cut(label, 300))
                .detail(cut(detail, 500)).baseAmount(base).rate(rate).amount(Money.of(amount)).sourceType(sourceType).sourceId(sourceId).sortOrder(order).build();
    }

    private static BigDecimal sum(List<MonthlyInvoiceLine> ls, String section) {
        return Money.of(ls.stream().filter(l -> section.equals(l.getSection())).map(MonthlyInvoiceLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    // ---- the daily steps: 06:00 restriction, 08:00 notices --------------------------------------------------

    public record StepRun(LocalDate date, int overdue, int restricted, int notices, List<String> notes) {
    }

    /** 06:00: every open invoice past its last day is restricted (and marked overdue). {@code businessId} narrows it (QA). */
    public StepRun restrictDue(LocalDate today, String businessId) {
        return asSystem(() -> {
            int overdue = 0;
            int restricted = 0;
            List<String> notes = new ArrayList<>();
            for (MonthlyInvoice i : openInvoices(businessId)) {
                try {
                    if (markOverdue(i, today)) {
                        overdue++;
                    }
                    if (today.isAfter(i.lastDay()) && !i.restricted()) {
                        restrict(i, today);
                        restricted++;
                    }
                } catch (RuntimeException e) {
                    notes.add(i.getNumber() + ": " + e.getMessage());
                    log.error("Restriction step for {} failed: {}", i.getNumber(), e.getMessage(), e);
                }
            }
            return new StepRun(today, overdue, restricted, 0, notes);
        });
    }

    /** 08:00: the day's reminders (6th, 7th; due day; 9th, 10th; final warning on the last day; restricted notice). */
    public StepRun remind(LocalDate today, String businessId) {
        return asSystem(() -> {
            int overdue = 0;
            int notices = 0;
            List<String> notes = new ArrayList<>();
            for (MonthlyInvoice i : openInvoices(businessId)) {
                try {
                    if (markOverdue(i, today)) {
                        overdue++;
                    }
                    String kind = noticeFor(i, today);
                    if (kind != null && sendNotice(i, kind, today)) {
                        notices++;
                    }
                } catch (RuntimeException e) {
                    notes.add(i.getNumber() + ": " + e.getMessage());
                    log.error("Reminder step for {} failed: {}", i.getNumber(), e.getMessage(), e);
                }
            }
            return new StepRun(today, overdue, 0, notices, notes);
        });
    }

    /** Which notice an open invoice gets on {@code today}; null for none. */
    static String noticeFor(MonthlyInvoice i, LocalDate today) {
        LocalDate due = i.getDueOn();
        LocalDate last = i.lastDay();
        if (today.isAfter(last)) {
            return i.restricted() ? MonthlyInvoiceEvent.RESTRICTED : null;
        }
        if (today.equals(last) && today.isAfter(due)) {
            return MonthlyInvoiceEvent.FINAL_WARNING;
        }
        if (today.isAfter(due)) {
            return MonthlyInvoiceEvent.OVERDUE;
        }
        if (today.equals(due)) {
            return MonthlyInvoiceEvent.DUE_TODAY;
        }
        if (!today.isBefore(due.minusDays(2))) {
            return MonthlyInvoiceEvent.REMINDER;
        }
        return null;
    }

    private List<MonthlyInvoice> openInvoices(String businessId) {
        List<MonthlyInvoice> open = StringUtils.hasText(businessId) ? invoices.findByBusinessIdAndStatusIn(businessId, OPEN) : invoices.findByStatusIn(OPEN);
        return open.stream().filter(this::billable).sorted(Comparator.comparing(MonthlyInvoice::getDueOn)).toList();
    }

    private boolean markOverdue(MonthlyInvoice i, LocalDate today) {
        if (MonthlyInvoice.DUE.equals(i.getStatus()) && today.isAfter(i.getDueOn())) {
            save(i, x -> {
                if (MonthlyInvoice.DUE.equals(x.getStatus())) {
                    x.setStatus(MonthlyInvoice.OVERDUE);
                }
            });
            return true;
        }
        return false;
    }

    /** Starts the restriction: the business's hotels have POS push paused (the fee pause of fee_collections) and the hold is on. */
    void restrict(MonthlyInvoice i, LocalDate today) {
        save(i, x -> {
            x.setRestrictedAt(Instant.now());
            x.setRestrictionLiftedAt(null);
        });
        setPosPause(i.getBusinessId(), true);
        holds.forget(i.getBusinessId());
        logEvent(i, MonthlyInvoiceEvent.RESTRICTED + "_ON", today, null, null, "Restriction on: POS push, new sales and new bookings paused", null);
        log.warn("Business {} restricted: invoice {} unpaid after {}", i.getBusinessName(), i.getNumber(), i.lastDay());
    }

    /** Lifts the restriction (paid, extended, voided). */
    void lift(MonthlyInvoice i, String why) {
        if (i.getRestrictedAt() == null || i.getRestrictionLiftedAt() != null) {
            return;
        }
        save(i, x -> x.setRestrictionLiftedAt(Instant.now()));
        boolean other = invoices.findByBusinessIdAndStatusIn(i.getBusinessId(), OPEN).stream()
                .anyMatch(o -> !o.getId().equals(i.getId()) && o.restricted());
        if (!other) {
            setPosPause(i.getBusinessId(), false);
        }
        holds.forget(i.getBusinessId());
        logEvent(i, MonthlyInvoiceEvent.LIFTED, LocalDate.now(LAGOS), null, null, why, null);
        log.info("Restriction for {} lifted: {}", i.getBusinessName(), why);
    }

    /** The invoice pause on every hotel of the business; resuming leaves a device-invoice pause in place. */
    private void setPosPause(String businessId, boolean paused) {
        List<String> hotelIds = tenant.business(businessId).map(b -> b.hotels().stream().map(TenantClient.Hotel::id).toList()).orElse(List.of());
        for (String hotelId : hotelIds) {
            FeeCollection c = tx.execute(st -> {
                FeeCollection x = collections.findByHotelId(hotelId).orElseGet(() -> FeeCollection.builder().businessId(businessId).hotelId(hotelId).build());
                x.setPosPaused(paused);
                return collections.save(x);
            });
            if (paused || c == null || !c.isDevicePosPaused()) {
                pos.pausePos(businessId, hotelId, paused);
            }
        }
    }

    // ---- notices ------------------------------------------------------------------------------------------

    private void tellIssued(MonthlyInvoice inv, TenantClient.Business b, LocalDate today) {
        sendNotice(inv, MonthlyInvoiceEvent.ISSUED, today);
    }

    /** Sends the notice once per kind and day: an email to the owner and a bell notice to owners and managers of every location. */
    boolean sendNotice(MonthlyInvoice i, String kind, LocalDate today) {
        if (events.existsByInvoiceIdAndKindAndOnDate(i.getId(), kind, today)) {
            return false;
        }
        TenantClient.Business b = tenant.business(i.getBusinessId()).orElse(null);
        Notice n = noticeText(i, kind, b);
        String to = b == null ? null : b.ownerEmail();
        String outcome;
        if (!billable(i)) {
            outcome = "SKIPPED_QA_ONLY";
        } else if (!StringUtils.hasText(to)) {
            outcome = "NO_EMAIL";
        } else {
            deliveries.send(List.of(Delivery.email(to.trim(), n.subject(), n.text(), n.emailKind(), i.getId() + ":" + kind + ":" + today)));
            outcome = "EMAILED";
        }
        try {
            tx.executeWithoutResult(st -> events.save(MonthlyInvoiceEvent.builder().businessId(i.getBusinessId()).invoiceId(i.getId()).kind(kind)
                    .onDate(today).recipient(to).outcome(outcome).note(cut(n.subject(), 500)).build()));
        } catch (DataIntegrityViolationException dup) {
            return false; // another instance sent it
        }
        if (billable(i) && b != null) {
            bell(i, b, kind, n.bellTitle(), n.bellBody(), today);
        }
        return true;
    }

    private void bell(MonthlyInvoice i, TenantClient.Business b, String kind, String title, String body, LocalDate today) {
        EventPublisher p = publisher.getIfAvailable();
        if (p == null) {
            return;
        }
        for (TenantClient.Hotel h : b.hotels()) {
            NoticeEvent e = NoticeEvent.builder().kind(NOTICE_KIND).title(title).body(body).link("/billing?invoice=" + i.getId())
                    .roles(List.of("OWNER", "MANAGER")).build();
            e.setEventId(UUID.nameUUIDFromBytes((i.getId() + ":" + kind + ":" + today + ":" + h.id()).getBytes(StandardCharsets.UTF_8)).toString());
            e.setBusinessId(b.id());
            e.setHotelId(h.id());
            try {
                p.publish(KafkaTopics.NOTICE, e);
            } catch (RuntimeException ex) {
                log.warn("Bell notice {} for {} not sent: {}", kind, i.getNumber(), ex.getMessage());
            }
        }
    }

    public record Notice(String subject, String text, Delivery.Kind emailKind, String bellTitle, String bellBody) {
    }

    /** The words of each notice (also used by the QA email preview). */
    Notice noticeText(MonthlyInvoice i, String kind, TenantClient.Business b) {
        String hello = "Hello " + (b != null && StringUtils.hasText(b.ownerName()) ? b.ownerName().trim() : "there") + ",\n\n";
        String link = payLink(i);
        String monthName = MONTH.format(YearMonth.parse(i.getMonth()));
        String owed = "NGN " + naira(i.outstanding());
        LocalDate pauseDay = i.lastDay().plusDays(1);
        String pauses = "from " + SHORT.format(pauseDay) + " " + pauseDay.getYear() + " at 06:00, card/transfer POS payments, new sales and new bookings pause until it is paid";
        String stillWorks = "Signing in, checking guests out, recording cash on existing bills and tabs, reports and paying the invoice keep working.";
        String footer = "\n\n" + "Hospio360 · " + COMPANY_LINE;
        String summary = summary(i);
        return switch (kind) {
            case MonthlyInvoiceEvent.ISSUED -> MonthlyInvoice.CARRIED_FORWARD.equals(i.getStatus())
                    ? new Notice("Hospio360 · " + monthName + ": " + "NGN " + naira(i.getTotal()) + " carried forward",
                    hello + "Your Hospio360 invoice for " + monthName + " comes to NGN " + naira(i.getTotal()) + ", under NGN "
                            + naira(nz(settings.fees().invoiceMinimum())) + ". There is nothing to pay now: it is carried forward to next month's invoice.\n\n"
                            + summary + "\nSee it: " + link + footer, Delivery.Kind.INVOICE, "Invoice " + i.getNumber() + " carried forward",
                    "NGN " + naira(i.getTotal()) + " for " + monthName + " moves to next month's invoice")
                    : new Notice("Hospio360 invoice " + i.getNumber() + " · " + monthName + " · " + owed + " due " + SHORT.format(i.getDueOn()),
                    hello + "Your Hospio360 invoice for " + monthName + " is ready.\n\n" + summary + "Due by: " + DAY.format(i.getDueOn()) + "\n\n"
                            + "Pay now (card, bank transfer or USSD): " + link + "\n\nIf it isn't paid by " + DAY.format(i.getDueOn()) + ", reminders follow and "
                            + pauses + "." + footer,
                    Delivery.Kind.INVOICE, "Invoice " + i.getNumber() + " · " + owed, monthName + " · due " + DAY.format(i.getDueOn()));
            case MonthlyInvoiceEvent.REMINDER -> new Notice("Reminder: Hospio360 invoice " + i.getNumber() + " is due on " + DAY.format(i.getDueOn()),
                    hello + "Your Hospio360 invoice " + i.getNumber() + " for " + monthName + " is due on " + DAY.format(i.getDueOn()) + ".\n\nInvoice: "
                            + i.getNumber() + "\nAmount due: " + owed + "\nDue by: " + DAY.format(i.getDueOn()) + "\n\nPay now: " + link + footer,
                    Delivery.Kind.INVOICE_REMINDER, "Invoice " + i.getNumber() + " due " + SHORT.format(i.getDueOn()), owed + " · pay now to keep everything on");
            case MonthlyInvoiceEvent.DUE_TODAY -> new Notice("Due today: Hospio360 invoice " + i.getNumber() + " (" + owed + ")",
                    hello + "Your Hospio360 invoice " + i.getNumber() + " for " + monthName + " is due today.\n\nInvoice: " + i.getNumber() + "\nAmount due: "
                            + owed + "\nDue by: " + DAY.format(i.getDueOn()) + "\n\nPay now: " + link + footer,
                    Delivery.Kind.INVOICE_REMINDER, "Invoice " + i.getNumber() + " is due today", owed + " · pay now");
            case MonthlyInvoiceEvent.OVERDUE -> new Notice("Overdue: Hospio360 invoice " + i.getNumber() + " (" + owed + ")",
                    hello + "Your Hospio360 invoice " + i.getNumber() + " for " + monthName + " was due on " + DAY.format(i.getDueOn())
                            + " and is still unpaid.\n\nInvoice: " + i.getNumber() + "\nAmount due: " + owed + "\nLast day to pay: " + DAY.format(i.lastDay())
                            + "\n\nIf it isn't paid, " + pauses + ". " + stillWorks + "\n\nPay now: " + link + footer,
                    Delivery.Kind.INVOICE_REMINDER, "Invoice " + i.getNumber() + " is overdue", owed + " · pay by " + SHORT.format(i.lastDay()) + " to avoid a pause");
            case MonthlyInvoiceEvent.FINAL_WARNING -> new Notice("Final notice: pay Hospio360 invoice " + i.getNumber() + " today to avoid a pause",
                    hello + "This is the final notice for Hospio360 invoice " + i.getNumber() + " (" + monthName + "). Today is the last day to pay.\n\n"
                            + "Invoice: " + i.getNumber() + "\nAmount due: " + owed + "\nPause starts: " + DAY.format(pauseDay) + " 06:00\n\n"
                            + "If it isn't paid, " + pauses + ". " + stillWorks + "\n\nPay now: " + link + footer,
                    Delivery.Kind.INVOICE_FINAL, "Final notice: invoice " + i.getNumber(),
                    "Pay " + owed + " today. From " + SHORT.format(pauseDay) + " 06:00 card/transfer POS payments, new sales and new bookings pause");
            case MonthlyInvoiceEvent.RESTRICTED -> new Notice("Paused: card/transfer payments, new sales and new bookings · invoice " + i.getNumber() + " unpaid",
                    hello + "Hospio360 invoice " + i.getNumber() + " (" + monthName + ", " + owed + ") is unpaid, so card/transfer POS payments, new sales "
                            + "and new bookings are paused. " + stillWorks + "\n\nEverything resumes within a minute of payment.\n\nPay now: " + link + footer,
                    Delivery.Kind.INVOICE_FINAL, "Paused until invoice " + i.getNumber() + " is paid",
                    "Card/transfer POS payments, new sales and bookings are paused. The owner can pay " + owed + " now");
            default -> new Notice("Hospio360 invoice " + i.getNumber(), hello + summary + "\nSee it: " + link + footer, Delivery.Kind.INVOICE,
                    "Invoice " + i.getNumber(), owed);
        };
    }

    /** The invoice's figures, one "Label: value" a line (the email lays them out as a table). */
    private String summary(MonthlyInvoice i) {
        StringBuilder t = new StringBuilder();
        t.append("Invoice: ").append(i.getNumber()).append('\n');
        t.append("Business: ").append(nzs(i.getBusinessName())).append('\n');
        t.append("Month: ").append(MONTH.format(YearMonth.parse(i.getMonth()))).append('\n');
        if (i.getPlatformTotal().signum() != 0) {
            t.append("Platform fee: NGN ").append(naira(i.getPlatformTotal())).append('\n');
        }
        if (i.getMessagesTotal().signum() != 0) {
            t.append("Guest messages: NGN ").append(naira(i.getMessagesTotal())).append('\n');
        }
        if (i.getDevicesTotal().signum() != 0) {
            t.append("Devices & internet: NGN ").append(naira(i.getDevicesTotal())).append('\n');
        }
        if (i.getCarriedInTotal().signum() != 0) {
            t.append("Carried in: NGN ").append(naira(i.getCarriedInTotal())).append('\n');
        }
        if (i.getAdjustmentsTotal().signum() != 0) {
            t.append("Credits and adjustments: NGN ").append(naira(i.getAdjustmentsTotal())).append('\n');
        }
        if (i.getVatAmount().signum() != 0) {
            t.append("VAT (").append(pct(i.getVatPct())).append("%): NGN ").append(naira(i.getVatAmount())).append('\n');
        }
        t.append("Total: NGN ").append(naira(i.getTotal())).append('\n');
        return t.toString();
    }

    String payLink(MonthlyInvoice i) {
        return hotelAppUrl + "/dashboard/billing?invoice=" + i.getId();
    }

    /** QA: the email a notice would send, without sending it (rendered by the QA preview). */
    public Notice preview(String invoiceId, String kind) {
        MonthlyInvoice i = asSystem(() -> invoices.findById(invoiceId).orElseThrow(() -> new ResourceNotFoundException("Invoice not found")));
        return noticeText(i, kind, asSystem(() -> tenant.business(i.getBusinessId()).orElse(null)));
    }

    // ---- paying ----------------------------------------------------------------------------------------------

    /** The owner pays the full outstanding amount through Hospio360's Paystack link; back to {@code returnUrl} in the hotel app. */
    public PlatformPaystackClient.Checkout payOnline(String invoiceId, String returnUrl) {
        HospioUserDetails user = CurrentUser.require();
        MonthlyInvoice i = mine(invoiceId);
        if (!i.open()) {
            throw new BadRequestException(MonthlyInvoice.PAID.equals(i.getStatus()) ? "This invoice is already paid"
                    : "This invoice isn't payable (" + label(i.getStatus()) + ")");
        }
        BigDecimal owed = i.outstanding();
        if (owed.signum() <= 0) {
            throw new BadRequestException("Nothing is owed on this invoice");
        }
        String reference = i.getNumber() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String back = StringUtils.hasText(returnUrl) && returnUrl.startsWith(hotelAppUrl) ? returnUrl : payLink(i);
        PlatformPaystackClient.Checkout c = paystack.initialize(user.getEmail(), owed, reference, back);
        asSystem(() -> {
            save(i, x -> x.setPaystackRef(c.reference()));
            return null;
        });
        return c;
    }

    /** Back from Paystack, or its webhook (forwarded by messaging-service): checked with Paystack, recorded once. */
    public InvoiceView confirm(String reference) {
        if (!StringUtils.hasText(reference) || !reference.startsWith("HIN-")) {
            throw new BadRequestException("That isn't a Hospio360 invoice payment");
        }
        MonthlyInvoice i = asSystem(() -> invoices.findByPaystackRef(reference).or(() -> {
            int dash = reference.lastIndexOf('-');
            return invoices.findByNumber(reference.substring(0, dash));
        }).orElseThrow(() -> new ResourceNotFoundException("Invoice not found")));
        if (asSystem(() -> payments.findByViaAndReference("PAYSTACK", reference)).isPresent() || !i.open()) {
            return asSystem(() -> view(invoices.findById(i.getId()).orElseThrow(), false));
        }
        PlatformPaystackClient.Verified v = paystack.verify(reference);
        if (!v.success()) {
            throw new BadRequestException("Paystack hasn't confirmed this payment" + (v.message() == null ? "" : ": " + v.message()));
        }
        applyPayment(i.getId(), v.amount(), "PAYSTACK", reference, LocalDate.now(LAGOS), "Paystack", null);
        return asSystem(() -> view(invoices.findById(i.getId()).orElseThrow(), false));
    }

    public record PaymentRequest(BigDecimal amount, String via, String reference, LocalDate paidOn, String note) {
    }

    /** An admin records money received outside Paystack (a bank transfer): part payments allowed, the balance stays due. */
    public InvoiceView recordPayment(String invoiceId, PaymentRequest r) {
        HospioUserDetails user = CurrentUser.require();
        if (r == null || r.amount() == null || r.amount().signum() <= 0) {
            throw new BadRequestException("Enter the amount received");
        }
        if (!StringUtils.hasText(r.reference())) {
            throw new BadRequestException("Enter the bank reference (or receipt number) of the payment");
        }
        String via = StringUtils.hasText(r.via()) ? r.via().trim().toUpperCase(Locale.ROOT) : "BANK_TRANSFER";
        if (!OFFLINE_VIA.contains(via)) {
            throw new BadRequestException("How it was paid: " + String.join(", ", OFFLINE_VIA));
        }
        MonthlyInvoice i = asSystem(() -> invoices.findById(invoiceId).orElseThrow(() -> new ResourceNotFoundException("Invoice not found")));
        if (!i.open()) {
            throw new BadRequestException("This invoice is " + label(i.getStatus()) + ": nothing is owed on it");
        }
        if (Money.of(r.amount()).compareTo(i.outstanding()) > 0) {
            throw new BadRequestException("At most NGN " + naira(i.outstanding()) + " is owed on this invoice");
        }
        LocalDate paidOn = r.paidOn() == null ? LocalDate.now(LAGOS) : r.paidOn();
        if (paidOn.isAfter(LocalDate.now(LAGOS))) {
            throw new BadRequestException("The payment date can't be in the future");
        }
        applyPayment(invoiceId, r.amount(), via, r.reference().trim(), paidOn, user.getFullName(), r.note());
        return asSystem(() -> view(invoices.findById(invoiceId).orElseThrow(), true));
    }

    /**
     * Records a payment once (unique per channel and reference), then: a fully paid invoice settles everything on it (days, device items,
     * message invoices, carried-in invoices) and lifts the restriction; the marketer's commission follows what was received; the owner
     * gets the receipt.
     */
    void applyPayment(String invoiceId, BigDecimal amount, String via, String reference, LocalDate paidOn, String by, String note) {
        record Applied(MonthlyInvoice invoice, MonthlyInvoicePayment payment, boolean paid) {
        }
        Applied a;
        try {
            a = asSystem(() -> tx.execute(st -> {
                MonthlyInvoice i = invoices.lockById(invoiceId).orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
                if (!i.open() || payments.findByViaAndReference(via, reference).isPresent()) {
                    return null;
                }
                BigDecimal got = Money.of(amount.min(i.outstanding()));
                if (got.signum() <= 0) {
                    return null;
                }
                String prefix = "RCP-" + YearMonth.from(paidOn).toString().replace("-", "") + "-";
                invoices.lock("monthly-invoice-receipt:" + prefix);
                String receipt = prefix + String.format("%04d", payments.lastNumber(prefix + "%") + 1);
                MonthlyInvoicePayment p = payments.save(MonthlyInvoicePayment.builder().businessId(i.getBusinessId()).invoiceId(i.getId())
                        .receiptNumber(receipt).amount(got).via(via).reference(cut(reference, 100)).paidOn(paidOn).recordedByName(by)
                        .note(cut(note, 300)).build());
                i.setPaidAmount(Money.of(i.getPaidAmount().add(got)));
                boolean paid = i.getPaidAmount().compareTo(i.getTotal()) >= 0;
                if (paid) {
                    i.setStatus(MonthlyInvoice.PAID);
                    i.setPaidAt(Instant.now());
                    i.setPaidVia(via);
                }
                invoices.save(i);
                events.save(MonthlyInvoiceEvent.builder().businessId(i.getBusinessId()).invoiceId(i.getId()).kind(paid ? MonthlyInvoiceEvent.PAID
                        : MonthlyInvoiceEvent.PAYMENT).onDate(LocalDate.now(LAGOS)).actorName(by).note(cut("NGN " + naira(got) + " by " + label(via)
                        + " (" + reference + "), receipt " + receipt, 500)).build());
                return new Applied(i, p, paid);
            }));
        } catch (DataIntegrityViolationException dup) {
            log.info("Payment {} {} on invoice {} already recorded", via, reference, invoiceId);
            return;
        }
        if (a == null) {
            return;
        }
        if (a.paid()) {
            settle(a.invoice());
            lift(a.invoice(), "Paid (" + label(via) + ", " + reference + ")");
        }
        syncReceived(a.invoice().getId());
        sendReceipt(a.invoice(), a.payment(), a.paid());
        log.info("Invoice {}: NGN {} received by {} ({}){}", a.invoice().getNumber(), a.payment().getAmount(), via, reference, a.paid() ? ", paid" : "");
    }

    /** A paid invoice pays what it billed, and the invoices carried into it. */
    private void settle(MonthlyInvoice i) {
        List<MonthlyInvoice> all = new ArrayList<>();
        all.add(i);
        all.addAll(asSystem(() -> invoices.findByCarriedIntoId(i.getId())).stream().filter(c -> MonthlyInvoice.CARRIED_FORWARD.equals(c.getStatus())).toList());
        for (MonthlyInvoice inv : all) {
            if (inv != i) {
                save(inv, x -> {
                    x.setStatus(MonthlyInvoice.PAID);
                    x.setPaidAt(Instant.now());
                    x.setPaidVia("CARRIED");
                    x.setPaidAmount(x.getTotal().max(BigDecimal.ZERO));
                });
            }
            List<MonthlyInvoiceLine> ls = asSystem(() -> lines.findByInvoiceIdOrderBySortOrderAscCreatedAtAsc(inv.getId()));
            for (DailyFee d : asSystem(() -> fees.findByMonthlyInvoiceIdOrderByHotelNameAscFeeDateAsc(inv.getId()))) {
                if (d.getDeviceFee().signum() > 0 && d.getDevicePackageId() != null) {
                    try {
                        asSystem(() -> {
                            devices.creditTransaction(d, d.getDeviceFee(), i.getNumber() + ":" + d.getId().substring(0, 8));
                            return null;
                        });
                    } catch (RuntimeException e) {
                        log.error("Device share of {} on {} not credited: {}", d.getFeeDate(), i.getNumber(), e.getMessage());
                    }
                }
            }
            for (MonthlyInvoiceLine l : ls) {
                if (MonthlyInvoiceLine.DEVICE_INVOICE.equals(l.getSourceType()) && l.getSourceId() != null) {
                    try {
                        deviceInvoices.pay(l.getSourceId(), "INVOICE", i.getNumber(), null, null);
                    } catch (RuntimeException e) {
                        log.error("Device item {} on {} not settled: {}", l.getSourceId(), i.getNumber(), e.getMessage());
                    }
                }
            }
            List<String> msgIds = ls.stream().filter(l -> MonthlyInvoiceLine.COMM_INVOICE.equals(l.getSourceType())).map(MonthlyInvoiceLine::getSourceId).toList();
            if (!msgIds.isEmpty()) {
                messages.settle(new MessageBillingDtos.Link(inv.getId(), i.getNumber(), msgIds));
            }
        }
    }

    /**
     * What Hospio360 has received of each day's platform fee on this invoice (and the invoices carried into it): the fee times the share of
     * the invoice paid, after credit notes. Each day's {@code collected} follows it, and the marketer's commission follows the change
     * (docs/plan-marketers.md).
     */
    void syncReceived(String invoiceId) {
        try {
            asSystem(() -> {
                tx.executeWithoutResult(st -> {
                    MonthlyInvoice i = invoices.findById(invoiceId).orElseThrow();
                    BigDecimal gross = i.getTotal().subtract(i.getAdjustmentsTotal());
                    BigDecimal received = i.getPaidAmount().min(i.getTotal()).max(BigDecimal.ZERO);
                    BigDecimal share = MonthlyInvoice.VOID.equals(i.getStatus()) ? BigDecimal.ZERO : gross.signum() <= 0 ? BigDecimal.ONE
                            : received.divide(gross, 10, RoundingMode.HALF_UP).min(BigDecimal.ONE);
                    boolean full = MonthlyInvoice.PAID.equals(i.getStatus()) && i.getAdjustmentsTotal().signum() >= 0;
                    List<String> ids = new ArrayList<>();
                    ids.add(i.getId());
                    invoices.findByCarriedIntoId(i.getId()).forEach(c -> ids.add(c.getId()));
                    LocalDate today = LocalDate.now(LAGOS);
                    for (String id : ids) {
                        for (DailyFee d : fees.findByMonthlyInvoiceIdOrderByHotelNameAscFeeDateAsc(id)) {
                            if (d.getFee().signum() <= 0) {
                                continue;
                            }
                            BigDecimal target = full ? d.getFee() : Money.of(d.getFee().multiply(share));
                            d.setCollected(full ? Money.of(d.getFee().add(d.getDeviceFee())) : target);
                            if (full && DailyFee.INVOICED.equals(d.getStatus())) {
                                d.setStatus(DailyFee.PAID);
                            }
                            fees.save(d);
                            commission.syncReceived(d, target, i.getNumber(), today);
                        }
                    }
                });
                return null;
            });
        } catch (RuntimeException e) {
            log.error("Commission / received amounts for invoice {} not updated: {}", invoiceId, e.getMessage(), e);
        }
    }

    private void sendReceipt(MonthlyInvoice i, MonthlyInvoicePayment p, boolean paid) {
        try {
            TenantClient.Business b = asSystem(() -> tenant.business(i.getBusinessId()).orElse(null));
            String to = b == null ? null : b.ownerEmail();
            MonthlyInvoice fresh = asSystem(() -> invoices.findById(i.getId()).orElse(i));
            String hello = "Hello " + (b != null && StringUtils.hasText(b.ownerName()) ? b.ownerName().trim() : "there") + ",\n\n";
            String text = hello + "Thank you: we received your payment for Hospio360 invoice " + i.getNumber() + ".\n\nReceipt: " + p.getReceiptNumber()
                    + "\nInvoice: " + i.getNumber() + "\nPaid: NGN " + naira(p.getAmount()) + "\nPaid by: " + label(p.getVia()) + "\nReference: " + p.getReference()
                    + "\nDate: " + DAY.format(p.getPaidOn()) + "\nBalance: NGN " + naira(fresh.outstanding()) + "\n\n"
                    + (paid ? "The invoice is paid in full" + (i.getRestrictedAt() != null ? " and everything is back on." : ".")
                    : "NGN " + naira(fresh.outstanding()) + " is still due on this invoice.")
                    + "\n\nDownload the receipt (PDF) and the invoice: " + payLink(i) + "\n\nHospio360 · " + COMPANY_LINE;
            String outcome;
            if (!billable(i)) {
                outcome = "SKIPPED_QA_ONLY";
            } else if (!StringUtils.hasText(to)) {
                outcome = "NO_EMAIL";
            } else {
                deliveries.send(List.of(Delivery.email(to.trim(), "Receipt " + p.getReceiptNumber() + " · Hospio360 invoice " + i.getNumber(), text,
                        Delivery.Kind.INVOICE_RECEIPT, p.getId())));
                outcome = "EMAILED";
            }
            logEvent(i, "RECEIPT", LocalDate.now(LAGOS), to, outcome, "Receipt " + p.getReceiptNumber() + " for NGN " + naira(p.getAmount()), null);
            if (billable(i) && b != null) {
                bell(i, b, "RECEIPT:" + p.getReceiptNumber(), paid ? "Invoice " + i.getNumber() + " paid" : "Payment received for " + i.getNumber(),
                        "NGN " + naira(p.getAmount()) + (paid ? (i.getRestrictedAt() != null ? " · everything is back on" : "") : " · NGN " + naira(fresh.outstanding()) + " still due"),
                        LocalDate.now(LAGOS));
            }
        } catch (RuntimeException e) {
            log.warn("Receipt for {} not sent: {}", i.getNumber(), e.getMessage());
        }
    }

    // ---- admin actions ------------------------------------------------------------------------------------------

    public record ExtendRequest(LocalDate until, String reason) {
    }

    /** One extension per invoice: the restriction moves to the morning after {@code until} (and is lifted now if on). Audit-logged. */
    public InvoiceView extend(String invoiceId, ExtendRequest r) {
        HospioUserDetails user = CurrentUser.require();
        if (r == null || r.until() == null || !StringUtils.hasText(r.reason())) {
            throw new BadRequestException("Give the new last day and the reason");
        }
        MonthlyInvoice i = asSystem(() -> invoices.findById(invoiceId).orElseThrow(() -> new ResourceNotFoundException("Invoice not found")));
        if (!i.open()) {
            throw new BadRequestException("Only an unpaid invoice can be extended");
        }
        if (i.getExtendedUntil() != null) {
            throw new BadRequestException("This invoice was already extended to " + DAY.format(i.getExtendedUntil()) + " (one extension per invoice)");
        }
        if (!r.until().isAfter(i.getGraceUntil()) || r.until().isBefore(LocalDate.now(LAGOS))) {
            throw new BadRequestException("The new last day must be after " + DAY.format(i.getGraceUntil()) + " and not in the past");
        }
        if (r.until().isAfter(i.getGraceUntil().plusDays(60))) {
            throw new BadRequestException("Extend by 60 days at most");
        }
        asSystem(() -> {
            save(i, x -> {
                x.setExtendedUntil(r.until());
                x.setExtensionReason(cut(r.reason().trim(), 300));
                x.setExtendedByName(user.getFullName());
            });
            MonthlyInvoice fresh = invoices.findById(invoiceId).orElseThrow();
            logEvent(fresh, MonthlyInvoiceEvent.EXTENDED, LocalDate.now(LAGOS), null, null, "Extended to " + DAY.format(r.until()) + ": " + r.reason().trim(),
                    user.getFullName());
            lift(fresh, "Extended to " + DAY.format(r.until()));
            return null;
        });
        return asSystem(() -> view(invoices.findById(invoiceId).orElseThrow(), true));
    }

    public record AdjustRequest(BigDecimal amount, String reason) {
    }

    /**
     * A credit note (negative amount) or a charge (positive) added to an issued invoice. A credit that covers what is owed pays the invoice;
     * a credit on a paid invoice becomes a credit on the next invoice. Commission follows (a credit claws back pro rata).
     */
    public InvoiceView adjust(String invoiceId, AdjustRequest r) {
        HospioUserDetails user = CurrentUser.require();
        if (r == null || r.amount() == null || r.amount().signum() == 0 || !StringUtils.hasText(r.reason())) {
            throw new BadRequestException("Give the amount (negative for a credit note) and the reason");
        }
        BigDecimal amount = Money.of(r.amount());
        boolean[] nowPaid = {false};
        MonthlyInvoice done = asSystem(() -> tx.execute(st -> {
            MonthlyInvoice i = invoices.lockById(invoiceId).orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
            if (!i.open() && !MonthlyInvoice.PAID.equals(i.getStatus())) {
                throw new BadRequestException("This invoice is " + label(i.getStatus()) + ": adjust the invoice it was carried into");
            }
            if (MonthlyInvoice.PAID.equals(i.getStatus()) && (amount.signum() > 0 || i.getCarriedIntoId() != null)) {
                throw new BadRequestException("A paid invoice only takes a credit note (once); add a charge to the next invoice instead");
            }
            if (i.getTotal().add(amount).signum() < 0) {
                throw new BadRequestException("A credit can't be more than the invoice total (NGN " + naira(i.getTotal()) + ")");
            }
            MonthlyInvoiceLine adj = line(i.getBusinessId(), null, MonthlyInvoiceLine.ADJUSTMENT, null, amount.signum() < 0 ? "Credit note" : "Adjustment",
                    r.reason().trim(), null, null, amount, null, null, 900 + lines.findByInvoiceIdOrderBySortOrderAscCreatedAtAsc(i.getId()).size());
            adj.setInvoiceId(i.getId());
            adj.setCreatedByName(user.getFullName());
            lines.save(adj);
            i.setAdjustmentsTotal(Money.of(i.getAdjustmentsTotal().add(amount)));
            i.setTotal(Money.of(i.getTotal().add(amount)));
            if (i.open() && i.getPaidAmount().compareTo(i.getTotal()) >= 0) {
                i.setStatus(MonthlyInvoice.PAID);
                i.setPaidAt(Instant.now());
                i.setPaidVia(i.getPaidVia() == null ? "CREDIT" : i.getPaidVia());
                nowPaid[0] = true;
            }
            invoices.save(i);
            events.save(MonthlyInvoiceEvent.builder().businessId(i.getBusinessId()).invoiceId(i.getId()).kind(MonthlyInvoiceEvent.ADJUSTED)
                    .onDate(LocalDate.now(LAGOS)).actorName(user.getFullName()).note(cut((amount.signum() < 0 ? "Credit note NGN " : "Charge NGN ")
                            + naira(amount.abs()) + ": " + r.reason().trim(), 500)).build());
            return i;
        }));
        if (nowPaid[0]) {
            settle(done);
            lift(done, "Paid by credit note");
        }
        syncReceived(invoiceId);
        return asSystem(() -> view(invoices.findById(invoiceId).orElseThrow(), true));
    }

    public record VoidRequest(String reason, Boolean reissue) {
    }

    /**
     * Cancels an invoice with no payment on it: what it billed is freed (days, device items, message invoices, carried-in invoices), the
     * restriction lifts, and with {@code reissue} a new invoice for the same month is issued today from the same charges.
     */
    public InvoiceView voidInvoice(String invoiceId, VoidRequest r) {
        HospioUserDetails user = CurrentUser.require();
        if (r == null || !StringUtils.hasText(r.reason())) {
            throw new BadRequestException("Say why the invoice is voided");
        }
        MonthlyInvoice v = asSystem(() -> tx.execute(st -> {
            MonthlyInvoice i = invoices.lockById(invoiceId).orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
            if (!i.open() && !MonthlyInvoice.CARRIED_FORWARD.equals(i.getStatus())) {
                throw new BadRequestException("Only an unpaid invoice can be voided (this one is " + label(i.getStatus()) + ")");
            }
            if (i.getPaidAmount().signum() > 0) {
                throw new BadRequestException("This invoice has payments on it: add a credit note instead of voiding it");
            }
            if (i.getCarriedIntoId() != null) {
                throw new BadRequestException("This invoice was carried into a later invoice: void that one first");
            }
            i.setStatus(MonthlyInvoice.VOID);
            i.setVoidReason(cut(r.reason().trim(), 300));
            invoices.save(i);
            for (DailyFee d : fees.findByMonthlyInvoiceIdOrderByHotelNameAscFeeDateAsc(i.getId())) {
                d.setMonthlyInvoiceId(null);
                if (DailyFee.INVOICED.equals(d.getStatus())) {
                    d.setStatus(DailyFee.ACCRUED);
                }
                fees.save(d);
            }
            for (DeviceInvoice d : deviceItems.findByMonthlyInvoiceId(i.getId())) {
                d.setMonthlyInvoiceId(null);
                d.setMonthlyInvoiceNumber(null);
                deviceItems.save(d);
            }
            for (MonthlyInvoice c : invoices.findByCarriedIntoId(i.getId())) {
                c.setCarriedIntoId(null);
                invoices.save(c);
            }
            events.save(MonthlyInvoiceEvent.builder().businessId(i.getBusinessId()).invoiceId(i.getId()).kind(MonthlyInvoiceEvent.VOIDED)
                    .onDate(LocalDate.now(LAGOS)).actorName(user.getFullName()).note(cut(r.reason().trim(), 500)).build());
            return i;
        }));
        messages.release(new MessageBillingDtos.Link(v.getId(), v.getNumber(), List.of()));
        if (v.getRestrictedAt() != null && v.getRestrictionLiftedAt() == null) {
            asSystem(() -> {
                lift(v, "Voided");
                return null;
            });
        }
        if (Boolean.TRUE.equals(r.reissue())) {
            MonthlyInvoice again = asSystem(() -> {
                TenantClient.Business b = tenant.business(v.getBusinessId()).orElseThrow(() -> new ResourceNotFoundException("Business not found"));
                return issue(b, YearMonth.parse(v.getMonth()), LocalDate.now(LAGOS), v.getId());
            });
            if (again != null) {
                asSystem(() -> {
                    save(v, x -> x.setReplacedById(again.getId()));
                    logEvent(again, MonthlyInvoiceEvent.REISSUED, LocalDate.now(LAGOS), null, null, "Reissued in place of " + v.getNumber() + ": " + r.reason().trim(),
                            user.getFullName());
                    return null;
                });
            }
        }
        return asSystem(() -> view(invoices.findById(v.getId()).orElseThrow(), true));
    }

    // ---- reading ------------------------------------------------------------------------------------------------

    /** The signed-in owner's (or manager's) business invoices, newest first. */
    public List<InvoiceRow> forMe() {
        HospioUserDetails user = CurrentUser.require();
        return invoices.findByBusinessIdOrderByMonthDescIssuedAtDesc(user.getBusinessId()).stream().map(this::row).toList();
    }

    /** One of the caller's business's invoices; a manager sees only their locations' lines and days. */
    public InvoiceView mineView(String id) {
        return view(mine(id), false);
    }

    MonthlyInvoice mine(String id) {
        HospioUserDetails user = CurrentUser.require();
        MonthlyInvoice i = invoices.findById(id).orElseThrow(() -> new ResourceNotFoundException("Invoice not found"));
        if (!user.isPlatform() && !i.getBusinessId().equals(user.getBusinessId())) {
            throw new ResourceNotFoundException("Invoice not found");
        }
        return i;
    }

    /** The day-by-day platform fee (and device share) of an invoice and the invoices carried into it; a manager's own locations only. */
    public List<DailyFeeService.DailyFeeView> days(String id, DailyFeeService dailyFees) {
        MonthlyInvoice i = mine(id);
        // Read as the platform once the invoice is known to be the caller's: row security would otherwise show only the location in the
        // request's hotel header. A manager is narrowed to their own locations by visibleHotels (from the token, not the header).
        return asSystem(() -> daysOf(i, dailyFees));
    }

    public List<DailyFeeService.DailyFeeView> daysAdmin(String id, DailyFeeService dailyFees) {
        MonthlyInvoice i = asSystem(() -> invoices.findById(id).orElseThrow(() -> new ResourceNotFoundException("Invoice not found")));
        return asSystem(() -> daysOf(i, dailyFees));
    }

    private List<DailyFeeService.DailyFeeView> daysOf(MonthlyInvoice i, DailyFeeService dailyFees) {
        Set<String> mineOnly = visibleHotels();
        List<DailyFee> out = new ArrayList<>(fees.findByMonthlyInvoiceIdOrderByHotelNameAscFeeDateAsc(i.getId()));
        invoices.findByCarriedIntoId(i.getId()).forEach(c -> out.addAll(fees.findByMonthlyInvoiceIdOrderByHotelNameAscFeeDateAsc(c.getId())));
        return out.stream().filter(d -> mineOnly == null || mineOnly.contains(d.getHotelId()))
                .sorted(Comparator.comparing(DailyFee::getHotelName, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(DailyFee::getFeeDate))
                .map(dailyFees::view).toList();
    }

    /** null: everything (owner, platform); else the manager's hotels. */
    private Set<String> visibleHotels() {
        HospioUserDetails user = CurrentUser.get().orElse(null);
        if (user == null || user.isPlatform() || user.has(com.hospio.common.enums.Role.OWNER)) {
            return null;
        }
        return Set.copyOf(user.getHotelIds());
    }

    public String payLinkOf(String invoiceId) {
        return asSystem(() -> invoices.findById(invoiceId).map(this::payLink).orElse(hotelAppUrl));
    }

    public MonthlyInvoicePayment paymentOfAdmin(String invoiceId, String paymentId) {
        return asSystem(() -> paymentOf(invoiceId, paymentId));
    }

    public MonthlyInvoicePayment paymentOf(String invoiceId, String paymentId) {
        MonthlyInvoicePayment p = payments.findById(paymentId).orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (!p.getInvoiceId().equals(invoiceId)) {
            throw new ResourceNotFoundException("Payment not found");
        }
        return p;
    }

    public InvoiceView adminView(String id) {
        return asSystem(() -> view(invoices.findById(id).orElseThrow(() -> new ResourceNotFoundException("Invoice not found")), true));
    }

    /** Admin list: filters by status (DUE, OVERDUE, RESTRICTED, PAID, CARRIED_FORWARD, VOID), month, business; counts and totals. */
    public InvoiceList adminList(String status, String month, String businessId) {
        return asSystem(() -> {
            List<InvoiceRow> all = (StringUtils.hasText(businessId) ? invoices.findByBusinessIdOrderByMonthDescIssuedAtDesc(businessId)
                    : invoices.findAllByOrderByMonthDescNumberAsc()).stream().filter(i -> !StringUtils.hasText(month) || month.equals(i.getMonth()))
                    .map(this::row).toList();
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String s : List.of("DUE", "DUE_SOON", "OVERDUE", "RESTRICTED", "PAID", "CARRIED_FORWARD", "VOID")) {
                counts.put(s, all.stream().filter(r -> r.displayStatus().equals(s)).count());
            }
            List<InvoiceRow> rows = all.stream().filter(r -> !StringUtils.hasText(status) || status.equalsIgnoreCase(r.displayStatus())
                    || status.equalsIgnoreCase(r.status())).toList();
            Map<String, BigDecimal> totals = new LinkedHashMap<>();
            List<InvoiceRow> live = rows.stream().filter(r -> !MonthlyInvoice.VOID.equals(r.status())).toList();
            totals.put("invoiced", Money.of(live.stream().map(InvoiceRow::total).reduce(BigDecimal.ZERO, BigDecimal::add)));
            totals.put("paid", Money.of(live.stream().map(InvoiceRow::paidAmount).reduce(BigDecimal.ZERO, BigDecimal::add)));
            totals.put("outstanding", Money.of(live.stream().filter(r -> OPEN.contains(r.status())).map(InvoiceRow::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add)));
            totals.put("platformFee", Money.of(live.stream().map(r -> r.sections().platform()).reduce(BigDecimal.ZERO, BigDecimal::add)));
            return new InvoiceList(rows, counts, totals);
        });
    }

    public String adminCsv(String status, String month, String businessId) {
        StringBuilder csv = new StringBuilder("number,business,month,issued,due,last_day,status,platform_fee,guest_messages,devices,carried_in,adjustments,total,paid,outstanding,extended_until\n");
        for (InvoiceRow r : adminList(status, month, businessId).invoices()) {
            csv.append(String.join(",", r.number(), q(r.businessName()), r.month(), String.valueOf(r.issuedOn()), String.valueOf(r.dueOn()),
                    String.valueOf(r.lastDay()), r.displayStatus(), plain(r.sections().platform()), plain(r.sections().messages()), plain(r.sections().devices()),
                    plain(r.sections().carriedIn()), plain(r.sections().adjustments()), plain(r.total()), plain(r.paidAmount()), plain(r.outstanding()),
                    r.extendedUntil() == null ? "" : r.extendedUntil().toString())).append('\n');
        }
        return csv.toString();
    }

    /** The business's billing state for the admin business page. */
    public record BusinessBilling(String state, int openInvoices, BigDecimal outstanding, String latestNumber, String latestStatus, boolean restricted) {
    }

    public BusinessBilling businessBilling(String businessId) {
        return asSystem(() -> {
            List<MonthlyInvoice> all = invoices.findByBusinessIdOrderByMonthDescIssuedAtDesc(businessId);
            List<MonthlyInvoice> open = all.stream().filter(MonthlyInvoice::open).toList();
            boolean restricted = open.stream().anyMatch(MonthlyInvoice::restricted);
            String state = restricted ? "RESTRICTED" : open.stream().anyMatch(i -> MonthlyInvoice.OVERDUE.equals(i.getStatus())) ? "OVERDUE"
                    : open.isEmpty() ? (all.isEmpty() ? "NO_INVOICES" : "PAID_UP") : "DUE";
            MonthlyInvoice latest = all.isEmpty() ? null : all.get(0);
            return new BusinessBilling(state, open.size(), Money.of(open.stream().map(MonthlyInvoice::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add)),
                    latest == null ? null : latest.getNumber(), latest == null ? null : displayStatus(latest, LocalDate.now(LAGOS)), restricted);
        });
    }

    /** The restriction services check before a new sale, booking or card/transfer payment (internal). */
    public BillingHold.Hold hold(String businessId) {
        return asSystem(() -> invoices.findByBusinessIdAndStatusIn(businessId, OPEN).stream().filter(MonthlyInvoice::restricted)
                .min(Comparator.comparing(MonthlyInvoice::getDueOn))
                .map(i -> new BillingHold.Hold(true, i.getNumber(), "the Hospio360 invoice " + i.getNumber() + " (" + MONTH.format(YearMonth.parse(i.getMonth()))
                        + ") is unpaid. They resume within a minute of the owner paying it under Billing & invoices. Check-out and cash on existing bills and tabs still work."))
                .orElse(BillingHold.Hold.NONE));
    }

    /** The hotel app's banner for the signed-in person. */
    public BillingStatus status() {
        HospioUserDetails user = CurrentUser.require();
        if (user.isPlatform() || user.getBusinessId() == null) {
            return BillingStatus.NONE;
        }
        boolean owner = user.has(com.hospio.common.enums.Role.OWNER);
        boolean manager = owner || user.isManagerOrOwner();
        List<MonthlyInvoice> open = invoices.findByBusinessIdAndStatusIn(user.getBusinessId(), OPEN);
        if (open.isEmpty()) {
            return BillingStatus.NONE;
        }
        MonthlyInvoice i = open.stream().filter(MonthlyInvoice::restricted).findFirst()
                .orElseGet(() -> open.stream().min(Comparator.comparing(MonthlyInvoice::getDueOn)).orElseThrow());
        LocalDate today = LocalDate.now(LAGOS);
        String state = statusState(i, today);
        if (!manager && !"RESTRICTED".equals(state)) {
            return BillingStatus.NONE;
        }
        String monthName = MONTH.format(YearMonth.parse(i.getMonth()));
        LocalDate pauseDay = i.lastDay().plusDays(1);
        String pauseText = SHORT.format(pauseDay) + " 06:00";
        String title = switch (state) {
            case "RESTRICTED" -> "Card/transfer POS payments, new sales and new bookings are paused";
            case "FINAL_WARNING" -> "Final notice: pay invoice " + i.getNumber() + " today";
            case "OVERDUE" -> "Invoice " + i.getNumber() + " is overdue";
            case "DUE_TODAY" -> "Invoice " + i.getNumber() + " is due today";
            case "DUE_SOON" -> "Invoice " + i.getNumber() + " is due on " + SHORT.format(i.getDueOn());
            default -> "Your " + monthName + " invoice is ready";
        };
        String message = switch (state) {
            case "RESTRICTED" -> "Hospio360 invoice " + i.getNumber() + " (" + monthName + ") is unpaid. Signing in, check-out, cash on existing bills and tabs, "
                    + "reports and paying the invoice still work. " + (owner ? "Pay now and everything resumes within a minute." : "Ask the owner to pay it.");
            case "FINAL_WARNING", "OVERDUE" -> "If it isn't paid, from " + pauseText + " card/transfer POS payments, new sales and new bookings pause until it is paid."
                    + (owner ? "" : " Ask the owner to pay it.");
            default -> "NGN " + naira(i.outstanding()) + " for " + monthName + ", due " + DAY.format(i.getDueOn()) + "." + (owner ? "" : " The owner pays it.");
        };
        return new BillingStatus(state, i.getId(), i.getNumber(), monthName, manager ? i.outstanding() : null, i.getDueOn(), i.lastDay(),
                restrictFrom(i), i.restricted(), owner && !user.isReadOnly(), title, message);
    }

    static String statusState(MonthlyInvoice i, LocalDate today) {
        if (i.restricted()) {
            return "RESTRICTED";
        }
        if (today.equals(i.lastDay()) && today.isAfter(i.getDueOn())) {
            return "FINAL_WARNING";
        }
        if (today.isAfter(i.getDueOn())) {
            return "OVERDUE";
        }
        if (today.equals(i.getDueOn())) {
            return "DUE_TODAY";
        }
        if (!today.isBefore(i.getDueOn().minusDays(2))) {
            return "DUE_SOON";
        }
        return "DUE";
    }

    static String displayStatus(MonthlyInvoice i, LocalDate today) {
        if (!i.open()) {
            return i.getStatus();
        }
        if (i.restricted()) {
            return "RESTRICTED";
        }
        if (MonthlyInvoice.OVERDUE.equals(i.getStatus()) || today.isAfter(i.getDueOn())) {
            return "OVERDUE";
        }
        return !today.isBefore(i.getDueOn().minusDays(2)) ? "DUE_SOON" : "DUE";
    }

    static Instant restrictFrom(MonthlyInvoice i) {
        return i.lastDay().plusDays(1).atTime(RESTRICT_AT).atZone(LAGOS).toInstant();
    }

    InvoiceRow row(MonthlyInvoice i) {
        return new InvoiceRow(i.getId(), i.getNumber(), i.getBusinessId(), i.getBusinessName(), i.getMonth(), MONTH.format(YearMonth.parse(i.getMonth())),
                i.getIssuedOn(), i.getDueOn(), i.lastDay(), i.getStatus(), displayStatus(i, LocalDate.now(LAGOS)), sections(i), i.getTotal(), i.getPaidAmount(),
                i.open() ? i.outstanding() : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), i.restricted(), i.getExtendedUntil());
    }

    private static SectionTotals sections(MonthlyInvoice i) {
        return new SectionTotals(i.getPlatformTotal(), i.getMessagesTotal(), i.getDevicesTotal(), i.getCarriedInTotal(), i.getAdjustmentsTotal());
    }

    InvoiceView view(MonthlyInvoice i, boolean admin) {
        Set<String> mineOnly = admin ? null : visibleHotels();
        HospioUserDetails user = CurrentUser.get().orElse(null);
        boolean canPay = !admin && user != null && !user.isPlatform() && !user.isReadOnly() && user.has(com.hospio.common.enums.Role.OWNER) && i.open()
                && i.outstanding().signum() > 0;
        List<LineView> ls = lines.findByInvoiceIdOrderBySortOrderAscCreatedAtAsc(i.getId()).stream()
                .filter(l -> mineOnly == null || l.getHotelId() == null || mineOnly.contains(l.getHotelId()))
                .map(l -> new LineView(l.getId(), l.getSection(), l.getHotelId(), l.getHotelName(), l.getLabel(), l.getDetail(), l.getBaseAmount(), l.getRate(),
                        l.getAmount(), l.getSourceType(), l.getSourceId(), l.getCreatedByName(), l.getCreatedAt())).toList();
        List<PaymentView> ps = payments.findByInvoiceIdOrderByCreatedAtAsc(i.getId()).stream()
                .map(p -> new PaymentView(p.getId(), p.getReceiptNumber(), p.getAmount(), p.getVia(), p.getReference(), p.getPaidOn(), p.getRecordedByName(),
                        p.getNote(), p.getCreatedAt())).toList();
        List<EventView> es = admin ? events.findByInvoiceIdOrderByCreatedAtAsc(i.getId()).stream()
                .map(e -> new EventView(e.getId(), e.getKind(), e.getOnDate(), e.getRecipient(), e.getOutcome(), e.getNote(), e.getActorName(), e.getCreatedAt()))
                .toList() : List.of();
        String carriedInto = i.getCarriedIntoId() == null ? null : invoices.findById(i.getCarriedIntoId()).map(MonthlyInvoice::getNumber).orElse(null);
        String reissueOf = i.getReissueOfId() == null ? null : invoices.findById(i.getReissueOfId()).map(MonthlyInvoice::getNumber).orElse(null);
        String replacedBy = i.getReplacedById() == null ? null : invoices.findById(i.getReplacedById()).map(MonthlyInvoice::getNumber).orElse(null);
        return new InvoiceView(i.getId(), i.getNumber(), i.getBusinessId(), i.getBusinessName(), i.getMonth(), MONTH.format(YearMonth.parse(i.getMonth())),
                i.getIssuedOn(), i.getDueOn(), i.getGraceUntil(), i.lastDay(), restrictFrom(i), i.getStatus(), displayStatus(i, LocalDate.now(LAGOS)), sections(i),
                i.getVatPct(), i.getVatAmount(), i.getTotal(), i.getPaidAmount(), i.open() ? i.outstanding() : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
                i.getPaidAt(), i.getPaidVia(), i.getExtendedUntil(), i.getExtensionReason(), i.getExtendedByName(), i.restricted(), i.getRestrictedAt(),
                carriedInto, i.getVoidReason(), reissueOf, replacedBy, canPay, ls, ps, es);
    }

    /** For the PDFs. */
    public MonthlyInvoice entity(String id, boolean admin) {
        return admin ? asSystem(() -> invoices.findById(id).orElseThrow(() -> new ResourceNotFoundException("Invoice not found"))) : mine(id);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private void logEvent(MonthlyInvoice i, String kind, LocalDate on, String recipient, String outcome, String note, String actor) {
        try {
            tx.executeWithoutResult(st -> events.save(MonthlyInvoiceEvent.builder().businessId(i.getBusinessId()).invoiceId(i.getId()).kind(kind).onDate(on)
                    .recipient(recipient).outcome(outcome).note(cut(note, 500)).actorName(actor).build()));
        } catch (RuntimeException e) {
            log.warn("Invoice event {} for {} not logged: {}", kind, i.getNumber(), e.getMessage());
        }
    }

    private void save(MonthlyInvoice i, java.util.function.Consumer<MonthlyInvoice> change) {
        tx.executeWithoutResult(st -> {
            MonthlyInvoice fresh = invoices.findById(i.getId()).orElse(i);
            change.accept(fresh);
            invoices.save(fresh);
            change.accept(i);
        });
    }

    static <T> T asSystem(Supplier<T> work) {
        return TenantContext.runWith(Tenant.system("monthly-invoices"), work);
    }

    static String label(String code) {
        if (code == null) {
            return "";
        }
        return switch (code) {
            case "BANK_TRANSFER" -> "bank transfer";
            case "PAYSTACK" -> "Paystack";
            case "CARRIED_FORWARD" -> "carried forward";
            default -> code.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    static String naira(BigDecimal v) {
        NumberFormat n = NumberFormat.getNumberInstance(Locale.UK);
        n.setMinimumFractionDigits(2);
        n.setMaximumFractionDigits(2);
        return n.format(v == null ? BigDecimal.ZERO : v);
    }

    static String pct(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String q(String s) {
        String v = s == null ? "" : s;
        return v.contains(",") || v.contains("\"") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String nzs(String s) {
        return s == null ? "" : s;
    }

    static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
