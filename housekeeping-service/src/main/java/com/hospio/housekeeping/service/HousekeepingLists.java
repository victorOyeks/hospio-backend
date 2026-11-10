package com.hospio.housekeeping.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.paging.Paging;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.housekeeping.dto.LaundryDtos;
import com.hospio.housekeeping.entity.LaundryOrder;
import com.hospio.housekeeping.entity.LinenBatch;
import com.hospio.housekeeping.entity.LostFoundItem;
import com.hospio.housekeeping.repository.LaundryOrderRepository;
import com.hospio.housekeeping.repository.LinenBatchRepository;
import com.hospio.housekeeping.repository.LostFoundItemRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Laundry orders, linen batches and lost & found paged in the database (docs/pagination.md): newest first, a text search, Lagos-day
 * dates and a status (or an alias such as OPEN); {@code counts} per status over every filter but the status.
 */
@Service
@RequiredArgsConstructor
public class HousekeepingLists {

    private final LaundryOrderRepository orders;
    private final LinenBatchRepository batches;
    private final LostFoundItemRepository lostFound;
    private final LaundryService laundry;
    private final GroupCounts groups;

    public record Query(String q, String status, LocalDate from, LocalDate to, String sort, Integer page, Integer size) {

        public Query {
            if (from != null && to != null && to.isBefore(from)) {
                throw new BadRequestException("to is before from");
            }
        }
    }

    @Transactional(readOnly = true)
    public PageWithCounts<LaundryDtos.OrderView> laundryOrders(String roomId, String bookingId, Query query) {
        List<Specification<LaundryOrder>> base = new ArrayList<>(common("createdAt", query, "number", "guestName", "roomNumber", "items", "lines",
                "note", "receivedByName"));
        if (StringUtils.hasText(roomId)) {
            base.add((r, q, cb) -> cb.equal(r.get("roomId"), roomId.trim()));
        }
        if (StringUtils.hasText(bookingId)) {
            base.add((r, q, cb) -> cb.equal(r.get("bookingId"), bookingId.trim()));
        }
        Specification<LaundryOrder> spec = Specification.allOf(base);
        Specification<LaundryOrder> filtered = spec.and(status(LaundryOrder.Status.class, query.status(),
                Map.of("OPEN", List.of("RECEIVED", "IN_PROGRESS", "READY"))));
        Sort sort = Paging.sort(query.sort(), Map.of("createdAt", "createdAt", "total", "total", "number", "number", "promisedAt", "promisedAt"),
                newest("createdAt"));
        Page<LaundryOrder> page = orders.findAll(filtered, Paging.of(query.page(), query.size(), sort));
        Map<String, Long> counts = groups.by(LaundryOrder.class, spec, "status");
        counts.put("OPEN", counts.getOrDefault("RECEIVED", 0L) + counts.getOrDefault("IN_PROGRESS", 0L) + counts.getOrDefault("READY", 0L));
        return PageWithCounts.of(page, laundry::orderView, counts, Map.of("value", groups.sum(LaundryOrder.class, filtered, "total")));
    }

    @Transactional(readOnly = true)
    public PageWithCounts<LaundryDtos.BatchView> linenBatches(Query query) {
        Specification<LinenBatch> spec = Specification.allOf(common("createdAt", query, "number", "lines", "note", "sentByName", "receivedByName",
                "closedReason"));
        Specification<LinenBatch> filtered = spec.and(status(LinenBatch.Status.class, query.status(),
                Map.of("OPEN", List.of("SENT", "RECEIVED", "PART_RETURNED"), "DONE", List.of("RETURNED", "CLOSED"))));
        Sort sort = Paging.sort(query.sort(), Map.of("createdAt", "createdAt", "number", "number", "status", "status"), newest("createdAt"));
        Page<LinenBatch> page = batches.findAll(filtered, Paging.of(query.page(), query.size(), sort));
        Map<String, Long> counts = groups.by(LinenBatch.class, spec, "status");
        counts.put("OPEN", counts.getOrDefault("SENT", 0L) + counts.getOrDefault("RECEIVED", 0L) + counts.getOrDefault("PART_RETURNED", 0L));
        // What was written off over the whole range (every status): condemned and lost or missing linen, at cost.
        java.math.BigDecimal condemned = groups.sum(LinenBatch.class, spec, "condemnedValue");
        java.math.BigDecimal lost = groups.sum(LinenBatch.class, spec, "lostValue");
        return PageWithCounts.of(page, laundry::batchView, counts, Map.of("condemned", condemned, "lost", lost, "writtenOff", condemned.add(lost)));
    }

    @Transactional(readOnly = true)
    public PageWithCounts<LostFoundItem> lostAndFound(Query query) {
        Specification<LostFoundItem> spec = Specification.allOf(common("foundAt", query, "description", "foundWhere", "returnedTo"));
        Specification<LostFoundItem> filtered = spec.and(status(LostFoundItem.Status.class, query.status(), Map.of()));
        Sort sort = Paging.sort(query.sort(), Map.of("foundAt", "foundAt", "status", "status"), newest("foundAt"));
        Page<LostFoundItem> page = lostFound.findAll(filtered, Paging.of(query.page(), query.size(), sort));
        return PageWithCounts.of(page, Function.identity(), groups.by(LostFoundItem.class, spec, "status"), null);
    }

    private static Sort newest(String at) {
        return Sort.by(Sort.Direction.DESC, at).and(Sort.by(Sort.Direction.DESC, "id"));
    }

    /** The hotel, the Lagos days on {@code at} and the text search over {@code text}. */
    private static <T> List<Specification<T>> common(String at, Query query, String... text) {
        String hotel = TenantContext.requireHotel();
        List<Specification<T>> out = new ArrayList<>();
        out.add((r, q, cb) -> cb.equal(r.get("hotelId"), hotel));
        out.add((r, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (query.from() != null) {
                ps.add(cb.greaterThanOrEqualTo(r.get(at), query.from().atStartOfDay(Paging.LAGOS).toInstant()));
            }
            if (query.to() != null) {
                ps.add(cb.lessThan(r.get(at), query.to().plusDays(1).atStartOfDay(Paging.LAGOS).toInstant()));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        });
        if (StringUtils.hasText(query.q())) {
            String like = Paging.like(query.q());
            out.add((r, q, cb) -> cb.or(Arrays.stream(text).map(a -> cb.like(cb.lower(cb.coalesce(r.get(a), "")), like, '\\'))
                    .toArray(Predicate[]::new)));
        }
        return out;
    }

    /** A comma-separated list of statuses or aliases; blank or ALL means any; an unknown one is a 400. */
    static <T, E extends Enum<E>> Specification<T> status(Class<E> type, String csv, Map<String, List<String>> aliases) {
        if (!StringUtils.hasText(csv)) {
            return (r, q, cb) -> cb.conjunction();
        }
        List<E> wanted = new ArrayList<>();
        for (String raw : csv.split(",")) {
            String s = raw.trim().toUpperCase(Locale.ROOT);
            if (s.isEmpty() || s.equals("ALL")) {
                continue;
            }
            for (String name : aliases.getOrDefault(s, List.of(s))) {
                try {
                    wanted.add(Enum.valueOf(type, name));
                } catch (IllegalArgumentException e) {
                    throw new BadRequestException("Unknown status " + raw.trim() + "; use one of "
                            + Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", ")));
                }
            }
        }
        return wanted.isEmpty() ? (r, q, cb) -> cb.conjunction() : (r, q, cb) -> r.get("status").in(wanted);
    }
}
