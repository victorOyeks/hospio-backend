package com.hospio.inventory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalRequestedEvent;
import com.hospio.common.kafka.events.DeliveryReceivedEvent;
import com.hospio.common.kafka.events.OrderSettledEvent;
import com.hospio.common.kafka.events.StockIssuedEvent;
import com.hospio.common.kafka.events.StockLowEvent;
import com.hospio.common.kafka.events.StockRequestedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.inventory.client.ApprovalLimit;
import com.hospio.inventory.dto.InventoryRequests;
import com.hospio.inventory.dto.InventoryViews;
import com.hospio.inventory.dto.InventoryViews.CountLine;
import com.hospio.inventory.dto.InventoryViews.DeliveryLine;
import com.hospio.inventory.dto.InventoryViews.OrderLine;
import com.hospio.inventory.dto.InventoryViews.RequestLine;
import com.hospio.inventory.entity.PurchaseOrder;
import com.hospio.inventory.entity.StockCount;
import com.hospio.inventory.entity.StockItem;
import com.hospio.inventory.entity.StockLevel;
import com.hospio.inventory.entity.StockMovement;
import com.hospio.inventory.entity.StockRequest;
import com.hospio.inventory.entity.Store;
import com.hospio.inventory.entity.Supplier;
import com.hospio.inventory.repository.DeliveryRepository;
import com.hospio.inventory.repository.PurchaseOrderRepository;
import com.hospio.inventory.repository.StockCountRepository;
import com.hospio.inventory.repository.StockItemRepository;
import com.hospio.inventory.repository.StockLevelRepository;
import com.hospio.inventory.repository.StockMovementRepository;
import com.hospio.inventory.repository.StockRequestRepository;
import com.hospio.inventory.repository.StoreRepository;
import com.hospio.inventory.repository.SupplierRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Stores and stock, requests from teams, transfers, suppliers, purchase orders, deliveries, counts, value and usage (docs/inventory-stores.md). */
@Service
@Slf4j
@RequiredArgsConstructor
public class InventoryService {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    static final Set<String> KINDS = Set.of("MAIN", "KITCHEN", "BAR", "HOUSEKEEPING", "LAUNDRY", "MAINTENANCE", "OTHER");
    /** Teams that ask the store and may own a store of their kind. */
    static final Set<Role> TEAMS = EnumSet.of(Role.KITCHEN, Role.BAR, Role.HOUSEKEEPING, Role.LAUNDRY, Role.MAINTENANCE, Role.FRONT_OFFICE);
    static final Set<PurchaseOrder.Status> OPEN_ORDERS = EnumSet.of(PurchaseOrder.Status.DRAFT, PurchaseOrder.Status.PENDING_APPROVAL,
            PurchaseOrder.Status.SENT, PurchaseOrder.Status.PARTIAL);
    static final Set<StockCount.Status> OPEN_COUNTS = EnumSet.of(StockCount.Status.OPEN, StockCount.Status.COUNTED);

    private final StoreRepository stores;
    private final StockItemRepository items;
    private final StockLevelRepository levels;
    private final StockMovementRepository movements;
    private final StockRequestRepository requests;
    private final SupplierRepository suppliers;
    private final PurchaseOrderRepository orders;
    private final DeliveryRepository deliveries;
    private final StockCountRepository counts;
    private final ObjectMapper objectMapper;
    private final EventPublisher events;
    private final ApprovalLimit approvalLimit;
    private final DeliveryService deliveryService;

    // ---- who may do what ----------------------------------------------------------------------------

    /** The store's own people: INVENTORY at Edit or Full, a manager or owner, the platform. */
    static boolean isStore(HospioUserDetails u) {
        return !u.isReadOnly() && (u.isPlatform() || u.isManagerOrOwner() || u.has(Role.INVENTORY, AccessLevel.EDIT));
    }

    /** Who may read values: the store, managers and owners (also read-only support), finance. */
    static boolean seesMoney(HospioUserDetails u) {
        return u.isPlatform() || u.isManagerOrOwner() || u.has(Role.INVENTORY) || u.has(Role.FINANCE);
    }

    static Set<String> teamsOf(HospioUserDetails u) {
        return TEAMS.stream().filter(u::has).map(Role::name).collect(Collectors.toCollection(TreeSet::new));
    }

    /** Whoever works a store may use or waste its stock: the store's own team (Kitchen, Bar, ...), the inventory team or a manager. */
    static boolean canUse(HospioUserDetails user, Store store) {
        if (user.isReadOnly()) {
            return false;
        }
        if (user.isPlatform() || user.isManagerOrOwner() || user.has(Role.INVENTORY, AccessLevel.EDIT)) {
            return true;
        }
        try {
            return user.has(Role.valueOf(store.getKind().toUpperCase()), AccessLevel.EDIT);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static HospioUserDetails user() {
        return CurrentUser.require();
    }

    // ---- stores ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<InventoryViews.StoreView> stores() {
        String hotel = TenantContext.requireHotel();
        boolean money = CurrentUser.get().map(InventoryService::seesMoney).orElse(false);
        Map<String, StockItem> itemMap = itemMap();
        List<StockLevel> all = levels.findByHotelId(hotel);
        return stores.findByHotelIdAndActiveTrueOrderByName(hotel).stream().map(s -> storeView(s, all, itemMap, money)).toList();
    }

    private InventoryViews.StoreView storeView(Store s, List<StockLevel> all, Map<String, StockItem> itemMap, boolean money) {
        int count = 0;
        int low = 0;
        BigDecimal value = BigDecimal.ZERO;
        for (StockLevel l : all) {
            StockItem i = itemMap.get(l.getItemId());
            if (!s.getId().equals(l.getStoreId()) || i == null || !i.isActive()) {
                continue;
            }
            count++;
            if (l.getQuantity().compareTo(i.getMinLevel()) <= 0) {
                low++;
            }
            value = value.add(l.getQuantity().multiply(cost(i)));
        }
        return new InventoryViews.StoreView(s.getId(), s.getName(), s.getKind(), s.isActive(), count, low, money ? Money.of(value) : null);
    }

    @Transactional
    public InventoryViews.StoreView saveStore(String id, InventoryRequests.StoreRequest request) {
        String hotel = TenantContext.requireHotel();
        Store s = id == null ? new Store() : store(id);
        String name = request.name().trim();
        String kind = request.kind() == null ? (s.getKind() == null ? "MAIN" : s.getKind()) : request.kind().trim().toUpperCase(Locale.ROOT);
        if (!KINDS.contains(kind)) {
            throw new BadRequestException("Pick a kind: Main, Kitchen, Bar, Housekeeping, Laundry, Maintenance or Other");
        }
        boolean taken = stores.findByHotelIdOrderByName(hotel).stream()
                .anyMatch(o -> o.isActive() && !o.getId().equals(s.getId()) && o.getName().trim().equalsIgnoreCase(name));
        if (taken) {
            throw new BadRequestException("There's already a store called " + name);
        }
        if (Boolean.FALSE.equals(request.active()) && s.getId() != null && s.isActive()) {
            long holding = levels.findByStoreId(s.getId()).stream().filter(l -> l.getQuantity().signum() > 0).count();
            if (holding > 0) {
                throw new BadRequestException(s.getName() + " still holds stock (" + holding + (holding == 1 ? " item" : " items")
                        + ") · move or count it out first");
            }
        }
        s.setName(name);
        s.setKind(kind);
        if (request.active() != null) {
            s.setActive(request.active());
        }
        Store saved = stores.save(s);
        return storeView(saved, levels.findByHotelId(hotel), itemMap(), true);
    }

    // ---- items --------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<InventoryViews.ItemView> items() {
        String hotel = TenantContext.requireHotel();
        Map<String, BigDecimal> onHand = new HashMap<>();
        levels.findByHotelId(hotel).forEach(l -> onHand.merge(l.getItemId(), l.getQuantity(), BigDecimal::add));
        Map<String, Supplier> sup = supplierMap();
        return items.findByHotelIdAndActiveTrueOrderByCategoryAscNameAsc(hotel).stream()
                .map(i -> itemView(i, sup, onHand.getOrDefault(i.getId(), BigDecimal.ZERO))).toList();
    }

    private InventoryViews.ItemView itemView(StockItem i, Map<String, Supplier> sup, BigDecimal onHand) {
        Supplier s = i.getSupplierId() == null ? null : sup.get(i.getSupplierId());
        return new InventoryViews.ItemView(i.getId(), i.getName(), i.getCategory(), i.getUnit(), i.getMinLevel(), i.getParLevel(), i.getUnitCost(),
                i.getSupplierId(), s == null ? null : s.getName(), i.isActive(), Money.of(onHand));
    }

