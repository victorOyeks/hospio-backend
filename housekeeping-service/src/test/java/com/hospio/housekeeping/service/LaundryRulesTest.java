package com.hospio.housekeeping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.exception.BadRequestException;
import com.hospio.housekeeping.entity.LaundryOrder;
import com.hospio.housekeeping.entity.LinenBatch;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Prices, totals, status moves, settlement and cancel, linen-batch arithmetic (docs/laundry-maintenance.md · Laundry). */
class LaundryRulesTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void expressDefaultsToOneAndAHalfTimesAndLinesAddUp() {
        assertThat(LaundryRules.defaultExpress(d("1500"))).isEqualByComparingTo("2250");
        assertThat(LaundryRules.defaultExpress(d("333"))).isEqualByComparingTo("500");
        assertThat(LaundryRules.unitPrice(d("1500"), d("2000"), true)).isEqualByComparingTo("2000");
        assertThat(LaundryRules.unitPrice(d("1500"), null, true)).isEqualByComparingTo("2250");
        assertThat(LaundryRules.unitPrice(d("1500"), d("2000"), false)).isEqualByComparingTo("1500");
        List<LaundryRules.Line> lines = List.of(LaundryRules.Line.of("p1", "Shirt", d("3"), d("1500")), LaundryRules.Line.of("p2", "Suit", d("1"), d("6000")));
        assertThat(lines.get(0).amount()).isEqualByComparingTo("4500");
        assertThat(LaundryRules.total(lines)).isEqualByComparingTo("10500.00");
    }

    @Test
    void ordersMoveOneStepAtATimeAndOnlyGoBackSettled() {
        LaundryOrder o = order();
        LaundryRules.checkMove(o, LaundryOrder.Status.IN_PROGRESS);
        assertThatThrownBy(() -> LaundryRules.checkMove(o, LaundryOrder.Status.READY)).isInstanceOf(BadRequestException.class)
                .hasMessage("LDR-00001 is received · next it goes in progress");
        o.setStatus(LaundryOrder.Status.READY);
        assertThatThrownBy(() -> LaundryRules.checkMove(o, LaundryOrder.Status.DELIVERED))
                .hasMessage("Charge it to the room or take payment before it goes back to the guest");
        o.setChargedToRoom(true);
        LaundryRules.checkMove(o, LaundryOrder.Status.DELIVERED);
        o.setStatus(LaundryOrder.Status.DELIVERED);
        assertThatThrownBy(() -> LaundryRules.checkMove(o, LaundryOrder.Status.READY)).hasMessage("LDR-00001 is already delivered");
        o.setStatus(LaundryOrder.Status.CANCELLED);
        assertThatThrownBy(() -> LaundryRules.checkMove(o, LaundryOrder.Status.IN_PROGRESS)).hasMessage("LDR-00001 is cancelled");
    }

    @Test
    void anOrderIsSettledOnceAndNeverWhenCancelledOrEmpty() {
        LaundryOrder o = order();
        LaundryRules.checkSettle(o);
        o.setChargedToRoom(true);
        assertThatThrownBy(() -> LaundryRules.checkSettle(o)).hasMessage("LDR-00001 is already charged to the room");
        LaundryOrder p = order();
        p.setPaid(true);
        assertThatThrownBy(() -> LaundryRules.checkSettle(p)).hasMessage("LDR-00001 is already paid");
        LaundryOrder c = order();
        c.setStatus(LaundryOrder.Status.CANCELLED);
        assertThatThrownBy(() -> LaundryRules.checkSettle(c)).hasMessage("LDR-00001 is cancelled");
        LaundryOrder z = order();
        z.setTotal(BigDecimal.ZERO);
        assertThatThrownBy(() -> LaundryRules.checkSettle(z)).hasMessage("There is nothing to charge on LDR-00001");
    }

    @Test
    void cancelOnlyBeforeTheBillOrTheTill() {
        LaundryRules.checkCancel(order());
        LaundryOrder charged = order();
        charged.setChargedToRoom(true);
        assertThatThrownBy(() -> LaundryRules.checkCancel(charged)).hasMessage("LDR-00001 is already on the guest's bill · it can't be cancelled here");
        LaundryOrder paid = order();
        paid.setPaid(true);
        assertThatThrownBy(() -> LaundryRules.checkCancel(paid)).hasMessage("LDR-00001 is already paid · refund the payment instead");
        LaundryOrder done = order();
        done.setStatus(LaundryOrder.Status.DELIVERED);
        assertThatThrownBy(() -> LaundryRules.checkCancel(done)).hasMessage("LDR-00001 is already delivered");
    }

    @Test
    void batchArithmeticKeepsEveryPieceAccountedFor() {
        // 20 sent, 18 arrived (2 missing), 15 back clean, 1 condemned: 2 still at the laundry
        LaundryRules.BatchLine sheet = new LaundryRules.BatchLine("i1", "Bed sheet", "pcs", d("20"), d("18"), d("15"), d("1"), d("2"), d("6500"));
        assertThat(sheet.missing()).isEqualByComparingTo("2");
        assertThat(sheet.lostAtLaundry()).isEqualByComparingTo("0");
        assertThat(sheet.outstanding()).isEqualByComparingTo("2");
        LaundryRules.BatchLine towel = new LaundryRules.BatchLine("i2", "Bath towel", "pcs", d("10"), d("10"), d("10"), d("0"), d("0"), d("4200"));
        assertThat(LaundryRules.afterReturn(List.of(sheet, towel))).isEqualTo(LinenBatch.Status.PART_RETURNED);
        // closing short writes the 2 off as lost at the laundry
        LaundryRules.BatchLine closed = sheet.with(sheet.received(), sheet.returned(), sheet.condemned(), sheet.lost().add(sheet.outstanding()));
        assertThat(closed.outstanding()).isEqualByComparingTo("0");
        assertThat(closed.lostAtLaundry()).isEqualByComparingTo("2");
        assertThat(LaundryRules.afterReturn(List.of(closed, towel))).isEqualTo(LinenBatch.Status.RETURNED);
        assertThat(LaundryRules.missing(List.of(sheet, towel))).isEqualByComparingTo("2");
        // not yet received: nothing missing, nothing outstanding
        LaundryRules.BatchLine sent = new LaundryRules.BatchLine("i3", "Pillowcase", "pcs", d("12"), null, d("0"), d("0"), d("0"), null);
        assertThat(sent.missing()).isEqualByComparingTo("0");
        assertThat(sent.outstanding()).isEqualByComparingTo("0");
    }

    private static LaundryOrder order() {
        return LaundryOrder.builder().number("LDR-00001").total(d("4500")).items("3 × Shirt").status(LaundryOrder.Status.RECEIVED).build();
    }
}
