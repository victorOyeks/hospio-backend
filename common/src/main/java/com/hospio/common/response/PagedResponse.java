package com.hospio.common.response;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** Paged list in the shape the front end reads: content plus page metadata. */
public record PagedResponse<T>(List<T> content, int number, int size, long totalElements, int totalPages,
                               boolean first, boolean last, boolean empty) {

    public static <E, T> PagedResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PagedResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.isFirst(), page.isLast(), page.isEmpty());
    }
}
