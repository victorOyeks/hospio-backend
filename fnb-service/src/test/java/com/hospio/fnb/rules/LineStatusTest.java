package com.hospio.fnb.rules;

import static com.hospio.fnb.entity.OrderLine.Status.CANCELLED;
import static com.hospio.fnb.entity.OrderLine.Status.NEW;
import static com.hospio.fnb.entity.OrderLine.Status.READY;
import static com.hospio.fnb.entity.OrderLine.Status.SENT;
import static com.hospio.fnb.entity.OrderLine.Status.SERVED;
import static org.assertj.core.api.Assertions.assertThat;

import com.hospio.fnb.entity.Order;
import java.util.List;
import org.junit.jupiter.api.Test;

class LineStatusTest {

    @Test
    void theOrderIsReadyWhenEveryLiveLineIsReady() {
        assertThat(LineStatus.rollup(Order.Status.SENT, List.of(READY, SENT))).isEqualTo(Order.Status.SENT);
        assertThat(LineStatus.rollup(Order.Status.SENT, List.of(READY, READY))).isEqualTo(Order.Status.READY);
        assertThat(LineStatus.rollup(Order.Status.SENT, List.of(READY, SERVED))).isEqualTo(Order.Status.READY);
    }

    @Test
    void theOrderIsServedWhenAllLiveLinesAreServed() {
        assertThat(LineStatus.rollup(Order.Status.READY, List.of(SERVED, SERVED))).isEqualTo(Order.Status.SERVED);
    }

    @Test
    void cancelledLinesDoNotCount() {
        assertThat(LineStatus.rollup(Order.Status.SENT, List.of(READY, CANCELLED))).isEqualTo(Order.Status.READY);
        assertThat(LineStatus.rollup(Order.Status.SENT, List.of(CANCELLED, CANCELLED))).isEqualTo(Order.Status.SENT);
    }

    @Test
    void aNewLineBehindAReadyOrderPutsItBackToSent() {
        assertThat(LineStatus.rollup(Order.Status.READY, List.of(READY, NEW))).isEqualTo(Order.Status.SENT);
        assertThat(LineStatus.rollup(Order.Status.SERVED, List.of(SERVED, SENT))).isEqualTo(Order.Status.SENT);
    }

    @Test
    void aClosedOrderNeverMoves() {
        assertThat(LineStatus.rollup(Order.Status.CLOSED, List.of(READY))).isEqualTo(Order.Status.CLOSED);
        assertThat(LineStatus.rollup(Order.Status.CANCELLED, List.of(SERVED))).isEqualTo(Order.Status.CANCELLED);
    }
}
