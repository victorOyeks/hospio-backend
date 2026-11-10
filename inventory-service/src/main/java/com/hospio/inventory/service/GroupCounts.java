package com.hospio.inventory.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** Tab counts for a paged list: rows per value of one attribute (usually the status) under a filter, counted in the database, plus "all". */
@Component
@RequiredArgsConstructor
public class GroupCounts {

    private final EntityManager em;

    public <T> Map<String, Long> by(Class<T> type, Specification<T> spec, String attribute) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
        Root<T> root = q.from(type);
        q.multiselect(root.get(attribute), cb.count(root));
        Predicate p = spec == null ? null : spec.toPredicate(root, q, cb);
        if (p != null) {
            q.where(p);
        }
        q.groupBy(root.get(attribute));
        Map<String, Long> out = new LinkedHashMap<>();
        long all = 0;
        for (Object[] r : em.createQuery(q).getResultList()) {
            long n = ((Number) r[1]).longValue();
            out.put(r[0] == null ? "NONE" : (r[0] instanceof Enum<?> e ? e.name() : String.valueOf(r[0])), n);
            all += n;
        }
        out.put("all", all);
        return out;
    }

    /** The sum of a money attribute under a filter (zero when nothing matches). */
    public <T> java.math.BigDecimal sum(Class<T> type, Specification<T> spec, String attribute) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<java.math.BigDecimal> q = cb.createQuery(java.math.BigDecimal.class);
        Root<T> root = q.from(type);
        q.select(cb.coalesce(cb.sum(root.<java.math.BigDecimal>get(attribute)), java.math.BigDecimal.ZERO));
        Predicate p = spec == null ? null : spec.toPredicate(root, q, cb);
        if (p != null) {
            q.where(p);
        }
        java.math.BigDecimal v = em.createQuery(q).getSingleResult();
        return v == null ? java.math.BigDecimal.ZERO : v;
    }
}
