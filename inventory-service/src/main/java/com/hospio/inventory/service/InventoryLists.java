package com.hospio.inventory.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.paging.Paging;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.response.PagedResponse;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.inventory.dto.InventoryViews;
import com.hospio.inventory.entity.Delivery;
import com.hospio.inventory.entity.PurchaseOrder;
import com.hospio.inventory.entity.StockCount;
import com.hospio.inventory.entity.StockItem;
import com.hospio.inventory.entity.StockMovement;
import com.hospio.inventory.entity.StockRequest;
import com.hospio.inventory.entity.Store;
import com.hospio.inventory.entity.Supplier;
import com.hospio.inventory.repository.DeliveryRepository;
import com.hospio.inventory.repository.PurchaseOrderRepository;
import com.hospio.inventory.repository.StockCountRepository;
import com.hospio.inventory.repository.StockMovementRepository;
import com.hospio.inventory.repository.StockRequestRepository;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * The store's growing lists, paged in the database (docs/pagination.md): team requests, purchase orders, deliveries, stock counts and
 * stock movements. Visibility follows {@link InventoryService}: a team sees its own requests and counts, the store, managers and finance
 * see all. Tab counts are per status over every filter but the status.
 */
@Service
@RequiredArgsConstructor
public class InventoryLists {

    private static final Sort NEWEST = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private final InventoryService inventory;
    private final StockRequestRepository requests;
    private final PurchaseOrderRepository orders;
    private final DeliveryRepository deliveries;
    private final StockCountRepository counts;
    private final StockMovementRepository movements;
    private final GroupCounts groups;

    /** The filters every list here shares. Dates are Lagos days, both inclusive, on when the row was made. */
    public record Query(String q, String status, LocalDate from, LocalDate to, String sort, Integer page, Integer size) {
    }

