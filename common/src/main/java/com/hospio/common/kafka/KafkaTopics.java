package com.hospio.common.kafka;

import java.util.List;

/** Every Kafka topic in Hospio, in one place so a producer and its consumers never drift apart on spelling. */
public final class KafkaTopics {

    private KafkaTopics() {
    }

    public static final String PREFIX = "hospio-";

    // Identity & Access
    public static final String USER_CREATED = PREFIX + "user-created";
    public static final String USER_SIGNED_IN = PREFIX + "user-signed-in";
    public static final String ROLE_GRANTED = PREFIX + "role-granted";
    public static final String ACCESS_CHANGED = PREFIX + "access-changed";

    // Tenant & Onboarding
    public static final String BUSINESS_CREATED = PREFIX + "business-created";
    public static final String HOTEL_CREATED = PREFIX + "hotel-created";
    public static final String BUSINESS_STAGE_CHANGED = PREFIX + "business-stage-changed";
    public static final String HANDOVER_SENT = PREFIX + "handover-sent";
    public static final String HANDOVER_ACCEPTED = PREFIX + "handover-accepted";

    // Messages to people (email, SMS, WhatsApp); messaging-service subscribes on its own. Kept out of ALL so the
    // platform activity log never stores a temporary password or an invite link.
    public static final String DELIVERY_REQUESTED = PREFIX + "delivery-requested";

    // Hotel configuration
    public static final String SETTING_CHANGED = PREFIX + "setting-changed";

    // Property & Rooms
    public static final String ROOM_OUT_OF_ORDER = PREFIX + "room-out-of-order";
    public static final String ROOM_BACK_IN_SERVICE = PREFIX + "room-back-in-service";

    // Reservations & Guests
    public static final String BOOKING_CREATED = PREFIX + "booking-created";
    public static final String BOOKING_CHANGED = PREFIX + "booking-changed";
    public static final String BOOKING_CANCELLED = PREFIX + "booking-cancelled";
    public static final String GUEST_CHECKED_IN = PREFIX + "guest-checked-in";
    public static final String GUEST_CHECKED_OUT = PREFIX + "guest-checked-out";

    // Folio & Billing
    public static final String CHARGE_POSTED = PREFIX + "charge-posted";
    public static final String FOLIO_OPENED = PREFIX + "folio-opened";
    public static final String FOLIO_CLOSED = PREFIX + "folio-closed";
    public static final String DISCOUNT_APPLIED = PREFIX + "discount-applied";
    public static final String INVOICE_ISSUED = PREFIX + "invoice-issued";

    // Payments
    public static final String PAYMENT_REQUESTED = PREFIX + "payment-requested";
    public static final String PAYMENT_APPROVED = PREFIX + "payment-approved";
    public static final String PAYMENT_DECLINED = PREFIX + "payment-declined";
    public static final String REFUND_APPROVED = PREFIX + "refund-approved";
    public static final String POS_OFFLINE = PREFIX + "pos-offline";

    // Food & beverage
    public static final String ORDER_CREATED = PREFIX + "order-created";
    public static final String ITEM_SOLD_OUT = PREFIX + "item-sold-out";

    // Housekeeping & Laundry
    public static final String ROOM_CLEANED = PREFIX + "room-cleaned";
    public static final String PROBLEM_REPORTED = PREFIX + "problem-reported";
    public static final String LINEN_REQUESTED = PREFIX + "linen-requested";

    // Maintenance & Tasks
    public static final String REPAIR_OPENED = PREFIX + "repair-opened";
    public static final String REPAIR_DONE = PREFIX + "repair-done";
    public static final String PARTS_REQUESTED = PREFIX + "parts-requested";

    // Inventory & Procurement
    public static final String STOCK_LOW = PREFIX + "stock-low";
    public static final String STOCK_REQUESTED = PREFIX + "stock-requested";
    public static final String STOCK_ISSUED = PREFIX + "stock-issued";
    public static final String DELIVERY_RECEIVED = PREFIX + "delivery-received";

    // Finance & Workforce
    public static final String EXPENSE_RECORDED = PREFIX + "expense-recorded";
    public static final String DAY_CLOSED = PREFIX + "day-closed";
    public static final String CASH_BANKED = PREFIX + "cash-banked";
    public static final String SHIFT_CHANGE_REQUESTED = PREFIX + "shift-change-requested";
    public static final String LEAVE_REQUESTED = PREFIX + "leave-requested";
    public static final String LEAVE_DECIDED = PREFIX + "leave-decided";
    public static final String SHIFT_REQUEST_DECIDED = PREFIX + "shift-request-decided";
    public static final String TILL_CLOSED = PREFIX + "till-closed";

    // Messaging & Notifications
    public static final String MESSAGE_SENT = PREFIX + "message-sent";

    // Platform & Insights
    public static final String APPROVAL_REQUESTED = PREFIX + "approval-requested";
    public static final String APPROVAL_DECIDED = PREFIX + "approval-decided";
    public static final String APPROVAL_ANSWERED = PREFIX + "approval-answered";
    public static final String CHANGE_BY_PLATFORM = PREFIX + "change-by-platform";

    /** Every topic; platform-service's activity log and the live Departments board subscribe to all of them. */
    public static final List<String> ALL = List.of(
            USER_CREATED, USER_SIGNED_IN, ROLE_GRANTED, ACCESS_CHANGED,
            BUSINESS_CREATED, HOTEL_CREATED, BUSINESS_STAGE_CHANGED, HANDOVER_SENT, HANDOVER_ACCEPTED,
            SETTING_CHANGED,
            ROOM_OUT_OF_ORDER, ROOM_BACK_IN_SERVICE,
            BOOKING_CREATED, BOOKING_CHANGED, BOOKING_CANCELLED, GUEST_CHECKED_IN, GUEST_CHECKED_OUT,
            CHARGE_POSTED, FOLIO_OPENED, FOLIO_CLOSED, DISCOUNT_APPLIED, INVOICE_ISSUED,
            PAYMENT_REQUESTED, PAYMENT_APPROVED, PAYMENT_DECLINED, REFUND_APPROVED, POS_OFFLINE,
            ORDER_CREATED, ITEM_SOLD_OUT,
            ROOM_CLEANED, PROBLEM_REPORTED, LINEN_REQUESTED,
            REPAIR_OPENED, REPAIR_DONE, PARTS_REQUESTED,
            STOCK_LOW, STOCK_REQUESTED, STOCK_ISSUED, DELIVERY_RECEIVED,
            EXPENSE_RECORDED, DAY_CLOSED, CASH_BANKED, SHIFT_CHANGE_REQUESTED, LEAVE_REQUESTED,
            LEAVE_DECIDED, SHIFT_REQUEST_DECIDED, TILL_CLOSED,
            MESSAGE_SENT,
            APPROVAL_REQUESTED, APPROVAL_DECIDED, APPROVAL_ANSWERED, CHANGE_BY_PLATFORM);

    public static final String[] ALL_ARRAY = ALL.toArray(new String[0]);
}
