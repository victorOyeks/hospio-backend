package com.hospio.fnb.dto;

import com.hospio.fnb.entity.Order;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One page of the sales history with the money over every matching sale. {@code totals}: {@code sales} counts the sales not cancelled,
 * {@code sold} is their total, split into {@code paid} (taken at the outlet), {@code onRooms} and {@code unpaid}; {@code cancelled}
 * counts cancelled sales. {@code outlets} and {@code attendants} break the same money down (each ignores its own filter).
 * {@code earlierUnpaid} is what still owes money from before {@code from}. {@code viewer} says what the caller may do.
 */
public record SalesHistory(LocalDate from, LocalDate to, int page, int size, long matching, List<Row> rows, Totals totals,
                           List<OutletTotals> outlets, List<AttendantTotals> attendants, EarlierUnpaid earlierUnpaid, Viewer viewer,
                           List<OutletRef> outletChoices) {

    /** One sale: {@code items} reads "3 Coca-Cola, 2 Orange juice"; {@code methods} lists CASH, CARD, TRANSFER and ROOM as they were used. */
    public record Row(String id, String number, String outletId, String outletName, String outletKind, Order.Kind kind, String tableNo,
                      String roomNumber, String guestName, Instant createdAt, String openedBy, String openedByName, String items, int itemCount,
                      BigDecimal total, BigDecimal paidAmount, BigDecimal balance, String status, Order.Status orderStatus, List<String> methods,
                      Instant settledAt, String settledBy, String settledByName, String cancelReason) {
    }

    public record Totals(long sales, BigDecimal sold, BigDecimal paid, BigDecimal unpaid, BigDecimal onRooms, long cancelled, BigDecimal cancelledAmount) {
    }

    public record OutletTotals(String outletId, String name, String kind, long sales, BigDecimal sold, BigDecimal paid, BigDecimal unpaid,
                               BigDecimal onRooms, long cancelled) {
    }

    /** {@code userId} is empty for sales nobody is recorded as opening. */
    public record AttendantTotals(String userId, String name, long sales, BigDecimal sold, BigDecimal paid, BigDecimal unpaid, BigDecimal onRooms,
                                  long cancelled) {
    }

    public record EarlierUnpaid(long count, BigDecimal amount, Instant oldest) {
    }

    /**
     * {@code mine}: the list is the caller's own sales (Edit-level staff start there). {@code canExport}: Full access or management.
     * {@code actsOnOthers}: may take payment, charge or cancel on sales someone else opened (Full access or a manager).
     * {@code management}: manager, owner or finance, who see every outlet.
     */
    public record Viewer(String userId, boolean management, boolean mine, boolean mineByDefault, boolean canExport, boolean actsOnOthers) {
    }

    public record OutletRef(String id, String name, String kind) {
    }
}
