package com.hospio.inventory.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalRequestedEvent;
import com.hospio.common.kafka.events.DeliveryReceivedEvent;
import com.hospio.common.kafka.events.StockIssuedEvent;
import com.hospio.common.kafka.events.StockLowEvent;
import com.hospio.common.kafka.events.StockRequestedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.inventory.dto.InventoryRequests;
import com.hospio.inventory.entity.Delivery;
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
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Inventory 1.x-7.x: stores and stock, requests from teams, orders, deliveries, counts, suppliers. */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private static final TypeReference<List<InventoryRequests.Line>> LINES = new TypeReference<>() {
    };

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

    @Value("${hospio.approvals.manager-spend-limit:500000}")
    private BigDecimal managerLimit;

    // ---- stores and items ---------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Store> stores() {
        return stores.findByHotelIdAndActiveTrueOrderByName(TenantContext.requireHotel());
    }

    @Transactional
    public Store saveStore(String id, InventoryRequests.StoreRequest request) {
        Store s = id == null ? new Store() : stores.findById(id).orElseThrow(() -> new ResourceNotFoundException("Store not found"));
        s.setName(request.name());
        if (request.kind() != null) {
            s.setKind(request.kind());
        }
        if (request.active() != null) {
            s.setActive(request.active());
        }
        return stores.save(s);
    }

    @Transactional(readOnly = true)
    public List<StockItem> items() {
        return items.findByHotelIdAndActiveTrueOrderByCategoryAscNameAsc(TenantContext.requireHotel());
    }

    @Transactional
    public StockItem saveItem(String id, InventoryRequests.ItemRequest request) {
        StockItem i = id == null ? new StockItem() : item(id);
        i.setName(request.name());
        i.setCategory(request.category());
        if (request.unit() != null) {
            i.setUnit(request.unit());
        }
        if (request.minLevel() != null) {
            i.setMinLevel(request.minLevel());
        }
        i.setUnitCost(request.unitCost());
        i.setSupplierId(request.supplierId());
        if (request.active() != null) {
            i.setActive(request.active());
        }
        return items.save(i);
    }

    @Transactional(readOnly = true)
    public List<StockLevel> levels(String storeId) {
        return storeId == null ? levels.findByHotelId(TenantContext.requireHotel()) : levels.findByStoreId(storeId);
    }

    @Transactional(readOnly = true)
    public List<StockMovement> movements() {
        return movements.findByHotelIdOrderByCreatedAtDesc(TenantContext.requireHotel());
    }

    @Transactional
    public StockLevel adjust(InventoryRequests.AdjustRequest request) {
        if (!CurrentUser.require().isManagerOrOwner() && !CurrentUser.require().has(com.hospio.common.enums.Role.INVENTORY, com.hospio.common.enums.AccessLevel.FULL)) {
            throw new ForbiddenException("Correcting a stock level needs Full access");
        }
        StockLevel level = level(request.itemId(), request.storeId());
        BigDecimal delta = request.quantity().subtract(level.getQuantity());
        return move(level, StockMovement.Kind.ADJUSTED, delta, null, request.reason());
    }

    @Transactional
    public StockLevel useOrWaste(InventoryRequests.UseOrWasteRequest request) {
        StockLevel level = level(request.itemId(), request.storeId());
        return move(level, request.waste() ? StockMovement.Kind.WASTE : StockMovement.Kind.USED, request.quantity().negate(), null, request.note());
    }

    // ---- requests from teams ------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<StockRequest> openRequests() {
        return requests.findByHotelIdAndStatusOrderByCreatedAt(TenantContext.requireHotel(), StockRequest.Status.REQUESTED);
    }

    @Transactional(readOnly = true)
    public List<StockRequest> requestsFrom(String team) {
        return requests.findByHotelIdAndFromTeamOrderByCreatedAtDesc(TenantContext.requireHotel(), team.toUpperCase());
    }

    @Transactional
    public StockRequest askTheStore(InventoryRequests.StockRequestDto dto) {
        HospioUserDetails user = CurrentUser.require();
        StockRequest r = requests.save(StockRequest.builder().fromTeam(dto.fromTeam().toUpperCase()).storeId(dto.storeId())
                .lines(write(dto.lines())).note(dto.note()).requestedBy(user.getUserId()).build());
        events.publish(KafkaTopics.STOCK_REQUESTED, StockRequestedEvent.builder().requestId(r.getId()).fromTeam(r.getFromTeam())
                .storeId(r.getStoreId()).lines(summarise(dto.lines())).requestedBy(user.getFullName()).build());
        return r;
    }

    @Transactional
    public StockRequest issue(String requestId, InventoryRequests.IssueRequest request) {
        HospioUserDetails user = CurrentUser.require();
        StockRequest r = requests.findById(requestId).orElseThrow(() -> new ResourceNotFoundException("Request not found"));
        if (r.getStatus() != StockRequest.Status.REQUESTED) {
            throw new BadRequestException("This request is already " + r.getStatus().name().toLowerCase().replace('_', ' '));
        }
        List<InventoryRequests.Line> asked = read(r.getLines());
        List<InventoryRequests.Line> issued = request.lines() == null || request.lines().isEmpty() ? asked : request.lines();
        for (InventoryRequests.Line l : issued) {
            StockLevel level = level(l.itemId(), request.storeId());
            if (level.getQuantity().compareTo(l.quantity()) < 0) {
                throw new BadRequestException("Only " + level.getQuantity() + " " + item(l.itemId()).getName() + " in the store");
            }
            move(level, StockMovement.Kind.ISSUED, l.quantity().negate(), "REQ-" + r.getId(), "Issued to " + r.getFromTeam());
        }
        r.setStatus(issued.size() < asked.size() ? StockRequest.Status.PART_ISSUED : StockRequest.Status.ISSUED);
        r.setIssuedBy(user.getUserId());
        r.setIssuedAt(Instant.now());
        requests.save(r);
        events.publish(KafkaTopics.STOCK_ISSUED, StockIssuedEvent.builder().requestId(r.getId()).toTeam(r.getFromTeam()).itemCount(issued.size()).build());
        return r;
    }

    @Transactional
    public StockRequest decline(String requestId) {
        StockRequest r = requests.findById(requestId).orElseThrow(() -> new ResourceNotFoundException("Request not found"));
        r.setStatus(StockRequest.Status.DECLINED);
        return requests.save(r);
    }

    // ---- suppliers, orders, deliveries --------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Supplier> suppliers() {
        return suppliers.findByHotelIdAndActiveTrueOrderByName(TenantContext.requireHotel());
    }

    @Transactional
    public Supplier saveSupplier(String id, InventoryRequests.SupplierRequest request) {
        Supplier s = id == null ? new Supplier() : suppliers.findById(id).orElseThrow(() -> new ResourceNotFoundException("Supplier not found"));
        s.setName(request.name());
        s.setContactName(request.contactName());
        s.setPhone(request.phone());
        s.setEmail(request.email());
        s.setPaymentTerms(request.paymentTerms());
        if (request.active() != null) {
            s.setActive(request.active());
        }
        return suppliers.save(s);
    }

    @Transactional(readOnly = true)
    public List<PurchaseOrder> purchaseOrders() {
        return orders.findByHotelIdOrderByCreatedAtDesc(TenantContext.requireHotel());
    }

    @Transactional(readOnly = true)
    public PurchaseOrder purchaseOrder(String id) {
        return orders.findById(id).orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    }

    /** New order: sent straight away under the manager's limit, otherwise waits for approval. */
    @Transactional
    public PurchaseOrder createOrder(InventoryRequests.PurchaseOrderRequest request) {
        HospioUserDetails user = CurrentUser.require();
        suppliers.findById(request.supplierId()).orElseThrow(() -> new ResourceNotFoundException("Supplier not found"));
        BigDecimal total = request.lines().stream().map(l -> Money.of(l.unitCost()).multiply(l.quantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
        PurchaseOrder po = PurchaseOrder.builder().supplierId(request.supplierId()).storeId(request.storeId()).lines(write(request.lines()))
                .total(Money.of(total)).expectedOn(request.expectedOn()).createdBy(user.getUserId())
                .number("PO-" + LocalDate.now().getYear() + "-" + String.format("%05d", orders.countByHotelId(TenantContext.requireHotel()) + 1)).build();
        if (total.compareTo(managerLimit) > 0 && !user.has(com.hospio.common.enums.Role.OWNER) && !user.isPlatform()) {
            po.setStatus(PurchaseOrder.Status.PENDING_APPROVAL);
            po.setApprovalId(UUID.randomUUID().toString());
            orders.save(po);
            events.publish(KafkaTopics.APPROVAL_REQUESTED, ApprovalRequestedEvent.builder().approvalId(po.getApprovalId()).sourceModule("INVENTORY")
                    .sourceRef(po.getId()).kind("PURCHASE_ORDER").amount(po.getTotal()).requesterId(user.getUserId()).requesterName(user.getFullName())
                    .approverLevel("OWNER").description("Purchase order " + po.getNumber() + " of " + po.getTotal()).build());
            return po;
        }
        po.setStatus(PurchaseOrder.Status.SENT);
        po.setSentAt(Instant.now());
        return orders.save(po);
    }

    @Transactional
    public void onOrderDecided(String approvalId, String decision) {
        orders.findByApprovalId(approvalId).ifPresent(po -> {
            if (po.getStatus() != PurchaseOrder.Status.PENDING_APPROVAL) {
                return;
            }
            if ("APPROVED".equals(decision)) {
                po.setStatus(PurchaseOrder.Status.SENT);
                po.setSentAt(Instant.now());
            } else if ("DECLINED".equals(decision)) {
                po.setStatus(PurchaseOrder.Status.CANCELLED);
            }
            orders.save(po);
        });
    }

    @Transactional(readOnly = true)
    public List<Delivery> deliveries() {
        return deliveries.findByHotelIdOrderByCreatedAtDesc(TenantContext.requireHotel());
    }

    @Transactional
    public Delivery receive(InventoryRequests.DeliveryRequest request) {
        HospioUserDetails user = CurrentUser.require();
        String supplierName = request.supplierName();
        if (request.purchaseOrderId() != null) {
            PurchaseOrder po = purchaseOrder(request.purchaseOrderId());
            po.setStatus(PurchaseOrder.Status.RECEIVED);
            orders.save(po);
            if (supplierName == null) {
                supplierName = suppliers.findById(po.getSupplierId()).map(Supplier::getName).orElse(null);
            }
        }
        BigDecimal total = BigDecimal.ZERO;
        for (InventoryRequests.Line l : request.lines()) {
            move(level(l.itemId(), request.storeId()), StockMovement.Kind.RECEIVED, l.quantity(), "DEL", "Delivery" + (supplierName == null ? "" : " from " + supplierName));
            total = total.add(Money.of(l.unitCost()).multiply(l.quantity()));
        }
        Delivery d = deliveries.save(Delivery.builder().purchaseOrderId(request.purchaseOrderId()).supplierName(supplierName).storeId(request.storeId())
                .lines(write(request.lines())).total(Money.of(total)).note(request.note()).receivedBy(user.getUserId()).build());
        events.publish(KafkaTopics.DELIVERY_RECEIVED, DeliveryReceivedEvent.builder().deliveryId(d.getId()).purchaseOrderId(d.getPurchaseOrderId())
                .supplierName(supplierName).total(d.getTotal()).build());
        return d;
    }

    // ---- counts -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<StockCount> counts() {
        return counts.findByHotelIdOrderByCreatedAtDesc(TenantContext.requireHotel());
    }

    @Transactional
    public StockCount startCount(InventoryRequests.CountRequest request) {
        return counts.save(StockCount.builder().storeId(request.storeId()).startedBy(CurrentUser.require().getUserId()).build());
    }

    @Transactional
    public StockCount submitCount(String id, InventoryRequests.CountLinesRequest request) {
        StockCount c = count(id);
        c.setLines(write(request.lines()));
        c.setStatus(StockCount.Status.COUNTED);
        c.setCountedBy(CurrentUser.require().getUserId());
        return counts.save(c);
    }

    @Transactional
    public StockCount approveCount(String id) {
        HospioUserDetails user = CurrentUser.require();
        if (!user.isManagerOrOwner() && !user.has(com.hospio.common.enums.Role.INVENTORY, com.hospio.common.enums.AccessLevel.FULL)) {
            throw new ForbiddenException("Approving a count needs Full access");
        }
        StockCount c = count(id);
        if (c.getStatus() != StockCount.Status.COUNTED) {
            throw new BadRequestException("Nothing counted yet");
        }
        for (InventoryRequests.Line l : read(c.getLines())) {
            StockLevel level = level(l.itemId(), c.getStoreId());
            BigDecimal delta = l.quantity().subtract(level.getQuantity());
            if (delta.signum() != 0) {
                move(level, StockMovement.Kind.COUNTED, delta, "COUNT-" + c.getId(), "Stock count");
            }
        }
        c.setStatus(StockCount.Status.APPROVED);
        c.setApprovedBy(user.getUserId());
        c.setClosedAt(Instant.now());
        return counts.save(c);
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private StockLevel move(StockLevel level, StockMovement.Kind kind, BigDecimal delta, String reference, String note) {
        level.setQuantity(Money.of(level.getQuantity().add(delta)));
        if (level.getQuantity().signum() < 0) {
            throw new BadRequestException("That would take the stock below zero");
        }
        levels.save(level);
        movements.save(StockMovement.builder().itemId(level.getItemId()).storeId(level.getStoreId()).kind(kind).quantity(delta)
                .reference(reference).note(note).byUser(TenantContext.current().map(t -> t.actorId()).orElse(null)).build());
        StockItem item = item(level.getItemId());
        if (delta.signum() < 0 && level.getQuantity().compareTo(item.getMinLevel()) <= 0) {
            events.publish(KafkaTopics.STOCK_LOW, StockLowEvent.builder().itemId(item.getId()).itemName(item.getName()).storeId(level.getStoreId())
                    .storeName(stores.findById(level.getStoreId()).map(Store::getName).orElse(null)).quantity(level.getQuantity())
                    .minLevel(item.getMinLevel()).unit(item.getUnit()).build());
        }
        return level;
    }

    private StockLevel level(String itemId, String storeId) {
        item(itemId);
        stores.findById(storeId).orElseThrow(() -> new ResourceNotFoundException("Store not found"));
        return levels.findByItemIdAndStoreId(itemId, storeId).orElseGet(() -> levels.save(StockLevel.builder().itemId(itemId).storeId(storeId).build()));
    }

    private StockItem item(String id) {
        return items.findById(id).orElseThrow(() -> new ResourceNotFoundException("Stock item not found"));
    }

    private StockCount count(String id) {
        return counts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Count not found"));
    }

    private String write(List<InventoryRequests.Line> lines) {
        try {
            return objectMapper.writeValueAsString(lines);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Lines could not be stored");
        }
    }

    private List<InventoryRequests.Line> read(String json) {
        try {
            return json == null ? List.of() : objectMapper.readValue(json, LINES);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String summarise(List<InventoryRequests.Line> lines) {
        return lines.stream().map(l -> l.quantity() + " x " + (l.name() == null ? item(l.itemId()).getName() : l.name())).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
