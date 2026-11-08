package com.hospio.inventory.controller;

import com.hospio.common.response.ApiResponse;
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
import com.hospio.inventory.service.InventoryService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Inventory 1.1-7.1; Kitchen 4.x and Bar 5.x ask the store through /requests. */
@RestController
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService service;

    @GetMapping("/stores")
    public ApiResponse<List<Store>> stores() {
        return ApiResponse.ok(service.stores());
    }

    @PostMapping("/stores")
    public ApiResponse<Store> addStore(@Valid @RequestBody InventoryRequests.StoreRequest request) {
        return ApiResponse.ok("Store added", service.saveStore(null, request));
    }

    @PutMapping("/stores/{id}")
    public ApiResponse<Store> updateStore(@PathVariable String id, @Valid @RequestBody InventoryRequests.StoreRequest request) {
        return ApiResponse.ok("Saved", service.saveStore(id, request));
    }

    @GetMapping("/items")
    public ApiResponse<List<StockItem>> items() {
        return ApiResponse.ok(service.items());
    }

    @PostMapping("/items")
    public ApiResponse<StockItem> addItem(@Valid @RequestBody InventoryRequests.ItemRequest request) {
        return ApiResponse.ok("Item added", service.saveItem(null, request));
    }

    @PutMapping("/items/{id}")
    public ApiResponse<StockItem> updateItem(@PathVariable String id, @Valid @RequestBody InventoryRequests.ItemRequest request) {
        return ApiResponse.ok("Saved", service.saveItem(id, request));
    }

    @GetMapping("/levels")
    public ApiResponse<List<StockLevel>> levels(@RequestParam(required = false) String storeId) {
        return ApiResponse.ok(service.levels(storeId));
    }

    @GetMapping("/movements")
    public ApiResponse<List<StockMovement>> movements() {
        return ApiResponse.ok(service.movements());
    }

    @PostMapping("/levels/adjust")
    public ApiResponse<StockLevel> adjust(@Valid @RequestBody InventoryRequests.AdjustRequest request) {
        return ApiResponse.ok("Level corrected", service.adjust(request));
    }

    @PostMapping("/levels/use")
    public ApiResponse<StockLevel> useOrWaste(@Valid @RequestBody InventoryRequests.UseOrWasteRequest request) {
        return ApiResponse.ok("Recorded", service.useOrWaste(request));
    }

    @GetMapping("/requests")
    public ApiResponse<List<StockRequest>> openRequests(@RequestParam(required = false) String team) {
        return ApiResponse.ok(team == null ? service.openRequests() : service.requestsFrom(team));
    }

    @PostMapping("/requests")
    public ApiResponse<StockRequest> askTheStore(@Valid @RequestBody InventoryRequests.StockRequestDto request) {
        return ApiResponse.ok("Request sent to the store", service.askTheStore(request));
    }

    @PostMapping("/requests/{id}/issue")
    public ApiResponse<StockRequest> issue(@PathVariable String id, @Valid @RequestBody InventoryRequests.IssueRequest request) {
        return ApiResponse.ok("Issued", service.issue(id, request));
    }

    @PostMapping("/requests/{id}/decline")
    public ApiResponse<StockRequest> decline(@PathVariable String id) {
        return ApiResponse.ok("Declined", service.decline(id));
    }

    @GetMapping("/suppliers")
    public ApiResponse<List<Supplier>> suppliers() {
        return ApiResponse.ok(service.suppliers());
    }

    @PostMapping("/suppliers")
    public ApiResponse<Supplier> addSupplier(@Valid @RequestBody InventoryRequests.SupplierRequest request) {
        return ApiResponse.ok("Supplier added", service.saveSupplier(null, request));
    }

    @PutMapping("/suppliers/{id}")
    public ApiResponse<Supplier> updateSupplier(@PathVariable String id, @Valid @RequestBody InventoryRequests.SupplierRequest request) {
        return ApiResponse.ok("Saved", service.saveSupplier(id, request));
    }

    @GetMapping("/purchase-orders")
    public ApiResponse<List<PurchaseOrder>> purchaseOrders() {
        return ApiResponse.ok(service.purchaseOrders());
    }

    @GetMapping("/purchase-orders/{id}")
    public ApiResponse<PurchaseOrder> purchaseOrder(@PathVariable String id) {
        return ApiResponse.ok(service.purchaseOrder(id));
    }

    @PostMapping("/purchase-orders")
    public ApiResponse<PurchaseOrder> createOrder(@Valid @RequestBody InventoryRequests.PurchaseOrderRequest request) {
        PurchaseOrder po = service.createOrder(request);
        return ApiResponse.ok(po.getStatus() == PurchaseOrder.Status.PENDING_APPROVAL ? "Sent to the owner for approval" : "Order sent", po);
    }

    @GetMapping("/deliveries")
    public ApiResponse<List<Delivery>> deliveries() {
        return ApiResponse.ok(service.deliveries());
    }

    @PostMapping("/deliveries")
    public ApiResponse<Delivery> receive(@Valid @RequestBody InventoryRequests.DeliveryRequest request) {
        return ApiResponse.ok("Delivery received", service.receive(request));
    }

    @GetMapping("/counts")
    public ApiResponse<List<StockCount>> counts() {
        return ApiResponse.ok(service.counts());
    }

    @PostMapping("/counts")
    public ApiResponse<StockCount> startCount(@Valid @RequestBody InventoryRequests.CountRequest request) {
        return ApiResponse.ok("Count started", service.startCount(request));
    }

    @PutMapping("/counts/{id}")
    public ApiResponse<StockCount> submitCount(@PathVariable String id, @Valid @RequestBody InventoryRequests.CountLinesRequest request) {
        return ApiResponse.ok("Count submitted", service.submitCount(id, request));
    }

    @PostMapping("/counts/{id}/approve")
    public ApiResponse<StockCount> approveCount(@PathVariable String id) {
        return ApiResponse.ok("Count approved", service.approveCount(id));
    }
}
