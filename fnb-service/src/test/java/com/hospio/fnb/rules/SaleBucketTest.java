package com.hospio.fnb.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.exception.BadRequestException;
import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.Outlet;
import java.math.BigDecimal;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/** Where a sale stands, and how its total splits into paid, on a room and still due. */
class SaleBucketTest {

    private static Order sale(Order.Kind kind, Order.Status status, String total) {
        return Order.builder().kind(kind).status(status).total(new BigDecimal(total)).build();
    }

    @Test
    void aQuickBarSaleNotPaidIsToPayWhateverItsTicketSays() {
        assertThat(SaleBucket.of(sale(Order.Kind.TAKEAWAY, Order.Status.OPEN, "4500.00"), Outlet.Kind.BAR)).isEqualTo(SaleBucket.TO_PAY);
        assertThat(SaleBucket.of(sale(Order.Kind.DINE_IN, Order.Status.SENT, "4500.00"), Outlet.Kind.BAR)).isEqualTo(SaleBucket.TO_PAY);
    }

    @Test
    void anOpenTabAndAKitchenTicketStillCookingAreInProgress() {
        assertThat(SaleBucket.of(sale(Order.Kind.TAB, Order.Status.OPEN, "4500.00"), Outlet.Kind.BAR)).isEqualTo(SaleBucket.IN_PROGRESS);
        assertThat(SaleBucket.of(sale(Order.Kind.DINE_IN, Order.Status.SENT, "4500.00"), Outlet.Kind.KITCHEN)).isEqualTo(SaleBucket.IN_PROGRESS);
        assertThat(SaleBucket.of(sale(Order.Kind.DINE_IN, Order.Status.READY, "4500.00"), Outlet.Kind.RESTAURANT)).isEqualTo(SaleBucket.IN_PROGRESS);
        assertThat(SaleBucket.of(sale(Order.Kind.TAKEAWAY, Order.Status.OPEN, "0.00"), Outlet.Kind.BAR)).isEqualTo(SaleBucket.IN_PROGRESS);
    }

    @Test
    void aServedKitchenTicketNotPaidIsToPay() {
        assertThat(SaleBucket.of(sale(Order.Kind.DINE_IN, Order.Status.SERVED, "4500.00"), Outlet.Kind.KITCHEN)).isEqualTo(SaleBucket.TO_PAY);
    }

    @Test
    void paidRoomPartPaidCancelledAndComplimentaryWinInThatOrder() {
        Order cancelled = sale(Order.Kind.TAB, Order.Status.CANCELLED, "4500.00");
        cancelled.setPaidAmount(new BigDecimal("100.00"));
        assertThat(SaleBucket.of(cancelled, Outlet.Kind.BAR)).isEqualTo(SaleBucket.CANCELLED);

        Order room = sale(Order.Kind.TAKEAWAY, Order.Status.CLOSED, "4500.00");
        room.setChargedToRoom(true);
        room.setPaidAmount(new BigDecimal("1000.00"));
        assertThat(SaleBucket.of(room, Outlet.Kind.BAR)).isEqualTo(SaleBucket.ON_ROOM);

        Order paid = sale(Order.Kind.TAKEAWAY, Order.Status.CLOSED, "4500.00");
        paid.setPaid(true);
        paid.setPaidAmount(new BigDecimal("4500.00"));
        assertThat(SaleBucket.of(paid, Outlet.Kind.BAR)).isEqualTo(SaleBucket.PAID);

        Order comp = sale(Order.Kind.DINE_IN, Order.Status.CLOSED, "0.00");
        comp.setSettledBy("COMPLIMENTARY");
        assertThat(SaleBucket.of(comp, Outlet.Kind.KITCHEN)).isEqualTo(SaleBucket.PAID);

        Order part = sale(Order.Kind.TAB, Order.Status.OPEN, "4500.00");
        part.setPaidAmount(new BigDecimal("2000.00"));
        assertThat(SaleBucket.of(part, Outlet.Kind.BAR)).isEqualTo(SaleBucket.PART_PAID);
    }

    @Test
    void thePartsOfASaleAddUpToItsTotal() {
        Order part = sale(Order.Kind.TAKEAWAY, Order.Status.OPEN, "4500.00");
        part.setPaidAmount(new BigDecimal("2000.00"));
        SaleBucket.Parts p = SaleBucket.parts(part);
        assertThat(p.paid()).isEqualByComparingTo("2000.00");
        assertThat(p.unpaid()).isEqualByComparingTo("2500.00");
        assertThat(p.onRoom()).isEqualByComparingTo("0");

        part.setChargedToRoom(true);
        p = SaleBucket.parts(part);
        assertThat(p.paid()).isEqualByComparingTo("2000.00");
        assertThat(p.onRoom()).isEqualByComparingTo("2500.00");
        assertThat(p.unpaid()).isEqualByComparingTo("0");

        Order over = sale(Order.Kind.TAKEAWAY, Order.Status.CLOSED, "4500.00");
        over.setPaid(true);
        over.setPaidAmount(new BigDecimal("5000.00"));
        p = SaleBucket.parts(over);
        assertThat(p.paid()).isEqualByComparingTo("4500.00");
        assertThat(p.paid().add(p.onRoom()).add(p.unpaid())).isEqualByComparingTo("4500.00");

        Order cancelled = sale(Order.Kind.TAKEAWAY, Order.Status.CANCELLED, "4500.00");
        p = SaleBucket.parts(cancelled);
        assertThat(p.paid().add(p.onRoom()).add(p.unpaid())).isEqualByComparingTo("0");
    }

    @Test
    void theStatusFilterReadsOneBucketUnpaidOrEverything() {
        assertThat(SaleBucket.parseFilter(null)).isEqualTo(EnumSet.allOf(SaleBucket.class));
        assertThat(SaleBucket.parseFilter("unpaid")).containsExactlyInAnyOrder(SaleBucket.TO_PAY, SaleBucket.PART_PAID);
        assertThat(SaleBucket.parseFilter("to-pay")).containsExactly(SaleBucket.TO_PAY);
        assertThat(SaleBucket.parseFilter("ON_ROOM,CANCELLED")).containsExactlyInAnyOrder(SaleBucket.ON_ROOM, SaleBucket.CANCELLED);
        assertThatThrownBy(() -> SaleBucket.parseFilter("REFUNDED")).isInstanceOf(BadRequestException.class).hasMessageContaining("Unknown status");
    }
}
