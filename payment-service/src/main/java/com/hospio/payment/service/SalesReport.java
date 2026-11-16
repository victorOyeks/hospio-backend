package com.hospio.payment.service;

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
 * Refunds that take back counter sales (docs/plan-daily-fees.md): refunds approved that Lagos day on payments for restaurant,
 * bar or kitchen orders, counted negative. Refunds on folio payments don't change the sales (the charge stays unless voided).
 * The refund is taken whole: its VAT share isn't known here, so the hotel is credited slightly more, never less.
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
        List<Object[]> rows = em.createNativeQuery("select coalesce(sum(r.amount), 0), count(*) from payment.refunds r join payment.payment_requests p "
                        + "on p.id = r.payment_id where r.hotel_id = :hotelId and r.status = 'APPROVED' and p.order_id is not null "
                        + "and r.decided_at >= :from and r.decided_at < :to")
                .setParameter("hotelId", hotelId).setParameter("from", from).setParameter("to", to).getResultList();
        Object[] r = rows.isEmpty() ? new Object[] {null, null} : rows.get(0);
        BigDecimal amount = r[0] == null ? BigDecimal.ZERO : new BigDecimal(r[0].toString()).negate();
        return DailySales.of(new DailySales.Line("OUTLET_REFUNDS", "Refunds on counter sales", amount, r[1] == null ? 0 : ((Number) r[1]).longValue()));
    }
}
