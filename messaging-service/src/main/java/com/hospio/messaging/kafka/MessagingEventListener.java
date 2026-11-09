package com.hospio.messaging.kafka;

import com.hospio.common.enums.NotificationChannel;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalDecidedEvent;
import com.hospio.common.kafka.events.ApprovalRequestedEvent;
import com.hospio.common.kafka.events.BookingCreatedEvent;
import com.hospio.common.kafka.events.DayClosedEvent;
import com.hospio.common.kafka.events.GuestCheckedOutEvent;
import com.hospio.common.kafka.events.HospioEvent;
import com.hospio.common.kafka.events.ItemSoldOutEvent;
import com.hospio.common.kafka.events.LeaveDecidedEvent;
import com.hospio.common.kafka.events.LeaveRequestedEvent;
import com.hospio.common.kafka.events.LinenRequestedEvent;
import com.hospio.common.kafka.events.OrderCreatedEvent;
import com.hospio.common.kafka.events.PartsRequestedEvent;
import com.hospio.common.kafka.events.PaymentApprovedEvent;
import com.hospio.common.kafka.events.PosOfflineEvent;
import com.hospio.common.kafka.events.ProblemReportedEvent;
import com.hospio.common.kafka.events.RepairOpenedEvent;
import com.hospio.common.kafka.events.RoomOutOfOrderEvent;
import com.hospio.common.kafka.events.ShiftChangeRequestedEvent;
import com.hospio.common.kafka.events.ShiftRequestDecidedEvent;
import com.hospio.common.kafka.events.StockIssuedEvent;
import com.hospio.common.kafka.events.StockLowEvent;
import com.hospio.common.kafka.events.StockRequestedEvent;
import com.hospio.common.kafka.events.TillClosedEvent;
import com.hospio.common.tenancy.TenantBinder;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.messaging.service.NotificationService;
import com.hospio.messaging.service.OutboundService;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns events into what people see: receipts and stay summaries to guests, and in-app
 * notifications to the roles that need to act (handoff card 12: StockLow · RoomOutOfOrder ·
 * POSOffline · ApprovalRequested/Decided → Notifications).
 */
@Component
@RequiredArgsConstructor
public class MessagingEventListener {

    private final NotificationService notifications;
    private final OutboundService outbound;
    private final TenantBinder tenant;

