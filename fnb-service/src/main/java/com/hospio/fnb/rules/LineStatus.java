package com.hospio.fnb.rules;

import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.OrderLine;
import java.util.List;

/** How the status of each dish or drink rolls up to the order's. */
public final class LineStatus {

    private LineStatus() {
    }

    /**
     * The order's status after its lines changed. Cancelled lines don't count. Every live line served makes the order SERVED; every
     * live line ready or served (at least one still waiting to be served) makes it READY; otherwise the order keeps its status. An
     * order that is already closed or cancelled never moves.
     */
    public static Order.Status rollup(Order.Status current, List<OrderLine.Status> lines) {
        if (current == Order.Status.CLOSED || current == Order.Status.CANCELLED) {
            return current;
        }
        List<OrderLine.Status> live = lines.stream().filter(s -> s != OrderLine.Status.CANCELLED).toList();
        if (live.isEmpty()) {
            return current;
        }
        if (live.stream().allMatch(s -> s == OrderLine.Status.SERVED)) {
            return Order.Status.SERVED;
        }
        if (live.stream().allMatch(s -> s == OrderLine.Status.READY || s == OrderLine.Status.SERVED)) {
            return Order.Status.READY;
        }
        if (current == Order.Status.READY || current == Order.Status.SERVED) {
            // Something new was added behind a ready or served order: it is being worked on again.
            return Order.Status.SENT;
        }
        return current;
    }
}
