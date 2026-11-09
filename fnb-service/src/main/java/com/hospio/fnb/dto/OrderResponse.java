package com.hospio.fnb.dto;

import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.OrderLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(String id, String number, String outletId, Order.Kind kind, String tableId, String tableNo, String roomId,
                            String roomNumber, String bookingId, String guestName, Order.Status status, BigDecimal subtotal, BigDecimal tax,
                            BigDecimal discount, BigDecimal total, boolean chargedToRoom, boolean paid, String paymentId, String openedBy,
                            String openedByName, Instant sentAt, Instant closedAt, String closedBy, String closedByName, String settledBy,
                            Instant settledAt, String cancelReason, BigDecimal paidAmount, String notes, Instant createdAt, List<OrderLine> lines) {
    public static OrderResponse of(Order o, List<OrderLine> lines) {
        return new OrderResponse(o.getId(), o.getNumber(), o.getOutletId(), o.getKind(), o.getTableId(), o.getTableNo(), o.getRoomId(),
                o.getRoomNumber(), o.getBookingId(), o.getGuestName(), o.getStatus(), o.getSubtotal(), o.getTax(), o.getDiscount(), o.getTotal(),
                o.isChargedToRoom(), o.isPaid(), o.getPaymentId(), o.getOpenedBy(), o.getOpenedByName(), o.getSentAt(), o.getClosedAt(),
                o.getClosedBy(), o.getClosedByName(), o.getSettledBy(), o.getSettledAt(), o.getCancelReason(), o.getPaidAmount(), o.getNotes(),
                o.getCreatedAt(), lines);
    }
}
