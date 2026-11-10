package com.hospio.common.paging;

import com.hospio.common.exception.BadRequestException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * The paging contract every growing list follows (docs/pagination.md): {@code page} counts from 0, {@code size} is 1 to 100
 * (25 when absent; anything else is a 400), {@code sort} is {@code field} or {@code field,asc|desc} over the fields a list allows.
 * A list that still answers unpaged for internal callers checks {@link #requested} first.
 */
public final class Paging {

    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 100;
    /** How far back a list with a natural window (payments, expenses, notifications, approvals) reads when no {@code from} is given. */
    public static final int DEFAULT_WINDOW_DAYS = 90;
    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private Paging() {
    }

    /** True when the caller asked for a page (either parameter present): the list answers a {@code PagedResponse}. */
    public static boolean requested(Integer page, Integer size) {
        return page != null || size != null;
    }

    /** The checked size: 25 when absent, 1 to 100 or a 400. */
    public static int size(Integer size) {
        if (size == null) {
            return DEFAULT_SIZE;
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new BadRequestException("size is 1 to " + MAX_SIZE);
        }
        return size;
    }

    /** The checked page: 0 when absent, never negative. */
    public static int page(Integer page) {
        if (page == null) {
            return 0;
        }
        if (page < 0) {
            throw new BadRequestException("page counts from 0");
        }
        return page;
    }

    public static PageRequest of(Integer page, Integer size, Sort sort) {
        return PageRequest.of(page(page), size(size), sort == null ? Sort.unsorted() : sort);
    }

    /**
     * The sort a list asked for: {@code field} or {@code field,asc|desc}, where {@code field} is one of {@code allowed}'s keys (mapped onto
     * the entity's property path). Blank means {@code fallback}; an unknown field is a 400. The fallback's orders follow as tie-breakers.
     */
    public static Sort sort(String sort, Map<String, String> allowed, Sort fallback) {
        if (sort == null || sort.isBlank()) {
            return fallback;
        }
        String[] parts = sort.split(",");
        String key = parts[0].trim();
        String property = allowed.get(key);
        if (property == null) {
            throw new BadRequestException("can't sort by " + key + "; use one of " + String.join(", ", allowed.keySet()));
        }
        Sort.Direction dir = Sort.Direction.DESC;
        if (parts.length > 1) {
            String d = parts[1].trim().toLowerCase(Locale.ROOT);
            if (d.equals("asc")) {
                dir = Sort.Direction.ASC;
            } else if (!d.equals("desc") && !d.isEmpty()) {
                throw new BadRequestException("sort direction is asc or desc");
            }
        }
        return Sort.by(dir, property).and(fallback);
    }

    /** {@code from} when given, else {@link #DEFAULT_WINDOW_DAYS} days before today in Lagos. */
    public static LocalDate windowFrom(LocalDate from) {
        return from != null ? from : LocalDate.now(LAGOS).minusDays(DEFAULT_WINDOW_DAYS);
    }

    /** A page cut from a list already filtered and ordered (only for short lists that are assembled in memory). */
    public static <E, T> com.hospio.common.response.PagedResponse<T> slice(List<E> all, Integer page, Integer size, Function<E, T> mapper) {
        int p = page(page);
        int s = size(size);
        int from = Math.min(all.size(), p * s);
        int to = Math.min(all.size(), from + s);
        int pages = (int) Math.ceil(all.size() / (double) s);
        return new com.hospio.common.response.PagedResponse<>(all.subList(from, to).stream().map(mapper).toList(), p, s, all.size(), pages,
                p == 0, p + 1 >= pages, to == from);
    }

    /** A {@code like} pattern for a case-insensitive contains search: lower-cased, with % and _ taken literally. */
    public static String like(String q) {
        String s = q.trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + s + "%";
    }
}
