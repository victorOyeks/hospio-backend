package com.hospio.finance.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.domain.Specification;

/**
 * A list's tab counts in one query: {@code count(*)} over the base filter plus {@code sum(case when <tab> then 1 else 0 end)} per tab,
 * instead of a COUNT per tab. Answers {@code all} first, then the tabs in the order given.
 */
final class TabCounts {

    private TabCounts() {
    }

    static <T> Map<String, Long> of(EntityManager em, Class<T> type, Specification<T> base, Map<String, Specification<T>> tabs) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> q = cb.createQuery(Object[].class);
        Root<T> r = q.from(type);
        List<Selection<?>> select = new ArrayList<>();
        select.add(cb.count(r));
        for (Specification<T> tab : tabs.values()) {
            select.add(cb.sum(cb.<Long>selectCase().when(tab.toPredicate(r, q, cb), 1L).otherwise(0L)));
        }
        q.multiselect(select).where(base.toPredicate(r, q, cb));
        Object[] row = em.createQuery(q).getSingleResult();
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("all", number(row[0]));
        int i = 1;
        for (String name : tabs.keySet()) {
            out.put(name, number(row[i++]));
        }
        return out;
    }

    private static long number(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
