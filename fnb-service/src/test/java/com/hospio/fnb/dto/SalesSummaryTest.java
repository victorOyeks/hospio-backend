package com.hospio.fnb.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.OrderLine;
import com.hospio.fnb.entity.OrderPayment;
import com.hospio.fnb.entity.Outlet;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Sales are what was settled that day: paid at the outlet and charged to rooms apart, cancelled counted, top items by quantity. */
class SalesSummaryTest {

    private static Outlet outlet(String id, String name, Outlet.Kind kind) {
        Outlet o = Outlet.builder().name(name).kind(kind).build();
        o.setId(id);
        return o;
    }

    private static Order order(String id, String outletId, String total, String settledBy) {
        Order o = Order.builder().outletId(outletId).number(id).total(new BigDecimal(total)).settledBy(settledBy).build();
        o.setId(id);
        return o;
    }

    private static OrderLine line(String orderId, String name, int qty, String unit, String discount) {
        return OrderLine.builder().orderId(orderId).name(name).quantity(qty).unitPrice(new BigDecimal(unit)).discount(new BigDecimal(discount)).build();
    }

    private static OrderPayment payment(String orderId, String method, String amount) {
        return OrderPayment.builder().orderId(orderId).paymentId("p-" + orderId + method).method(method).amount(new BigDecimal(amount)).build();
    }

    @Test
    void splitsPaidAtTheOutletFromChargedToRoomsAndCountsByMethod() {
        Outlet bar = outlet("bar", "Bar", Outlet.Kind.BAR);
        Outlet kitchen = outlet("kit", "Restaurant", Outlet.Kind.KITCHEN);
        Order cash = order("o1", "bar", "3000.00", "PAYMENT");
        Order split = order("o2", "bar", "5000.00", "PAYMENT");
        Order room = order("o3", "bar", "2500.00", "ROOM");
        Order dinner = order("o4", "kit", "12000.00", "PAYMENT");
        Order cancelled = order("o5", "bar", "1500.00", "CANCELLED");
        cancelled.setStatus(Order.Status.CANCELLED);

        SalesSummary s = SalesSummary.build(LocalDate.of(2026, 11, 9), List.of(bar, kitchen), List.of(cash, split, room, dinner), List.of(cancelled),
                List.of(line("o1", "Star lager", 2, "1500", "0"), line("o2", "Star lager", 1, "1500", "0"), line("o2", "Whisky shot", 2, "2500", "0"),
                        line("o3", "Gin and tonic", 1, "3125", "625"), line("o4", "Beef steak and chips", 1, "12000", "0")),
                List.of(payment("o1", "CASH", "3000"), payment("o2", "CARD", "3000"), payment("o2", "CASH", "2000"), payment("o4", "TRANSFER", "12000")));

        SalesSummary.OutletSales b = s.outlets().get(0);
        assertThat(b.name()).isEqualTo("Bar");
        assertThat(b.orders()).isEqualTo(3);
        assertThat(b.sales()).isEqualByComparingTo("10500.00");
        assertThat(b.paidAtOutlet()).isEqualByComparingTo("8000.00");
        assertThat(b.chargedToRooms()).isEqualByComparingTo("2500.00");
        assertThat(b.cancelled()).isEqualTo(1);
        assertThat(b.byMethod().get("CASH")).isEqualByComparingTo("5000.00");
        assertThat(b.byMethod().get("CARD")).isEqualByComparingTo("3000.00");
        assertThat(b.byMethod().get("TRANSFER")).isEqualByComparingTo("0");
        assertThat(b.topItems().get(0).name()).isEqualTo("Star lager");
        assertThat(b.topItems().get(0).qty()).isEqualTo(3);
        assertThat(b.topItems().get(0).sales()).isEqualByComparingTo("4500.00");
        assertThat(b.topItems()).extracting(SalesSummary.TopItem::name).contains("Gin and tonic");
        assertThat(b.topItems().stream().filter(t -> t.name().equals("Gin and tonic")).findFirst().orElseThrow().sales()).isEqualByComparingTo("2500.00");

        assertThat(s.totals().orders()).isEqualTo(4);
        assertThat(s.totals().sales()).isEqualByComparingTo("22500.00");
        assertThat(s.totals().paidAtOutlet()).isEqualByComparingTo("20000.00");
        assertThat(s.totals().chargedToRooms()).isEqualByComparingTo("2500.00");
        assertThat(s.totals().cancelled()).isEqualTo(1);
        assertThat(s.totals().byMethod().get("TRANSFER")).isEqualByComparingTo("12000.00");
    }

    @Test
    void anOutletWithNoSalesStillAppearsWithZeros() {
        SalesSummary s = SalesSummary.build(LocalDate.of(2026, 11, 9), List.of(outlet("bar", "Bar", Outlet.Kind.BAR)), List.of(), List.of(), List.of(), List.of());

        assertThat(s.outlets()).hasSize(1);
        assertThat(s.outlets().get(0).sales()).isEqualByComparingTo("0");
        assertThat(s.outlets().get(0).byMethod()).containsKeys("CASH", "CARD", "TRANSFER");
        assertThat(s.outlets().get(0).topItems()).isEmpty();
    }
}
