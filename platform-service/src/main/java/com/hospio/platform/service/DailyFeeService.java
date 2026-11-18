package com.hospio.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.billing.DailySales;
import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.platform.client.DailySalesClient;
import com.hospio.platform.client.FeeDebitClient;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.entity.DailyFee;
import com.hospio.platform.entity.FeeCollection;
import com.hospio.platform.repository.DailyFeeRepository;
import com.hospio.platform.repository.FeeCollectionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Hospio's daily fee (docs/plan-daily-fees.md): a share of what each hotel sold on a Lagos day, worked out after midnight and
 * debited at 06:00 from the hotel's own Moniepoint or OPay account. A business is billed this way when its fee model is
 * PERCENT_OF_SALES (the platform default, 1.5 %).
 *
 * <p>Each hotel and day is worked out once (a row per hotel and date); a day is never worked out on part of its sales (a service
 * that doesn't answer leaves the day for the next run). While a hotel's debit is off it is in shadow mode: the statement still goes
 * out, nothing is owed or carried. With the debit on, what a day leaves unpaid is carried to the next day; after 3 unpaid days in a
 * row POS push pauses for the hotel until a debit goes through.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyFeeService {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    static final int PAUSE_AFTER_UNPAID_DAYS = 3;
    static final Set<String> STAGES = Set.of("LIVE", "AT_RISK");

    private final DailyFeeRepository fees;
    private final FeeCollectionRepository collections;
    private final DailySalesClient sales;
    private final FeeDebitClient debits;
    private final TenantClient tenant;
    private final FeeService feeRules;
    private final DeliveryService deliveries;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final DevicePackageService devices;

    public record RunResult(LocalDate date, int worked, int skipped, int statements, List<String> notes) {
    }

    // ---- working out a day ----------------------------------------------------------------------------

    /** Works out {@code date} for every hotel of every business billed daily, then sends each owner one statement. */
    public RunResult runDay(LocalDate date) {
        return TenantContext.runWith(Tenant.system("daily-fees"), () -> {
            List<String> notes = new ArrayList<>();
            int worked = 0;
            int skipped = 0;
            int statements = 0;
            for (TenantClient.Business summary : billedDaily()) {
                TenantClient.Business b = summary.hotels().isEmpty() ? tenant.business(summary.id()).orElse(summary) : summary;
                BigDecimal rate = feeRules.valueFor(b);
                List<DailyFee> made = new ArrayList<>();
                for (TenantClient.Hotel h : b.hotels()) {
                    try {
                        DailyFee f = workOut(b, h, date, rate);
                        if (f != null) {
                            made.add(f);
                            worked++;
                        } else {
                            skipped++;
                        }
                    } catch (RuntimeException e) {
                        skipped++;
                        notes.add(b.name() + " · " + h.name() + ": not worked out (" + e.getMessage() + ")");
                        log.warn("Daily fee for {} ({}) on {} not worked out: {}", h.name(), h.id(), date, e.getMessage());
                    }
                }
                if (!made.isEmpty() && sendStatement(b, date, made)) {
                    statements++;
                }
            }
            return new RunResult(date, worked, skipped, statements, notes);
        });
    }

    /** Businesses LIVE or AT_RISK whose fee model is PERCENT_OF_SALES. */
    List<TenantClient.Business> billedDaily() {
        List<TenantClient.Business> out = new ArrayList<>();
        for (String stage : STAGES) {
            for (TenantClient.Business b : tenant.businesses(stage, null)) {
                if (FeeService.PERCENT_OF_SALES.equals(feeRules.modelFor(b))) {
                    out.add(b);
                }
            }
        }
        return out;
    }

    /** The hotel's fee for the day; null when it was already worked out. */
    DailyFee workOut(TenantClient.Business b, TenantClient.Hotel h, LocalDate date, BigDecimal rate) {
        if (fees.findByHotelIdAndFeeDate(h.id(), date).isPresent()) {
            return null;
        }
        List<DailySales.Line> lines = sales.sales(h.id(), date);
        BigDecimal total = Money.of(lines.stream().map(DailySales.Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal fee = feeOn(total, rate);
        // The device line (a TRANSACTION package) is worked out before earlier days are carried, so its cap counts them once.
        DevicePackageService.DeviceLine device = devices.lineFor(h.id(), date, total);
        return tx.execute(st -> {
            FeeCollection c = collections.findByHotelId(h.id()).orElse(null);
            boolean debitOn = c != null && c.isDebitEnabled();
            BigDecimal carried = BigDecimal.ZERO;
            BigDecimal deviceCarried = BigDecimal.ZERO;
            String devicePackage = device.packageId();
            if (debitOn) {
                // Every earlier day left short (including one whose pending debit failed after its next day was worked out). Its platform
                // and device remainders are carried separately, so the platform fee keeps being paid first.
                for (DailyFee prev : fees.findByHotelIdAndStatusInAndFeeDateBefore(h.id(), List.of(DailyFee.PART_PAID, DailyFee.UNPAID), date)) {
                    carried = carried.add(prev.platformRemaining());
                    deviceCarried = deviceCarried.add(prev.deviceRemaining());
                    if (devicePackage == null && prev.getDevicePackageId() != null && prev.deviceRemaining().signum() > 0) {
                        devicePackage = prev.getDevicePackageId();
                    }
                    prev.setStatus(DailyFee.CARRIED);
                    fees.save(prev);
                }
            }
            // Shadow mode (debit off) collects nothing, so the device line is only shown, not owed.
            BigDecimal deviceFee = device.fee();
            BigDecimal due = Money.of(fee.add(carried).add(deviceFee).add(deviceCarried));
            String status = due.signum() <= 0 ? DailyFee.NO_FEE : debitOn ? DailyFee.SCHEDULED : DailyFee.SHADOW;
            DailyFee f = DailyFee.builder().businessId(b.id()).hotelId(h.id()).hotelName(h.name()).feeDate(date).salesTotal(total)
                    .salesLines(write(lines)).rate(rate).fee(fee).carriedIn(Money.of(carried)).due(due).status(status)
                    .provider(c == null ? null : c.getProvider()).devicePackageId(devicePackage).deviceRate(device.rate())
                    .deviceFee(deviceFee).deviceCarriedIn(Money.of(deviceCarried)).build();
            return fees.save(f);
        });
    }

    /** {@code rate} % of the day's sales, to the kobo; nothing on a day that sold nothing (or less than it gave back). */
    static BigDecimal feeOn(BigDecimal sales, BigDecimal rate) {
        if (sales == null || sales.signum() <= 0 || rate == null || rate.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return sales.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    // ---- the morning statement ------------------------------------------------------------------------

    boolean sendStatement(TenantClient.Business b, LocalDate date, List<DailyFee> made) {
        String to = b.ownerEmail();
        if (!StringUtils.hasText(to)) {
            log.warn("Daily fee statement for {} on {} has no owner email", b.name(), date);
            return false;
        }
        deliveries.send(Delivery.email(to, "Hospio360 · " + DAY.format(date) + ": your sales and Hospio fee", statementText(b, date, made),
                Delivery.Kind.FEE_STATEMENT, b.id() + ":" + date));
        tx.executeWithoutResult(st -> made.forEach(f -> {
            f.setStatementSentAt(Instant.now());
            fees.save(f);
        }));
        return true;
    }

    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.UK);

    String statementText(TenantClient.Business b, LocalDate date, List<DailyFee> made) {
        NumberFormat n = NumberFormat.getNumberInstance(Locale.UK);
        n.setMinimumFractionDigits(2);
        n.setMaximumFractionDigits(2);
        StringBuilder t = new StringBuilder();
        t.append("Hello ").append(StringUtils.hasText(b.ownerName()) ? b.ownerName() : "there").append(",\n\n");
        t.append("Here is what ").append(b.name()).append(" sold on ").append(DAY.format(date)).append(" (VAT excluded) and Hospio360's fee.\n\n");
        BigDecimal debit = BigDecimal.ZERO;
        boolean anyShadow = false;
        for (DailyFee f : made) {
            t.append(f.getHotelName()).append('\n');
            for (DailySales.Line l : read(f.getSalesLines())) {
                if (l.amount().signum() != 0) {
                    t.append("  ").append(l.label()).append(": NGN ").append(n.format(l.amount())).append('\n');
                }
            }
            t.append("  Sales: NGN ").append(n.format(f.getSalesTotal())).append("\n");
            t.append("  Fee at ").append(f.getRate().stripTrailingZeros().toPlainString()).append(" %: NGN ").append(n.format(f.getFee())).append('\n');
            if (f.getCarriedIn().signum() > 0) {
                t.append("  Unpaid from earlier days: NGN ").append(n.format(f.getCarriedIn())).append('\n');
            }
            if (f.getDeviceFee().signum() > 0) {
                t.append("  Devices at ").append(f.getDeviceRate().stripTrailingZeros().toPlainString()).append(" %: NGN ").append(n.format(f.getDeviceFee()))
                        .append('\n');
            }
            if (f.getDeviceCarriedIn().signum() > 0) {
                t.append("  Devices unpaid from earlier days: NGN ").append(n.format(f.getDeviceCarriedIn())).append('\n');
            }
            if (DailyFee.SCHEDULED.equals(f.getStatus())) {
                debit = debit.add(f.getDue());
            } else if (DailyFee.SHADOW.equals(f.getStatus())) {
                anyShadow = true;
            }
            t.append('\n');
        }
        if (debit.signum() > 0) {
            t.append("NGN ").append(n.format(debit)).append(" will be debited from your account at 6:00am today.\n");
        }
        if (anyShadow) {
            t.append("Nothing is debited yet: this is how your daily fee will look once automatic debit is switched on.\n");
        }
        t.append("\nSee every day and every line in Hospio360 under Hospio fees. Reply to this email if something looks wrong.\n\nHospio360\n");
        return t.toString();
    }

    // ---- reading -------------------------------------------------------------------------------------

    public record DailyFeeView(String id, String businessId, String hotelId, String hotelName, LocalDate date, BigDecimal sales,
                               List<DailySales.Line> lines, BigDecimal rate, BigDecimal fee, BigDecimal carriedIn, BigDecimal due,
                               BigDecimal collected, String status, String provider, String debitRef, int attempts, String lastError,
                               Instant statementSentAt, Instant debitedAt, String devicePackageId, BigDecimal deviceRate, BigDecimal deviceFee,
                               BigDecimal deviceCarriedIn, BigDecimal platformPaid, BigDecimal devicePaid, BigDecimal refunded, String refundNote) {
    }

    public DailyFeeView view(DailyFee f) {
        return new DailyFeeView(f.getId(), f.getBusinessId(), f.getHotelId(), f.getHotelName(), f.getFeeDate(), f.getSalesTotal(),
                read(f.getSalesLines()), f.getRate(), f.getFee(), f.getCarriedIn(), f.getDue(), f.getCollected(), f.getStatus(), f.getProvider(),
                f.getDebitRef(), f.getAttempts(), f.getLastError(), f.getStatementSentAt(), f.getDebitedAt(), f.getDevicePackageId(), f.getDeviceRate(),
                f.getDeviceFee(), f.getDeviceCarriedIn(), f.platformPaid(), f.devicePaid(), f.getRefunded(), f.getRefundNote());
    }

    /** The owner's own business, newest first (row security keeps it to their business). */
    public List<DailyFeeView> forOwner(LocalDate from, LocalDate to) {
        HospioUserDetails user = CurrentUser.require();
        LocalDate[] r = range(from, to);
        return fees.findByBusinessIdAndFeeDateBetweenOrderByFeeDateDescHotelNameAsc(user.getBusinessId(), r[0], r[1]).stream().map(this::view).toList();
    }

    /** A manager's hotel (the hotel in the token, checked against the caller's hotels by the tenant filter), newest first. */
    public List<DailyFeeView> forHotel(String hotelId, LocalDate from, LocalDate to) {
        LocalDate[] r = range(from, to);
        return fees.findByHotelIdAndFeeDateBetweenOrderByFeeDateDesc(hotelId, r[0], r[1]).stream().map(this::view).toList();
    }

    /** Platform admins: one business, one hotel, or everything, newest first. */
    public List<DailyFeeView> forAdmin(String businessId, String hotelId, LocalDate from, LocalDate to) {
        LocalDate[] r = range(from, to);
        List<DailyFee> rows = StringUtils.hasText(hotelId) ? fees.findByHotelIdAndFeeDateBetweenOrderByFeeDateDesc(hotelId, r[0], r[1])
                : StringUtils.hasText(businessId) ? fees.findByBusinessIdAndFeeDateBetweenOrderByFeeDateDescHotelNameAsc(businessId, r[0], r[1])
                : fees.findByFeeDateBetweenOrderByFeeDateDescHotelNameAsc(r[0], r[1]);
        return rows.stream().map(this::view).toList();
    }

    static LocalDate[] range(LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(LAGOS) : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        if (start.isAfter(end)) {
            throw new BadRequestException("from must be on or before to");
        }
        if (start.plusDays(366).isBefore(end)) {
            throw new BadRequestException("Ask for a year at most");
        }
        return new LocalDate[] {start, end};
    }

    /** One hotel's day only (QA on staging, QaFeeController): the same work-out as the 00:05 run, without touching other hotels. */
    public DailyFeeView workOutOne(String businessId, String hotelId, LocalDate date) {
        return TenantContext.runWith(Tenant.system("daily-fees-qa"), () -> {
            TenantClient.Business b = tenant.business(businessId).orElseThrow(() -> new ResourceNotFoundException("Business not found"));
            TenantClient.Hotel h = b.hotels().stream().filter(x -> x.id().equals(hotelId)).findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Hotel not found"));
            DailyFee f = workOut(b, h, date, feeRules.valueFor(b));
            if (f == null) {
                throw new BadRequestException("That day is already worked out for this hotel");
            }
            return view(f);
        });
    }

    /** Works a shadow or no-fee day out again (e.g. after a correction); a day with a debit on it is never redone. */
    public DailyFeeView redo(String id) {
        DailyFee f = fees.findById(id).orElseThrow(() -> new ResourceNotFoundException("Daily fee not found"));
        if (!DailyFee.SHADOW.equals(f.getStatus()) && !DailyFee.NO_FEE.equals(f.getStatus())) {
            throw new BadRequestException("Only a day in shadow mode or with no fee can be worked out again");
        }
        TenantClient.Business b = tenant.business(f.getBusinessId()).orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        TenantClient.Hotel h = b.hotels().stream().filter(x -> x.id().equals(f.getHotelId())).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Hotel not found"));
        tx.executeWithoutResult(st -> fees.delete(f));
        DailyFee again = workOut(b, h, f.getFeeDate(), feeRules.valueFor(b));
        return view(again);
    }

    /** Every hotel's fee state in one call (the admin portal's Hotels page): provider, debit, POS pause, latest day. */
    public record HotelFeeState(String hotelId, String provider, boolean debitEnabled, boolean posPaused, DailyFeeView lastFee) {
    }

    public List<HotelFeeState> hotelStates() {
        java.util.Map<String, FeeCollection> byHotel = new java.util.HashMap<>();
        collections.findAll().forEach(c -> byHotel.put(c.getHotelId(), c));
        java.util.Map<String, DailyFee> last = new java.util.HashMap<>();
        fees.latestPerHotel().forEach(f -> last.put(f.getHotelId(), f));
        java.util.Set<String> ids = new java.util.TreeSet<>(byHotel.keySet());
        ids.addAll(last.keySet());
        return ids.stream().map(id -> {
            FeeCollection c = byHotel.get(id);
            DailyFee f = last.get(id);
            return new HotelFeeState(id, c == null ? null : c.getProvider(), c != null && c.isDebitEnabled(), c != null && c.isPosPaused(),
                    f == null ? null : view(f));
        }).toList();
    }

    // ---- collection settings (SUPER_ADMIN) ------------------------------------------------------------

    public record CollectionView(String hotelId, String provider, boolean debitEnabled, boolean posPaused, String changedByName, Instant changedAt,
                                 List<com.hospio.common.billing.FeeDebitDtos.ProviderState> providers) {
    }

    public CollectionView collection(String businessId, String hotelId) {
        FeeCollection c = collections.findByHotelId(hotelId).orElse(null);
        List<com.hospio.common.billing.FeeDebitDtos.ProviderState> states = debits.providers(hotelId);
        return new CollectionView(hotelId, c == null ? null : c.getProvider(), c != null && c.isDebitEnabled(), c != null && c.isPosPaused(),
                c == null ? null : c.getChangedByName(), c == null ? null : c.getChangedAt(), states);
    }

    /**
     * Sets the hotel's debit provider and switches the debit on or off. Switching on needs the provider's debit configured on the
     * server and the hotel's keys for it in payment-service; switching off returns the hotel to shadow mode and lifts a POS pause.
     */
    public CollectionView setCollection(String businessId, String hotelId, String provider, boolean debitEnabled) {
        String p = StringUtils.hasText(provider) ? provider.trim().toUpperCase(Locale.ROOT) : null;
        if (p != null && !Set.of("MONIEPOINT", "OPAY").contains(p)) {
            throw new BadRequestException("The provider is MONIEPOINT or OPAY");
        }
        if (debitEnabled) {
            if (p == null) {
                throw new BadRequestException("Choose the provider the fee is debited from");
            }
            com.hospio.common.billing.FeeDebitDtos.ProviderState s = debits.providers(hotelId).stream().filter(x -> x.provider().equals(p)).findFirst().orElse(null);
            if (s == null || !s.debitConfigured()) {
                throw new BadRequestException(label(p) + " debit isn't configured on the server yet" + (s == null || s.reason() == null ? "" : ": " + s.reason()));
            }
            if (!s.hotelKeys()) {
                throw new BadRequestException("Enter the hotel's " + label(p) + " keys first (POS provider)");
            }
        }
        HospioUserDetails user = CurrentUser.require();
        tx.executeWithoutResult(st -> {
            FeeCollection c = collections.findByHotelId(hotelId)
                    .orElseGet(() -> FeeCollection.builder().businessId(businessId).hotelId(hotelId).build());
            c.setProvider(p);
            c.setDebitEnabled(debitEnabled);
            c.setChangedBy(user.getUserId());
            c.setChangedByName(user.getFullName());
            c.setChangedAt(Instant.now());
            if (!debitEnabled && c.isPosPaused()) {
                c.setPosPaused(false);
                if (!c.isDevicePosPaused()) {
                    debits.pausePos(businessId, hotelId, false);
                }
            }
            collections.save(c);
        });
        return collection(businessId, hotelId);
    }

    static String label(String provider) {
        return "OPAY".equals(provider) ? "OPay" : "Moniepoint";
    }

    // ---- json ----------------------------------------------------------------------------------------

    String write(List<DailySales.Line> lines) {
        try {
            return mapper.writeValueAsString(lines);
        } catch (Exception e) {
            throw new IllegalStateException("Sales lines couldn't be written", e);
        }
    }

    List<DailySales.Line> read(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<List<DailySales.Line>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    // ---- the 06:00 debit (Phase 4) is in FeeDebitRunner --------------------------------------------------

    DailyFeeRepository repository() {
        return fees;
    }

    FeeCollectionRepository collectionRepository() {
        return collections;
    }
}
