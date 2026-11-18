package com.hospio.fnb.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A sale's bill for the pay screen (docs/plan-venue-type.md, phase 4): the order, what is still to pay, its split and parts, the
 * discounts and requests on it, tips taken, and what the caller may do now.
 */
public record Bill(OrderResponse order, BigDecimal balance, Split split, List<Payment> payments, List<Request> requests, List<Tip> tips,
                   BigDecimal tipsTotal, Can can) {

    /** {@code remaining}: what the parts still owe together; {@code canUndo}: no part has been paid yet. */
    public record Split(String id, String mode, BigDecimal amount, String createdByName, Instant createdAt, List<Part> parts, BigDecimal paid,
                        BigDecimal remaining, boolean canUndo) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Part(String id, int position, String label, BigDecimal amount, BigDecimal tax, BigDecimal serviceCharge, BigDecimal paidAmount,
                       BigDecimal due, String status, String method, String roomNumber, List<PartItem> items, Instant paidAt, String paidByName) {
    }

    /** A line on a part of a split by item; {@code share} reads "1/2" when the line is shared, "2 of 3" for some of its units. */
    public record PartItem(String lineId, String name, String share, BigDecimal amount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Payment(String paymentId, String method, BigDecimal amount, Instant at, String reference, String takenByName, String partId,
                          BigDecimal tip) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(String id, String approvalId, String kind, String orderId, String orderNumber, String outletName, String lineId,
                          String summary, BigDecimal pct, BigDecimal amount, BigDecimal worth, String reason, String status, String requestedBy,
                          String requestedByName, Instant requestedAt, String decidedByName, String decisionNote, Instant decidedAt) {
    }

    public record Tip(String paymentId, BigDecimal amount, String method, String staffName, Instant at) {
    }

    /**
     * {@code discountLimitPct}: what the caller gives without asking (100 for a manager, 0 when every discount of theirs is a
     * request); {@code voidAsks}: a sent item or the whole sale is a request for them, not a button. {@code note}: why money can't be
     * taken by them, when it can't.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Can(boolean pay, boolean split, boolean undoSplit, boolean chargeToRoom, boolean discount, BigDecimal discountLimitPct,
                      boolean voidAsks, boolean tips, boolean decide, String note) {
    }

    /** {@code mode}: EQUAL ({@code people}), AMOUNT ({@code amounts}: the rest of the bill is the last part) or ITEM ({@code parts}). */
    public record SplitRequest(String mode, Integer people, List<BigDecimal> amounts, List<PartRequest> parts) {
    }

    /** One guest's part of a split by item: the lines on it, each with how many shares of the line this guest takes (1 when left out). */
    public record PartRequest(String label, List<PartLine> lines) {
    }

    public record PartLine(String lineId, Integer shares) {
    }

    /**
     * A discount by hand: on one line ({@code lineId}) or the whole bill; {@code pct} or {@code amount}, or {@code complimentary}
     * (free). {@code reason} is one of the location's reasons (Hotel setup → Lists).
     */
    public record DiscountRequest(String lineId, BigDecimal pct, BigDecimal amount, Boolean complimentary, String reason) {
    }

    /** What a discount or void call did: changed the bill now ({@code request} null), or sent it to a manager. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Outcome(Bill bill, Request request, String message) {
    }

    public record VoidRequest(String lineId, String reason) {
    }
}
