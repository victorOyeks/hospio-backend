package com.hospio.inventory.controller;

import com.hospio.common.response.ApiResponse;
import com.hospio.inventory.dto.InventoryRequests;
import com.hospio.inventory.dto.InventoryViews;
import com.hospio.inventory.entity.StockLevel;
import com.hospio.inventory.service.InventoryService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Inventory and stores (docs/inventory-stores.md); Kitchen and Bar use levels, use/waste, requests and counts. */
@RestController
@RequiredArgsConstructor
public class InventoryController {

    /** Any member of the hotel's staff (reads of the catalogue every team needs to ask the store). */
    private static final String STAFF = "isAuthenticated()";
    /** A team that works a store or asks it for stock (the service checks the store and team), the inventory team, or a manager. */
    private static final String TEAM = "@access.manager() or @access.atLeast('INVENTORY','EDIT') or @access.atLeast('KITCHEN','EDIT') or @access.atLeast('BAR','EDIT')"
            + " or @access.atLeast('HOUSEKEEPING','EDIT') or @access.atLeast('LAUNDRY','EDIT') or @access.atLeast('MAINTENANCE','EDIT')"
            + " or @access.atLeast('FRONT_OFFICE','EDIT')";
    /** The store's own work: the inventory team at Edit or Full, or a manager / owner. */
    private static final String STORE = "@access.manager() or @access.atLeast('INVENTORY','EDIT')";
    /** Values and the paper trail: the store, managers and owners (also read-only), finance. */
    private static final String MONEY = "@access.managerView() or @access.has('INVENTORY') or @access.has('FINANCE')";

    private final InventoryService service;

    // ---- stores and items

    @GetMapping("/stores")
    @PreAuthorize(STAFF)
    public ApiResponse<List<InventoryViews.StoreView>> stores() {
        return ApiResponse.ok(service.stores());
    }

    @PostMapping("/stores")
    @PreAuthorize(STORE)
    public ApiResponse<InventoryViews.StoreView> addStore(@Valid @RequestBody InventoryRequests.StoreRequest request) {
        return ApiResponse.ok("Store added", service.saveStore(null, request));
    }

    @PutMapping("/stores/{id}")
    @PreAuthorize(STORE)
    public ApiResponse<InventoryViews.StoreView> updateStore(@PathVariable String id, @Valid @RequestBody InventoryRequests.StoreRequest request) {
        return ApiResponse.ok("Saved", service.saveStore(id, request));
    }

    @GetMapping("/items")
    @PreAuthorize(STAFF)
    public ApiResponse<List<InventoryViews.ItemView>> items() {
        return ApiResponse.ok(service.items());
    }

    @GetMapping("/items/{id}")
    @PreAuthorize(STAFF)
    public ApiResponse<InventoryViews.ItemDetail> item(@PathVariable String id) {
        return ApiResponse.ok(service.itemDetail(id));
    }

    @PostMapping("/items")
    @PreAuthorize(STORE)
    public ApiResponse<InventoryViews.ItemView> addItem(@Valid @RequestBody InventoryRequests.ItemRequest request) {
        return ApiResponse.ok("Item added", service.saveItem(null, request));
    }

    @PutMapping("/items/{id}")
    @PreAuthorize(STORE)
    public ApiResponse<InventoryViews.ItemView> updateItem(@PathVariable String id, @Valid @RequestBody InventoryRequests.ItemRequest request) {
        return ApiResponse.ok("Saved", service.saveItem(id, request));
    }

    @GetMapping("/levels")
    @PreAuthorize(STAFF)
    public ApiResponse<List<StockLevel>> levels(@RequestParam(required = false) String storeId) {
        return ApiResponse.ok(service.levels(storeId));
    }