    @Transactional
    @KafkaListener(topics = KafkaTopics.PAYMENT_APPROVED)
    public void onPaymentApproved(PaymentApprovedEvent e) {
        bound(e, () -> {
            if ("NEVER".equalsIgnoreCase(e.getReceiptPreference())) {
                return null;
            }
            String body = outbound.render(e.getHotelId(), "RECEIPT", NotificationChannel.WHATSAPP,
                    "Thank you {guest}. We received {amount} by {method}. Ref {ref}.",
                    Map.of("guest", nz(e.getGuestName()), "amount", String.valueOf(e.getAmount()), "method", nz(e.getMethod()).toLowerCase(), "ref", nz(e.getProviderRef())));
            if (e.getGuestPhone() != null) {
                outbound.queue(NotificationChannel.WHATSAPP, e.getGuestPhone(), "Receipt", body, "RECEIPT", "RECEIPT-W-" + e.getPaymentId());
            } else if (e.getGuestEmail() != null) {
                outbound.queue(NotificationChannel.EMAIL, e.getGuestEmail(), "Your receipt", body, "RECEIPT", "RECEIPT-E-" + e.getPaymentId());
            }
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.GUEST_CHECKED_OUT)
    public void onCheckedOut(GuestCheckedOutEvent e) {
        bound(e, () -> {
            String body = outbound.render(e.getHotelId(), "STAY_SUMMARY", NotificationChannel.EMAIL,
                    "Thank you for staying with us, {guest}. Your stay in room {room} has ended. Balance: {balance}.",
                    Map.of("guest", nz(e.getGuestName()), "room", nz(e.getRoomNumber()), "balance", String.valueOf(e.getFolioBalance())));
            if (e.getSummaryChannels() != null) {
                for (String ch : e.getSummaryChannels()) {
                    if ("EMAIL".equalsIgnoreCase(ch) && e.getGuestEmail() != null) {
                        outbound.queue(NotificationChannel.EMAIL, e.getGuestEmail(), "Your stay summary", body, "STAY_SUMMARY", "SUMMARY-E-" + e.getBookingId());
                    }
                    if ("WHATSAPP".equalsIgnoreCase(ch) && e.getGuestPhone() != null) {
                        outbound.queue(NotificationChannel.WHATSAPP, e.getGuestPhone(), "Stay summary", body, "STAY_SUMMARY", "SUMMARY-W-" + e.getBookingId());
                    }
                }
            }
            if (e.getFolioBalance() != null && e.getFolioBalance().signum() > 0) {
                notifications.notifyRoles(e.getEventId(), "PENDING_PAYMENT", e.getGuestName() + " checked out with " + e.getFolioBalance() + " pending",
                        "Room " + e.getRoomNumber(), "/payments", "FRONT_OFFICE", "MANAGER");
            }
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.BOOKING_CREATED)
    public void onBookingCreated(BookingCreatedEvent e) {
        bound(e, () -> {
            notifications.notifyRoles(e.getEventId(), "BOOKING", "New booking: " + e.getGuestName(),
                    e.getCheckInDate() + " to " + e.getCheckOutDate() + (e.getRoomNumber() == null ? "" : " · room " + e.getRoomNumber()), "/bookings/" + e.getBookingId(), "FRONT_OFFICE");
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.STOCK_LOW)
    public void onStockLow(StockLowEvent e) {
        bound(e, () -> {
            notifications.notifyRoles(e.getEventId(), "STOCK_LOW", e.getItemName() + " is low", e.getQuantity() + " " + e.getUnit() + " left in " + nz(e.getStoreName()), "/inventory", "INVENTORY", "MANAGER");
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.ROOM_OUT_OF_ORDER)
    public void onRoomOutOfOrder(RoomOutOfOrderEvent e) {
        bound(e, () -> {
            notifications.notifyRoles(e.getEventId(), "ROOM_OUT_OF_ORDER", "Room " + e.getRoomNumber() + " out of order", e.getReason(), "/rooms", "FRONT_OFFICE", "HOUSEKEEPING", "MANAGER");
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.POS_OFFLINE)
    public void onPosOffline(PosOfflineEvent e) {
        bound(e, () -> {
            notifications.notifyRoles(e.getEventId(), "POS_OFFLINE", "POS " + e.getTerminalName() + " is offline", "Cash only until it is back", "/payments",
                    e.getRole() == null ? "MANAGER" : e.getRole(), "MANAGER");
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.APPROVAL_REQUESTED)
    public void onApprovalRequested(ApprovalRequestedEvent e) {
        bound(e, () -> {
            notifications.notifyRoles(e.getEventId(), "APPROVAL", "Approval needed: " + e.getKind().toLowerCase().replace('_', ' '), e.getDescription(), "/approvals/" + e.getApprovalId(),
                    "OWNER".equals(e.getApproverLevel()) ? "OWNER" : "MANAGER");
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.APPROVAL_DECIDED)
    public void onApprovalDecided(ApprovalDecidedEvent e) {
        bound(e, () -> {
            notifications.notifyRoles(e.getEventId(), "APPROVAL_DECIDED", e.getDecidedByName() + " " + e.getDecision().toLowerCase() + " your " + e.getKind().toLowerCase().replace('_', ' '),
                    nz(e.getNote()), "/approvals/" + e.getApprovalId(), e.getSourceModule());
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = {KafkaTopics.ORDER_CREATED})
    public void onOrderCreated(OrderCreatedEvent e) {
        bound(e, () -> {
            if (e.getRoomId() != null) {
                notifications.notifyRoles(e.getEventId(), "ROOM_SERVICE", "New room-service order", e.getOutletName() + " · " + e.getTotal(), "/orders/" + e.getOrderId(), "BAR".equals(e.getOutletKind()) ? "BAR" : "KITCHEN");
            }
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.ITEM_SOLD_OUT)
    public void onSoldOut(ItemSoldOutEvent e) {
        bound(e, () -> {
            notifications.notifyRoles(e.getEventId(), "SOLD_OUT", e.getItemName() + " turned off", "at " + e.getOutletName(), "/menu", "FRONT_OFFICE", "MANAGER");
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = {KafkaTopics.REPAIR_OPENED})
    public void onRepairOpened(RepairOpenedEvent e) {
        bound(e, () -> {
            notifications.notifyRoles(e.getEventId(), "REPAIR", "New repair" + (e.getRoomNumber() == null ? "" : " · " + e.getRoomNumber()), e.getTitle(), "/repairs/" + e.getRepairId(), "MAINTENANCE");
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.PROBLEM_REPORTED)
    public void onProblem(ProblemReportedEvent e) {
        bound(e, () -> {
            if (e.getRepairId() == null) {
                notifications.notifyRoles(e.getEventId(), "PROBLEM", "Problem in room " + e.getRoomNumber(), e.getDescription(), "/repairs", "MAINTENANCE", "MANAGER");
            }
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.LINEN_REQUESTED)
    public void onLinen(LinenRequestedEvent e) {
        bound(e, () -> {
            notifications.notifyRoles(e.getEventId(), "LINEN", "Linen request · room " + e.getRoomNumber(), e.getItems(), "/laundry", "LAUNDRY");
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.STOCK_REQUESTED)
    public void onStockRequested(StockRequestedEvent e) {
        bound(e, () -> {
            notifications.notifyRoles(e.getEventId(), "STOCK_REQUEST", "New request · " + e.getFromTeam(), e.getLines(), "/inventory/requests", "INVENTORY");
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.STOCK_ISSUED)
    public void onStockIssued(StockIssuedEvent e) {
        bound(e, () -> {
            notifications.notifyRoles(e.getEventId(), "STOCK_ISSUED", "Supplies on the way", e.getItemCount() + " items issued", "/inventory/requests", e.getToTeam());
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.PARTS_REQUESTED)
    public void onParts(PartsRequestedEvent e) {
        bound(e, () -> {
            notifications.notifyRoles(e.getEventId(), "PARTS", "Parts requested", e.getQuantity() + " x " + e.getPartName(), "/repairs/" + e.getRepairId(), "MANAGER", "INVENTORY");
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = {KafkaTopics.SHIFT_CHANGE_REQUESTED})
    public void onShiftChange(ShiftChangeRequestedEvent e) {
        bound(e, () -> {
            notifications.notifyRoles(e.getEventId(), "SHIFT", e.getStaffName() + " asked to " + e.getKind() + " a shift", "on " + e.getShiftDate(), "/staff/rota", "MANAGER");
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.LEAVE_REQUESTED)
    public void onLeave(LeaveRequestedEvent e) {
        bound(e, () -> {
            notifications.notifyRoles(e.getEventId(), "LEAVE", e.getStaffName() + " asked for leave", e.getFromDate() + " to " + e.getToDate(), "/staff/leave", "MANAGER");
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.LEAVE_DECIDED)
    public void onLeaveDecided(LeaveDecidedEvent e) {
        bound(e, () -> {
            notifications.notify(e.getEventId(), e.getStaffUserId(), null, "LEAVE_DECIDED",
                    "Your leave was " + nz(e.getDecision()).toLowerCase().replace('_', ' '),
                    e.getFromDate() + " to " + e.getToDate() + (e.getNote() == null || e.getNote().isBlank() ? "" : " · " + e.getNote()), "/shifts");
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.SHIFT_REQUEST_DECIDED)
    public void onShiftRequestDecided(ShiftRequestDecidedEvent e) {
        bound(e, () -> {
            notifications.notify(e.getEventId(), e.getStaffUserId(), null, "SHIFT_DECIDED",
                    "Your " + nz(e.getKind()).toLowerCase() + " request was " + nz(e.getDecision()).toLowerCase().replace('_', ' '),
                    "Shift on " + e.getShiftDate(), "/shifts");
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.TILL_CLOSED)
    public void onTillClosed(TillClosedEvent e) {
        bound(e, () -> {
            if (e.getVariance() != null && e.getVariance().signum() != 0) {
                String role = nz(e.getRole()).toLowerCase().replace('_', ' ');
                notifications.notifyRoles(e.getEventId(), "TILL_SHORT",
                        (e.getVariance().signum() < 0 ? "Till short " : "Till over ") + e.getVariance().abs().toPlainString() + " · " + role,
                        "Expected " + e.getExpectedCash().toPlainString() + " · counted " + e.getCountedCash().toPlainString(), "/financials", "MANAGER");
            }
            return null;
        });
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.DAY_CLOSED)
    public void onDayClosed(DayClosedEvent e) {
        bound(e, () -> {
            notifications.notifyRoles(e.getEventId(), "DAY_CLOSED", "Day closed · " + e.getBusinessDate(),
                    "Revenue " + (e.getRevenue() == null ? "0" : e.getRevenue().toPlainString()) + " · closed by " + nz(e.getClosedBy()), "/night-audit", "OWNER", "MANAGER");
            return null;
        });
    }

    private void bound(HospioEvent e, Supplier<Void> work) {
        tenant.bind(e);
        try {
            work.get();
        } finally {
            TenantContext.clear();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
