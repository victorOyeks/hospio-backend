package com.hospio.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class InventoryRequests {

    private InventoryRequests() {
    }

    public record StoreRequest(@NotBlank String name, String kind, Boolean active) {
    }

    public record ItemRequest(@NotBlank String name, String category, String unit, BigDecimal minLevel, BigDecimal unitCost,
                              String supplierId, Boolean active) {
    }

    public record Line(@NotBlank String itemId, String name, @NotNull BigDecimal quantity, BigDecimal unitCost) {
    }

    public record AdjustRequest(@NotBlank String itemId, @NotBlank String storeId, @NotNull BigDecimal quantity, @NotBlank String reason) {
    }

    public record UseOrWasteRequest(@NotBlank String itemId, @NotBlank String storeId, @NotNull BigDecimal quantity, boolean waste, String note) {
    }

    public record StockRequestDto(@NotBlank String fromTeam, String storeId, @NotNull List<Line> lines, String note) {
    }

    public record IssueRequest(@NotBlank String storeId, List<Line> lines) {
    }

    public record SupplierRequest(@NotBlank String name, String contactName, String phone, String email, String paymentTerms, Boolean active) {
    }

    public record PurchaseOrderRequest(@NotBlank String supplierId, String storeId, @NotNull List<Line> lines, LocalDate expectedOn) {
    }

    public record DeliveryRequest(String purchaseOrderId, String supplierName, @NotBlank String storeId, @NotNull List<Line> lines, String note) {
    }

    public record CountRequest(@NotBlank String storeId) {
    }

    public record CountLinesRequest(@NotNull List<Line> lines) {
    }
}