    @GetMapping("/movements")
    @PreAuthorize(MONEY)
    public ApiResponse<List<InventoryViews.MovementView>> movements(@RequestParam(required = false) String itemId, @RequestParam(required = false) String storeId,
                                                                   @RequestParam(required = false) String kind,
                                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.movements(itemId, storeId, kind, from, to));
    }

    @PostMapping("/levels/adjust")
    @PreAuthorize("@access.manager() or @access.atLeast('INVENTORY','FULL')")
    public ApiResponse<StockLevel> adjust(@Valid @RequestBody InventoryRequests.AdjustRequest request) {
        return ApiResponse.ok("Level corrected", service.adjust(request));
    }

    @PostMapping("/levels/use")
    @PreAuthorize(TEAM)
    public ApiResponse<StockLevel> useOrWaste(@Valid @RequestBody InventoryRequests.UseOrWasteRequest request) {
        return ApiResponse.ok("Recorded", service.useOrWaste(request));
    }

    // ---- transfers

    @PostMapping("/transfers")
    @PreAuthorize(STORE)
    public ApiResponse<InventoryViews.TransferView> transfer(@Valid @RequestBody InventoryRequests.TransferRequest request) {
        return ApiResponse.ok("Stock moved", service.transfer(request));
    }

    @GetMapping("/transfers")
    @PreAuthorize(MONEY)
    public ApiResponse<List<InventoryViews.TransferView>> transfers(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.transfers(from, to));
    }

    // ---- requests

    @GetMapping("/requests")
    @PreAuthorize(STAFF)
    public ApiResponse<List<InventoryViews.RequestView>> requests(@RequestParam(required = false) String team, @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.requests(team, status));
    }

    @PostMapping("/requests")
    @PreAuthorize(TEAM)
    public ApiResponse<InventoryViews.RequestView> askTheStore(@Valid @RequestBody InventoryRequests.StockRequestDto request) {
        return ApiResponse.ok("Request sent to the store", service.askTheStore(request));
    }

    @PostMapping("/requests/{id}/issue")
    @PreAuthorize(STORE)
    public ApiResponse<InventoryViews.RequestView> issue(@PathVariable String id, @Valid @RequestBody InventoryRequests.IssueRequest request) {
        InventoryViews.RequestView r = service.issue(id, request);
        return ApiResponse.ok("PART_ISSUED".equals(r.status()) ? "Part issued · the rest stays open" : "Issued", r);
    }

    @PostMapping("/requests/{id}/decline")
    @PreAuthorize(STORE)
    public ApiResponse<InventoryViews.RequestView> decline(@PathVariable String id, @RequestBody(required = false) InventoryRequests.ReasonRequest request) {
        return ApiResponse.ok("Declined · the team is told", service.decline(id, request == null ? null : request.reason()));
    }

    // ---- suppliers

    @GetMapping("/suppliers")
    @PreAuthorize(MONEY)
    public ApiResponse<List<InventoryViews.SupplierView>> suppliers() {
        return ApiResponse.ok(service.suppliers());
    }

    @PostMapping("/suppliers")
    @PreAuthorize(STORE)
    public ApiResponse<InventoryViews.SupplierView> addSupplier(@Valid @RequestBody InventoryRequests.SupplierRequest request) {
        return ApiResponse.ok("Supplier added", service.saveSupplier(null, request));
    }

    @PutMapping("/suppliers/{id}")
    @PreAuthorize(STORE)
    public ApiResponse<InventoryViews.SupplierView> updateSupplier(@PathVariable String id, @Valid @RequestBody InventoryRequests.SupplierRequest request) {
        return ApiResponse.ok("Saved", service.saveSupplier(id, request));
    }

    // ---- purchase orders

    @GetMapping("/purchase-orders")
    @PreAuthorize(MONEY)
    public ApiResponse<List<InventoryViews.OrderView>> purchaseOrders(@RequestParam(required = false) String status) {
        return ApiResponse.ok(service.purchaseOrders(status));
    }

    @GetMapping("/purchase-orders/{id}")
    @PreAuthorize(MONEY)
    public ApiResponse<InventoryViews.OrderView> purchaseOrder(@PathVariable String id) {
        return ApiResponse.ok(service.purchaseOrder(id));
    }

    @PostMapping("/purchase-orders")
    @PreAuthorize(STORE)
    public ApiResponse<InventoryViews.OrderView> createOrder(@Valid @RequestBody InventoryRequests.PurchaseOrderRequest request) {
        InventoryViews.OrderView po = service.createOrder(request);
        return ApiResponse.ok(message(po), po);
    }

    @PutMapping("/purchase-orders/{id}")
    @PreAuthorize(STORE)
    public ApiResponse<InventoryViews.OrderView> updateOrder(@PathVariable String id, @Valid @RequestBody InventoryRequests.PurchaseOrderRequest request) {
        InventoryViews.OrderView po = service.updateOrder(id, request);
        return ApiResponse.ok(message(po), po);
    }

    @PostMapping("/purchase-orders/{id}/submit")
    @PreAuthorize(STORE)
    public ApiResponse<InventoryViews.OrderView> submit(@PathVariable String id) {
        InventoryViews.OrderView po = service.submit(id);
        return ApiResponse.ok(message(po), po);
    }

    @PostMapping("/purchase-orders/{id}/cancel")
    @PreAuthorize(STORE)
    public ApiResponse<InventoryViews.OrderView> cancelOrder(@PathVariable String id, @RequestBody(required = false) InventoryRequests.ReasonRequest request) {
        return ApiResponse.ok("Order cancelled", service.cancelOrder(id, request == null ? null : request.reason()));
    }

    @PostMapping("/purchase-orders/{id}/close")
    @PreAuthorize(STORE)
    public ApiResponse<InventoryViews.OrderView> closeOrder(@PathVariable String id, @RequestBody(required = false) InventoryRequests.ReasonRequest request) {
        return ApiResponse.ok("Order closed short", service.closeOrder(id, request == null ? null : request.reason()));
    }

    private static String message(InventoryViews.OrderView po) {
        return switch (po.status()) {
            case "DRAFT" -> "Saved as a draft";
            case "PENDING_APPROVAL" -> "Sent to the " + ("OWNER".equals(po.approverLevel()) ? "owner" : "manager") + " for approval";
            case "SENT" -> "Order sent to " + po.supplierName();
            default -> "Saved";
        };
    }

    // ---- deliveries

    @GetMapping("/deliveries")
    @PreAuthorize(MONEY)
    public ApiResponse<List<InventoryViews.DeliveryView>> deliveries() {
        return ApiResponse.ok(service.deliveries());
    }

    @PostMapping("/deliveries")
    @PreAuthorize(STORE)
    public ApiResponse<InventoryViews.DeliveryView> receive(@Valid @RequestBody InventoryRequests.DeliveryRequest request) {
        return ApiResponse.ok("Delivery received · stock updated", service.receive(request));
    }

    // ---- counts

    @GetMapping("/counts")
    @PreAuthorize(STAFF)
    public ApiResponse<List<InventoryViews.CountView>> counts() {
        return ApiResponse.ok(service.counts());
    }

    @PostMapping("/counts")
    @PreAuthorize(TEAM)
    public ApiResponse<InventoryViews.CountView> startCount(@Valid @RequestBody InventoryRequests.CountRequest request) {
        return ApiResponse.ok("Count started", service.startCount(request));
    }

    @PutMapping("/counts/{id}")
    @PreAuthorize(TEAM)
    public ApiResponse<InventoryViews.CountView> submitCount(@PathVariable String id, @Valid @RequestBody InventoryRequests.CountLinesRequest request) {
        return ApiResponse.ok("Count submitted · a manager approves the difference", service.submitCount(id, request));
    }

    /** The store may call it; the service answers anyone but a manager or owner with "Approving a count is for a manager". */
    @PostMapping("/counts/{id}/approve")
    @PreAuthorize(STORE)
    public ApiResponse<InventoryViews.CountView> approveCount(@PathVariable String id) {
        return ApiResponse.ok("Count approved · levels corrected", service.approveCount(id));
    }

    @PostMapping("/counts/{id}/cancel")
    @PreAuthorize(STORE)
    public ApiResponse<InventoryViews.CountView> cancelCount(@PathVariable String id, @RequestBody(required = false) InventoryRequests.ReasonRequest request) {
        return ApiResponse.ok("Count cancelled", service.cancelCount(id, request == null ? null : request.reason()));
    }

    // ---- money

    @GetMapping("/value")
    @PreAuthorize(MONEY)
    public ApiResponse<InventoryViews.ValueReport> value() {
        return ApiResponse.ok(service.value());
    }

    @GetMapping("/usage")
    @PreAuthorize(MONEY)
    public ApiResponse<InventoryViews.UsageReport> usage(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.usage(from, to));
    }
}