    @Transactional(readOnly = true)
    public InventoryViews.ItemDetail itemDetail(String id) {
        StockItem i = item(id);
        Map<String, Store> storeMap = storeMap();
        List<StockLevel> held = levels.findByItemId(id);
        BigDecimal onHand = held.stream().map(StockLevel::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean money = CurrentUser.get().map(InventoryService::seesMoney).orElse(false);
        List<InventoryViews.LevelView> lv = held.stream().filter(l -> storeMap.containsKey(l.getStoreId()))
                .map(l -> {
                    Store s = storeMap.get(l.getStoreId());
                    return new InventoryViews.LevelView(s.getId(), s.getName(), s.getKind(), l.getQuantity(),
                            money ? Money.of(l.getQuantity().multiply(cost(i))) : null);
                })
                .sorted(Comparator.comparing(InventoryViews.LevelView::storeName)).toList();
        Map<String, StockItem> one = Map.of(i.getId(), i);
        List<InventoryViews.MovementView> mv = movements.findTop30ByItemIdOrderByCreatedAtDesc(id).stream()
                .map(m -> movementView(m, one, storeMap, money)).toList();
        return new InventoryViews.ItemDetail(itemView(i, supplierMap(), onHand), lv, mv);
    }

    @Transactional
    public InventoryViews.ItemView saveItem(String id, InventoryRequests.ItemRequest request) {
        String hotel = TenantContext.requireHotel();
        StockItem i = id == null ? new StockItem() : item(id);
        String name = request.name().trim();
        boolean taken = items.findByHotelIdOrderByCategoryAscNameAsc(hotel).stream()
                .anyMatch(o -> o.isActive() && !o.getId().equals(i.getId()) && o.getName().trim().equalsIgnoreCase(name));
        if (taken) {
            throw new BadRequestException("There's already an item called " + name);
        }
        BigDecimal min = request.minLevel() != null ? request.minLevel() : i.getMinLevel() == null ? BigDecimal.ZERO : i.getMinLevel();
        BigDecimal par = request.parLevel() != null ? request.parLevel() : i.getParLevel() == null ? BigDecimal.ZERO : i.getParLevel();
        if (min.signum() < 0 || par.signum() < 0) {
            throw new BadRequestException("Levels can't be negative");
        }
        if (par.signum() > 0 && par.compareTo(min) < 0) {
            throw new BadRequestException("The reorder-up-to level can't be below the minimum (" + StockRules.qty(min) + ")");
        }
        if (request.unitCost() != null && request.unitCost().signum() < 0) {
            throw new BadRequestException("The unit cost can't be negative");
        }
        if (StringUtils.hasText(request.supplierId())) {
            suppliers.findById(request.supplierId()).orElseThrow(() -> new BadRequestException("That supplier isn't in this hotel's list"));
        }
        Store opening = StringUtils.hasText(request.storeId()) ? store(request.storeId()) : null;
        BigDecimal openingQty = request.openingQuantity() == null ? BigDecimal.ZERO : request.openingQuantity();
        if (openingQty.signum() < 0) {
            throw new BadRequestException("The opening quantity can't be negative");
        }
        if (Boolean.FALSE.equals(request.active()) && i.getId() != null && i.isActive()) {
            Map<String, Store> sm = storeMap();
            levels.findByItemId(i.getId()).stream().filter(l -> l.getQuantity().signum() > 0).findFirst().ifPresent(l -> {
                throw new BadRequestException(i.getName() + " still has " + StockRules.qty(l.getQuantity()) + " " + i.getUnit() + " in "
                        + (sm.containsKey(l.getStoreId()) ? sm.get(l.getStoreId()).getName() : "a store") + " · count it out or move it first");
            });
        }
        i.setName(name);
        if (request.category() != null) {
            i.setCategory(StringUtils.hasText(request.category()) ? request.category().trim() : null);
        }
        if (StringUtils.hasText(request.unit())) {
            i.setUnit(request.unit().trim());
        }
        i.setMinLevel(Money.of(min));
        i.setParLevel(Money.of(par));
        if (request.unitCost() != null) {
            i.setUnitCost(Money.of(request.unitCost()));
        }
        if (request.supplierId() != null) {
            i.setSupplierId(StringUtils.hasText(request.supplierId()) ? request.supplierId() : null);
        }
        if (request.active() != null) {
            i.setActive(request.active());
        }
        boolean created = i.getId() == null;
        StockItem saved = items.save(i);
        if (created || opening != null) {
            Store where = opening != null ? opening : mainStore();
            StockLevel level = level(saved.getId(), where.getId());
            if (openingQty.signum() > 0) {
                move(level, StockMovement.Kind.RECEIVED, openingQty, "OPENING", "Opening stock", null, saved.getUnitCost());
            }
        }
        BigDecimal onHand = levels.findByItemId(saved.getId()).stream().map(StockLevel::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        return itemView(saved, supplierMap(), onHand);
    }

    @Transactional(readOnly = true)
    public List<StockLevel> levels(String storeId) {
        return storeId == null ? levels.findByHotelId(TenantContext.requireHotel()) : levels.findByStoreId(storeId);
    }

    // ---- movements ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<InventoryViews.MovementView> movements(String itemId, String storeId, String kind, LocalDate from, LocalDate to) {
        String hotel = TenantContext.requireHotel();
        List<StockMovement> rows = from == null && to == null ? movements.findTop300ByHotelIdOrderByCreatedAtDesc(hotel)
                : movements.findByHotelIdAndCreatedAtBetweenOrderByCreatedAtDesc(hotel, start(from == null ? to.minusDays(30) : from),
                        end(to == null ? LocalDate.now(LAGOS) : to));
        Map<String, StockItem> im = itemMap();
        Map<String, Store> sm = storeMap();
        return rows.stream().filter(m -> itemId == null || itemId.equals(m.getItemId())).filter(m -> storeId == null || storeId.equals(m.getStoreId()))
                .filter(m -> kind == null || m.getKind().name().equalsIgnoreCase(kind)).limit(300).map(m -> movementView(m, im, sm, true)).toList();
    }

    private InventoryViews.MovementView movementView(StockMovement m, Map<String, StockItem> im, Map<String, Store> sm, boolean money) {
        StockItem i = im.get(m.getItemId());
        Store s = sm.get(m.getStoreId());
        BigDecimal c = m.getUnitCost() != null ? m.getUnitCost() : i == null ? BigDecimal.ZERO : cost(i);
        return new InventoryViews.MovementView(m.getId(), m.getCreatedAt(), m.getItemId(), i == null ? "Item" : i.getName(), i == null ? null : i.getUnit(),
                m.getStoreId(), s == null ? "Store" : s.getName(), m.getKind().name(), m.getQuantity(), money ? c : null,
                money ? Money.of(m.getQuantity().multiply(c)) : null, m.getReference(), m.getNote(), m.getTeam(), m.getByName());
    }

    @Transactional
    public StockLevel adjust(InventoryRequests.AdjustRequest request) {
        HospioUserDetails u = user();
        if (u.isReadOnly() || !(u.isPlatform() || u.isManagerOrOwner() || u.has(Role.INVENTORY, AccessLevel.FULL))) {
            throw new ForbiddenException("Correcting a stock level needs Full access or a manager");
        }
        if (request.quantity().signum() < 0) {
            throw new BadRequestException("A stock level can't be negative");
        }
        StockLevel level = level(request.itemId(), request.storeId());
        BigDecimal delta = Money.of(request.quantity()).subtract(level.getQuantity());
        if (delta.signum() == 0) {
            throw new BadRequestException("That is already the level · nothing to correct");
        }
        return move(level, StockMovement.Kind.ADJUSTED, delta, "ADJUST", request.reason().trim(), null, null);
    }

    @Transactional
    public StockLevel useOrWaste(InventoryRequests.UseOrWasteRequest request) {
        Store store = store(request.storeId());
        if (!canUse(user(), store)) {
            throw new ForbiddenException("Using or wasting stock in the " + store.getName() + " is for its team, the store or a manager");
        }
        if (request.quantity().signum() <= 0) {
            throw new BadRequestException("Enter a quantity above zero");
        }
        StockLevel level = level(request.itemId(), request.storeId());
        return move(level, request.waste() ? StockMovement.Kind.WASTE : StockMovement.Kind.USED, request.quantity().negate(), null, request.note(),
                teamOfStore(store), null);
    }

    // ---- selling draws stock ---------------------------------------------------------------------------

    /**
     * A kitchen or bar order was settled: every sold line draws its recipe (per portion, times quantity) from the outlet's store as USED
     * movements referenced {@code ORDER-{orderId}}. Done once per order however often the event arrives. Stock never goes below zero
     * here: what the store doesn't hold is left for a count to put right. Items without a recipe draw nothing.
     */
    @Transactional
    public void onOrderSettled(OrderSettledEvent e) {
        if (e.getStoreId() == null || e.getLines() == null || e.getOrderId() == null) {
            return;
        }
        String reference = "ORDER-" + e.getOrderId();
        if (movements.existsByReferenceAndKind(reference, StockMovement.Kind.USED)) {
            return;
        }
        Store store = stores.findById(e.getStoreId()).orElse(null);
        if (store == null) {
            log.warn("Order {} names store {} which this hotel doesn't have; nothing drawn", e.getOrderNumber(), e.getStoreId());
            return;
        }
        Map<String, BigDecimal> needed = new LinkedHashMap<>();
        for (OrderSettledEvent.Line line : e.getLines()) {
            if (line.getRecipe() == null) {
                continue;
            }
            for (OrderSettledEvent.Ingredient i : line.getRecipe()) {
                if (i.getStockItemId() != null && i.getQuantity() != null) {
                    needed.merge(i.getStockItemId(), i.getQuantity().multiply(BigDecimal.valueOf(line.getQuantity())), BigDecimal::add);
                }
            }
        }
        for (Map.Entry<String, BigDecimal> n : needed.entrySet()) {
            if (items.findById(n.getKey()).isEmpty()) {
                log.warn("Order {} uses stock item {} which this hotel doesn't have", e.getOrderNumber(), n.getKey());
                continue;
            }
            StockLevel level = level(n.getKey(), e.getStoreId());
            BigDecimal take = Money.of(n.getValue()).min(level.getQuantity());
            if (take.signum() <= 0) {
                log.warn("Order {} needed {} of stock item {} but the store has none", e.getOrderNumber(), n.getValue(), n.getKey());
                continue;
            }
            move(level, StockMovement.Kind.USED, take.negate(), reference, "Sold on order " + e.getOrderNumber()
                    + (take.compareTo(Money.of(n.getValue())) < 0 ? " (store was short by " + Money.of(n.getValue()).subtract(take) + ")" : ""),
                    teamOfStore(store), null);
        }
    }

    // ---- stock moved for other services (docs/laundry-maintenance.md) ---------------------------------

    /**
     * Maintenance parts used on a work order, linen moved to and from the laundry, linen condemned or lost. Stores are the hotel's first
     * active store of each kind. Idempotent by reference: when movements with this reference exist, they are answered again and
     * nothing moves. All lines are checked before anything moves, so a short line leaves the store untouched.
     */
    @Transactional
    public com.hospio.inventory.dto.StockMoves.Result stockMoves(com.hospio.inventory.dto.StockMoves.Request r) {
        if (r == null || r.reference() == null || r.reference().isBlank() || r.kind() == null) {
            throw new BadRequestException("Say what the stock is for");
        }
        String hotel = TenantContext.requireHotel();
        Store from = storeOfKind(hotel, r.fromKind());
        Store to = r.kind() == com.hospio.inventory.dto.StockMoves.Kind.TRANSFER ? storeOfKind(hotel, r.toKind()) : null;
        Map<String, StockItem> im = itemMap();
        List<StockMovement> done = movements.findByHotelIdAndReferenceOrderByCreatedAt(hotel, r.reference());
        if (!done.isEmpty()) {
            List<com.hospio.inventory.dto.StockMoves.MovedLine> lines = done.stream().filter(m -> m.getQuantity().signum() < 0).map(m -> {
                StockItem i = im.get(m.getItemId());
                BigDecimal q = m.getQuantity().negate();
                BigDecimal c = m.getUnitCost() == null ? BigDecimal.ZERO : m.getUnitCost();
                return new com.hospio.inventory.dto.StockMoves.MovedLine(m.getItemId(), i == null ? null : i.getName(), i == null ? null : i.getUnit(),
                        q, c, Money.of(q.multiply(c)));
            }).toList();
            return new com.hospio.inventory.dto.StockMoves.Result(r.reference(), from.getId(), from.getName(), to == null ? null : to.getId(),
                    to == null ? null : to.getName(), lines, Money.of(lines.stream().map(com.hospio.inventory.dto.StockMoves.MovedLine::value)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)), true);
        }
        if (r.lines() == null || r.lines().isEmpty()) {
            throw new BadRequestException("Add at least one item");
        }
        Map<String, BigDecimal> wanted = new LinkedHashMap<>();
        for (com.hospio.inventory.dto.StockMoves.Line l : r.lines()) {
            if (l == null || l.itemId() == null || l.quantity() == null) {
                throw new BadRequestException("Pick an item and a quantity on every line");
            }
            if (l.quantity().signum() < 0) {
                throw new BadRequestException("Quantities can't be negative");
            }
            if (l.quantity().signum() == 0) {
                continue;
            }
            StockItem i = im.get(l.itemId());
            if (i == null) {
                throw new BadRequestException("That item isn't in this hotel's stock");
            }
            if (r.category() != null && !r.category().equalsIgnoreCase(i.getCategory() == null ? "" : i.getCategory())) {
                throw new BadRequestException(i.getName() + " isn't " + r.category().toLowerCase(Locale.ROOT));
            }
            wanted.merge(l.itemId(), Money.of(l.quantity()), BigDecimal::add);
        }
        if (wanted.isEmpty()) {
            throw new BadRequestException("Enter a quantity above zero");
        }
        for (Map.Entry<String, BigDecimal> w : wanted.entrySet()) {
            BigDecimal on = levels.findByItemIdAndStoreId(w.getKey(), from.getId()).map(StockLevel::getQuantity).orElse(BigDecimal.ZERO);
            if (on.compareTo(w.getValue()) < 0) {
                StockItem i = im.get(w.getKey());
                throw new BadRequestException("Only " + StockRules.qty(on) + " " + i.getUnit() + " " + i.getName() + " in the " + from.getName());
            }
        }
        String note = trim(r.note(), 300);
        List<com.hospio.inventory.dto.StockMoves.MovedLine> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> w : wanted.entrySet()) {
            StockItem i = im.get(w.getKey());
            BigDecimal cost = cost(i);
            switch (r.kind()) {
                case USE -> move(level(w.getKey(), from.getId()), StockMovement.Kind.USED, w.getValue().negate(), r.reference(), note, r.team(), cost);
                case WASTE -> move(level(w.getKey(), from.getId()), StockMovement.Kind.WASTE, w.getValue().negate(), r.reference(), note, r.team(), cost);
                case TRANSFER -> {
                    // Linen going to and from the laundry is not stock issued to a team: no team on the pair, so usage per team stays honest.
                    move(level(w.getKey(), from.getId()), StockMovement.Kind.TRANSFER_OUT, w.getValue().negate(), r.reference(), note, null, cost);
                    move(level(w.getKey(), to.getId()), StockMovement.Kind.TRANSFER_IN, w.getValue(), r.reference(), note, null, cost);
                }
            }
            out.add(new com.hospio.inventory.dto.StockMoves.MovedLine(i.getId(), i.getName(), i.getUnit(), w.getValue(), cost,
                    Money.of(w.getValue().multiply(cost))));
        }
        return new com.hospio.inventory.dto.StockMoves.Result(r.reference(), from.getId(), from.getName(), to == null ? null : to.getId(),
                to == null ? null : to.getName(), out, Money.of(out.stream().map(com.hospio.inventory.dto.StockMoves.MovedLine::value)
                .reduce(BigDecimal.ZERO, BigDecimal::add)), false);
    }

    private Store storeOfKind(String hotel, String kind) {
        if (kind == null || kind.isBlank()) {
            throw new BadRequestException("Say which store");
        }
        String k = kind.toUpperCase(Locale.ROOT);
        return stores.findByHotelIdAndActiveTrueOrderByName(hotel).stream().filter(s -> k.equals(s.getKind())).findFirst()
                .orElseThrow(() -> new BadRequestException("There's no " + teamLabel(k) + " store · add one in Inventory first"));
    }

    // ---- requests from teams ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<InventoryViews.RequestView> requests(String team, String status) {
        HospioUserDetails u = user();
        String hotel = TenantContext.requireHotel();
        boolean all = isStore(u) || u.isManagerOrOwner() || u.isPlatform() || u.has(Role.INVENTORY);
        Set<String> mine = teamsOf(u);
        String wanted = team == null ? null : team.trim().toUpperCase(Locale.ROOT);
        if (!all && wanted != null && !mine.contains(wanted)) {
            throw new ForbiddenException("You can only see your own team's requests");
        }
        Map<String, StockItem> im = itemMap();
        Map<String, Store> sm = storeMap();
        return requests.findByHotelIdOrderByCreatedAtDesc(hotel).stream()
                .filter(r -> all || mine.contains(r.getFromTeam()))
                .filter(r -> wanted == null || wanted.equals(r.getFromTeam()))
                .filter(r -> status == null || matchesStatus(r.getStatus(), status))
                .map(r -> requestView(r, im, sm)).toList();
    }

    private static boolean matchesStatus(StockRequest.Status s, String wanted) {
        if ("OPEN".equalsIgnoreCase(wanted)) {
            return s == StockRequest.Status.REQUESTED || s == StockRequest.Status.PART_ISSUED;
        }
        return s.name().equalsIgnoreCase(wanted);
    }

    private InventoryViews.RequestView requestView(StockRequest r, Map<String, StockItem> im, Map<String, Store> sm) {
        Store s = r.getStoreId() == null ? null : sm.get(r.getStoreId());
        return new InventoryViews.RequestView(r.getId(), r.getFromTeam(), r.getStoreId(), s == null ? null : s.getName(), requestLines(r, im),
                r.getNote(), r.getStatus().name(), r.getRequestedBy(), r.getRequestedByName(), r.getIssuedByName(), r.getIssuedAt(), r.getReason(),
                r.getCreatedAt());
    }

    private List<RequestLine> requestLines(StockRequest r, Map<String, StockItem> im) {
        return read(r.getLines(), RequestLine.class).stream().map(l -> {
            StockItem i = im.get(l.itemId());
            return new RequestLine(l.itemId(), l.name() != null ? l.name() : i == null ? "Item" : i.getName(),
                    l.unit() != null ? l.unit() : i == null ? "pcs" : i.getUnit(), l.quantity(), l.issued() == null ? BigDecimal.ZERO : l.issued());
        }).toList();
    }

    @Transactional
    public InventoryViews.RequestView askTheStore(InventoryRequests.StockRequestDto dto) {
        HospioUserDetails u = user();
        if (u.isReadOnly()) {
            throw new ForbiddenException("This session is read-only");
        }
        String team = dto.fromTeam().trim().toUpperCase(Locale.ROOT);
        Role role;
        try {
            role = Role.valueOf(team);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown team " + dto.fromTeam());
        }
        if (!TEAMS.contains(role) && role != Role.INVENTORY) {
            throw new BadRequestException("Requests come from a team: Kitchen, Bar, Housekeeping, Laundry, Maintenance or Front office");
        }
        if (!isStore(u) && !u.has(role, AccessLevel.EDIT)) {
            Set<String> mine = teamsOf(u);
            throw new ForbiddenException("You can only ask the store for your own team" + (mine.isEmpty() ? "" : " (" + String.join(", ", mine) + ")"));
        }
        Store to = StringUtils.hasText(dto.storeId()) ? store(dto.storeId()) : null;
        Map<String, BigDecimal> wanted = mergeLines(dto.lines(), "ask for");
        List<RequestLine> lines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> w : wanted.entrySet()) {
            StockItem i = item(w.getKey());
            lines.add(new RequestLine(i.getId(), i.getName(), i.getUnit(), w.getValue(), BigDecimal.ZERO));
        }
        StockRequest r = requests.save(StockRequest.builder().fromTeam(team).storeId(to == null ? null : to.getId()).lines(write(lines))
                .note(trim(dto.note(), 300)).requestedBy(u.getUserId()).requestedByName(u.getFullName()).build());
        events.publish(KafkaTopics.STOCK_REQUESTED, StockRequestedEvent.builder().requestId(r.getId()).fromTeam(r.getFromTeam())
                .storeId(r.getStoreId()).lines(lines.stream().map(l -> StockRules.qty(l.quantity()) + " " + l.unit() + " " + l.name())
                        .collect(Collectors.joining(", "))).requestedBy(u.getFullName()).build());
        return requestView(r, itemMap(), storeMap());
    }

    @Transactional
    public InventoryViews.RequestView issue(String requestId, InventoryRequests.IssueRequest request) {
        HospioUserDetails u = user();
        StockRequest r = requests.findById(requestId).orElseThrow(() -> new ResourceNotFoundException("Request not found"));
        if (r.getStatus() != StockRequest.Status.REQUESTED && r.getStatus() != StockRequest.Status.PART_ISSUED) {
            throw new BadRequestException("This request is already " + label(r.getStatus()));
        }
        Store from = store(request.storeId());
        if (from.getId().equals(r.getStoreId())) {
            throw new BadRequestException("That is the store the request is for · issue from another store");
        }
        Store to = r.getStoreId() == null ? null : stores.findById(r.getStoreId()).orElse(null);
        Map<String, StockItem> im = itemMap();
        List<RequestLine> asked = new ArrayList<>(requestLines(r, im));
        Map<String, BigDecimal> outstanding = new LinkedHashMap<>();
        asked.forEach(l -> outstanding.merge(l.itemId(), l.quantity().subtract(l.issued()).max(BigDecimal.ZERO), BigDecimal::add));
        Map<String, BigDecimal> giving = new LinkedHashMap<>();
        if (request.lines() == null || request.lines().isEmpty()) {
            outstanding.forEach((k, v) -> {
                if (v.signum() > 0) {
                    giving.put(k, v);
                }
            });
        } else {
            for (InventoryRequests.Line l : request.lines()) {
                if (l.quantity() == null || l.quantity().signum() < 0) {
                    throw new BadRequestException("Quantities can't be negative");
                }
                if (!outstanding.containsKey(l.itemId())) {
                    throw new BadRequestException(nameOf(l.itemId(), im) + " isn't on this request");
                }
                if (l.quantity().signum() > 0) {
                    giving.merge(l.itemId(), Money.of(l.quantity()), BigDecimal::add);
                }
            }
        }
        if (giving.isEmpty()) {
            throw new BadRequestException("Enter how much to issue");
        }
        String reference = "REQ-" + shortId(r.getId()) + "-" + shortId(UUID.randomUUID().toString()).substring(0, 4);
        for (Map.Entry<String, BigDecimal> g : giving.entrySet()) {
            StockItem item = im.get(g.getKey());
            BigDecimal left = outstanding.get(g.getKey());
            if (g.getValue().compareTo(left) > 0) {
                throw new BadRequestException("Only " + StockRules.qty(left) + " " + item.getUnit() + " " + item.getName() + " is still outstanding");
            }
            StockLevel src = level(g.getKey(), from.getId());
            if (src.getQuantity().compareTo(g.getValue()) < 0) {
                throw new BadRequestException("Only " + StockRules.qty(src.getQuantity()) + " " + item.getUnit() + " " + item.getName() + " in the " + from.getName());
            }
            BigDecimal c = cost(item);
            if (to != null) {
                move(src, StockMovement.Kind.TRANSFER_OUT, g.getValue().negate(), reference, "Issued to " + teamLabel(r.getFromTeam()) + " · " + to.getName(), r.getFromTeam(), c);
                move(level(g.getKey(), to.getId()), StockMovement.Kind.TRANSFER_IN, g.getValue(), reference, "From " + from.getName(), r.getFromTeam(), c);
            } else {
                move(src, StockMovement.Kind.ISSUED, g.getValue().negate(), reference, "Issued to " + teamLabel(r.getFromTeam()), r.getFromTeam(), c);
            }
        }
        List<RequestLine> updated = new ArrayList<>();
        Map<String, BigDecimal> toGive = new HashMap<>(giving);
        for (RequestLine l : asked) {
            BigDecimal room = l.quantity().subtract(l.issued()).max(BigDecimal.ZERO);
            BigDecimal add = toGive.getOrDefault(l.itemId(), BigDecimal.ZERO).min(room);
            toGive.computeIfPresent(l.itemId(), (k, v) -> v.subtract(add));
            updated.add(new RequestLine(l.itemId(), l.name(), l.unit(), l.quantity(), l.issued().add(add)));
        }
        boolean complete = updated.stream().allMatch(l -> l.issued().compareTo(l.quantity()) >= 0);
        r.setLines(write(updated));
        r.setStatus(complete ? StockRequest.Status.ISSUED : StockRequest.Status.PART_ISSUED);
        r.setIssuedBy(u.getUserId());
        r.setIssuedByName(u.getFullName());
        r.setIssuedAt(Instant.now());
        requests.save(r);
        events.publish(KafkaTopics.STOCK_ISSUED, StockIssuedEvent.builder().requestId(r.getId()).toTeam(r.getFromTeam()).itemCount(giving.size())
                .status(r.getStatus().name()).note(giving.entrySet().stream().map(g -> StockRules.qty(g.getValue()) + " " + im.get(g.getKey()).getUnit() + " "
                        + im.get(g.getKey()).getName()).collect(Collectors.joining(", "))).build());
        return requestView(r, im, storeMap());
    }

    @Transactional
    public InventoryViews.RequestView decline(String requestId, String reason) {
        HospioUserDetails u = user();
        if (!StringUtils.hasText(reason)) {
            throw new BadRequestException("Give a reason so the team knows why");
        }
        StockRequest r = requests.findById(requestId).orElseThrow(() -> new ResourceNotFoundException("Request not found"));
        if (r.getStatus() != StockRequest.Status.REQUESTED && r.getStatus() != StockRequest.Status.PART_ISSUED) {
            throw new BadRequestException("This request is already " + label(r.getStatus()));
        }
        r.setStatus(r.getStatus() == StockRequest.Status.PART_ISSUED ? StockRequest.Status.CLOSED : StockRequest.Status.DECLINED);
        r.setReason(trim(reason, 300));
        r.setIssuedBy(u.getUserId());
        r.setIssuedByName(u.getFullName());
        r.setIssuedAt(Instant.now());
        requests.save(r);
        events.publish(KafkaTopics.STOCK_ISSUED, StockIssuedEvent.builder().requestId(r.getId()).toTeam(r.getFromTeam()).itemCount(0)
                .status(r.getStatus().name()).note(r.getReason()).build());
        return requestView(r, itemMap(), storeMap());
    }

    // ---- transfers ------------------------------------------------------------------------------------

    @Transactional
    public InventoryViews.TransferView transfer(InventoryRequests.TransferRequest request) {
        Store from = store(request.fromStoreId());
        Store to = store(request.toStoreId());
        if (from.getId().equals(to.getId())) {
            throw new BadRequestException("Pick two different stores");
        }
        Map<String, BigDecimal> moving = mergeLines(request.lines(), "move");
        Map<String, StockItem> im = itemMap();
        String reference = "TRF-" + shortId(UUID.randomUUID().toString());
        String team = teamOfStore(to);
        String note = trim(request.note(), 200);
        for (Map.Entry<String, BigDecimal> m : moving.entrySet()) {
            StockItem item = item(m.getKey());
            StockLevel src = level(m.getKey(), from.getId());
            if (src.getQuantity().compareTo(m.getValue()) < 0) {
                throw new BadRequestException("Only " + StockRules.qty(src.getQuantity()) + " " + item.getUnit() + " " + item.getName() + " in the " + from.getName());
            }
            BigDecimal c = cost(item);
            move(src, StockMovement.Kind.TRANSFER_OUT, m.getValue().negate(), reference, "To " + to.getName() + (note == null ? "" : " · " + note), team, c);
            move(level(m.getKey(), to.getId()), StockMovement.Kind.TRANSFER_IN, m.getValue(), reference, "From " + from.getName() + (note == null ? "" : " · " + note), team, c);
        }
        return transfers(LocalDate.now(LAGOS), LocalDate.now(LAGOS)).stream().filter(t -> reference.equals(t.reference())).findFirst()
                .orElseThrow(() -> new IllegalStateException("Transfer " + reference + " not recorded"));
    }

    @Transactional(readOnly = true)
    public List<InventoryViews.TransferView> transfers(LocalDate from, LocalDate to) {
        String hotel = TenantContext.requireHotel();
        LocalDate t = to == null ? LocalDate.now(LAGOS) : to;
        LocalDate f = from == null ? t.minusDays(30) : from;
        List<StockMovement> rows = movements.findByHotelIdAndCreatedAtBetweenOrderByCreatedAtDesc(hotel, start(f), end(t));
        Map<String, StockItem> im = itemMap();
        Map<String, Store> sm = storeMap();
        Map<String, List<StockMovement>> byRef = new LinkedHashMap<>();
        for (StockMovement m : rows) {
            if (m.getReference() != null && (m.getKind() == StockMovement.Kind.TRANSFER_OUT || m.getKind() == StockMovement.Kind.TRANSFER_IN
                    || m.getKind() == StockMovement.Kind.ISSUED)) {
                byRef.computeIfAbsent(m.getReference(), k -> new ArrayList<>()).add(m);
            }
        }
        List<InventoryViews.TransferView> out = new ArrayList<>();
        for (Map.Entry<String, List<StockMovement>> e : byRef.entrySet()) {
            List<StockMovement> outs = e.getValue().stream().filter(m -> m.getQuantity().signum() < 0).toList();
            if (outs.isEmpty()) {
                continue;
            }
            StockMovement first = outs.get(0);
            StockMovement in = e.getValue().stream().filter(m -> m.getKind() == StockMovement.Kind.TRANSFER_IN).findFirst().orElse(null);
            Store fs = sm.get(first.getStoreId());
            Store ts = in == null ? null : sm.get(in.getStoreId());
            BigDecimal value = BigDecimal.ZERO;
            List<RequestLine> lines = new ArrayList<>();
            for (StockMovement m : outs) {
                StockItem i = im.get(m.getItemId());
                BigDecimal q = m.getQuantity().negate();
                value = value.add(q.multiply(m.getUnitCost() != null ? m.getUnitCost() : i == null ? BigDecimal.ZERO : cost(i)));
                lines.add(new RequestLine(m.getItemId(), i == null ? "Item" : i.getName(), i == null ? null : i.getUnit(), q, q));
            }
            out.add(new InventoryViews.TransferView(e.getKey(), first.getCreatedAt(), first.getStoreId(), fs == null ? "Store" : fs.getName(),
                    in == null ? null : in.getStoreId(), ts == null ? null : ts.getName(), first.getTeam(), lines, Money.of(value), first.getByName(),
                    first.getNote()));
        }
        return out;
    }

    // ---- suppliers ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<InventoryViews.SupplierView> suppliers() {
        String hotel = TenantContext.requireHotel();
        List<StockItem> all = items.findByHotelIdAndActiveTrueOrderByCategoryAscNameAsc(hotel);
        List<PurchaseOrder> pos = orders.findByHotelIdOrderByCreatedAtDesc(hotel);
        Map<String, String> poSupplier = pos.stream().collect(Collectors.toMap(PurchaseOrder::getId, PurchaseOrder::getSupplierId));
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        List<com.hospio.inventory.entity.Delivery> recent = deliveries.findByHotelIdOrderByCreatedAtDesc(hotel).stream()
                .filter(d -> d.getCreatedAt() != null && d.getCreatedAt().isAfter(since)).toList();
        return suppliers.findByHotelIdOrderByName(hotel).stream().map(s -> {
            List<String> ids = all.stream().filter(i -> s.getId().equals(i.getSupplierId())).map(StockItem::getId).toList();
            List<PurchaseOrder> mine = pos.stream().filter(p -> s.getId().equals(p.getSupplierId())).toList();
            int open = (int) mine.stream().filter(p -> OPEN_ORDERS.contains(p.getStatus())).count();
            Instant last = mine.stream().map(PurchaseOrder::getCreatedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            BigDecimal spent = recent.stream().filter(d -> s.getId().equals(poSupplier.get(d.getPurchaseOrderId()))
                    || (d.getPurchaseOrderId() == null && s.getName().equalsIgnoreCase(Objects.toString(d.getSupplierName(), ""))))
                    .map(com.hospio.inventory.entity.Delivery::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
            return new InventoryViews.SupplierView(s.getId(), s.getName(), s.getContactName(), s.getPhone(), s.getEmail(), s.getPaymentTerms(),
                    s.getAddress(), s.getNotes(), s.isActive(), ids.size(), ids, open, last, Money.of(spent));
        }).toList();
    }

    @Transactional
    public InventoryViews.SupplierView saveSupplier(String id, InventoryRequests.SupplierRequest request) {
        String hotel = TenantContext.requireHotel();
        Supplier s = id == null ? new Supplier() : suppliers.findById(id).orElseThrow(() -> new ResourceNotFoundException("Supplier not found"));
        String name = request.name().trim();
        if (name.replaceAll("[^\\p{L}]", "").length() < 2) {
            throw new BadRequestException("Give the supplier a real name");
        }
        boolean taken = suppliers.findByHotelIdOrderByName(hotel).stream()
                .anyMatch(o -> o.isActive() && !o.getId().equals(s.getId()) && o.getName().trim().equalsIgnoreCase(name));
        if (taken) {
            throw new BadRequestException("There's already a supplier called " + name);
        }
        String phone = StringUtils.hasText(request.phone()) ? request.phone().trim() : null;
        if (phone != null && !validPhone(phone)) {
            throw new BadRequestException("Enter a phone number like 0803 123 4567");
        }
        String email = StringUtils.hasText(request.email()) ? request.email().trim() : null;
        if (email != null && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")) {
            throw new BadRequestException("Enter an email address like orders@supplier.ng");
        }
        if (Boolean.FALSE.equals(request.active()) && s.getId() != null && s.isActive()) {
            long open = orders.findBySupplierIdAndStatusIn(s.getId(), OPEN_ORDERS).size();
            if (open > 0) {
                throw new BadRequestException(s.getName() + " has " + open + (open == 1 ? " open order" : " open orders") + " · cancel or close them first");
            }
        }
        s.setName(name);
        s.setContactName(trim(request.contactName(), 150));
        s.setPhone(phone);
        s.setEmail(email);
        s.setPaymentTerms(trim(request.paymentTerms(), 100));
        s.setAddress(trim(request.address(), 300));
        s.setNotes(trim(request.notes(), 500));
        if (request.active() != null) {
            s.setActive(request.active());
        }
        Supplier saved = suppliers.save(s);
        if (request.itemIds() != null) {
            Set<String> wanted = new HashSet<>(request.itemIds());
            for (String itemId : wanted) {
                StockItem i = items.findById(itemId).orElseThrow(() -> new BadRequestException("One of those items isn't in this hotel's list"));
                i.setSupplierId(saved.getId());
                items.save(i);
            }
            for (StockItem i : items.findBySupplierId(saved.getId())) {
                if (!wanted.contains(i.getId())) {
                    i.setSupplierId(null);
                    items.save(i);
                }
            }
        }
        return suppliers().stream().filter(v -> v.id().equals(saved.getId())).findFirst().orElseThrow();
    }

    static boolean validPhone(String phone) {
        if (!phone.matches("^\\+?[0-9 ()-]{7,24}$")) {
            return false;
        }
        String d = phone.replaceAll("[^0-9]", "");
        String key = d.length() <= 10 ? d : d.substring(d.length() - 10);
        return d.length() >= 10 && d.length() <= 15 && !key.matches("^(\\d)\\1*$");
    }

    // ---- purchase orders ------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<InventoryViews.OrderView> purchaseOrders(String status) {
        String hotel = TenantContext.requireHotel();
        Map<String, Supplier> sup = supplierMap();
        Map<String, Store> sm = storeMap();
        Map<String, StockItem> im = itemMap();
        return orders.findByHotelIdOrderByCreatedAtDesc(hotel).stream().filter(p -> status == null || p.getStatus().name().equalsIgnoreCase(status))
                .map(p -> orderView(p, sup, sm, im, null, null)).toList();
    }

    @Transactional(readOnly = true)
    public InventoryViews.OrderView purchaseOrder(String id) {
        PurchaseOrder po = order(id);
        Map<String, Store> sm = storeMap();
        Map<String, StockItem> im = itemMap();
        List<InventoryViews.DeliveryView> ds = deliveries.findByPurchaseOrderIdOrderByCreatedAt(id).stream()
                .map(d -> deliveryView(d, Map.of(po.getId(), po), sm, im)).toList();
        return orderView(po, supplierMap(), sm, im, ds, null);
    }

    private InventoryViews.OrderView orderView(PurchaseOrder p, Map<String, Supplier> sup, Map<String, Store> sm, Map<String, StockItem> im,
                                               List<InventoryViews.DeliveryView> ds, List<Delivery> sent) {
        Supplier s = sup.get(p.getSupplierId());
        Store st = p.getStoreId() == null ? null : sm.get(p.getStoreId());
        return new InventoryViews.OrderView(p.getId(), p.getNumber(), p.getSupplierId(), s == null ? "Supplier" : s.getName(), p.getStoreId(),
                st == null ? null : st.getName(), orderLines(p, im), p.getTotal(), p.getReceivedValue(), p.getStatus().name(), p.getExpectedOn(),
                p.getNote(), p.getCreatedByName(), p.getCreatedAt(), p.getSentAt(), p.getSentTo(), p.getApproverLevel(), p.getDecisionNote(),
                p.getCancelReason(), ds, sent);
    }

    private List<OrderLine> orderLines(PurchaseOrder p, Map<String, StockItem> im) {
        return read(p.getLines(), OrderLine.class).stream().map(l -> {
            StockItem i = im.get(l.itemId());
            return new OrderLine(l.itemId(), l.name() != null ? l.name() : i == null ? "Item" : i.getName(),
                    l.unit() != null ? l.unit() : i == null ? "pcs" : i.getUnit(), l.quantity(), l.unitCost() == null ? BigDecimal.ZERO : l.unitCost(),
                    l.received() == null ? BigDecimal.ZERO : l.received(), l.damaged() == null ? BigDecimal.ZERO : l.damaged());
        }).toList();
    }

    @Transactional
    public InventoryViews.OrderView createOrder(InventoryRequests.PurchaseOrderRequest request) {
        HospioUserDetails u = user();
        PurchaseOrder po = PurchaseOrder.builder().createdBy(u.getUserId()).createdByName(u.getFullName())
                .number("PO-" + LocalDate.now(LAGOS).getYear() + "-" + String.format("%05d", orders.countByHotelId(TenantContext.requireHotel()) + 1))
                .lines("[]").supplierId(request.supplierId()).build();
        fillOrder(po, request);
        orders.save(po);
        if (Boolean.TRUE.equals(request.submit())) {
            return submit(po.getId());
        }
        return orderView(po, supplierMap(), storeMap(), itemMap(), null, null);
    }

    @Transactional
    public InventoryViews.OrderView updateOrder(String id, InventoryRequests.PurchaseOrderRequest request) {
        PurchaseOrder po = order(id);
        if (po.getStatus() != PurchaseOrder.Status.DRAFT) {
            throw new BadRequestException("Only a draft can be changed · " + po.getNumber() + " is " + label(po.getStatus()));
        }
        fillOrder(po, request);
        orders.save(po);
        if (Boolean.TRUE.equals(request.submit())) {
            return submit(po.getId());
        }
        return orderView(po, supplierMap(), storeMap(), itemMap(), null, null);
    }

    private void fillOrder(PurchaseOrder po, InventoryRequests.PurchaseOrderRequest request) {
        Supplier s = suppliers.findById(request.supplierId()).orElseThrow(() -> new BadRequestException("That supplier isn't in this hotel's list"));
        if (!s.isActive()) {
            throw new BadRequestException(s.getName() + " is no longer an active supplier");
        }
        Store store = StringUtils.hasText(request.storeId()) ? store(request.storeId()) : mainStore();
        if (request.expectedOn() != null && request.expectedOn().isBefore(LocalDate.now(LAGOS))) {
            throw new BadRequestException("The expected date is in the past");
        }
        if (request.lines().isEmpty()) {
            throw new BadRequestException("Add at least one item");
        }
        Set<String> seen = new HashSet<>();
        List<OrderLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (InventoryRequests.Line l : request.lines()) {
            StockItem i = item(l.itemId());
            if (!i.isActive()) {
                throw new BadRequestException(i.getName() + " is no longer stocked");
            }
            if (!seen.add(i.getId())) {
                throw new BadRequestException(i.getName() + " is on the order twice");
            }
            if (l.quantity() == null || l.quantity().signum() <= 0) {
                throw new BadRequestException("Enter a quantity above zero for " + i.getName());
            }
            BigDecimal c = l.unitCost() != null ? l.unitCost() : cost(i);
            if (c.signum() < 0) {
                throw new BadRequestException("The cost of " + i.getName() + " can't be negative");
            }
            lines.add(new OrderLine(i.getId(), i.getName(), i.getUnit(), Money.of(l.quantity()), Money.of(c), BigDecimal.ZERO, BigDecimal.ZERO));
            total = total.add(l.quantity().multiply(c));
        }
        po.setSupplierId(s.getId());
        po.setStoreId(store.getId());
        po.setLines(write(lines));
        po.setTotal(Money.of(total));
        po.setExpectedOn(request.expectedOn());
        po.setNote(trim(request.note(), 500));
    }

    /** A draft goes to the supplier now, or to the manager (raised by the storekeeper) or owner (raised by a manager) over the limit. */
    @Transactional
    public InventoryViews.OrderView submit(String id) {
        HospioUserDetails u = user();
        PurchaseOrder po = order(id);
        if (po.getStatus() != PurchaseOrder.Status.DRAFT) {
            throw new BadRequestException(po.getNumber() + " is already " + label(po.getStatus()));
        }
        StockRules.Caller caller = u.has(Role.OWNER) || u.isPlatform() ? StockRules.Caller.OWNER
                : u.isManagerOrOwner() ? StockRules.Caller.MANAGER : StockRules.Caller.STAFF;
        BigDecimal limit = approvalLimit.forHotel(TenantContext.requireHotel());
        String approver = StockRules.approverFor(po.getTotal(), limit, caller);
        Map<String, Supplier> sup = supplierMap();
        if (approver != null) {
            po.setStatus(PurchaseOrder.Status.PENDING_APPROVAL);
            po.setApprovalId(UUID.randomUUID().toString());
            po.setApproverLevel(approver);
            orders.save(po);
            Supplier s = sup.get(po.getSupplierId());
            events.publish(KafkaTopics.APPROVAL_REQUESTED, ApprovalRequestedEvent.builder().approvalId(po.getApprovalId()).sourceModule("INVENTORY")
                    .sourceRef(po.getId()).kind("PURCHASE_ORDER").amount(po.getTotal()).requesterId(u.getUserId()).requesterName(u.getFullName())
                    .requesterRole(caller == StockRules.Caller.MANAGER ? "MANAGER" : "INVENTORY").approverLevel(approver)
                    .description("Purchase order " + po.getNumber() + " · " + (s == null ? "supplier" : s.getName()) + " · " + StockRules.naira(po.getTotal()))
                    .build());
            return orderView(po, sup, storeMap(), itemMap(), null, null);
        }
        List<Delivery> sent = send(po, sup);
        return orderView(po, sup, storeMap(), itemMap(), null, sent);
    }

    /** Marks the order sent and sends it to the supplier: email when they have one, else SMS. Echoed on staging. */
    private List<Delivery> send(PurchaseOrder po, Map<String, Supplier> sup) {
        po.setStatus(PurchaseOrder.Status.SENT);
        po.setSentAt(Instant.now());
        Supplier s = sup.get(po.getSupplierId());
        Map<String, Store> sm = storeMap();
        Store st = po.getStoreId() == null ? null : sm.get(po.getStoreId());
        StringBuilder text = new StringBuilder("Purchase order ").append(po.getNumber()).append(s == null ? "" : " for " + s.getName()).append(":\n");
        for (OrderLine l : orderLines(po, itemMap())) {
            text.append("- ").append(StockRules.qty(l.quantity())).append(' ').append(l.unit()).append(' ').append(l.name()).append(" at ")
                    .append(StockRules.naira(l.unitCost())).append(" = ").append(StockRules.naira(l.quantity().multiply(l.unitCost()))).append('\n');
        }
        text.append("Total ").append(StockRules.naira(po.getTotal())).append('.');
        text.append(" Deliver to the ").append(st == null ? "main store" : st.getName());
        if (po.getExpectedOn() != null) {
            text.append(" by ").append(po.getExpectedOn());
        }
        text.append('.');
        if (po.getNote() != null) {
            text.append(" Note: ").append(po.getNote());
        }
        List<Delivery> out = List.of();
        if (s != null && StringUtils.hasText(s.getEmail())) {
            po.setSentTo(s.getEmail());
            out = deliveryService.send(Delivery.email(s.getEmail(), "Purchase order " + po.getNumber(), text.toString(), Delivery.Kind.PURCHASE_ORDER, po.getId()));
        } else if (s != null && StringUtils.hasText(s.getPhone())) {
            po.setSentTo(s.getPhone());
            out = deliveryService.send(Delivery.sms(s.getPhone(), text.toString(), Delivery.Kind.PURCHASE_ORDER, po.getId()));
        }
        orders.save(po);
        return out;
    }

    @Transactional
    public InventoryViews.OrderView cancelOrder(String id, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BadRequestException("Give a reason for cancelling");
        }
        PurchaseOrder po = order(id);
        if (po.getStatus() == PurchaseOrder.Status.PARTIAL) {
            throw new BadRequestException("Something has already been received on " + po.getNumber() + " · close it instead");
        }
        if (!OPEN_ORDERS.contains(po.getStatus())) {
            throw new BadRequestException(po.getNumber() + " is already " + label(po.getStatus()));
        }
        po.setStatus(PurchaseOrder.Status.CANCELLED);
        po.setCancelReason(trim(reason, 300));
        orders.save(po);
        return orderView(po, supplierMap(), storeMap(), itemMap(), null, null);
    }

    @Transactional
    public InventoryViews.OrderView closeOrder(String id, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BadRequestException("Give a reason for closing the order short");
        }
        PurchaseOrder po = order(id);
        if (po.getStatus() == PurchaseOrder.Status.SENT) {
            throw new BadRequestException("Nothing has been received on " + po.getNumber() + " · cancel it instead");
        }
        if (po.getStatus() != PurchaseOrder.Status.PARTIAL) {
            throw new BadRequestException(po.getNumber() + " is " + label(po.getStatus()) + " · only a part-received order can be closed");
        }
        po.setStatus(PurchaseOrder.Status.CLOSED);
        po.setCancelReason(trim(reason, 300));
        orders.save(po);
        return orderView(po, supplierMap(), storeMap(), itemMap(), null, null);
    }

    @Transactional
    public void onOrderDecided(String approvalId, String decision, String decidedByName, String note) {
        orders.findByApprovalId(approvalId).ifPresent(po -> {
            if (po.getStatus() != PurchaseOrder.Status.PENDING_APPROVAL) {
                return;
            }
            String who = decidedByName == null ? "" : " by " + decidedByName;
            if ("APPROVED".equals(decision)) {
                po.setDecisionNote(trim("Approved" + who + (StringUtils.hasText(note) ? " · " + note : ""), 500));
                send(po, supplierMap());
            } else if ("DECLINED".equals(decision)) {
                po.setStatus(PurchaseOrder.Status.CANCELLED);
                po.setDecisionNote(trim("Declined" + who + (StringUtils.hasText(note) ? " · " + note : ""), 500));
                po.setCancelReason(po.getDecisionNote());
                orders.save(po);
            } else if (StringUtils.hasText(note)) {
                po.setDecisionNote(trim("Question" + who + " · " + note, 500));
                orders.save(po);
            }
        });
    }

    // ---- deliveries -----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<InventoryViews.DeliveryView> deliveries() {
        String hotel = TenantContext.requireHotel();
        Map<String, PurchaseOrder> pos = orders.findByHotelIdOrderByCreatedAtDesc(hotel).stream()
                .collect(Collectors.toMap(PurchaseOrder::getId, Function.identity()));
        Map<String, Store> sm = storeMap();
        Map<String, StockItem> im = itemMap();
        return deliveries.findByHotelIdOrderByCreatedAtDesc(hotel).stream().map(d -> deliveryView(d, pos, sm, im)).toList();
    }

    private InventoryViews.DeliveryView deliveryView(com.hospio.inventory.entity.Delivery d, Map<String, PurchaseOrder> pos, Map<String, Store> sm,
                                                     Map<String, StockItem> im) {
        PurchaseOrder po = d.getPurchaseOrderId() == null ? null : pos.get(d.getPurchaseOrderId());
        Store st = sm.get(d.getStoreId());
        List<DeliveryLine> lines = read(d.getLines(), DeliveryLine.class).stream().map(l -> {
            StockItem i = im.get(l.itemId());
            return new DeliveryLine(l.itemId(), l.name() != null ? l.name() : i == null ? "Item" : i.getName(), l.unit() != null ? l.unit() : i == null ? "pcs" : i.getUnit(),
                    l.quantity(), l.unitCost(), z(l.damaged()), l.ordered(), z(l.shortQty()), z(l.overQty()), l.note());
        }).toList();
        return new InventoryViews.DeliveryView(d.getId(), d.getPurchaseOrderId(), po == null ? null : po.getNumber(), d.getSupplierName(), d.getStoreId(),
                st == null ? null : st.getName(), lines, d.getTotal(), d.getInvoiceNumber(), d.getNote(), d.getSummary(), d.getReceivedByName(), d.getCreatedAt());
    }

    @Transactional
    public InventoryViews.DeliveryView receive(InventoryRequests.DeliveryRequest request) {
        HospioUserDetails u = user();
        PurchaseOrder po = StringUtils.hasText(request.purchaseOrderId()) ? order(request.purchaseOrderId()) : null;
        Map<String, StockItem> im = itemMap();
        String supplierName = trim(request.supplierName(), 150);
        Store store;
        Map<String, OrderLine> onOrder = new LinkedHashMap<>();
        if (po != null) {
            if (po.getStatus() != PurchaseOrder.Status.SENT && po.getStatus() != PurchaseOrder.Status.PARTIAL) {
                throw new BadRequestException(po.getNumber() + " is " + label(po.getStatus()) + (po.getStatus() == PurchaseOrder.Status.DRAFT
                        || po.getStatus() == PurchaseOrder.Status.PENDING_APPROVAL ? " · it hasn't gone to the supplier yet" : ""));
            }
            orderLines(po, im).forEach(l -> onOrder.put(l.itemId(), l));
            store = StringUtils.hasText(request.storeId()) ? store(request.storeId()) : po.getStoreId() != null ? store(po.getStoreId()) : mainStore();
            if (supplierName == null) {
                supplierName = suppliers.findById(po.getSupplierId()).map(Supplier::getName).orElse(null);
            }
        } else {
            if (supplierName == null) {
                throw new BadRequestException("Name the supplier, or pick the order this delivery is for");
            }
            if (!StringUtils.hasText(request.storeId())) {
                throw new BadRequestException("Pick the store the delivery goes into");
            }
            store = store(request.storeId());
        }
        Map<String, InventoryRequests.Line> given = new LinkedHashMap<>();
        for (InventoryRequests.Line l : request.lines()) {
            StockItem i = item(l.itemId());
            if (po != null && !onOrder.containsKey(i.getId())) {
                throw new BadRequestException(i.getName() + " isn't on " + po.getNumber());
            }
            if (given.containsKey(i.getId())) {
                throw new BadRequestException(i.getName() + " is on the delivery twice");
            }
            if (l.quantity().signum() < 0 || (l.damaged() != null && l.damaged().signum() < 0) || (l.unitCost() != null && l.unitCost().signum() < 0)) {
                throw new BadRequestException("Quantities and costs can't be negative (" + i.getName() + ")");
            }
            given.put(i.getId(), l);
        }
        boolean anything = given.values().stream().anyMatch(l -> l.quantity().signum() > 0 || (l.damaged() != null && l.damaged().signum() > 0));
        if (!anything) {
            throw new BadRequestException("Enter what arrived");
        }
        String reference = "DEL-" + (po == null ? shortId(UUID.randomUUID().toString()) : po.getNumber());
        BigDecimal total = BigDecimal.ZERO;
        List<DeliveryLine> lines = new ArrayList<>();
        int shortCount = 0;
        int overCount = 0;
        int damagedCount = 0;
        Set<String> keys = new java.util.LinkedHashSet<>(onOrder.keySet());
        keys.addAll(given.keySet());
        for (String itemId : keys) {
            StockItem i = im.get(itemId);
            InventoryRequests.Line l = given.get(itemId);
            OrderLine ol = onOrder.get(itemId);
            BigDecimal q = l == null ? BigDecimal.ZERO : Money.of(l.quantity());
            BigDecimal dmg = l == null || l.damaged() == null ? BigDecimal.ZERO : Money.of(l.damaged());
            BigDecimal c = l != null && l.unitCost() != null ? Money.of(l.unitCost()) : ol != null ? ol.unitCost() : cost(i);
            BigDecimal outstanding = ol == null ? null : ol.quantity().subtract(ol.received()).max(BigDecimal.ZERO);
            BigDecimal shortQ = outstanding == null ? BigDecimal.ZERO : outstanding.subtract(q).max(BigDecimal.ZERO);
            BigDecimal overQ = outstanding == null ? BigDecimal.ZERO : q.subtract(outstanding).max(BigDecimal.ZERO);
            if (l == null && (outstanding == null || outstanding.signum() == 0)) {
                continue;
            }
            if (shortQ.signum() > 0) {
                shortCount++;
            }
            if (overQ.signum() > 0) {
                overCount++;
            }
            if (dmg.signum() > 0) {
                damagedCount++;
            }
            if (q.signum() > 0) {
                BigDecimal onHand = levels.findByItemId(itemId).stream().map(StockLevel::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
                i.setUnitCost(StockRules.weightedCost(onHand, i.getUnitCost(), q, c));
                items.save(i);
                move(level(itemId, store.getId()), StockMovement.Kind.RECEIVED, q, reference,
                        "Delivery" + (supplierName == null ? "" : " from " + supplierName), null, c);
                total = total.add(q.multiply(c));
            }
            lines.add(new DeliveryLine(itemId, i.getName(), i.getUnit(), q, c, dmg, outstanding, shortQ, overQ, l == null ? null : trim(l.note(), 200)));
        }
        List<String> bits = new ArrayList<>();
        if (shortCount > 0) {
            bits.add(shortCount + " short");
        }
        if (overCount > 0) {
            bits.add(overCount + " over");
        }
        if (damagedCount > 0) {
            bits.add(damagedCount + " damaged");
        }
        String summary = bits.isEmpty() ? null : String.join(" · ", bits);
        if (po != null) {
            List<OrderLine> updated = new ArrayList<>();
            for (OrderLine ol : onOrder.values()) {
                InventoryRequests.Line l = given.get(ol.itemId());
                BigDecimal q = l == null ? BigDecimal.ZERO : Money.of(l.quantity());
                BigDecimal dmg = l == null || l.damaged() == null ? BigDecimal.ZERO : Money.of(l.damaged());
                updated.add(new OrderLine(ol.itemId(), ol.name(), ol.unit(), ol.quantity(), ol.unitCost(), ol.received().add(q), ol.damaged().add(dmg)));
            }
            boolean complete = updated.stream().allMatch(ol -> ol.received().compareTo(ol.quantity()) >= 0);
            po.setLines(write(updated));
            po.setStatus(complete ? PurchaseOrder.Status.RECEIVED : PurchaseOrder.Status.PARTIAL);
            po.setReceivedValue(Money.of(po.getReceivedValue().add(total)));
            orders.save(po);
        }
        com.hospio.inventory.entity.Delivery d = deliveries.save(com.hospio.inventory.entity.Delivery.builder().purchaseOrderId(po == null ? null : po.getId())
                .supplierName(supplierName).storeId(store.getId()).lines(write(lines)).total(Money.of(total)).note(trim(request.note(), 300))
                .invoiceNumber(trim(request.invoiceNumber(), 60)).summary(summary).receivedBy(u.getUserId()).receivedByName(u.getFullName()).build());
        events.publish(KafkaTopics.DELIVERY_RECEIVED, DeliveryReceivedEvent.builder().deliveryId(d.getId()).purchaseOrderId(d.getPurchaseOrderId())
                .supplierName(supplierName).total(d.getTotal()).build());
        return deliveryView(d, po == null ? Map.of() : Map.of(po.getId(), po), storeMap(), itemMap());
    }

    // ---- counts -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<InventoryViews.CountView> counts() {
        HospioUserDetails u = user();
        Map<String, Store> sm = storeMap();
        boolean all = isStore(u) || seesMoney(u);
        Set<String> mine = teamsOf(u);
        return counts.findByHotelIdOrderByCreatedAtDesc(TenantContext.requireHotel()).stream()
                .filter(c -> all || (sm.containsKey(c.getStoreId()) && mine.contains(sm.get(c.getStoreId()).getKind())))
                .map(c -> countView(c, sm)).toList();
    }

    private InventoryViews.CountView countView(StockCount c, Map<String, Store> sm) {
        Store s = sm.get(c.getStoreId());
        return new InventoryViews.CountView(c.getId(), c.getStoreId(), s == null ? "Store" : s.getName(), c.getStatus().name(),
                read(c.getLines(), CountLine.class), c.getVarianceValue(), c.getNote(), c.getStartedByName(), c.getCountedByName(),
                c.getApprovedByName(), c.getCancelReason(), c.getCreatedAt(), c.getClosedAt());
    }

    @Transactional
    public InventoryViews.CountView startCount(InventoryRequests.CountRequest request) {
        HospioUserDetails u = user();
        Store store = store(request.storeId());
        if (!canUse(u, store)) {
            throw new ForbiddenException("Counting the " + store.getName() + " is for its team, the store or a manager");
        }
        counts.findFirstByStoreIdAndStatusIn(store.getId(), OPEN_COUNTS).ifPresent(open -> {
            throw new BadRequestException("The " + store.getName() + " already has a count " + (open.getStatus() == StockCount.Status.OPEN ? "open" : "waiting for approval")
                    + (open.getStartedByName() == null ? "" : " (started by " + open.getStartedByName() + ")") + " · finish or cancel it first");
        });
        StockCount c = counts.save(StockCount.builder().storeId(store.getId()).startedBy(u.getUserId()).startedByName(u.getFullName()).build());
        return countView(c, storeMap());
    }

    @Transactional
    public InventoryViews.CountView submitCount(String id, InventoryRequests.CountLinesRequest request) {
        HospioUserDetails u = user();
        StockCount c = count(id);
        Store store = store(c.getStoreId());
        if (!canUse(u, store)) {
            throw new ForbiddenException("Counting the " + store.getName() + " is for its team, the store or a manager");
        }
        if (!OPEN_COUNTS.contains(c.getStatus())) {
            throw new BadRequestException("This count is already " + c.getStatus().name().toLowerCase(Locale.ROOT));
        }
        if (request.lines().isEmpty()) {
            throw new BadRequestException("Enter what you counted");
        }
        Map<String, BigDecimal> counted = new LinkedHashMap<>();
        for (InventoryRequests.Line l : request.lines()) {
            if (l.quantity() == null || l.quantity().signum() < 0) {
                throw new BadRequestException("A counted quantity can't be negative (" + nameOf(l.itemId(), itemMap()) + ")");
            }
            if (counted.put(l.itemId(), Money.of(l.quantity())) != null) {
                throw new BadRequestException(nameOf(l.itemId(), itemMap()) + " is on the count twice");
            }
        }
        List<CountLine> lines = new ArrayList<>();
        BigDecimal value = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : counted.entrySet()) {
            StockItem i = item(e.getKey());
            BigDecimal expected = levels.findByItemIdAndStoreId(i.getId(), store.getId()).map(StockLevel::getQuantity).orElse(BigDecimal.ZERO);
            BigDecimal variance = e.getValue().subtract(expected);
            BigDecimal c1 = cost(i);
            BigDecimal v = Money.of(variance.multiply(c1));
            value = value.add(v);
            lines.add(new CountLine(i.getId(), i.getName(), i.getUnit(), expected, e.getValue(), variance, c1, v));
        }
        c.setLines(write(lines));
        c.setVarianceValue(Money.of(value));
        c.setNote(trim(request.note(), 500));
        c.setStatus(StockCount.Status.COUNTED);
        c.setCountedBy(u.getUserId());
        c.setCountedByName(u.getFullName());
        return countView(counts.save(c), storeMap());
    }

    @Transactional
    public InventoryViews.CountView approveCount(String id) {
        HospioUserDetails u = user();
        if (u.isReadOnly() || !(u.isManagerOrOwner() || u.isPlatform())) {
            throw new ForbiddenException("Approving a count is for a manager");
        }
        StockCount c = count(id);
        if (c.getStatus() == StockCount.Status.OPEN) {
            throw new BadRequestException("Nothing counted yet");
        }
        if (c.getStatus() != StockCount.Status.COUNTED) {
            throw new BadRequestException("This count is already " + c.getStatus().name().toLowerCase(Locale.ROOT));
        }
        for (CountLine l : read(c.getLines(), CountLine.class)) {
            if (l.variance() == null || l.variance().signum() == 0) {
                continue;
            }
            StockLevel level = level(l.itemId(), c.getStoreId());
            BigDecimal delta = StockRules.countDelta(level.getQuantity(), l.variance());
            if (delta.signum() != 0) {
                move(level, StockMovement.Kind.COUNTED, delta, "COUNT-" + shortId(c.getId()), "Stock count", null, l.unitCost());
            }
        }
        c.setStatus(StockCount.Status.APPROVED);
        c.setApprovedBy(u.getUserId());
        c.setApprovedByName(u.getFullName());
        c.setClosedAt(Instant.now());
        return countView(counts.save(c), storeMap());
    }

    @Transactional
    public InventoryViews.CountView cancelCount(String id, String reason) {
        if (!StringUtils.hasText(reason)) {
            throw new BadRequestException("Give a reason for cancelling the count");
        }
        StockCount c = count(id);
        if (!OPEN_COUNTS.contains(c.getStatus())) {
            throw new BadRequestException("This count is already " + c.getStatus().name().toLowerCase(Locale.ROOT));
        }
        c.setStatus(StockCount.Status.CANCELLED);
        c.setCancelReason(trim(reason, 300));
        c.setClosedAt(Instant.now());
        return countView(counts.save(c), storeMap());
    }

    // ---- money ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public InventoryViews.ValueReport value() {
        String hotel = TenantContext.requireHotel();
        Map<String, StockItem> im = itemMap();
        Map<String, Supplier> sup = supplierMap();
        List<StockLevel> all = levels.findByHotelId(hotel);
        List<InventoryViews.StoreView> sv = stores.findByHotelIdAndActiveTrueOrderByName(hotel).stream().map(s -> storeView(s, all, im, true)).toList();
        Map<String, Store> sm = storeMap();
        List<InventoryViews.LowItem> low = new ArrayList<>();
        for (StockLevel l : all) {
            StockItem i = im.get(l.getItemId());
            Store s = sm.get(l.getStoreId());
            if (i == null || !i.isActive() || s == null || !s.isActive() || l.getQuantity().compareTo(i.getMinLevel()) > 0) {
                continue;
            }
            Supplier su = i.getSupplierId() == null ? null : sup.get(i.getSupplierId());
            low.add(new InventoryViews.LowItem(i.getId(), i.getName(), i.getUnit(), s.getId(), s.getName(), l.getQuantity(), i.getMinLevel(), i.getParLevel(),
                    StockRules.suggest(l.getQuantity(), i.getMinLevel(), i.getParLevel()), cost(i), i.getSupplierId(), su == null ? null : su.getName()));
        }
        low.sort(Comparator.comparing(InventoryViews.LowItem::storeName).thenComparing(InventoryViews.LowItem::name));
        BigDecimal onOrder = BigDecimal.ZERO;
        for (PurchaseOrder p : orders.findByHotelIdAndStatusIn(hotel, EnumSet.of(PurchaseOrder.Status.SENT, PurchaseOrder.Status.PARTIAL))) {
            for (OrderLine ol : orderLines(p, im)) {
                onOrder = onOrder.add(ol.quantity().subtract(ol.received()).max(BigDecimal.ZERO).multiply(ol.unitCost()));
            }
        }
        BigDecimal total = sv.stream().map(InventoryViews.StoreView::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new InventoryViews.ValueReport(Money.of(total), sv, low, Money.of(onOrder));
    }

    @Transactional(readOnly = true)
    public InventoryViews.UsageReport usage(LocalDate from, LocalDate to) {
        String hotel = TenantContext.requireHotel();
        LocalDate t = to == null ? LocalDate.now(LAGOS) : to;
        LocalDate f = from == null ? t.minusDays(29) : from;
        if (f.isAfter(t)) {
            throw new BadRequestException("The start date is after the end date");
        }
        Map<String, StockItem> im = itemMap();
        List<Store> all = stores.findByHotelIdOrderByName(hotel);
        Map<String, BigDecimal[]> byStore = new LinkedHashMap<>();
        all.forEach(s -> byStore.put(s.getId(), zeros(6)));
        Map<String, BigDecimal> byTeam = new java.util.TreeMap<>();
        for (StockMovement m : movements.findByHotelIdAndCreatedAtBetweenOrderByCreatedAtDesc(hotel, start(f), end(t))) {
            StockItem i = im.get(m.getItemId());
            BigDecimal c = m.getUnitCost() != null ? m.getUnitCost() : i == null ? BigDecimal.ZERO : cost(i);
            BigDecimal v = m.getQuantity().multiply(c);
            BigDecimal[] row = byStore.computeIfAbsent(m.getStoreId(), k -> zeros(6));
            switch (m.getKind()) {
                case RECEIVED -> row[0] = row[0].add(v);
                case TRANSFER_IN -> row[1] = row[1].add(v);
                case TRANSFER_OUT, ISSUED -> row[2] = row[2].add(v.negate());
                case USED -> row[3] = row[3].add(v.negate());
                case WASTE -> row[4] = row[4].add(v.negate());
                case COUNTED, ADJUSTED -> row[5] = row[5].add(v);
                default -> {
                }
            }
            if (m.getTeam() != null && (m.getKind() == StockMovement.Kind.ISSUED || m.getKind() == StockMovement.Kind.TRANSFER_OUT)) {
                byTeam.merge(m.getTeam(), v.negate(), BigDecimal::add);
            }
        }
        Map<String, Store> sm = all.stream().collect(Collectors.toMap(Store::getId, Function.identity()));
        List<InventoryViews.StoreUsage> rows = new ArrayList<>();
        BigDecimal[] tot = zeros(6);
        for (Map.Entry<String, BigDecimal[]> e : byStore.entrySet()) {
            Store s = sm.get(e.getKey());
            BigDecimal[] r = e.getValue();
            for (int k = 0; k < 6; k++) {
                tot[k] = tot[k].add(r[k]);
            }
            if (s == null || (!s.isActive() && java.util.Arrays.stream(r).allMatch(x -> x.signum() == 0))) {
                continue;
            }
            rows.add(new InventoryViews.StoreUsage(s.getId(), s.getName(), s.getKind(), Money.of(r[0]), Money.of(r[1]), Money.of(r[2]), Money.of(r[3]),
                    Money.of(r[4]), Money.of(r[5])));
        }
        List<InventoryViews.TeamUsage> teams = byTeam.entrySet().stream().map(e -> new InventoryViews.TeamUsage(e.getKey(), Money.of(e.getValue()))).toList();
        BigDecimal issued = teams.stream().map(InventoryViews.TeamUsage::issued).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new InventoryViews.UsageReport(f, t, rows, teams, new InventoryViews.UsageTotals(Money.of(tot[0]), Money.of(issued), Money.of(tot[3]),
                Money.of(tot[4]), Money.of(tot[5])));
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private StockLevel move(StockLevel level, StockMovement.Kind kind, BigDecimal delta, String reference, String note, String team, BigDecimal unitCost) {
        StockItem item = item(level.getItemId());
        BigDecimal before = level.getQuantity();
        BigDecimal after = Money.of(before.add(delta));
        if (after.signum() < 0) {
            String where = stores.findById(level.getStoreId()).map(Store::getName).orElse("store");
            throw new BadRequestException("Only " + StockRules.qty(before) + " " + item.getUnit() + " " + item.getName() + " in the " + where);
        }
        level.setQuantity(after);
        levels.save(level);
        Tenant t = TenantContext.current().orElse(null);
        movements.save(StockMovement.builder().itemId(level.getItemId()).storeId(level.getStoreId()).kind(kind).quantity(Money.of(delta))
                .reference(reference).note(trim(note, 300)).team(team).unitCost(unitCost != null ? Money.of(unitCost) : item.getUnitCost())
                .byUser(t == null ? null : t.actorId()).byName(t == null ? null : trim(t.actorName(), 150)).build());
        if (StockRules.crossedLow(before, after, item.getMinLevel())) {
            events.publish(KafkaTopics.STOCK_LOW, StockLowEvent.builder().itemId(item.getId()).itemName(item.getName()).storeId(level.getStoreId())
                    .storeName(stores.findById(level.getStoreId()).map(Store::getName).orElse(null)).quantity(level.getQuantity())
                    .minLevel(item.getMinLevel()).unit(item.getUnit()).build());
        }
        return level;
    }

    private StockLevel level(String itemId, String storeId) {
        item(itemId);
        store(storeId);
        return levels.findByItemIdAndStoreId(itemId, storeId).orElseGet(() -> levels.save(StockLevel.builder().itemId(itemId).storeId(storeId).build()));
    }

    private Map<String, BigDecimal> mergeLines(List<InventoryRequests.Line> lines, String verb) {
        if (lines == null || lines.isEmpty()) {
            throw new BadRequestException("Add at least one item");
        }
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (InventoryRequests.Line l : lines) {
            StockItem i = item(l.itemId());
            if (l.quantity() == null || l.quantity().signum() <= 0) {
                throw new BadRequestException("Enter a quantity above zero to " + verb + " (" + i.getName() + ")");
            }
            out.merge(i.getId(), Money.of(l.quantity()), BigDecimal::add);
        }
        return out;
    }

    private Store store(String id) {
        return stores.findById(id).orElseThrow(() -> new ResourceNotFoundException("Store not found"));
    }

    private Store mainStore() {
        return stores.findByHotelIdAndActiveTrueOrderByName(TenantContext.requireHotel()).stream().filter(s -> "MAIN".equals(s.getKind())).findFirst()
                .orElseThrow(() -> new BadRequestException("Add a main store first (Inventory → Stores)"));
    }

    private StockItem item(String id) {
        return items.findById(id).orElseThrow(() -> new ResourceNotFoundException("Stock item not found"));
    }

    private PurchaseOrder order(String id) {
        return orders.findById(id).orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }

    private StockCount count(String id) {
        return counts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Count not found"));
    }

    private Map<String, Store> storeMap() {
        return stores.findByHotelIdOrderByName(TenantContext.requireHotel()).stream().collect(Collectors.toMap(Store::getId, Function.identity()));
    }

    private Map<String, StockItem> itemMap() {
        return items.findByHotelIdOrderByCategoryAscNameAsc(TenantContext.requireHotel()).stream().collect(Collectors.toMap(StockItem::getId, Function.identity()));
    }

    private Map<String, Supplier> supplierMap() {
        return suppliers.findByHotelIdOrderByName(TenantContext.requireHotel()).stream().collect(Collectors.toMap(Supplier::getId, Function.identity()));
    }

    private static BigDecimal cost(StockItem i) {
        return i.getUnitCost() == null ? BigDecimal.ZERO : i.getUnitCost();
    }

    private static BigDecimal z(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }

    private static String teamOfStore(Store s) {
        try {
            Role r = Role.valueOf(s.getKind());
            return TEAMS.contains(r) ? r.name() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String teamLabel(String team) {
        if (team == null) {
            return "the team";
        }
        String t = team.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static String label(Enum<?> status) {
        return status.name().toLowerCase(Locale.ROOT).replace('_', ' ').replace("pending approval", "waiting for approval")
                .replace("part issued", "part issued");
    }

    private static String nameOf(String itemId, Map<String, StockItem> im) {
        StockItem i = im.get(itemId);
        return i == null ? "That item" : i.getName();
    }

    private static String shortId(String id) {
        return id.replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String trim(String s, int max) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static Instant start(LocalDate d) {
        return d.atStartOfDay(LAGOS).toInstant();
    }

    private static Instant end(LocalDate d) {
        return d.plusDays(1).atStartOfDay(LAGOS).toInstant();
    }

    private String write(Object lines) {
        try {
            return objectMapper.writeValueAsString(lines);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Lines could not be stored");
        }
    }

    private <T> List<T> read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JavaType t = objectMapper.getTypeFactory().constructCollectionType(List.class, type);
            return objectMapper.readerFor(t).without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(json);
        } catch (JsonProcessingException e) {
            log.warn("Unreadable lines {}: {}", json, e.getMessage());
            return List.of();
        }
    }
}
