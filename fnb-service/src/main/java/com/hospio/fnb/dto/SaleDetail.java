package com.hospio.fnb.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One sale for the detail panel: the row as the list shows it, every line (cancelled ones with why and by whom), the money, each
 * payment taken, the timeline, and what the caller may do now ({@code can}).
 */
public record SaleDetail(SalesHistory.Row sale, String roomId, String bookingId, String tableId, String notes, BigDecimal subtotal,
                         BigDecimal discount, BigDecimal tax, BigDecimal total, BigDecimal paidAmount, BigDecimal balance, List<Line> lines,
                         List<Payment> payments, Timeline timeline, Can can) {

    /** {@code choices} are what the guest chose with what each adds per portion today (from the menu item's current choices). */
    public record Line(String id, String name, int quantity, BigDecimal unitPrice, String options, List<Choice> choices, String notes,
                       BigDecimal discount, BigDecimal amount, String status, String cancelReason, String cancelledByName) {
    }

    public record Choice(String name, BigDecimal extra) {
    }

    /** One approved payment: {@code takenByName} may be null for payments approved by the terminal on its own (payment-service has it). */
    public record Payment(String paymentId, String method, BigDecimal amount, Instant at, String reference, String takenByName) {
    }

    public record Timeline(Instant openedAt, String openedByName, Instant sentAt, Instant settledAt, String settledBy, String settledByName,
                           Instant closedAt, String closedByName) {
    }

    /** {@code note} says why something is not offered (someone else's sale, needs a manager, part paid). */
    public record Can(boolean pay, boolean chargeToRoom, boolean cancel, String note) {
    }
}
