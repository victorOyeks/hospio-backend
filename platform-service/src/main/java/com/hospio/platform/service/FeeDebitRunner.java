package com.hospio.platform.service;

import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.platform.client.FeeDebitClient;
import com.hospio.platform.entity.DailyFee;
import com.hospio.platform.entity.FeeCollection;
import com.hospio.platform.repository.DailyFeeRepository;
import com.hospio.platform.repository.FeeCollectionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Collects the daily fee (docs/plan-daily-fees.md). At 06:00 every SCHEDULED day is debited from the hotel's provider account
 * through payment-service; later runs ask about PENDING debits and try UNPAID days again (3 tries a day). What a day leaves unpaid
 * is carried into the next day's fee when that day is worked out. A hotel with 3 unpaid days in a row has POS push paused until a
 * debit goes through. Each try has its own reference (HF-date-hotel-try), so a repeat never takes the money twice.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeeDebitRunner {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    static final int MAX_TRIES = 3;
    static final DateTimeFormatter REF_DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final DailyFeeRepository fees;
    private final FeeCollectionRepository collections;
    private final FeeDebitClient debits;
    private final TransactionTemplate tx;
    private final DevicePackageService devices;

    public record DebitRun(int tried, int paid, int pending, int unpaid, int paused, int resumed) {
    }

    /** Debits (or asks about) every day up to yesterday that is due. */
    public DebitRun run() {
        return TenantContext.runWith(Tenant.system("fee-debit"), () -> {
            LocalDate upTo = LocalDate.now(LAGOS).minusDays(1);
            int tried = 0;
            int paid = 0;
            int pending = 0;
            int unpaid = 0;
            Set<String> hotels = new LinkedHashSet<>();
            for (DailyFee f : fees.findByStatusInAndFeeDateLessThanEqualOrderByFeeDateAsc(
                    List.of(DailyFee.SCHEDULED, DailyFee.PENDING, DailyFee.UNPAID, DailyFee.PART_PAID), upTo)) {
                hotels.add(f.getHotelId());
                String after = step(f);
                if (after == null) {
                    continue;
                }
                tried++;
                switch (after) {
                    case DailyFee.PAID -> paid++;
                    case DailyFee.PENDING -> pending++;
                    default -> unpaid++;
                }
            }
            int[] pauses = pauses(hotels, upTo);
            log.info("Fee debit run: {} tried, {} paid, {} pending, {} unpaid; POS paused at {}, resumed at {}", tried, paid, pending, unpaid, pauses[0], pauses[1]);
            return new DebitRun(tried, paid, pending, unpaid, pauses[0], pauses[1]);
        });
    }

    /** One day's next step; the status it ends in, or null when nothing was done (debit off, out of tries, carried). */
    String step(DailyFee f) {
        FeeCollection c = collections.findByHotelId(f.getHotelId()).orElse(null);
        if (c == null || !c.isDebitEnabled() || c.getProvider() == null) {
            // Switched off since the day was worked out: back to shadow, nothing owed.
            if (DailyFee.SCHEDULED.equals(f.getStatus())) {
                save(f, x -> x.setStatus(DailyFee.SHADOW));
            }
            return null;
        }
        if (DailyFee.PENDING.equals(f.getStatus()) && f.getDebitRef() != null) {
            return apply(f, debits.status(f.getHotelId(), f.getProvider() == null ? c.getProvider() : f.getProvider(), f.getDebitRef()), false);
        }
        if (f.getAttempts() >= MAX_TRIES || f.remaining().signum() <= 0) {
            return null;
        }
        String ref = "HF-" + REF_DAY.format(f.getFeeDate()) + "-" + f.getHotelId().replace("-", "").substring(0, 8).toUpperCase() + "-" + (f.getAttempts() + 1);
        BigDecimal amount = Money.of(f.remaining());
        save(f, x -> {
            x.setAttempts(x.getAttempts() + 1);
            x.setDebitRef(ref);
            x.setProvider(c.getProvider());
            x.setStatus(DailyFee.PENDING);
        });
        return apply(f, debits.debit(f.getHotelId(), c.getProvider(), amount, ref, "Hospio360 fee " + f.getFeeDate()), true);
    }

    private String apply(DailyFee f, com.hospio.common.billing.FeeDebitDtos.DebitResult r, boolean fresh) {
        BigDecimal deviceBefore = f.devicePaid();
        String status = switch (r.status()) {
            case "APPROVED" -> {
                BigDecimal got = r.collected() == null || r.collected().signum() <= 0 ? f.remaining() : r.collected().min(f.remaining());
                f.setCollected(Money.of(f.getCollected().add(got)));
                f.setDebitedAt(Instant.now());
                yield f.remaining().signum() <= 0 ? DailyFee.PAID : DailyFee.PART_PAID;
            }
            case "FAILED" -> f.getCollected().signum() > 0 ? DailyFee.PART_PAID : DailyFee.UNPAID;
            default -> DailyFee.PENDING;
        };
        String error = "APPROVED".equals(r.status()) ? null : r.message();
        save(f, x -> {
            x.setCollected(f.getCollected());
            x.setDebitedAt(f.getDebitedAt());
            x.setStatus(status);
            x.setLastError(error == null ? null : error.length() > 500 ? error.substring(0, 500) : error);
        });
        // The device part of what this debit took (the platform fee is paid first) goes to the hotel's device package, once per reference.
        BigDecimal toDevices = f.devicePaid().subtract(deviceBefore);
        if (toDevices.signum() > 0) {
            try {
                devices.creditTransaction(f, toDevices, f.getDebitRef());
            } catch (RuntimeException e) {
                log.error("Device share {} of debit {} not credited to package {}: {}", toDevices, f.getDebitRef(), f.getDevicePackageId(), e.getMessage());
            }
        }
        return status;
    }

    /** Pauses POS push where the last 3 days with a fee are all short; resumes where the newest is paid. [paused, resumed]. */
    int[] pauses(Set<String> hotels, LocalDate upTo) {
        int paused = 0;
        int resumed = 0;
        for (String hotelId : hotels) {
            FeeCollection c = collections.findByHotelId(hotelId).orElse(null);
            if (c == null || !c.isDebitEnabled()) {
                continue;
            }
            List<DailyFee> recent = fees.findTop5ByHotelIdAndFeeDateLessThanEqualOrderByFeeDateDesc(hotelId, upTo).stream()
                    .filter(f -> f.getDue().signum() > 0).toList();
            boolean shouldPause = shortRun(recent) >= DailyFeeService.PAUSE_AFTER_UNPAID_DAYS;
            boolean newestPaid = !recent.isEmpty() && DailyFee.PAID.equals(recent.get(0).getStatus());
            if (shouldPause && !c.isPosPaused()) {
                tx.executeWithoutResult(st -> {
                    c.setPosPaused(true);
                    collections.save(c);
                });
                debits.pausePos(c.getBusinessId(), hotelId, true);
                paused++;
            } else if (c.isPosPaused() && newestPaid) {
                tx.executeWithoutResult(st -> {
                    c.setPosPaused(false);
                    collections.save(c);
                });
                // A device invoice 14 days overdue keeps POS paused until it is paid.
                if (!c.isDevicePosPaused()) {
                    debits.pausePos(c.getBusinessId(), hotelId, false);
                }
                resumed++;
            }
        }
        return new int[] {paused, resumed};
    }

    /** How many of the newest days in a row were left short (unpaid, part paid, or carried on). */
    static int shortRun(List<DailyFee> newestFirst) {
        int n = 0;
        for (DailyFee f : newestFirst) {
            if (DailyFee.UNPAID.equals(f.getStatus()) || DailyFee.PART_PAID.equals(f.getStatus()) || DailyFee.CARRIED.equals(f.getStatus())) {
                n++;
            } else {
                break;
            }
        }
        return n;
    }

    private void save(DailyFee f, java.util.function.Consumer<DailyFee> change) {
        tx.executeWithoutResult(st -> {
            DailyFee fresh = fees.findById(f.getId()).orElse(f);
            change.accept(fresh);
            fees.save(fresh);
            change.accept(f);
        });
    }
}
