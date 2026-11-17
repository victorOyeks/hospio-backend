package com.hospio.housekeeping.service;

import com.hospio.common.billing.DailySales;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Laundry's share of a hotel's daily sales (docs/plan-daily-fees.md): guest laundry paid directly that Lagos day, at the order's
 * total (laundry carries no VAT). Laundry charged to the room counts on the folio instead. A paid order cancelled later counts back
 * on the day it was cancelled.
 */
@Service
public class SalesReport {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public DailySales daily(String hotelId, LocalDate day) {
        Instant from = day.atStartOfDay(LAGOS).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(LAGOS).toInstant();
        Object[] paid = one("select coalesce(sum(total - service_charge), 0), count(*) from housekeeping.laundry_orders where hotel_id = :hotelId and paid "
                + "and not charged_to_room and paid_at >= :from and paid_at < :to", hotelId, from, to);
        Object[] undone = one("select coalesce(sum(total - service_charge), 0), count(*) from housekeeping.laundry_orders where hotel_id = :hotelId and paid "
                + "and not charged_to_room and cancelled_at >= :from and cancelled_at < :to and paid_at < cancelled_at", hotelId, from, to);
        BigDecimal amount = dec(paid[0]).subtract(dec(undone[0]));
        return DailySales.of(new DailySales.Line("LAUNDRY", "Guest laundry paid directly", amount, num(paid[1]) + num(undone[1])));
    }

    private Object[] one(String sql, String hotelId, Instant from, Instant to) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(sql).setParameter("hotelId", hotelId).setParameter("from", from).setParameter("to", to).getResultList();
        return rows.isEmpty() ? new Object[] {null, null} : rows.get(0);
    }

    private static BigDecimal dec(Object v) {
        return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
    }

    private static long num(Object v) {
        return v == null ? 0 : ((Number) v).longValue();
    }
}
