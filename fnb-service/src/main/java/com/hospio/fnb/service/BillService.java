package com.hospio.fnb.service;

import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.util.Money;
import com.hospio.fnb.client.OutletSettings;
import com.hospio.fnb.dto.Bill;
import com.hospio.fnb.dto.OrderResponse;
import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.OrderPayment;
import com.hospio.fnb.entity.OrderSplit;
import com.hospio.fnb.entity.OrderSplitPart;
import com.hospio.fnb.entity.OrderTip;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.repository.OrderPaymentRepository;
import com.hospio.fnb.repository.OrderRepository;
import com.hospio.fnb.repository.OrderSplitPartRepository;
import com.hospio.fnb.repository.OrderSplitRepository;
import com.hospio.fnb.repository.OrderTipRepository;
import com.hospio.fnb.repository.SaleRequestRepository;
import com.hospio.fnb.rules.OutletAccess;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The bill of one sale as the pay screen shows it: see {@link Bill}. Anyone who may open the order may read its bill. */
@Service
@RequiredArgsConstructor
public class BillService {

    private final OrderService orderService;
    private final SplitService splitService;
    private final DiscountService discounts;
    private final SaleRequestService saleRequests;
    private final OrderRepository orders;
    private final OrderSplitRepository splits;
    private final OrderSplitPartRepository parts;
    private final OrderPaymentRepository payments;
    private final OrderTipRepository tips;
    private final SaleRequestRepository requests;
    private final OutletSettings settings;
    private final StaffOutlets staffOutlets;

    @Transactional(readOnly = true)
    public Bill bill(String orderId) {
        HospioUserDetails user = CurrentUser.require();
        OrderResponse view = orderService.get(orderId);
        Order o = orders.findById(orderId).orElseThrow();
        Outlet outlet = orderService.outlet(o.getOutletId());
        OutletSettings.Settings s = settings.get(o.getHotelId());
        boolean open = o.getStatus() != Order.Status.CLOSED && o.getStatus() != Order.Status.CANCELLED && !o.isSettled();
        BigDecimal balance = open ? Money.of(o.balance()) : Money.of(BigDecimal.ZERO);

        List<OrderTip> tipRows = tips.findByOrderId(orderId);
        Map<String, BigDecimal> tipByPayment = tipRows.stream().collect(Collectors.toMap(OrderTip::getPaymentId, OrderTip::getAmount, BigDecimal::add));
        List<Bill.Payment> pays = payments.findByOrderId(orderId).stream().sorted(Comparator.comparing(OrderPayment::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(p -> new Bill.Payment(p.getPaymentId(), p.getMethod(), p.getAmount(), p.getCreatedAt(), p.getReference(), p.getTakenByName(),
                        p.getPartId(), tipByPayment.get(p.getPaymentId()))).toList();

        // The split that stands, or the one that was just finished (so a settled bill still shows how it was paid).
        OrderSplit split = splits.findByOrderIdOrderByCreatedAtDesc(orderId).stream().filter(sp -> !OrderSplit.UNDONE.equals(sp.getStatus())).findFirst().orElse(null);
        Bill.Split splitView = null;
        boolean anyPartPaid = false;
        if (split != null) {
            List<OrderSplitPart> ps = parts.findBySplitIdOrderByPosition(split.getId());
            anyPartPaid = ps.stream().anyMatch(p -> Money.isPositive(p.getPaidAmount()));
            BigDecimal paid = ps.stream().map(OrderSplitPart::getPaidAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            splitView = new Bill.Split(split.getId(), split.getMode(), split.getAmount(), split.getCreatedByName(), split.getCreatedAt(),
                    ps.stream().map(p -> new Bill.Part(p.getId(), p.getPosition(), p.getLabel(), p.getAmount(), p.getTax(), p.getServiceCharge(),
                            p.getPaidAmount(), Money.of(p.due()), p.getStatus(), p.getMethod(), p.getRoomNumber(), splitService.read(p.getItems()),
                            p.getPaidAt(), p.getPaidByName())).toList(),
                    Money.of(paid), Money.of(split.getAmount().subtract(paid).max(BigDecimal.ZERO)),
                    OrderSplit.ACTIVE.equals(split.getStatus()) && !anyPartPaid);
        }
        boolean splitActive = split != null && OrderSplit.ACTIVE.equals(split.getStatus());

        boolean money = open && splitService.takesMoney(user, outlet, o, s);
        boolean owes = balance.signum() > 0;
        boolean assigned = staffOutlets.allowed(user, outlet);
        boolean onBill = !user.isReadOnly() && assigned && (OutletAccess.cashier(user) || OutletAccess.manager(user)
                || (OutletAccess.takes(user, outlet) && (!OutletAccess.ownOnly(user, outlet) || OutletAccess.own(user, o.getOpenedBy()))));
        boolean roomMay = money || OutletAccess.frontOffice(user) || (OutletAccess.waiter(user) && OutletAccess.own(user, o.getOpenedBy()) && assigned);
        String note = !open ? null : !assigned ? StaffOutlets.refusal(outlet)
                : !money && OutletAccess.waiter(user) && !s.waitersTakePayment() ? "Waiters don't take payment here: the cashier settles this bill" : null;
        Bill.Can can = new Bill.Can(money && owes, money && owes && !splitActive, money && splitActive && !anyPartPaid,
                open && owes && roomMay && s.roomCharge(), open && onBill && !splitActive, discounts.limitFor(user, s),
                open && onBill && !OutletAccess.manager(user), s.recordTips(), user.isPlatform() || (user.isManagerOrOwner() && !user.isReadOnly()), note);
        return new Bill(view, balance, splitView, pays, saleRequests.views(requests.findByOrderIdOrderByCreatedAtDesc(orderId)),
                tipRows.stream().map(t -> new Bill.Tip(t.getPaymentId(), t.getAmount(), t.getMethod(), t.getStaffName(), t.getCreatedAt())).toList(),
                Money.of(tipRows.stream().map(OrderTip::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)), can);
    }
}
