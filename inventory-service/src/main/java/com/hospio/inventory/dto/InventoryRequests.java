package com.hospio.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class InventoryRequests {

    private InventoryRequests() {
    }

    public record StoreRequest(@NotBlank(message = "Give the store a name") String name, String kind, Boolean active) {
    }

    public record ItemRequest(@NotBlank(message = "Give the item a name") String name, String category, String unit, BigDecimal minLevel,
                              BigDecimal parLevel, BigDecimal unitCost, String supplierId, Boolean active, String storeId,
                              BigDecimal openingQuantity) {
    }

    /** A line on a request, issue, transfer, order, delivery or count. Which fields matter depends on where it is sent. */
    public record Line(@NotBlank String itemId, String name, @NotNull(message = "Enter a quantity on every line") BigDecimal quantity,
                       BigDecimal unitCost, BigDecimal damaged, String note) {

        public Line(String itemId, String name, BigDecimal quantity, BigDecimal unitCost) {
            this(itemId, name, quantity, unitCost, null, null);
        }
    }

    public record AdjustRequest(@NotBlank String itemId, @NotBlank String storeId, @NotNull BigDecimal quantity,
                                @NotBlank(message = "Give a reason for the correction") String reason) {
    }

    public record UseOrWasteRequest(@NotBlank String itemId, @NotBlank String storeId, @NotNull BigDecimal quantity, boolean waste, String note) {
    }

    public record StockRequestDto(@NotBlank(message = "Say which team the request is for") String fromTeam, String storeId,
                                  @NotNull(message = "Add at least one item") List<Line> lines, String note) {
    }

    public record IssueRequest(@NotBlank(message = "Pick the store to issue from") String storeId, List<Line> lines) {
    }

    public record ReasonRequest(String reason) {
    }

    public record TransferRequest(@NotBlank(message = "Pick the store to move from") String fromStoreId,
                                  @NotBlank(message = "Pick the store to move to") String toStoreId,
                                  @NotNull(message = "Add at least one item") List<Line> lines, String note) {
    }

    public record SupplierRequest(@NotBlank(message = "Give the supplier a name") String name, String contactName, String phone, String email,
                                  String paymentTerms, String address, String notes, Boolean active, List<String> itemIds) {
    }

    public record PurchaseOrderRequest(@NotBlank(message = "Pick a supplier") String supplierId, String storeId,
                                       @NotNull(message = "Add at least one item") List<Line> lines, LocalDate expectedOn, String note,
                                       Boolean submit) {
    }

    public record DeliveryRequest(String purchaseOrderId, String supplierName, String storeId, String invoiceNumber,
                                  @NotNull(message = "Add what arrived") List<Line> lines, String note) {
    }

    public record CountRequest(@NotBlank(message = "Pick the store to count") String storeId) {
    }

    public record CountLinesRequest(@NotNull(message = "Enter what you counted") List<Line> lines, String note) {
    }
}
