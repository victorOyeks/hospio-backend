package com.hospio.maintenance.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.paging.Paging;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.maintenance.dto.MaintenanceViews;
import com.hospio.maintenance.entity.Asset;
import com.hospio.maintenance.entity.Repair;
import com.hospio.maintenance.repository.RepairRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Work orders paged in the database (docs/pagination.md). {@code scope} picks the tab: open, mine (assigned to me), raised (raised by me),
 * overdue, held, unassigned (all four open only), done (done or cancelled) or all. {@code counts} has every tab over the same search,
 * room and dates, so the tab numbers don't move between pages.
 */
@Service
@RequiredArgsConstructor
public class RepairLists {

    static final List<String> SCOPES = List.of("open", "mine", "raised", "overdue", "held", "unassigned", "done", "all");
    private static final Set<Repair.Status> CLOSED = EnumSet.complementOf(EnumSet.copyOf(WorkOrderRules.OPEN));

    private final RepairRepository repairs;
    private final MaintenanceService service;

    public record Query(String scope, String roomId, String q, LocalDate from, LocalDate to, String sort, Integer page, Integer size) {
    }

    @Transactional(readOnly = true)
    public PageWithCounts<MaintenanceViews.RepairView> page(Query query) {
        HospioUserDetails u = CurrentUser.require();
        String hotel = TenantContext.requireHotel();
        String scope = query.scope() == null || query.scope().isBlank() ? "open" : query.scope().trim().toLowerCase(Locale.ROOT);
        if (!SCOPES.contains(scope)) {
            throw new BadRequestException("Pick a list: " + String.join(", ", SCOPES));
        }
        if (query.from() != null && query.to() != null && query.to().isBefore(query.from())) {
            throw new BadRequestException("to is before from");
        }
        Instant now = Instant.now();
        Specification<Repair> base = base(hotel, query);
        Sort fallback = "done".equals(scope)
                ? Sort.by(Sort.Order.desc("doneAt").nullsLast(), Sort.Order.desc("createdAt"), Sort.Order.desc("id"))
                : Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));
        Sort sort = Paging.sort(query.sort(), Map.of("createdAt", "createdAt", "dueAt", "dueAt", "doneAt", "doneAt", "priority", "priority",
                "number", "number", "status", "status"), fallback);
        Page<Repair> page = repairs.findAll(base.and(scope(scope, u.getUserId(), now)), Paging.of(query.page(), query.size(), sort));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String s : SCOPES) {
            counts.put(s, repairs.count(base.and(scope(s, u.getUserId(), now))));
        }
        Map<String, Asset> am = service.assetMap(hotel);
        return PageWithCounts.of(page, r -> service.view(r, am, null, now), counts, null);
    }

    /** The search, room and dates every tab shares. */
    static Specification<Repair> base(String hotel, Query query) {
        return (r, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(r.get("hotelId"), hotel));
            if (StringUtils.hasText(query.roomId())) {
                ps.add(cb.equal(r.get("roomId"), query.roomId().trim()));
            }
            if (query.from() != null) {
                ps.add(cb.greaterThanOrEqualTo(r.get("createdAt"), query.from().atStartOfDay(Paging.LAGOS).toInstant()));
            }
            if (query.to() != null) {
                ps.add(cb.lessThan(r.get("createdAt"), query.to().plusDays(1).atStartOfDay(Paging.LAGOS).toInstant()));
            }
            if (StringUtils.hasText(query.q())) {
                String like = Paging.like(query.q());
                List<Predicate> any = new ArrayList<>();
                for (String a : List.of("number", "title", "roomNumber", "area", "assignedToName", "reportedByName", "description")) {
                    any.add(cb.like(cb.lower(cb.coalesce(r.get(a), "")), like, '\\'));
                }
                ps.add(cb.or(any.toArray(new Predicate[0])));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    static Specification<Repair> scope(String scope, String me, Instant now) {
        return (r, q, cb) -> {
            Predicate open = r.get("status").in(WorkOrderRules.OPEN);
            return switch (scope) {
                case "open" -> open;
                case "mine" -> cb.and(open, cb.equal(r.get("assignedTo"), me));
                case "raised" -> cb.and(open, cb.equal(r.get("reportedBy"), me));
                case "overdue" -> cb.and(open, cb.lessThan(r.get("dueAt"), now));
                case "held" -> cb.equal(r.get("status"), Repair.Status.ON_HOLD);
                case "unassigned" -> cb.and(open, cb.isNull(r.get("assignedTo")));
                case "done" -> r.get("status").in(CLOSED);
                default -> cb.conjunction();
            };
        };
    }
}
