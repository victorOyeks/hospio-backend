package com.hospio.reservation.service;

import com.hospio.common.billing.DailySales;
import com.hospio.reservation.repository.BookingRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reservation's share of a hotel's daily sales (docs/plan-daily-fees.md): deposits kept on no-shows. A cancelled booking's
 * deposit is not counted: it stays the guest's credit or is refunded. A later refund of a kept deposit counts back on its day.
 */
@Service
@RequiredArgsConstructor
public class SalesReport {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final BookingRepository bookings;

    @Transactional(readOnly = true)
    public DailySales daily(String hotelId, LocalDate date) {
        Instant from = date.atStartOfDay(LAGOS).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(LAGOS).toInstant();
        Object[] kept = first(bookings.keptOnNoShows(hotelId, from, to));
        Object[] refunded = first(bookings.noShowRefunds(hotelId, from, to));
        BigDecimal amount = decimal(kept[0]).add(decimal(refunded[0]));
        long count = count(kept[1]) + count(refunded[1]);
        return DailySales.of(new DailySales.Line("NO_SHOW_DEPOSITS", "Deposits kept on no-shows", amount, count));
    }

    private static Object[] first(List<Object[]> rows) {
        return rows.isEmpty() ? new Object[] {null, null} : rows.get(0);
    }

    static BigDecimal decimal(Object v) {
        return v == null ? BigDecimal.ZERO : v instanceof BigDecimal b ? b : new BigDecimal(v.toString());
    }

    static long count(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }
}
