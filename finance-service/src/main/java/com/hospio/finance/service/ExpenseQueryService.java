package com.hospio.finance.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.paging.Paging;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.finance.entity.Expense;
import com.hospio.finance.entity.NightAudit;
import com.hospio.finance.repository.ExpenseRepository;
import com.hospio.finance.repository.NightAuditRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * The hotel's expenses and closed night audits a page at a time (docs/pagination.md), filtered, counted and summed in the database.
 * Expenses are newest first by date, then by when they were recorded. Tabs: {@code approval} (waiting for approval), {@code questions},
 * {@code paid} (approved or paid), {@code requests} (waiting or questioned). The 90-day window applies to settled expenses only: one still
 * waiting or questioned is listed however old, unless {@code from} is given.
 */
@Service
@RequiredArgsConstructor
public class ExpenseQueryService {

    public static final List<String> TABS = List.of("approval", "questions", "paid", "requests");
    private static final Map<String, String> SORTS = Map.of("date", "expenseDate", "amount", "amount", "category", "category",
            "description", "description", "status", "status", "createdAt", "createdAt");
    private static final List<Expense.Status> OPEN = List.of(Expense.Status.PENDING_APPROVAL, Expense.Status.QUESTION);

    private final ExpenseRepository expenses;
    private final NightAuditRepository audits;
    private final EntityManager em;

    public record Filter(String tab, String status, String category, String q, LocalDate from, LocalDate to) {
    }

    /**
     * {@code counts}: all, approval, questions, paid, requests over the filtered range (without the tab or status). {@code totals}: {@code all}
     * (every expense in the range), {@code recorded} (all but declined), {@code requests} (waiting or questioned).
     */
    @Transactional(readOnly = true)
    public PageWithCounts<Expense> page(Filter f, Integer page, Integer size, String sort) {
        String tab = StringUtils.hasText(f.tab()) ? f.tab().trim().toLowerCase(Locale.ROOT) : null;
        if (tab != null && !"all".equals(tab) && !TABS.contains(tab)) {
            throw new BadRequestException("tab is one of all, " + String.join(", ", TABS));
        }
        Expense.Status status = parseStatus(f.status());
        Specification<Expense> base = base(f);
        Specification<Expense> spec = base;
        if (tab != null && !"all".equals(tab)) {
            spec = spec.and(tab(tab));
        }
        if (status != null) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("status"), status));
        }
        Sort order = Paging.sort(sort, SORTS, Sort.by(Sort.Direction.DESC, "expenseDate").and(Sort.by(Sort.Direction.DESC, "createdAt"))
                .and(Sort.by(Sort.Direction.DESC, "id")));
        Page<Expense> result = expenses.findAll(spec, Paging.of(page, size, order));
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("all", expenses.count(base));
        for (String t : TABS) {
            counts.put(t, expenses.count(base.and(tab(t))));
        }
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put("all", sum(base));
        totals.put("recorded", sum(base.and((r, q, cb) -> cb.notEqual(r.get("status"), Expense.Status.DECLINED))));
        totals.put("requests", sum(base.and(tab("requests"))));
        totals.put("matching", sum(spec));
        return PageWithCounts.of(result, e -> e, counts, totals);
    }

    /** Closed and open night audits of the hotel, newest business day first; {@code status} OPEN or CLOSED, {@code from}/{@code to} business days. */
    @Transactional(readOnly = true)
    public com.hospio.common.response.PagedResponse<NightAudit> audits(String status, LocalDate from, LocalDate to, Integer page, Integer size) {
        String hotel = TenantContext.requireHotel();
        NightAudit.Status wanted = null;
        if (StringUtils.hasText(status)) {
            try {
                wanted = NightAudit.Status.valueOf(status.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown status " + status);
            }
        }
        NightAudit.Status s = wanted;
        Specification<NightAudit> spec = (r, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(r.get("hotelId"), hotel));
            if (s != null) {
                ps.add(cb.equal(r.get("status"), s));
            }
            if (from != null) {
                ps.add(cb.greaterThanOrEqualTo(r.get("businessDate"), from));
            }
            if (to != null) {
                ps.add(cb.lessThanOrEqualTo(r.get("businessDate"), to));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return com.hospio.common.response.PagedResponse.of(audits.findAll(spec,
                Paging.of(page, size, Sort.by(Sort.Direction.DESC, "businessDate").and(Sort.by(Sort.Direction.DESC, "id")))), a -> a);
    }

    private static Expense.Status parseStatus(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            return Expense.Status.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown status " + s);
        }
    }

    static Specification<Expense> tab(String tab) {
        return (r, q, cb) -> switch (tab) {
            case "approval" -> cb.equal(r.get("status"), Expense.Status.PENDING_APPROVAL);
            case "questions" -> cb.equal(r.get("status"), Expense.Status.QUESTION);
            case "paid" -> r.get("status").in(Expense.Status.APPROVED, Expense.Status.PAID);
            default -> r.get("status").in(OPEN);
        };
    }

    private static Specification<Expense> base(Filter f) {
        String hotel = TenantContext.requireHotel();
        return (r, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(r.get("hotelId"), hotel));
            if (StringUtils.hasText(f.category())) {
                ps.add(cb.equal(r.get("category"), f.category().trim()));
            }
            if (StringUtils.hasText(f.q())) {
                String like = Paging.like(f.q());
                ps.add(cb.or(cb.like(cb.lower(cb.coalesce(r.get("description"), "")), like, '\\'),
                        cb.like(cb.lower(cb.coalesce(r.get("category"), "")), like, '\\'),
                        cb.like(cb.lower(cb.coalesce(r.get("requestedByName"), "")), like, '\\'),
                        cb.like(cb.lower(cb.coalesce(r.get("paidFrom"), "")), like, '\\')));
            }
            if (f.from() != null) {
                ps.add(cb.greaterThanOrEqualTo(r.get("expenseDate"), f.from()));
            } else {
                ps.add(cb.or(r.get("status").in(OPEN), cb.greaterThanOrEqualTo(r.get("expenseDate"), Paging.windowFrom(null))));
            }
            if (f.to() != null) {
                ps.add(cb.lessThanOrEqualTo(r.get("expenseDate"), f.to()));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private BigDecimal sum(Specification<Expense> spec) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<BigDecimal> q = cb.createQuery(BigDecimal.class);
        Root<Expense> r = q.from(Expense.class);
        q.select(cb.coalesce(cb.sum(r.<BigDecimal>get("amount")), BigDecimal.ZERO)).where(spec.toPredicate(r, q, cb));
        BigDecimal v = em.createQuery(q).getSingleResult();
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }
}
