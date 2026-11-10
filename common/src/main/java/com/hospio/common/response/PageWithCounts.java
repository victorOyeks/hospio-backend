package com.hospio.common.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/**
 * A {@link PagedResponse} that also carries what a screen shows above the list for the whole filtered range, not the page: tab
 * counts ({@code counts}, e.g. per status, computed without the status filter) and money ({@code totals}, in naira).
 */
public record PageWithCounts<T>(List<T> content, int number, int size, long totalElements, int totalPages, boolean first, boolean last,
                                boolean empty, Map<String, Long> counts, Map<String, BigDecimal> totals) {

    public static <E, T> PageWithCounts<T> of(Page<E> page, Function<E, T> mapper, Map<String, Long> counts, Map<String, BigDecimal> totals) {
        return new PageWithCounts<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(), page.getTotalElements(),
                page.getTotalPages(), page.isFirst(), page.isLast(), page.isEmpty(), counts == null ? Map.of() : counts,
                totals == null ? Map.of() : totals);
    }

    public static <T> PageWithCounts<T> of(PagedResponse<T> page, Map<String, Long> counts, Map<String, BigDecimal> totals) {
        return new PageWithCounts<>(page.content(), page.number(), page.size(), page.totalElements(), page.totalPages(), page.first(),
                page.last(), page.empty(), counts == null ? Map.of() : counts, totals == null ? Map.of() : totals);
    }
}