    // ---- requests -----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageWithCounts<InventoryViews.RequestView> requests(String team, Query query) {
        HospioUserDetails u = InventoryService.user();
        String hotel = TenantContext.requireHotel();
        boolean all = InventoryService.isStore(u) || u.isManagerOrOwner() || u.isPlatform() || u.has(com.hospio.common.enums.Role.INVENTORY);
        Set<String> mine = InventoryService.teamsOf(u);
        String wanted = StringUtils.hasText(team) ? team.trim().toUpperCase(Locale.ROOT) : null;
        if (!all && wanted != null && !mine.contains(wanted)) {
            throw new ForbiddenException("You can only see your own team's requests");
        }
        List<Specification<StockRequest>> base = new ArrayList<>();
        base.add(eq("hotelId", hotel));
        if (!all) {
            base.add(in("fromTeam", mine));
        }
        if (wanted != null) {
            base.add(eq("fromTeam", wanted));
        }
        base.add(between(query.from(), query.to()));
        base.add(like(query.q(), "requestedByName", "note", "lines", "fromTeam", "issuedByName", "reason"));
        Specification<StockRequest> spec = Specification.allOf(base);
        Specification<StockRequest> filtered = spec.and(statusIn(StockRequest.Status.class, query.status(),
                Map.of("OPEN", List.of("REQUESTED", "PART_ISSUED"))));
        Sort sort = Paging.sort(query.sort(), Map.of("createdAt", "createdAt", "status", "status", "fromTeam", "fromTeam"), NEWEST);
        Page<StockRequest> page = requests.findAll(filtered, Paging.of(query.page(), query.size(), sort));
        Map<String, StockItem> im = inventory.itemMap();
        Map<String, Store> sm = inventory.storeMap();
        Map<String, Long> c = groups.by(StockRequest.class, spec, "status");
        c.put("OPEN", c.getOrDefault("REQUESTED", 0L) + c.getOrDefault("PART_ISSUED", 0L));
        return PageWithCounts.of(page, r -> inventory.requestView(r, im, sm), c, null);
    }

    // ---- purchase orders ----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageWithCounts<InventoryViews.OrderView> purchaseOrders(String supplierId, Query query) {
        String hotel = TenantContext.requireHotel();
        Map<String, Supplier> sup = inventory.supplierMap();
        List<Specification<PurchaseOrder>> base = new ArrayList<>();
        base.add(eq("hotelId", hotel));
        if (StringUtils.hasText(supplierId)) {
            base.add(eq("supplierId", supplierId.trim()));
        }
        base.add(between(query.from(), query.to()));
        if (StringUtils.hasText(query.q())) {
            // The supplier's name lives on the supplier: match it here (a hotel has a handful) and look for those ids.
            String needle = query.q().trim().toLowerCase(Locale.ROOT);
            List<String> named = sup.values().stream().filter(s -> s.getName() != null && s.getName().toLowerCase(Locale.ROOT).contains(needle))
                    .map(Supplier::getId).toList();
            Specification<PurchaseOrder> text = like(query.q(), "number", "note", "lines", "createdByName");
            base.add(named.isEmpty() ? text : text.or(in("supplierId", named)));
        }
        Specification<PurchaseOrder> spec = Specification.allOf(base);
        Specification<PurchaseOrder> filtered = spec.and(statusIn(PurchaseOrder.Status.class, query.status(),
                Map.of("OPEN", List.of("SENT", "PARTIAL"), "DONE", List.of("RECEIVED", "CLOSED", "CANCELLED"))));
        Sort sort = Paging.sort(query.sort(), Map.of("createdAt", "createdAt", "number", "number", "total", "total", "expectedOn", "expectedOn",
                "status", "status"), NEWEST);
        Page<PurchaseOrder> page = orders.findAll(filtered, Paging.of(query.page(), query.size(), sort));
        Map<String, Store> sm = inventory.storeMap();
        Map<String, StockItem> im = inventory.itemMap();
        Map<String, Long> c = groups.by(PurchaseOrder.class, spec, "status");
        return PageWithCounts.of(page, p -> inventory.orderView(p, sup, sm, im, null, null), c, null);
    }

    // ---- deliveries ---------------------------------------------------------------------------------

    /** Deliveries, newest first; only the orders the page's rows point at are read (for their numbers). */
    @Transactional(readOnly = true)
    public PageWithCounts<InventoryViews.DeliveryView> deliveries(String purchaseOrderId, String storeId, Query query) {
        String hotel = TenantContext.requireHotel();
        List<Specification<Delivery>> base = new ArrayList<>();
        base.add(eq("hotelId", hotel));
        if (StringUtils.hasText(purchaseOrderId)) {
            base.add(eq("purchaseOrderId", purchaseOrderId.trim()));
        }
        if (StringUtils.hasText(storeId)) {
            base.add(eq("storeId", storeId.trim()));
        }
        base.add(between(query.from(), query.to()));
        if (StringUtils.hasText(query.q())) {
            // An order number finds its deliveries too.
            List<String> numbered = orders.findAll(Specification.allOf(List.of(eq("hotelId", hotel), like(query.q(), "number"))))
                    .stream().map(PurchaseOrder::getId).toList();
            Specification<Delivery> text = like(query.q(), "supplierName", "invoiceNumber", "receivedByName", "summary", "note", "lines");
            base.add(numbered.isEmpty() ? text : text.or(in("purchaseOrderId", numbered)));
        }
        Specification<Delivery> spec = Specification.allOf(base);
        Sort sort = Paging.sort(query.sort(), Map.of("createdAt", "createdAt", "total", "total", "supplierName", "supplierName"), NEWEST);
        Page<Delivery> page = deliveries.findAll(spec, Paging.of(query.page(), query.size(), sort));
        Set<String> poIds = page.getContent().stream().map(Delivery::getPurchaseOrderId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, PurchaseOrder> pos = poIds.isEmpty() ? Map.of()
                : orders.findAllById(poIds).stream().collect(Collectors.toMap(PurchaseOrder::getId, Function.identity()));
        Map<String, Store> sm = inventory.storeMap();
        Map<String, StockItem> im = inventory.itemMap();
        BigDecimal value = groups.sum(Delivery.class, spec, "total");
        return PageWithCounts.of(page, d -> inventory.deliveryView(d, pos, sm, im), Map.of("all", page.getTotalElements()),
                Map.of("value", value));
    }

    // ---- counts -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageWithCounts<InventoryViews.CountView> counts(String storeId, Query query) {
        HospioUserDetails u = InventoryService.user();
        String hotel = TenantContext.requireHotel();
        Map<String, Store> sm = inventory.storeMap();
        boolean all = InventoryService.isStore(u) || InventoryService.seesMoney(u);
        List<Specification<StockCount>> base = new ArrayList<>();
        base.add(eq("hotelId", hotel));
        if (!all) {
            Set<String> mine = InventoryService.teamsOf(u);
            base.add(in("storeId", sm.values().stream().filter(s -> mine.contains(s.getKind())).map(Store::getId).toList()));
        }
        if (StringUtils.hasText(storeId)) {
            base.add(eq("storeId", storeId.trim()));
        }
        base.add(between(query.from(), query.to()));
        if (StringUtils.hasText(query.q())) {
            String needle = query.q().trim().toLowerCase(Locale.ROOT);
            List<String> named = sm.values().stream().filter(s -> s.getName() != null && s.getName().toLowerCase(Locale.ROOT).contains(needle))
                    .map(Store::getId).toList();
            Specification<StockCount> text = like(query.q(), "startedByName", "countedByName", "approvedByName", "note", "cancelReason");
            base.add(named.isEmpty() ? text : text.or(in("storeId", named)));
        }
        Specification<StockCount> spec = Specification.allOf(base);
        Specification<StockCount> filtered = spec.and(statusIn(StockCount.Status.class, query.status(),
                Map.of("ACTIVE", List.of("OPEN", "COUNTED"))));
        Sort sort = Paging.sort(query.sort(), Map.of("createdAt", "createdAt", "status", "status", "varianceValue", "varianceValue"), NEWEST);
        Page<StockCount> page = counts.findAll(filtered, Paging.of(query.page(), query.size(), sort));
        return PageWithCounts.of(page, c -> inventory.countView(c, sm), groups.by(StockCount.class, spec, "status"), null);
    }

    // ---- movements ----------------------------------------------------------------------------------

    /** Stock movements in a date range (the last 90 days unless {@code from} is given), newest first, with their value. */
    @Transactional(readOnly = true)
    public PagedResponse<InventoryViews.MovementView> movements(String itemId, String storeId, String kind, Query query) {
        String hotel = TenantContext.requireHotel();
        List<Specification<StockMovement>> base = new ArrayList<>();
        base.add(eq("hotelId", hotel));
        if (StringUtils.hasText(itemId)) {
            base.add(eq("itemId", itemId.trim()));
        }
        if (StringUtils.hasText(storeId)) {
            base.add(eq("storeId", storeId.trim()));
        }
        base.add(statusIn(StockMovement.Kind.class, kind, Map.of(), "kind"));
        base.add(between(Paging.windowFrom(query.from()), query.to()));
        if (StringUtils.hasText(query.q())) {
            base.add(InventoryLists.<StockMovement>like(query.q(), "reference", "note", "byName", "team").or(itemNamed(query.q())));
        }
        Sort sort = Paging.sort(query.sort(), Map.of("createdAt", "createdAt", "quantity", "quantity"), NEWEST);
        Page<StockMovement> page = movements.findAll(Specification.allOf(base), Paging.of(query.page(), query.size(), sort));
        Map<String, StockItem> im = inventory.itemMap();
        Map<String, Store> sm = inventory.storeMap();
        return PagedResponse.of(page, m -> inventory.movementView(m, im, sm, true));
    }

    // ---- specifications -----------------------------------------------------------------------------

    static <T> Specification<T> eq(String attr, Object value) {
        return (r, q, cb) -> cb.equal(r.get(attr), value);
    }

    static <T> Specification<T> in(String attr, Collection<String> values) {
        return (r, q, cb) -> values.isEmpty() ? cb.disjunction() : r.get(attr).in(values);
    }

    /** created_at within the Lagos days {@code from}..{@code to} (either may be open). */
    static <T> Specification<T> between(LocalDate from, LocalDate to) {
        return (r, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (from != null) {
                ps.add(cb.greaterThanOrEqualTo(r.get("createdAt"), from.atStartOfDay(Paging.LAGOS).toInstant()));
            }
            if (to != null) {
                ps.add(cb.lessThan(r.get("createdAt"), to.plusDays(1).atStartOfDay(Paging.LAGOS).toInstant()));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /** Movements of an item whose name contains {@code q} (the list shows the item, so the search finds it by name). */
    static Specification<StockMovement> itemNamed(String q) {
        return (r, query, cb) -> {
            jakarta.persistence.criteria.Subquery<String> sq = query.subquery(String.class);
            jakarta.persistence.criteria.Root<StockItem> i = sq.from(StockItem.class);
            sq.select(i.get("id")).where(cb.like(cb.lower(i.get("name")), Paging.like(q), '\\'));
            return r.get("itemId").in(sq);
        };
    }

    /** Case-insensitive "contains" over any of the text attributes; nothing when {@code q} is blank. */
    static <T> Specification<T> like(String q, String... attrs) {
        return (r, query, cb) -> {
            if (!StringUtils.hasText(q)) {
                return cb.conjunction();
            }
            String pattern = Paging.like(q);
            List<Predicate> ps = new ArrayList<>();
            for (String a : attrs) {
                Expression<String> e = cb.lower(cb.coalesce(r.get(a), ""));
                ps.add(cb.like(e, pattern, '\\'));
            }
            return cb.or(ps.toArray(new Predicate[0]));
        };
    }

    static <T, E extends Enum<E>> Specification<T> statusIn(Class<E> type, String csv, Map<String, List<String>> aliases) {
        return statusIn(type, csv, aliases, "status");
    }

    /** A comma-separated list of statuses (or aliases such as OPEN) on {@code attr}; blank means any; an unknown one is a 400. */
    static <T, E extends Enum<E>> Specification<T> statusIn(Class<E> type, String csv, Map<String, List<String>> aliases, String attr) {
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
                    throw new BadRequestException("Unknown " + attr + " " + raw.trim() + "; use one of "
                            + Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", ")));
                }
            }
        }
        if (wanted.isEmpty()) {
            return (r, q, cb) -> cb.conjunction();
        }
        return (r, q, cb) -> r.get(attr).in(wanted);
    }

    /** The query from the request parameters (blank text means none). */
    public static Query query(String q, String status, LocalDate from, LocalDate to, String sort, Integer page, Integer size) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new BadRequestException("to is before from");
        }
        return new Query(StringUtils.hasText(q) ? q.trim() : null, status, from, to, sort, page, size);
    }
}
