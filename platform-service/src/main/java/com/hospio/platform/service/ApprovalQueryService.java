package com.hospio.platform.service;

import com.hospio.common.enums.ApprovalStatus;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.paging.Paging;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.entity.Approval;
import com.hospio.platform.repository.ApprovalRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The approvals list a page at a time (docs/pagination.md), filtered and counted in the database. Tabs follow the Approvals screen:
 * {@code waiting} (a manager-level request waiting, or a question asked about the caller's own request), {@code owner} (with the owner,
 * open), {@code decided} (approved, declined, or a question the manager asked) and {@code open} (waiting or questioned at any level).
 * The 90-day window applies to closed approvals only: an open one is listed however old it is, unless {@code from} is given.
 */
@Service
@RequiredArgsConstructor
public class ApprovalQueryService {

    public static final List<String> TABS = List.of("waiting", "owner", "decided", "open");
    private static final Map<String, String> SORTS = Map.of("createdAt", "createdAt", "amount", "amount", "kind", "kind",
            "requesterName", "requesterName", "status", "status");
    private static final List<ApprovalStatus> OPEN = List.of(ApprovalStatus.PENDING, ApprovalStatus.QUESTION);

    private final ApprovalRepository approvals;
    private final ApprovalService service;
    private final EntityManager em;

    public record Filter(String tab, String status, String kind, String q, LocalDate from, LocalDate to) {
    }

    /** {@code counts}: waiting, owner, decided, open, questions, all over the filtered range; {@code totals.waiting}: the money waiting on the caller. */
    @Transactional(readOnly = true)
    public PageWithCounts<Approval> page(Filter f, Integer page, Integer size, String sort) {
        String tab = f.tab() == null || f.tab().isBlank() ? null : f.tab().trim().toLowerCase(Locale.ROOT);
        if (tab != null && !TABS.contains(tab)) {
            throw new BadRequestException("tab is one of " + String.join(", ", TABS));
        }
        ApprovalStatus status = parseStatus(f.status());
        String me = CurrentUser.require().getUserId();
        Specification<Approval> base = base(f);
        Specification<Approval> spec = base;
        if (tab != null) {
            spec = spec.and(tab(tab, me));
        }
        if (status != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("status"), status));
        }
        Sort order = Paging.sort(sort, SORTS, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<Approval> result = approvals.findAll(spec, Paging.of(page, size, order));
        result.getContent().forEach(service::fillMissing);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String t : TABS) {
            counts.put(t, approvals.count(base.and(tab(t, me))));
        }
        counts.put("questions", approvals.count(base.and((r, q, cb) -> cb.equal(r.get("status"), ApprovalStatus.QUESTION))));
        counts.put("all", approvals.count(base));
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put("waiting", sum(base.and(tab("waiting", me))));
        totals.put("owner", sum(base.and(tab("owner", me))));
        return PageWithCounts.of(result, a -> a, counts, totals);
    }

    /** One approval of the caller's business (the screen opens it from a link even when it is not on the page). */
    @Transactional(readOnly = true)
    public Approval get(String id) {
        Approval a = approvals.findById(id).filter(x -> x.getBusinessId().equals(TenantContext.requireBusiness()))
                .orElseThrow(() -> new ResourceNotFoundException("Approval not found"));
        service.fillMissing(a);
        return a;
    }

    private static ApprovalStatus parseStatus(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            return ApprovalStatus.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown status " + s);
        }
    }

    static Specification<Approval> tab(String tab, String me) {
        return (r, q, cb) -> switch (tab) {
            case "waiting" -> cb.or(cb.and(cb.equal(r.get("status"), ApprovalStatus.PENDING), cb.equal(r.get("approverLevel"), "MANAGER")),
                    cb.and(cb.equal(r.get("status"), ApprovalStatus.QUESTION), cb.equal(r.get("requesterId"), me)));
            case "owner" -> cb.and(cb.equal(r.get("approverLevel"), "OWNER"), r.get("status").in(OPEN));
            case "decided" -> cb.or(r.get("status").in(ApprovalStatus.APPROVED, ApprovalStatus.DECLINED),
                    cb.and(cb.equal(r.get("status"), ApprovalStatus.QUESTION), cb.equal(r.get("approverLevel"), "MANAGER")));
            default -> r.get("status").in(OPEN);
        };
    }

    private static Specification<Approval> base(Filter f) {
        String business = TenantContext.requireBusiness();
        return (r, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(r.get("businessId"), business));
            if (StringUtils.hasText(f.kind())) {
                ps.add(cb.equal(r.get("kind"), f.kind().trim().toUpperCase(Locale.ROOT)));
            }
            if (StringUtils.hasText(f.q())) {
                String like = Paging.like(f.q());
                ps.add(cb.or(cb.like(cb.lower(cb.coalesce(r.get("description"), "")), like, '\\'),
                        cb.like(cb.lower(cb.coalesce(r.get("requesterName"), "")), like, '\\'),
                        cb.like(cb.lower(r.get("kind")), like, '\\'),
                        cb.like(cb.lower(cb.coalesce(r.get("hotelName"), "")), like, '\\')));
            }
            if (f.from() != null) {
                ps.add(cb.greaterThanOrEqualTo(r.get("createdAt"), f.from().atStartOfDay(Paging.LAGOS).toInstant()));
            } else {
                ps.add(cb.or(r.get("status").in(OPEN),
                        cb.greaterThanOrEqualTo(r.get("createdAt"), Paging.windowFrom(null).atStartOfDay(Paging.LAGOS).toInstant())));
            }
            if (f.to() != null) {
                ps.add(cb.lessThan(r.get("createdAt"), f.to().plusDays(1).atStartOfDay(Paging.LAGOS).toInstant()));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private BigDecimal sum(Specification<Approval> spec) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<BigDecimal> q = cb.createQuery(BigDecimal.class);
        Root<Approval> r = q.from(Approval.class);
        q.select(cb.coalesce(cb.sum(r.<BigDecimal>get("amount")), BigDecimal.ZERO)).where(spec.toPredicate(r, q, cb));
        BigDecimal v = em.createQuery(q).getSingleResult();
        return v == null ? BigDecimal.ZERO : v.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
