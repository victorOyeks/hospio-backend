package com.hospio.inventory.dto;

import com.hospio.common.delivery.Delivery;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** What the inventory screens read: rows with names resolved and lines as arrays (docs/inventory-stores.md). */
public final class InventoryViews {

    private InventoryViews() {
    }

    public record StoreView(String id, String name, String kind, boolean active, int items, int lowItems, BigDecimal value) {
    }

    public record ItemView(String id, String name, String category, String unit, BigDecimal minLevel, BigDecimal parLevel, BigDecimal unitCost,
                           String supplierId, String supplierName, boolean active, BigDecimal onHand) {
    }

    public record LevelView(String storeId, String storeName, String storeKind, BigDecimal quantity, BigDecimal value) {
    }

    public record ItemDetail(ItemView item, List<LevelView> levels, List<MovementView> movements) {
    }

    public record MovementView(String id, Instant at, String itemId, String itemName, String unit, String storeId, String storeName, String kind,
                               BigDecimal quantity, BigDecimal unitCost, BigDecimal value, String reference, String note, String team,
                               String byName) {
    }

    /** A request line: what was asked and how much has been issued so far. */
    public record RequestLine(String itemId, String name, String unit, BigDecimal quantity, BigDecimal issued) {
    }

    public record RequestView(String id, String fromTeam, String storeId, String storeName, List<RequestLine> lines, String note, String status,
                              String requestedBy, String requestedByName, String issuedByName, Instant issuedAt, String reason, Instant createdAt) {
    }

    public record TransferView(String reference, Instant at, String fromStoreId, String fromStore, String toStoreId, String toStore, String team,
                               List<RequestLine> lines, BigDecimal value, String byName, String note) {
    }

    public record SupplierView(String id, String name, String contactName, String phone, String email, String paymentTerms, String address,
                               String notes, boolean active, int items, List<String> itemIds, int openOrders, Instant lastOrderAt,
                               BigDecimal spent) {
    }

    /** An order line: ordered, received so far (good) and damaged so far. */
    public record OrderLine(String itemId, String name, String unit, BigDecimal quantity, BigDecimal unitCost, BigDecimal received,
                            BigDecimal damaged) {
    }

    public record OrderView(String id, String number, String supplierId, String supplierName, String storeId, String storeName,
                            List<OrderLine> lines, BigDecimal total, BigDecimal receivedValue, String status, LocalDate expectedOn, String note,
                            String createdByName, Instant createdAt, Instant sentAt, String sentTo, String approverLevel,
                            String decisionNote, String cancelReason, List<DeliveryView> deliveries, List<Delivery> sent) {
    }

    public record DeliveryLine(String itemId, String name, String unit, BigDecimal quantity, BigDecimal unitCost, BigDecimal damaged,
                               BigDecimal ordered, BigDecimal shortQty, BigDecimal overQty, String note) {
    }

    public record DeliveryView(String id, String purchaseOrderId, String poNumber, String supplierName, String storeId, String storeName,
                               List<DeliveryLine> lines, BigDecimal total, String invoiceNumber, String note, String summary,
                               String receivedByName, Instant receivedAt) {
    }

    public record CountLine(String itemId, String name, String unit, BigDecimal expected, BigDecimal quantity, BigDecimal variance,
                            BigDecimal unitCost, BigDecimal value) {
    }

    public record CountView(String id, String storeId, String storeName, String status, List<CountLine> lines, BigDecimal varianceValue,
                            String note, String startedByName, String countedByName, String approvedByName, String cancelReason,
                            Instant startedAt, Instant closedAt) {
    }

    public record LowItem(String itemId, String name, String unit, String storeId, String storeName, BigDecimal quantity, BigDecimal minLevel,
                          BigDecimal parLevel, BigDecimal suggest, BigDecimal unitCost, String supplierId, String supplierName) {
    }

    public record ValueReport(BigDecimal total, List<StoreView> stores, List<LowItem> low, BigDecimal onOrder) {
    }

    public record StoreUsage(String storeId, String name, String kind, BigDecimal received, BigDecimal transferredIn, BigDecimal issuedOut,
                             BigDecimal used, BigDecimal wasted, BigDecimal counted) {
    }

    public record TeamUsage(String team, BigDecimal issued) {
    }

    public record UsageTotals(BigDecimal received, BigDecimal issued, BigDecimal used, BigDecimal wasted, BigDecimal counted) {
    }

    public record UsageReport(LocalDate from, LocalDate to, List<StoreUsage> stores, List<TeamUsage> teams, UsageTotals totals) {
    }
}
