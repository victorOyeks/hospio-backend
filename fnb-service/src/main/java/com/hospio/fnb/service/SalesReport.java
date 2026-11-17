package com.hospio.fnb.service;

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
 * The outlets' share of a hotel's daily sales (docs/plan-daily-fees.md): money paid on restaurant, bar and kitchen orders that
 * Lagos day, VAT excluded (each payment less the order's tax in proportion). What is charged to a room is not here: it counts on
 * the folio. A part-paid order charged to the room counts its paid part here and the rest on the folio. Refunds are counted by
 * payment-service.
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
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("select coalesce(sum(case when o.total > 0 then p.amount * (o.total - o.tax - o.service_charge) / o.total "
                        + "else p.amount end), 0), count(*) from fnb.order_payments p join fnb.orders o on o.id = p.order_id "
                        + "where p.hotel_id = :hotelId and p.created_at >= :from and p.created_at < :to")
                .setParameter("hotelId", hotelId).setParameter("from", from).setParameter("to", to).getResultList();
        Object[] r = rows.isEmpty() ? new Object[] {null, null} : rows.get(0);
        BigDecimal amount = r[0] == null ? BigDecimal.ZERO : new BigDecimal(r[0].toString()).setScale(2, java.math.RoundingMode.HALF_UP);
        long count = r[1] == null ? 0 : ((Number) r[1]).longValue();
        return DailySales.of(new DailySales.Line("OUTLETS", "Restaurant, bar and kitchen (paid at the counter)", amount, count));
    }
}
