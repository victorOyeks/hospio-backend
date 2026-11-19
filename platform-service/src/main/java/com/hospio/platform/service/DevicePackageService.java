package com.hospio.platform.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.entity.DailyFee;
import com.hospio.platform.entity.DeviceCatalogueItem;
import com.hospio.platform.entity.DeviceInvoice;
import com.hospio.platform.entity.DevicePackage;
import com.hospio.platform.entity.DevicePackageItem;
import com.hospio.platform.entity.DevicePayment;
import com.hospio.platform.repository.DailyFeeRepository;
import com.hospio.platform.repository.DeviceCatalogueRepository;
import com.hospio.platform.repository.DeviceInvoiceRepository;
import com.hospio.platform.repository.DevicePackageItemRepository;
import com.hospio.platform.repository.DevicePackageRepository;
import com.hospio.platform.repository.DevicePaymentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Devices and internet Hospio360 supplies to hotels (docs/plan-device-packages.md): the catalogue, each hotel's package, its ledger,
 * and the device line of a TRANSACTION package in the daily fee. A package is put together during onboarding (DRAFT), installed
 * (collection starts that day) and paid off when its one-off total is collected. A part debit pays the platform fee first.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevicePackageService {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    public static final BigDecimal DEFAULT_RATE = new BigDecimal("2.5");
    static final int MAX_MONTHS = 6;
    static final BigDecimal MAX_PRICE = new BigDecimal("100000000");
    static final BigDecimal MAX_RATE = new BigDecimal("20");
    static final Set<String> OPTIONS = Set.of(DevicePackage.OWN_DEVICES, DevicePackage.TRANSACTION, DevicePackage.MONTHLY, DevicePackage.ONE_TIME);
    static final Set<String> KINDS = Set.of("ONE_OFF", "MONTHLY");
    /** Days whose device part may still be collected: counted against the package so the cap never asks for more than is left. */
    static final List<String> OPEN = List.of(DailyFee.SCHEDULED, DailyFee.PENDING, DailyFee.UNPAID, DailyFee.PART_PAID);

    private final DeviceCatalogueRepository catalogue;
    private final DevicePackageRepository packages;
    private final DevicePackageItemRepository items;
    private final DevicePaymentRepository payments;
    private final DailyFeeRepository fees;
    private final TenantClient tenant;
    private final TransactionTemplate tx;
    private final DeviceInvoiceRepository invoices;
    private final LegalService legal;

    // ---- views ---------------------------------------------------------------------------------------

    public record CatalogueView(String id, String name, String kind, BigDecimal unitPrice, boolean active, int sortOrder) {
    }

    public record ItemView(String id, String catalogueId, String name, String kind, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    public record PaymentView(String id, String source, BigDecimal amount, String reference, LocalDate paidOn, String note, String recordedByName,
                              Instant createdAt) {
    }

    /**
     * A package as every screen shows it. {@code monthlyInstalment} for MONTHLY; for TRANSACTION the device fee of the last 30 days
     * and, from its daily average, an estimate of the days left and the finish date (null while nothing has been collected).
     */
    public record PackageView(String id, String businessId, String hotelId, String hotelName, String status, String paymentOption,
                              BigDecimal deviceRate, Integer months, BigDecimal monthlyInstalment, BigDecimal total, BigDecimal paid,
                              BigDecimal remaining, BigDecimal dataPlanMonthly, String notes, String agreedByName, Instant agreedAt,
                              LocalDate installedAt, Instant paidOffAt, List<ItemView> items, List<PaymentView> payments,
                              BigDecimal last30Days, Integer daysLeft, LocalDate expectedFinish, Instant updatedAt,
                              boolean dataPlanRunning, LocalDate dataPlanFrom, LocalDate dataPlanStoppedOn, LocalDate nextDataPlanDue,
                              String dataPlanChangedByName, Instant dataPlanChangedAt, LocalDate dataPlanRenewedThrough, String dataPlanRenewedByName,
                              Instant dataPlanRenewedAt, Instant termsAcceptedAt, String termsAcceptedName, String termsVersion,
                              String signedScheduleRef) {
    }

    // ---- catalogue ------------------------------------------------------------------------------------

    public List<CatalogueView> catalogue() {
        return catalogue.findAllByOrderBySortOrderAscNameAsc().stream().map(DevicePackageService::catalogueView).toList();
    }

    public record CatalogueRequest(String name, String kind, BigDecimal unitPrice, Boolean active, Integer sortOrder) {
    }

    /** The words for a receipt printer: Hospio360 doesn't supply them (the owner, 19 Nov 2026; receipts go on WhatsApp). */
    static final String NO_PRINTERS = "Hospio360 doesn't supply receipt printers: receipts go to guests on WhatsApp";

    static boolean printer(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).contains("printer");
    }

    /**
     * Adds ({@code id} null) or changes a catalogue item. A package already put together keeps the prices it copied. A receipt
     * printer can't be offered again: it stays switched off.
     */
    public CatalogueView saveCatalogueItem(String id, CatalogueRequest r) {
        if (r == null || !StringUtils.hasText(r.name())) {
            throw new BadRequestException("Name the item");
        }
        String kind = kind(r.kind());
        BigDecimal price = price(r.unitPrice(), r.name());
        DeviceCatalogueItem item = id == null ? DeviceCatalogueItem.builder().build()
                : catalogue.findById(id).orElseThrow(() -> new ResourceNotFoundException("Catalogue item not found"));
        if (printer(r.name()) && (r.active() != null ? r.active() : item.isActive())) {
            throw new BadRequestException(NO_PRINTERS);
        }
        item.setName(r.name().trim());
        item.setKind(kind);
        item.setUnitPrice(price);
        if (r.active() != null) {
            item.setActive(r.active());
        }
        if (r.sortOrder() != null) {
            item.setSortOrder(r.sortOrder());
        }
        DeviceCatalogueItem saved = tx.execute(st -> catalogue.save(item));
        return catalogueView(saved);
    }

    // ---- a hotel's package -----------------------------------------------------------------------------

    /** A new line must be offered: not a switched-off catalogue item and not a receipt printer, unless the draft already lists it. */
    private void checkOffered(ItemRequest i, java.util.Set<String> had) {
        String cid = blank(i.catalogueId());
        if (cid != null ? had.contains(cid) : had.contains("name:" + i.name().trim().toLowerCase(Locale.ROOT))) {
            return;
        }
        if (printer(i.name())) {
            throw new BadRequestException(NO_PRINTERS);
        }
        if (cid != null) {
            catalogue.findById(cid).filter(c -> !c.isActive()).ifPresent(c -> {
                throw new BadRequestException(printer(c.getName()) ? NO_PRINTERS : c.getName() + " is no longer offered: choose another item");
            });
        }
    }

    public record ItemRequest(String catalogueId, String name, String kind, Integer quantity, BigDecimal unitPrice) {
    }

    public record SaveRequest(String paymentOption, List<ItemRequest> items, BigDecimal deviceRate, Integer months, String notes) {
    }

    /** The hotel's current package (anything not cancelled); null when it has none. */
    public PackageView forHotel(String hotelId) {
        return packages.findFirstByHotelIdAndStatusNotOrderByCreatedAtDesc(hotelId, DevicePackage.CANCELLED).map(this::view).orElse(null);
    }

    /** Every current package of a business (the owner's Hospio fees, the admin's business). */
    public List<PackageView> forBusiness(String businessId) {
        return packages.findByBusinessIdAndStatusNotOrderByHotelNameAsc(businessId, DevicePackage.CANCELLED).stream().map(this::view).toList();
    }

    /** Admin list: every package in the given statuses (all but cancelled when none given). */
    public List<PackageView> all(List<String> statuses) {
        List<String> s = statuses == null || statuses.isEmpty()
                ? List.of(DevicePackage.DRAFT, DevicePackage.INSTALLED, DevicePackage.PAID_OFF) : statuses;
        return packages.findByStatusInOrderByUpdatedAtDesc(s).stream().map(this::view).toList();
    }

    /**
     * Puts together (or changes) the hotel's package while it is a draft. The one-off items make the total; monthly items (a data
     * plan) make the monthly charge. Once installed the package can't be changed: cancel it and set up a new one.
     */
    public PackageView save(String businessId, String hotelId, SaveRequest r) {
        if (r == null) {
            throw new BadRequestException("Send the package");
        }
        String option = r.paymentOption() == null ? "" : r.paymentOption().trim().toUpperCase(Locale.ROOT);
        if (!OPTIONS.contains(option)) {
            throw new BadRequestException("Choose how the devices are paid for: own devices, from transactions, monthly or one time");
        }
        TenantClient.Hotel hotel = hotelOf(businessId, hotelId);
        // What the draft already lists may stay (an item switched off later, a receipt printer agreed before 19 Nov 2026); nothing
        // switched off, and no receipt printer, may be added.
        java.util.Set<String> had = new java.util.HashSet<>();
        packages.findFirstByHotelIdAndStatusNotOrderByCreatedAtDesc(hotelId, DevicePackage.CANCELLED).ifPresent(cur -> {
            for (DevicePackageItem i : items.findByPackageIdOrderByCreatedAtAsc(cur.getId())) {
                if (i.getCatalogueId() != null) {
                    had.add(i.getCatalogueId());
                }
                had.add("name:" + i.getName().trim().toLowerCase(Locale.ROOT));
            }
        });
        List<DevicePackageItem> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal monthly = BigDecimal.ZERO;
        for (ItemRequest i : r.items() == null ? List.<ItemRequest>of() : r.items()) {
            if (i == null || !StringUtils.hasText(i.name())) {
                throw new BadRequestException("Every item needs a name");
            }
            checkOffered(i, had);
            String kind = kind(i.kind());
            int qty = i.quantity() == null ? 0 : i.quantity();
            if (qty < 1 || qty > 1000) {
                throw new BadRequestException("Quantity of " + i.name().trim() + " must be between 1 and 1,000");
            }
            BigDecimal price = price(i.unitPrice(), i.name());
            BigDecimal line = Money.of(price.multiply(BigDecimal.valueOf(qty)));
            if ("ONE_OFF".equals(kind)) {
                total = total.add(line);
            } else {
                monthly = monthly.add(line);
            }
            lines.add(DevicePackageItem.builder().businessId(businessId).hotelId(hotelId).catalogueId(blank(i.catalogueId())).name(i.name().trim())
                    .kind(kind).quantity(qty).unitPrice(price).lineTotal(line).build());
        }
        BigDecimal rate = null;
        Integer months = null;
        switch (option) {
            case DevicePackage.OWN_DEVICES -> {
                if (total.signum() > 0) {
                    throw new BadRequestException("Own devices means nothing is supplied: remove the devices, or choose how they are paid for");
                }
            }
            case DevicePackage.TRANSACTION -> {
                requireDevices(total);
                rate = r.deviceRate() == null ? DEFAULT_RATE : r.deviceRate();
                if (rate.signum() <= 0 || rate.compareTo(MAX_RATE) > 0) {
                    throw new BadRequestException("The share of daily sales for devices must be more than 0 and at most 20 (%)");
                }
                if (rate.stripTrailingZeros().scale() > 2) {
                    throw new BadRequestException("Use at most two decimal places for the device share, like 2.5");
                }
            }
            case DevicePackage.MONTHLY -> {
                requireDevices(total);
                months = r.months();
                if (months == null || months < 1 || months > MAX_MONTHS) {
                    throw new BadRequestException("Monthly payment is over 1 to 6 months");
                }
            }
            default -> requireDevices(total);
        }
        HospioUserDetails user = CurrentUser.require();
        BigDecimal t = Money.of(total);
        BigDecimal m = Money.of(monthly);
        BigDecimal fRate = rate;
        Integer fMonths = months;
        String notes = r.notes() == null ? null : r.notes().trim().isEmpty() ? null : r.notes().trim().length() > 500 ? r.notes().trim().substring(0, 500) : r.notes().trim();
        DevicePackage saved = tx.execute(st -> {
            DevicePackage p = packages.findFirstByHotelIdAndStatusNotOrderByCreatedAtDesc(hotelId, DevicePackage.CANCELLED).orElse(null);
            if (p != null && !DevicePackage.DRAFT.equals(p.getStatus())) {
                throw new BadRequestException("This " + com.hospio.common.venue.VenuePlaces.at(hotelId).possessive() + " package is " + label(p.getStatus()) + ": it can't be changed. Cancel it to set up a new one");
            }
            if (p == null) {
                p = DevicePackage.builder().businessId(businessId).hotelId(hotelId).status(DevicePackage.DRAFT).build();
            }
            p.setHotelName(hotel.name());
            p.setPaymentOption(option);
            p.setDeviceRate(fRate);
            p.setMonths(fMonths);
            p.setTotal(t);
            p.setDataPlanMonthly(m);
            p.setNotes(notes);
            p.setAgreedBy(user.getUserId());
            p.setAgreedByName(user.getFullName());
            p.setAgreedAt(Instant.now());
            // The owner accepts the device terms for what is in the package: a change asks them again.
            p.setTermsAcceptedAt(null);
            p.setTermsAcceptedBy(null);
            p.setTermsAcceptedName(null);
            p.setTermsVersion(null);
            DevicePackage kept = packages.save(p);
            items.deleteByPackageId(kept.getId());
            items.flush();
            for (DevicePackageItem i : lines) {
                i.setPackageId(kept.getId());
                items.save(i);
            }
            return kept;
        });
        return view(saved);
    }

    /**
     * The owner accepts the device terms for a package not yet installed (Hospio fees → Devices): the acceptance is recorded with the
     * legal records (docs/legal-acceptance.md) and on the package. Accepting again after a change records it again.
     */
    public PackageView acceptTerms(String packageId, String version) {
        HospioUserDetails user = CurrentUser.require();
        DevicePackage p = tx.execute(st -> {
            DevicePackage x = lock(packageId);
            if (user.getBusinessId() == null || !user.getBusinessId().equals(x.getBusinessId())) {
                throw new ResourceNotFoundException("Device package not found");
            }
            if (!DevicePackage.DRAFT.equals(x.getStatus())) {
                throw new BadRequestException("This package is already " + label(x.getStatus()));
            }
            if (x.getTermsAcceptedAt() != null) {
                return x;
            }
            String accepted = legal.acceptDevices(x.getId(), x.getHotelId(), x.getHotelName(), version);
            x.setTermsAcceptedAt(Instant.now());
            x.setTermsAcceptedBy(user.getUserId());
            x.setTermsAcceptedName(user.getFullName());
            x.setTermsVersion(accepted);
            return packages.save(x);
        });
        return view(p);
    }

    /**
     * The devices are installed: collection starts that day (TRANSACTION: the device line in each daily fee from {@code on};
     * MONTHLY: the first instalment; ONE_TIME: the payment is recorded alongside). Own devices with no data plan have nothing to install.
     */
    public PackageView install(String packageId, LocalDate on) {
        return install(packageId, on, null);
    }

    /**
     * As {@link #install(String, LocalDate)}; the owner must have accepted the device terms for this package in the app first, unless the
     * admin gives the reference of a signed device schedule ({@code signedScheduleRef}, the exception path).
     */
    public PackageView install(String packageId, LocalDate on, String signedScheduleRef) {
        String schedule = signedScheduleRef == null || signedScheduleRef.isBlank() ? null
                : signedScheduleRef.trim().length() > 120 ? signedScheduleRef.trim().substring(0, 120) : signedScheduleRef.trim();
        LocalDate day = on == null ? LocalDate.now(LAGOS) : on;
        if (day.isAfter(LocalDate.now(LAGOS))) {
            throw new BadRequestException("The installation date can't be in the future");
        }
        DevicePackage p = tx.execute(st -> {
            DevicePackage x = lock(packageId);
            if (!DevicePackage.DRAFT.equals(x.getStatus())) {
                throw new BadRequestException("This package is already " + label(x.getStatus()));
            }
            if (DevicePackage.OWN_DEVICES.equals(x.getPaymentOption()) && x.getDataPlanMonthly().signum() <= 0) {
                throw new BadRequestException("Own devices with no data plan: there is nothing to install");
            }
            if (x.getTermsAcceptedAt() == null && schedule == null) {
                throw new BadRequestException("The owner hasn't accepted the device terms for this package yet (Hospio fees → Devices in the app), "
                        + "or enter the reference of the signed device schedule");
            }
            if (schedule != null && x.getTermsAcceptedAt() == null) {
                x.setSignedScheduleRef(schedule);
            }
            x.setStatus(x.getTotal().signum() > 0 ? DevicePackage.INSTALLED : DevicePackage.PAID_OFF);
            x.setInstalledAt(day);
            if (DevicePackage.PAID_OFF.equals(x.getStatus())) {
                x.setPaidOffAt(Instant.now());
            }
            return packages.save(x);
        });
        log.info("Device package {} for hotel {} installed on {} ({})", p.getId(), p.getHotelId(), day, p.getPaymentOption());
        return view(p);
    }

    public record PaymentRequest(BigDecimal amount, String reference, LocalDate paidOn, String note) {
    }

    /** An admin records money received (cash or transfer): ONE_TIME for a one-time package, MANUAL otherwise. */
    public PackageView recordPayment(String packageId, PaymentRequest r) {
        if (r == null || r.amount() == null || r.amount().signum() <= 0) {
            throw new BadRequestException("Enter the amount received");
        }
        if (!StringUtils.hasText(r.reference())) {
            throw new BadRequestException("Enter the payment reference (bank reference or receipt number)");
        }
        LocalDate paidOn = r.paidOn() == null ? LocalDate.now(LAGOS) : r.paidOn();
        if (paidOn.isAfter(LocalDate.now(LAGOS))) {
            throw new BadRequestException("The payment date can't be in the future");
        }
        HospioUserDetails user = CurrentUser.require();
        DevicePackage p = tx.execute(st -> {
            DevicePackage x = lock(packageId);
            if (!DevicePackage.INSTALLED.equals(x.getStatus())) {
                throw new BadRequestException(DevicePackage.DRAFT.equals(x.getStatus()) ? "Mark the devices installed first"
                        : "This package is " + label(x.getStatus()) + ": nothing is owed on it");
            }
            BigDecimal amount = Money.of(r.amount());
            if (amount.compareTo(x.remaining()) > 0) {
                throw new BadRequestException("That is more than is left (" + naira(x.remaining()) + ")");
            }
            String source = DevicePackage.ONE_TIME.equals(x.getPaymentOption()) ? DevicePayment.ONE_TIME : DevicePayment.MANUAL;
            if (payments.existsByPackageIdAndSourceAndReference(x.getId(), source, r.reference().trim())) {
                throw new BadRequestException("A payment with reference " + r.reference().trim() + " is already recorded");
            }
            payments.save(DevicePayment.builder().businessId(x.getBusinessId()).hotelId(x.getHotelId()).packageId(x.getId()).source(source)
                    .amount(amount).reference(r.reference().trim()).paidOn(paidOn).note(blank(r.note())).recordedBy(user.getUserId())
                    .recordedByName(user.getFullName()).build());
            return credit(x, amount);
        });
        return view(p);
    }

    /** Cancels a package. One with money collected on it can't be cancelled (it has to be paid off or settled by hand). */
    public PackageView cancel(String packageId) {
        DevicePackage p = tx.execute(st -> {
            DevicePackage x = lock(packageId);
            if (DevicePackage.CANCELLED.equals(x.getStatus())) {
                return x;
            }
            if (x.getPaid().signum() > 0) {
                throw new BadRequestException("Money has been collected on this package: it can't be cancelled");
            }
            x.setStatus(DevicePackage.CANCELLED);
            x.setCancelledAt(Instant.now());
            return packages.save(x);
        });
        return view(p);
    }

    // ---- the data plan after installation -------------------------------------------------------------

    public record DataPlanRequest(String action, BigDecimal monthly) {
    }

    /**
     * After installation the data plan can be stopped (no invoice due after today; what is invoiced stays owed), started again (from
     * the next due day, at {@code monthly} or its last price) or repriced (invoices issued from now on). Drafts are edited as a whole.
     */
    public PackageView setDataPlan(String packageId, DataPlanRequest r) {
        String action = r == null || r.action() == null ? "" : r.action().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("STOP", "START", "PRICE").contains(action)) {
            throw new BadRequestException("Say what to do with the data plan: STOP, START or PRICE");
        }
        HospioUserDetails user = CurrentUser.require();
        LocalDate today = LocalDate.now(LAGOS);
        DevicePackage p = tx.execute(st -> {
            DevicePackage x = lock(packageId);
            if (!DevicePackage.INSTALLED.equals(x.getStatus()) && !DevicePackage.PAID_OFF.equals(x.getStatus())) {
                throw new BadRequestException(DevicePackage.DRAFT.equals(x.getStatus()) ? "Change the data plan in the package itself until it is installed"
                        : "This package is cancelled");
            }
            switch (action) {
                case "STOP" -> {
                    if (!x.dataPlanRunning()) {
                        throw new BadRequestException("The data plan isn't running");
                    }
                    // From the first due day after today: nothing more is billed; what is already invoiced stays owed.
                    x.setDataPlanStoppedOn(x.dataPlanDueOnOrAfter(today.plusDays(1)));
                }
                case "START" -> {
                    if (x.dataPlanRunning()) {
                        throw new BadRequestException("The data plan is already running");
                    }
                    BigDecimal price = r.monthly() != null ? price(r.monthly(), "the data plan") : x.getDataPlanMonthly();
                    if (price == null || price.signum() <= 0) {
                        throw new BadRequestException("Enter the monthly price of the data plan");
                    }
                    x.setDataPlanMonthly(price);
                    x.setDataPlanFrom(x.dataPlanDueOnOrAfter(today));
                    x.setDataPlanStoppedOn(null);
                }
                default -> {
                    if (!x.dataPlanRunning()) {
                        throw new BadRequestException("Start the data plan to set its price");
                    }
                    BigDecimal price = price(r.monthly(), "the data plan");
                    if (price.signum() <= 0) {
                        throw new BadRequestException("Enter the monthly price of the data plan");
                    }
                    x.setDataPlanMonthly(price);
                }
            }
            x.setDataPlanChangedByName(user.getFullName());
            x.setDataPlanChangedAt(Instant.now());
            return packages.save(x);
        });
        log.info("Data plan of package {} ({}): {} by {}", p.getId(), p.getHotelName(), action, user.getFullName());
        return view(p);
    }

    public record RenewedRequest(LocalDate through) {
    }

    /** Hospio360 renewed the hotel's real data subscription with the provider (MTN, Airtel, Spectranet…) through this day. */
    public PackageView markDataPlanRenewed(String packageId, RenewedRequest r) {
        LocalDate today = LocalDate.now(LAGOS);
        if (r == null || r.through() == null) {
            throw new BadRequestException("Enter the day the provider subscription now runs to");
        }
        if (r.through().isBefore(today) || r.through().isAfter(today.plusDays(400))) {
            throw new BadRequestException("The renewal date must be from today to about a year ahead");
        }
        HospioUserDetails user = CurrentUser.require();
        DevicePackage p = tx.execute(st -> {
            DevicePackage x = lock(packageId);
            if (x.getDataPlanMonthly() == null || x.getDataPlanMonthly().signum() <= 0 || x.getInstalledAt() == null
                    || !(DevicePackage.INSTALLED.equals(x.getStatus()) || DevicePackage.PAID_OFF.equals(x.getStatus()))) {
                throw new BadRequestException("This package has no installed data plan");
            }
            x.setDataPlanRenewedThrough(r.through());
            x.setDataPlanRenewedByName(user.getFullName());
            x.setDataPlanRenewedAt(Instant.now());
            return packages.save(x);
        });
        return view(p);
    }

    /** One hotel's data plan for the renewals list: its next invoice and how long the real subscription still runs. */
    public record RenewalView(String packageId, String businessId, String hotelId, String hotelName, BigDecimal monthly, LocalDate nextInvoiceDue,
                              LocalDate renewedThrough, Integer daysLeft, String renewedByName, Instant renewedAt) {
    }

    /**
     * Running data plans whose real subscription needs renewing within {@code days} (or was never recorded), the most urgent first:
     * the team renews them with the provider and marks them renewed.
     */
    public List<RenewalView> dataPlanRenewals(int days) {
        int window = Math.max(0, Math.min(days, 90));
        LocalDate today = LocalDate.now(LAGOS);
        List<RenewalView> out = new ArrayList<>();
        for (DevicePackage p : packages.findByStatusInOrderByUpdatedAtDesc(List.of(DevicePackage.INSTALLED, DevicePackage.PAID_OFF))) {
            if (!p.dataPlanRunning() || p.getInstalledAt() == null) {
                continue;
            }
            LocalDate through = p.getDataPlanRenewedThrough();
            if (through != null && through.isAfter(today.plusDays(window))) {
                continue;
            }
            Integer left = through == null ? null : (int) java.time.temporal.ChronoUnit.DAYS.between(today, through);
            out.add(new RenewalView(p.getId(), p.getBusinessId(), p.getHotelId(), p.getHotelName(), p.getDataPlanMonthly(), nextDataPlanDue(p),
                    through, left, p.getDataPlanRenewedByName(), p.getDataPlanRenewedAt()));
        }
        out.sort(java.util.Comparator.comparing((RenewalView v) -> v.renewedThrough() == null ? LocalDate.MIN : v.renewedThrough()));
        return out;
    }

    // ---- the device line of the daily fee --------------------------------------------------------------

    /** The device line for a day: the package it collects towards, its rate and the day's fee. */
    public record DeviceLine(String packageId, BigDecimal rate, BigDecimal fee) {
        static final DeviceLine NONE = new DeviceLine(null, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * The device fee for a hotel's day: its TRANSACTION package, installed on or before the day and not paid off, at its rate on
     * the day's sales, capped at what is left once the device part of every day still open is counted. NONE otherwise.
     */
    public DeviceLine lineFor(String hotelId, LocalDate date, BigDecimal sales) {
        DevicePackage p = packages.findFirstByHotelIdAndStatusNotOrderByCreatedAtDesc(hotelId, DevicePackage.CANCELLED).orElse(null);
        if (p == null || !DevicePackage.TRANSACTION.equals(p.getPaymentOption()) || !p.collecting() || p.getInstalledAt() == null
                || p.getInstalledAt().isAfter(date)) {
            return DeviceLine.NONE;
        }
        BigDecimal open = fees.findByHotelIdAndStatusInAndFeeDateBefore(hotelId, OPEN, date.plusDays(1)).stream()
                .filter(f -> p.getId().equals(f.getDevicePackageId())).map(DailyFee::deviceRemaining).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal room = p.remaining().subtract(open).max(BigDecimal.ZERO);
        BigDecimal fee = DailyFeeService.feeOn(sales, p.getDeviceRate()).min(room);
        return new DeviceLine(p.getId(), p.getDeviceRate(), Money.of(fee));
    }

    /**
     * The device part of a debit went through: it is credited to the package once (keyed by the debit reference) and the package is
     * paid off when nothing is left. Runs as the system; the package row is locked.
     */
    public void creditTransaction(DailyFee f, BigDecimal amount, String reference) {
        if (f.getDevicePackageId() == null || amount == null || amount.signum() <= 0) {
            return;
        }
        TenantContext.runWith(Tenant.systemFor(f.getBusinessId(), f.getHotelId(), null, "device-collection"), () -> tx.executeWithoutResult(st -> {
            DevicePackage x = packages.lockById(f.getDevicePackageId()).orElse(null);
            if (x == null) {
                log.warn("Daily fee {} names device package {} that no longer exists", f.getId(), f.getDevicePackageId());
                return;
            }
            String ref = reference == null ? "HF-" + f.getId() : reference;
            if (payments.existsByPackageIdAndSourceAndReference(x.getId(), DevicePayment.TRANSACTION, ref)) {
                return;
            }
            BigDecimal credit = Money.of(amount.min(x.remaining()));
            if (credit.signum() <= 0) {
                return;
            }
            try {
                payments.save(DevicePayment.builder().businessId(x.getBusinessId()).hotelId(x.getHotelId()).packageId(x.getId())
                        .source(DevicePayment.TRANSACTION).amount(credit).reference(ref).dailyFeeId(f.getId()).paidOn(LocalDate.now(LAGOS))
                        .note("Device share of the " + f.getFeeDate() + " debit").build());
                payments.flush();
            } catch (DataIntegrityViolationException e) {
                return;
            }
            credit(x, credit);
        }));
    }

    // ---- inside ----------------------------------------------------------------------------------------

    /** Adds a payment to the package; paid off when nothing of the one-off total is left. Call inside a transaction with the row locked. */
    DevicePackage credit(DevicePackage x, BigDecimal amount) {
        x.setPaid(Money.of(x.getPaid().add(amount)));
        if (x.remaining().signum() <= 0 && DevicePackage.INSTALLED.equals(x.getStatus())) {
            x.setStatus(DevicePackage.PAID_OFF);
            x.setPaidOffAt(Instant.now());
            log.info("Device package {} for hotel {} is paid off", x.getId(), x.getHotelId());
        }
        return packages.save(x);
    }

    /** The next data plan invoice of a running plan: its first billed due day not invoiced yet (today's, once issued, moves on a month). */
    LocalDate nextDataPlanDue(DevicePackage p) {
        if (!p.dataPlanRunning() || p.getInstalledAt() == null) {
            return null;
        }
        java.util.Set<Integer> issued = invoices.findByPackageIdOrderByDueOnDescKindAsc(p.getId()).stream()
                .filter(i -> DeviceInvoice.DATA_PLAN.equals(i.getKind())).map(DeviceInvoice::getSequence).collect(java.util.stream.Collectors.toSet());
        return p.nextDataPlanDue(issued);
    }

    PackageView view(DevicePackage p) {
        List<ItemView> lines = items.findByPackageIdOrderByCreatedAtAsc(p.getId()).stream()
                .map(i -> new ItemView(i.getId(), i.getCatalogueId(), i.getName(), i.getKind(), i.getQuantity(), i.getUnitPrice(), i.getLineTotal())).toList();
        List<PaymentView> paid = payments.findByPackageIdOrderByPaidOnDescCreatedAtDesc(p.getId()).stream()
                .map(x -> new PaymentView(x.getId(), x.getSource(), x.getAmount(), x.getReference(), x.getPaidOn(), x.getNote(), x.getRecordedByName(),
                        x.getCreatedAt())).toList();
        BigDecimal instalment = DevicePackage.MONTHLY.equals(p.getPaymentOption()) && p.getMonths() != null && p.getMonths() > 0
                ? p.getTotal().divide(BigDecimal.valueOf(p.getMonths()), 2, RoundingMode.UP) : null;
        BigDecimal last30 = null;
        Integer daysLeft = null;
        LocalDate finish = null;
        if (DevicePackage.TRANSACTION.equals(p.getPaymentOption()) && p.getInstalledAt() != null) {
            LocalDate today = LocalDate.now(LAGOS);
            List<DailyFee> recent = fees.findByHotelIdAndFeeDateBetweenOrderByFeeDateDesc(p.getHotelId(), today.minusDays(30), today).stream()
                    .filter(f -> p.getId().equals(f.getDevicePackageId())).toList();
            last30 = Money.of(recent.stream().map(DailyFee::getDeviceFee).reduce(BigDecimal.ZERO, BigDecimal::add));
            if (DevicePackage.INSTALLED.equals(p.getStatus()) && !recent.isEmpty() && last30.signum() > 0) {
                BigDecimal perDay = last30.divide(BigDecimal.valueOf(recent.size()), 2, RoundingMode.HALF_UP);
                daysLeft = p.remaining().divide(perDay, 0, RoundingMode.UP).intValue();
                finish = today.plusDays(daysLeft);
            }
        }
        return new PackageView(p.getId(), p.getBusinessId(), p.getHotelId(), p.getHotelName(), p.getStatus(), p.getPaymentOption(), p.getDeviceRate(),
                p.getMonths(), instalment, p.getTotal(), p.getPaid(), p.remaining(), p.getDataPlanMonthly(), p.getNotes(), p.getAgreedByName(),
                p.getAgreedAt(), p.getInstalledAt(), p.getPaidOffAt(), lines, paid, last30, daysLeft, finish, p.getUpdatedAt(),
                p.dataPlanRunning(), p.getDataPlanFrom(), p.getDataPlanStoppedOn(), nextDataPlanDue(p),
                p.getDataPlanChangedByName(), p.getDataPlanChangedAt(), p.getDataPlanRenewedThrough(), p.getDataPlanRenewedByName(),
                p.getDataPlanRenewedAt(), p.getTermsAcceptedAt(), p.getTermsAcceptedName(), p.getTermsVersion(), p.getSignedScheduleRef());
    }

    static CatalogueView catalogueView(DeviceCatalogueItem i) {
        return new CatalogueView(i.getId(), i.getName(), i.getKind(), i.getUnitPrice(), i.isActive(), i.getSortOrder());
    }

    private TenantClient.Hotel hotelOf(String businessId, String hotelId) {
        TenantClient.Business b = tenant.business(businessId).orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        return b.hotels().stream().filter(h -> h.id().equals(hotelId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("That location isn't one of this business's"));
    }

    private DevicePackage lock(String id) {
        return packages.lockById(id).orElseThrow(() -> new ResourceNotFoundException("Device package not found"));
    }

    private static void requireDevices(BigDecimal total) {
        if (total.signum() <= 0) {
            throw new BadRequestException("Add the devices supplied (at least one item with a price), or choose own devices");
        }
    }

    private static String kind(String raw) {
        String k = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(k)) {
            throw new BadRequestException("An item is ONE_OFF (bought once) or MONTHLY (a data plan)");
        }
        return k;
    }

    private static BigDecimal price(BigDecimal p, String name) {
        if (p == null || p.signum() < 0 || p.compareTo(MAX_PRICE) > 0) {
            throw new BadRequestException("Price of " + (name == null ? "the item" : name.trim()) + " must be between ₦0 and ₦100,000,000");
        }
        if (p.stripTrailingZeros().scale() > 2) {
            throw new BadRequestException("Use naira and kobo for prices, at most two decimal places");
        }
        return Money.of(p);
    }

    private static String blank(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    static String label(String status) {
        return switch (status) {
            case DevicePackage.INSTALLED -> "installed";
            case DevicePackage.PAID_OFF -> "paid off";
            case DevicePackage.CANCELLED -> "cancelled";
            default -> "a draft";
        };
    }

    static String naira(BigDecimal v) {
        java.text.NumberFormat n = java.text.NumberFormat.getNumberInstance(Locale.UK);
        n.setMinimumFractionDigits(2);
        n.setMaximumFractionDigits(2);
        return "₦" + n.format(v);
    }
}
