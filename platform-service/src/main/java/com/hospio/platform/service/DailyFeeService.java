package com.hospio.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.billing.DailySales;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.platform.client.DailySalesClient;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.entity.DailyFee;
import com.hospio.platform.entity.FeeCollection;
import com.hospio.platform.repository.DailyFeeRepository;
import com.hospio.platform.repository.FeeCollectionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Hospio's daily fee (docs/plan-daily-fees.md): a share of what each hotel sold on a Lagos day, worked out after midnight. Since
 * 18 Nov 2026 nothing is debited: each day is ACCRUED and billed on the business's monthly invoice (docs/plan-monthly-invoices.md,
 * MonthlyInvoiceService). A business is billed this way when its fee model is PERCENT_OF_SALES (the platform default, 1.5 %).
 *
 * <p>Each hotel and day is worked out once (a row per hotel and date); a day is never worked out on part of its sales (a service
 * that doesn't answer leaves the day for the next run).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyFeeService {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    static final Set<String> STAGES = Set.of("LIVE", "AT_RISK");

    private final DailyFeeRepository fees;
    private final FeeCollectionRepository collections;
    private final DailySalesClient sales;
    private final TenantClient tenant;
    private final FeeService feeRules;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final DevicePackageService devices;

    public record RunResult(LocalDate date, int worked, int skipped, List<String> notes) {
    }

    // ---- working out a day ----------------------------------------------------------------------------

    /**
     * Works out {@code date} for every hotel of every business billed daily. Nothing is sent: the owner sees each day under Hospio fees
     * ("this month so far") and is billed on the month's invoice (the daily statement email stopped on 18 Nov 2026).
     */
    public RunResult runDay(LocalDate date) {
        return TenantContext.runWith(Tenant.system("daily-fees"), () -> {
            List<String> notes = new ArrayList<>();
            int worked = 0;
            int skipped = 0;
            for (TenantClient.Business summary : billedDaily()) {
                TenantClient.Business b = summary.hotels().isEmpty() ? tenant.business(summary.id()).orElse(summary) : summary;
                BigDecimal rate = feeRules.valueFor(b);
                for (TenantClient.Hotel h : b.hotels()) {
                    try {
                        DailyFee f = workOut(b, h, date, rate);
                        if (f != null) {
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
            }
            return new RunResult(date, worked, skipped, notes);
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
        // The device line (a TRANSACTION package): its share of the day's sales, capped at what the package has left.
        DevicePackageService.DeviceLine device = devices.lineFor(h.id(), date, total);
        return tx.execute(st -> {
            // No debits (docs/plan-monthly-invoices.md): the day's fee and device share wait for the month's invoice; nothing is carried.
            BigDecimal deviceFee = device.fee();
            BigDecimal due = Money.of(fee.add(deviceFee));
            String status = due.signum() <= 0 ? DailyFee.NO_FEE : DailyFee.ACCRUED;
            DailyFee f = DailyFee.builder().businessId(b.id()).hotelId(h.id()).hotelName(h.name()).feeDate(date).salesTotal(total)
                    .salesLines(write(lines)).rate(rate).fee(fee).carriedIn(BigDecimal.ZERO).due(due).status(status)
                    .devicePackageId(device.packageId()).deviceRate(device.rate()).deviceFee(deviceFee).deviceCarriedIn(BigDecimal.ZERO).build();
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
                throw new BadRequestException("That day is already worked out for this " + com.hospio.common.venue.VenuePlaces.at(hotelId).place());
            }
            return view(f);
        });
    }

    /** Works a shadow or no-fee day out again (e.g. after a correction); a day with a debit on it is never redone. */
    public DailyFeeView redo(String id) {
        DailyFee f = fees.findById(id).orElseThrow(() -> new ResourceNotFoundException("Daily fee not found"));
        if (f.getMonthlyInvoiceId() != null || !List.of(DailyFee.SHADOW, DailyFee.NO_FEE, DailyFee.ACCRUED).contains(f.getStatus())) {
            throw new BadRequestException("Only a day not on an invoice yet can be worked out again");
        }
        TenantClient.Business b = tenant.business(f.getBusinessId()).orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        TenantClient.Hotel h = b.hotels().stream().filter(x -> x.id().equals(f.getHotelId())).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Hotel not found"));
        tx.executeWithoutResult(st -> fees.delete(f));
        DailyFee again = workOut(b, h, f.getFeeDate(), feeRules.valueFor(b));
        return view(again);
    }

    /** Every hotel's fee state in one call (the admin portal's Hotels page): POS pause and latest day. */
    public record HotelFeeState(String hotelId, boolean posPaused, DailyFeeView lastFee) {
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
            return new HotelFeeState(id, c != null && c.isPosPaused(),
                    f == null ? null : view(f));
        }).toList();
    }

    // ---- POS pause (money roles) -------------------------------------------------------------------------

    /** Whether the hotel's POS payments are paused for an unpaid invoice, and who last changed its fee record (older records). */
    public record CollectionView(String hotelId, boolean posPaused, String changedByName, Instant changedAt) {
    }

    public CollectionView collection(String businessId, String hotelId) {
        FeeCollection c = collections.findByHotelId(hotelId).orElse(null);
        return new CollectionView(hotelId, c != null && c.isPosPaused(), c == null ? null : c.getChangedByName(), c == null ? null : c.getChangedAt());
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

    DailyFeeRepository repository() {
        return fees;
    }

    FeeCollectionRepository collectionRepository() {
        return collections;
    }
}
