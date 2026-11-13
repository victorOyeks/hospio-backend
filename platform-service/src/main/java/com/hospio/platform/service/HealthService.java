package com.hospio.platform.service;

import com.hospio.platform.client.TenantClient;
import com.hospio.platform.entity.HealthScore;
import com.hospio.platform.repository.ActivityEntryRepository;
import com.hospio.platform.repository.FeeStatementRepository;
import com.hospio.platform.repository.HealthScoreRepository;
import com.hospio.platform.repository.SupportTicketRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * L7 · Health per business, 0-100, from six factors: people active in the last 7 days, bookings and
 * payments in the last 7 days, open tickets, overdue fee statements and night audits missed this week.
 * Recomputed nightly for every LIVE business and on demand from the business page.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthService {

    public static final int GOOD = 70;
    public static final int WATCH = 40;

    private final HealthScoreRepository healthScores;
    private final ActivityEntryRepository activity;
    private final SupportTicketRepository tickets;
    private final FeeStatementRepository feeStatements;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public record Factors(long activeUsers7d, long bookings7d, long payments7d, long openTickets, long overdueStatements, long nightAuditsMissed7d) {
    }

    @Transactional(readOnly = true)
    public List<HealthScore> list(String businessId) {
        if (businessId != null && !businessId.isBlank()) {
            return healthScores.findByBusinessId(businessId).map(List::of).orElse(List.of());
        }
        return healthScores.findAllByOrderByScoreAsc();
    }

    @Transactional
    public HealthScore compute(String businessId) {
        Instant week = Instant.now().minus(7, ChronoUnit.DAYS);
        long dayCloses = activity.countByBusinessIdAndEventTypeAndOccurredAtAfter(businessId, "DayClosed", week);
        boolean everClosed = activity.existsByBusinessIdAndEventType(businessId, "DayClosed");
        Factors f = new Factors(
                activity.countDistinctActorsSince(businessId, week),
                activity.countByBusinessIdAndEventTypeAndOccurredAtAfter(businessId, "BookingCreated", week),
                activity.countByBusinessIdAndEventTypeAndOccurredAtAfter(businessId, "PaymentApproved", week),
                tickets.countByBusinessIdAndStatusIn(businessId, List.of("OPEN", "IN_PROGRESS")),
                feeStatements.findByBusinessIdAndStatusIn(businessId, List.of("OVERDUE")).size(),
                everClosed ? Math.max(0, 7 - dayCloses) : 0);
        HealthScore h = healthScores.findByBusinessId(businessId).orElseGet(() -> HealthScore.builder().businessId(businessId).build());
        h.setScore(score(f));
        h.setFactors(factorsJson(f));
        h.setComputedAt(Instant.now());
        return healthScores.save(h);
    }

    /** Nightly: every LIVE business tenant lists, each in its own transaction (one failure doesn't undo the others). */
    public int computeAll(List<TenantClient.Business> live) {
        int n = 0;
        for (TenantClient.Business b : live) {
            try {
                tx.executeWithoutResult(s -> compute(b.id()));
                n++;
            } catch (RuntimeException e) {
                log.warn("Health not computed for business {}: {}", b.id(), e.getMessage());
            }
        }
        return n;
    }

    /**
     * Adoption and activity earn points (up to 30 for people, 20 for bookings, 20 for payments, on top of a
     * base of 30); each open ticket costs 5, each overdue statement 15, each missed night audit 3.
     */
    public static int score(Factors f) {
        long s = 30;
        s += Math.min(f.activeUsers7d() * 5, 30);
        s += Math.min(f.bookings7d() * 2, 20);
        s += Math.min(f.payments7d(), 20);
        s -= f.openTickets() * 5;
        s -= f.overdueStatements() * 15;
        s -= f.nightAuditsMissed7d() * 3;
        return (int) Math.max(0, Math.min(100, s));
    }

    public static String bucket(int score) {
        return score >= GOOD ? "good" : score >= WATCH ? "watch" : "atRisk";
    }

    static String factorsJson(Factors f) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("activeUsers7d", f.activeUsers7d());
        m.put("bookings7d", f.bookings7d());
        m.put("payments7d", f.payments7d());
        m.put("openTickets", f.openTickets());
        m.put("overdueStatements", f.overdueStatements());
        m.put("nightAuditsMissed7d", f.nightAuditsMissed7d());
        StringBuilder sb = new StringBuilder("{");
        m.forEach((k, v) -> sb.append(sb.length() > 1 ? "," : "").append('"').append(k).append("\":").append(v));
        return sb.append('}').toString();
    }
}
