package com.hospio.housekeeping.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Requests and views of guest laundry, the price list and linen batches (docs/laundry-maintenance.md). */
public final class LaundryDtos {

    private LaundryDtos() {
    }

    public record PriceRequest(String name, String category, BigDecimal price, BigDecimal expressPrice, Boolean active, Integer sortOrder) {
    }

    public record OrderLine(String priceId, BigDecimal quantity) {
    }

    public record OrderRequest(String roomId, List<OrderLine> lines, Boolean express, Instant promisedAt, String note, Boolean chargeToRoom) {
    }

    public record StatusRequest(String status) {
    }

    public record PaidRequest(String paymentId) {
    }

    public record ReasonRequest(String reason) {
    }

    public record ItemLine(String itemId, BigDecimal quantity) {
    }

    public record BatchRequest(List<ItemLine> lines, String note) {
    }

    public record ReturnLine(String itemId, BigDecimal returned, BigDecimal condemned) {
    }

    public record ReturnRequest(List<ReturnLine> lines, String reason) {
    }

    public record PriceView(String id, String name, String category, BigDecimal price, BigDecimal expressPrice, boolean active, int sortOrder) {
    }

    public record OrderLineView(String priceId, String name, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount) {
    }

    public record OrderView(String id, String number, String bookingId, String guestName, String roomId, String roomNumber, List<OrderLineView> lines,
                            boolean express, BigDecimal total, String status, String settlement, boolean chargedToRoom, boolean paid, Instant chargedAt,
                            Instant paidAt, BigDecimal paidAmount, String paymentId, String paymentMethod, Instant promisedAt, String note,
                            String pocketNote, String cancelReason, String receivedByName, Instant createdAt, Instant deliveredAt) {
    }

    public record BatchLineView(String itemId, String name, String unit, BigDecimal sent, BigDecimal received, BigDecimal returned,
                                BigDecimal condemned, BigDecimal lost, BigDecimal missing, BigDecimal outstanding, BigDecimal unitCost) {
    }

    public record BatchView(String id, String number, String status, List<BatchLineView> lines, String note, String sentByName, Instant createdAt,
                            Instant receivedAt, String receivedByName, Instant returnedAt, String closedReason, BigDecimal condemnedValue,
                            BigDecimal lostValue, BigDecimal sent, BigDecimal missing, BigDecimal outstanding) {
    }

    public record Summary(LocalDate from, LocalDate to, long orders, BigDecimal income, BigDecimal charged, BigDecimal paid, long open, long cancelled,
                          BigDecimal condemnedValue, BigDecimal lostValue) {
    }
}
