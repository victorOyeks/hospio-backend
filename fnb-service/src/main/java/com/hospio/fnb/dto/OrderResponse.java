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
                            Instant settledAt, String cancelReason, BigDecimal paidAmount, String notes, Instant createdAt, List<OrderLine> lines,
                            String settledByUser, String settledByName, BigDecimal serviceCharge, String ticketFrom,
                            BigDecimal roomAmount, BigDecimal balance, BigDecimal billDiscountPct, BigDecimal billDiscountAmount,
                            String discountReason, String discountByName, int splitParts) {
    public static OrderResponse of(Order o, List<OrderLine> lines) {
        return new OrderResponse(o.getId(), o.getNumber(), o.getOutletId(), o.getKind(), o.getTableId(), o.getTableNo(), o.getRoomId(),
                o.getRoomNumber(), o.getBookingId(), o.getGuestName(), o.getStatus(), o.getSubtotal(), o.getTax(), o.getDiscount(), o.getTotal(),
                o.isChargedToRoom(), o.isPaid(), o.getPaymentId(), o.getOpenedBy(), o.getOpenedByName(), o.getSentAt(), o.getClosedAt(),
                o.getClosedBy(), o.getClosedByName(), o.getSettledBy(), o.getSettledAt(), o.getCancelReason(), o.getPaidAmount(), o.getNotes(),
                o.getCreatedAt(), lines, o.getSettledByUser(), o.getSettledByName(), o.getServiceCharge(), null,
                o.getRoomAmount(), o.isSettled() || o.getStatus() == Order.Status.CANCELLED ? BigDecimal.ZERO : o.balance(), o.getBillDiscountPct(),
                o.getBillDiscountAmount(), o.getDiscountReason(), o.getDiscountByName(), o.getSplitParts());
    }

    /**
     * Another outlet's order as a station's ticket (docs: outlets serving each other): only the lines this station makes, and the
     * name of the outlet that sold it. Money fields stay the order's (the bill is the selling outlet's).
     */
    public static OrderResponse ticket(Order o, List<OrderLine> stationLines, String soldAt) {
        OrderResponse r = of(o, stationLines);
        return new OrderResponse(r.id(), r.number(), r.outletId(), r.kind(), r.tableId(), r.tableNo(), r.roomId(), r.roomNumber(), r.bookingId(),
                r.guestName(), r.status(), r.subtotal(), r.tax(), r.discount(), r.total(), r.chargedToRoom(), r.paid(), r.paymentId(), r.openedBy(),
                r.openedByName(), r.sentAt(), r.closedAt(), r.closedBy(), r.closedByName(), r.settledBy(), r.settledAt(), r.cancelReason(), r.paidAmount(),
                r.notes(), r.createdAt(), stationLines, r.settledByUser(), r.settledByName(), r.serviceCharge(), soldAt,
                r.roomAmount(), r.balance(), r.billDiscountPct(), r.billDiscountAmount(), r.discountReason(), r.discountByName(), r.splitParts());
    }
}
