package com.hospio.fnb.service;

import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.fnb.dto.SalesSummary;
import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.OrderLine;
import com.hospio.fnb.entity.OrderPayment;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.repository.OrderLineRepository;
import com.hospio.fnb.repository.OrderPaymentRepository;
import com.hospio.fnb.repository.OrderRepository;
import com.hospio.fnb.repository.OutletRepository;
import com.hospio.fnb.rules.Days;
import com.hospio.fnb.rules.OutletAccess;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What the outlets sold on a day, from the orders settled that day (paid at the outlet or charged to a room). */
@Service
@RequiredArgsConstructor
public class SalesService {

    private final OutletRepository outlets;
    private final OrderRepository orders;
    private final OrderLineRepository lines;
    private final OrderPaymentRepository payments;

    /** Staff see the outlets they work; managers, owners and finance see all. */
    @Transactional(readOnly = true)
    public SalesSummary forDay(LocalDate date, String outletId) {
        HospioUserDetails user = CurrentUser.require();
        List<Outlet> visible = outlets.findByHotelIdOrderByName(TenantContext.requireHotel()).stream()
                .filter(o -> OutletAccess.sees(user, o) && (outletId == null || o.getId().equals(outletId))).toList();
        if (outletId != null && visible.isEmpty()) {
            throw new ForbiddenException("That outlet's sales aren't yours to see");
        }
        // Staff of one outlet don't see another's, even when they could see the board.
        List<Outlet> mine = user.isPlatform() || user.isManagerOrOwner() || user.has(com.hospio.common.enums.Role.FINANCE) ? visible
                : visible.stream().filter(o -> user.has(OutletAccess.roleFor(o))).toList();
        return summarise(TenantContext.requireHotel(), date == null ? Days.today() : date, mine);
    }

    /** For finance and the night audit: every outlet of the hotel, no caller to check. */
    @Transactional(readOnly = true)
    public SalesSummary forHotel(String hotelId, LocalDate date) {
        return summarise(hotelId, date == null ? Days.today() : date, outlets.findByHotelIdOrderByName(hotelId));
    }

    private SalesSummary summarise(String hotelId, LocalDate date, List<Outlet> of) {
        List<Order> settled = orders.findByHotelIdAndSettledAtGreaterThanEqualAndSettledAtLessThan(hotelId, Days.start(date), Days.end(date));
        List<Order> cancelled = orders.findByHotelIdAndStatusAndClosedAtGreaterThanEqualAndClosedAtLessThan(hotelId, Order.Status.CANCELLED,
                Days.start(date), Days.end(date));
        List<String> ids = settled.stream().map(Order::getId).toList();
        List<OrderLine> orderLines = ids.isEmpty() ? List.of() : lines.findByOrderIdIn(ids);
        List<OrderPayment> orderPayments = ids.isEmpty() ? List.of() : payments.findByOrderIdIn(ids);
        return SalesSummary.build(date, of, settled, cancelled, orderLines, orderPayments);
    }
}
