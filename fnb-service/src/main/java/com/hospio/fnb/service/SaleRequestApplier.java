package com.hospio.fnb.service;

import com.hospio.fnb.client.OutletSettings;
import com.hospio.fnb.dto.Bill;
import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.OrderLine;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.entity.SaleRequest;
import com.hospio.fnb.repository.OrderLineRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Does what an approved request asked, in a transaction of its own: when the sale has moved on (paid, closed, the item already
 * gone) the refusal rolls only this back, and the request is kept as "not applied" with the reason.
 */
@Service
@RequiredArgsConstructor
public class SaleRequestApplier {

    private final OrderService orderService;
    private final DiscountService discounts;
    private final OrderLineRepository lines;
    private final OutletSettings settings;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void apply(SaleRequest r, String approvedByName) {
        OutletSettings.Settings s = settings.get(orderService.peek(r.getOrderId()).getHotelId());
        Order o = orderService.lock(r.getOrderId());
        Outlet outlet = orderService.outlet(o.getOutletId());
        List<OrderLine> all = new ArrayList<>(lines.findByOrderIdOrderByCreatedAt(o.getId()));
        // The void counts against whoever asked for it; the reason says who approved it.
        String why = r.getReason() + " · approved by " + (approvedByName == null ? "a manager" : approvedByName);
        String reason = why.length() > 300 ? why.substring(0, 300) : why;
        switch (r.getKind()) {
            case VOID_LINE -> orderService.voidLine(o, outlet, s, all, OrderService.lineOf(all, r.getLineId()), reason, r.getRequestedBy(), r.getRequestedByName());
            case VOID_SALE -> orderService.voidSale(o, reason, r.getRequestedBy(), r.getRequestedByName());
            default -> {
                boolean comp = r.getKind() == SaleRequest.Kind.COMP_LINE || r.getKind() == SaleRequest.Kind.COMP_BILL;
                DiscountService.Plan plan = discounts.plan(o, all, new Bill.DiscountRequest(r.getLineId(), r.getPct(), r.getAmount(), comp, r.getReason()));
                discounts.apply(o, s, all, plan, r.getReason(), r.getRequestedBy(), r.getRequestedByName(), approvedByName == null ? "a manager" : approvedByName);
            }
        }
    }
}
