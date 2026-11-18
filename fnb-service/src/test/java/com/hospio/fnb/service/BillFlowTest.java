package com.hospio.fnb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalRequestedEvent;
import com.hospio.common.kafka.events.ChargePostedEvent;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.fnb.client.FolioClient;
import com.hospio.fnb.client.OutletSettings;
import com.hospio.fnb.client.PropertyClient;
import com.hospio.fnb.client.ReservationClient;
import com.hospio.fnb.dto.Bill;
import com.hospio.fnb.dto.OrderRequest;
import com.hospio.fnb.entity.MenuItem;
import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.OrderLine;
import com.hospio.fnb.entity.OrderPayment;
import com.hospio.fnb.entity.OrderSplit;
import com.hospio.fnb.entity.OrderSplitPart;
import com.hospio.fnb.entity.OrderTip;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.entity.SaleRequest;
import com.hospio.fnb.entity.StaffOutlet;
import com.hospio.fnb.repository.DiningTableRepository;
import com.hospio.fnb.repository.HappyHourRepository;
import com.hospio.fnb.repository.MenuItemRepository;
import com.hospio.fnb.repository.OrderLineRepository;
import com.hospio.fnb.repository.OrderPaymentRepository;
import com.hospio.fnb.repository.OrderRepository;
import com.hospio.fnb.repository.OrderSplitPartRepository;
import com.hospio.fnb.repository.OrderSplitRepository;
import com.hospio.fnb.repository.OrderTipRepository;
import com.hospio.fnb.repository.OutletRepository;
import com.hospio.fnb.repository.SaleRequestRepository;
import com.hospio.fnb.repository.StaffOutletRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Venue type, phase 4: a bill split equally, by item and by amount with each part paid on its own; tips kept out of the sale;
 * discounts within and over the limit; requests from the floor approved and declined; a person kept to their outlets.
 */
class BillFlowTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderLineRepository lines = mock(OrderLineRepository.class);
    private final OrderPaymentRepository orderPayments = mock(OrderPaymentRepository.class);
    private final MenuItemRepository items = mock(MenuItemRepository.class);
    private final OutletRepository outlets = mock(OutletRepository.class);
    private final HappyHourRepository happyHours = mock(HappyHourRepository.class);
    private final ReservationClient reservations = mock(ReservationClient.class);
    private final FolioClient folios = mock(FolioClient.class);
    private final OutletSettings settings = mock(OutletSettings.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final OrderSplitRepository splits = mock(OrderSplitRepository.class);
    private final OrderSplitPartRepository parts = mock(OrderSplitPartRepository.class);
    private final OrderTipRepository tips = mock(OrderTipRepository.class);
    private final SaleRequestRepository requests = mock(SaleRequestRepository.class);
    private final StaffOutletRepository staffRows = mock(StaffOutletRepository.class);

    private final StaffOutlets staffOutlets = new StaffOutlets(staffRows, outlets);
    private final OrderService orderService = new OrderService(orders, lines, orderPayments, mock(DiningTableRepository.class), items, outlets, happyHours,
            reservations, folios, mock(PropertyClient.class), settings, events, splits, parts, tips, staffOutlets);
    private final SplitService splitService = new SplitService(orderService, orders, lines, splits, parts, settings, staffOutlets, reservations, folios,
            events, new ObjectMapper());
    private final DiscountService discounts = new DiscountService(orderService, orders, lines, settings, staffOutlets);
    private final SaleRequestApplier applier = new SaleRequestApplier(orderService, discounts, lines, settings);
    private final SaleRequestService saleRequests = new SaleRequestService(orderService, discounts, applier, orders, lines, outlets, requests, settings, events);

    private final List<Order> storedOrders = new ArrayList<>();
    private final List<OrderLine> storedLines = new ArrayList<>();
    private final List<OrderPayment> storedPayments = new ArrayList<>();
    private final List<OrderSplit> storedSplits = new ArrayList<>();
    private final List<OrderSplitPart> storedParts = new ArrayList<>();
    private final List<OrderTip> storedTips = new ArrayList<>();
    private final List<SaleRequest> storedRequests = new ArrayList<>();
    private final List<StaffOutlet> assigned = new ArrayList<>();

    private final Outlet bar = Outlet.builder().name("Main Bar").kind(Outlet.Kind.BAR).build();
    private OutletSettings.Settings config = new OutletSettings.Settings(true, true, new BigDecimal("500000"), true, new BigDecimal("7.5"));

    @BeforeEach
    void setUp() {
        bar.setId("bar");
        bar.setHotelId("h1");
        when(outlets.findById("bar")).thenReturn(Optional.of(bar));
        when(outlets.lockById("bar")).thenReturn(Optional.of(bar));
        when(outlets.findByHotelIdOrderByName("h1")).thenReturn(List.of(bar));
        item("lager", "Star Lager", "1500.00");
        item("wine", "Red wine", "7000.00");
        item("fish", "Grilled fish", "4000.00");
        when(settings.get(any())).thenAnswer(inv -> config);
        when(happyHours.findByOutletIdAndActiveTrue("bar")).thenReturn(List.of());
        when(orders.save(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId(UUID.randomUUID().toString());
                o.setHotelId("h1");
                storedOrders.add(o);
            }
            return o;
        });
        when(orders.findById(anyString())).thenAnswer(inv -> storedOrders.stream().filter(o -> o.getId().equals(inv.getArgument(0))).findFirst());
        when(orders.lockById(anyString())).thenAnswer(inv -> storedOrders.stream().filter(o -> o.getId().equals(inv.getArgument(0))).findFirst());
        when(orders.peek(anyString())).thenAnswer(inv -> storedOrders.stream().filter(o -> o.getId().equals(inv.getArgument(0))).findFirst().map(BillFlowTest::peekOf));
        when(lines.save(any())).thenAnswer(inv -> {
            OrderLine l = inv.getArgument(0);
            if (l.getId() == null) {
                l.setId(UUID.randomUUID().toString());
                storedLines.add(l);
            }
            return l;
        });
        when(lines.findByOrderIdOrderByCreatedAt(anyString())).thenAnswer(inv -> new ArrayList<>(storedLines));
        when(orderPayments.save(any())).thenAnswer(inv -> {
            storedPayments.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(orderPayments.existsByPaymentId(anyString())).thenAnswer(inv -> storedPayments.stream().anyMatch(p -> p.getPaymentId().equals(inv.getArgument(0))));
        when(splits.save(any())).thenAnswer(inv -> {
            OrderSplit s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(UUID.randomUUID().toString());
                storedSplits.add(s);
            }
            return s;
        });
        when(splits.findFirstByOrderIdAndStatus(anyString(), anyString())).thenAnswer(inv -> storedSplits.stream()
                .filter(s -> s.getOrderId().equals(inv.getArgument(0)) && s.getStatus().equals(inv.getArgument(1))).findFirst());
        when(parts.save(any())).thenAnswer(inv -> {
            OrderSplitPart p = inv.getArgument(0);
            if (p.getId() == null) {
                p.setId(UUID.randomUUID().toString());
                storedParts.add(p);
            }
            return p;
        });
        when(parts.findBySplitIdOrderByPosition(anyString())).thenAnswer(inv -> storedParts.stream().filter(p -> p.getSplitId().equals(inv.getArgument(0)))
                .sorted(Comparator.comparingInt(OrderSplitPart::getPosition)).toList());
        when(parts.findById(anyString())).thenAnswer(inv -> storedParts.stream().filter(p -> p.getId().equals(inv.getArgument(0))).findFirst());
        when(tips.save(any())).thenAnswer(inv -> {
            storedTips.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(requests.save(any())).thenAnswer(inv -> {
            SaleRequest r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID().toString());
                storedRequests.add(r);
            }
            return r;
        });
        when(requests.findByApprovalId(anyString())).thenAnswer(inv -> storedRequests.stream().filter(r -> r.getApprovalId().equals(inv.getArgument(0))).findFirst());
        when(requests.findByOrderIdAndStatus(anyString(), anyString())).thenAnswer(inv -> storedRequests.stream()
                .filter(r -> r.getOrderId().equals(inv.getArgument(0)) && r.getStatus().equals(inv.getArgument(1))).toList());
        when(staffRows.findByHotelIdAndUserId(anyString(), anyString())).thenAnswer(inv -> assigned.stream()
                .filter(a -> a.getUserId().equals(inv.getArgument(1))).toList());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private void item(String id, String name, String price) {
        MenuItem m = MenuItem.builder().outletId("bar").category("Drinks").name(name).price(new BigDecimal(price)).build();
        m.setId(id);
        when(items.findById(id)).thenReturn(Optional.of(m));
    }

    private void signIn(String userId, String name, Map<Role, AccessLevel> roles) {
        HospioUserDetails user = new HospioUserDetails(userId, userId + "@x.ng", name, "b1", List.of("h1"), roles, JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        TenantContext.set(Tenant.fromUser(user, "h1"));
    }

    private void asBar() {
        signIn("bisi", "Bisi Bar", Map.of(Role.BAR, AccessLevel.EDIT));
    }

    private void asCashier() {
        signIn("chika", "Chika Cashier", Map.of(Role.CASHIER, AccessLevel.EDIT));
    }

    private void asWaiter() {
        signIn("wale", "Wale Waiter", Map.of(Role.WAITER, AccessLevel.EDIT));
    }

    private void asManager() {
        signIn("ada", "Ada Manager", Map.of(Role.MANAGER, AccessLevel.FULL));
    }

    private Order tab(String... itemIds) {
        List<OrderRequest.LineRequest> l = new ArrayList<>();
        for (String id : itemIds) {
            l.add(new OrderRequest.LineRequest(id, 1, null, null));
        }
        String id = orderService.create(new OrderRequest("bar", Order.Kind.TAB, null, null, "Table of three", null, l)).id();
        return storedOrders.stream().filter(o -> o.getId().equals(id)).findFirst().orElseThrow();
    }

    private List<OrderSplitPart> partsOf(Order o) {
        OrderSplit s = storedSplits.stream().filter(x -> x.getOrderId().equals(o.getId()) && !OrderSplit.UNDONE.equals(x.getStatus())).findFirst().orElseThrow();
        return storedParts.stream().filter(p -> p.getSplitId().equals(s.getId())).sorted(Comparator.comparingInt(OrderSplitPart::getPosition)).toList();
    }

    private static OrderRepository.Peek peekOf(Order o) {
        return new OrderRepository.Peek() {
            public String getHotelId() {
                return o.getHotelId();
            }

            public String getOutletId() {
                return o.getOutletId();
            }

            public String getRoomId() {
                return o.getRoomId();
            }

            public String getRoomNumber() {
                return o.getRoomNumber();
            }
        };
    }

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    // ---- splitting ------------------------------------------------------------------------------------

    @Test
    void tenThousandBetweenThreeIsPaidPartByPartAndClosesOnTheLast() {
        asBar();
        Order o = tab("lager", "lager", "wine"); // 1,500 + 1,500 + 7,000
        assertThat(o.getTotal()).isEqualByComparingTo("10000.00");
        splitService.split(o.getId(), new Bill.SplitRequest("EQUAL", 3, null, null));
        List<OrderSplitPart> ps = partsOf(o);
        assertThat(ps).extracting(OrderSplitPart::getAmount).containsExactly(n("3333.33"), n("3333.33"), n("3333.34"));
        assertThat(ps.stream().map(OrderSplitPart::getTax).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(o.getTax());

        // A payment that names no part is refused; one for a part is asked for that part's amount only.
        assertThat(orderService.due(o.getId(), null, null).refusal()).contains("split into 3 parts");
        assertThat(orderService.due(o.getId(), ps.get(0).getId(), null).due()).isEqualByComparingTo("3333.33");
        assertThat(orderService.due(o.getId(), ps.get(0).getId(), null).partLabel()).isEqualTo("Part 1 of 3");

        orderService.applyPayment(o.getId(), "pay-1", "CASH", n("3333.33"), null, ps.get(0).getId(), null);
        assertThat(ps.get(0).getStatus()).isEqualTo(OrderSplitPart.PAID);
        assertThat(o.getStatus()).isNotEqualTo(Order.Status.CLOSED);
        assertThat(o.balance()).isEqualByComparingTo("6666.67");
        assertThat(orderService.due(o.getId(), ps.get(0).getId(), null).refusal()).contains("already paid");
        // Once a part is paid the split stays.
        assertThatThrownBy(() -> splitService.undo(o.getId())).isInstanceOf(BadRequestException.class).hasMessageContaining("already paid");

        orderService.applyPayment(o.getId(), "pay-2", "CARD", n("3333.33"), "POS-REF", ps.get(1).getId(), null);
        orderService.applyPayment(o.getId(), "pay-3", "TRANSFER", n("3333.34"), null, ps.get(2).getId(), null);
        assertThat(o.isPaid()).isTrue();
        assertThat(o.getStatus()).isEqualTo(Order.Status.CLOSED);
        assertThat(o.getPaidAmount()).isEqualByComparingTo("10000.00");
        assertThat(storedSplits.get(0).getStatus()).isEqualTo(OrderSplit.DONE);
        // Three payments, one sale: the money adds up to the bill and nothing is counted twice.
        assertThat(storedPayments).hasSize(3);
        assertThat(storedPayments.stream().map(OrderPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("10000.00");
        assertThat(storedPayments).extracting(OrderPayment::getPartId).doesNotContainNull();
    }

    @Test
    void aSplitIsUndoneBeforeAnythingIsPaidAndTheBillChangesAgain() {
        asBar();
        Order o = tab("lager", "wine");
        splitService.split(o.getId(), new Bill.SplitRequest("EQUAL", 2, null, null));
        assertThatThrownBy(() -> orderService.addLine(o.getId(), new OrderRequest.LineRequest("lager", 1, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("is split");
        assertThatThrownBy(() -> splitService.split(o.getId(), new Bill.SplitRequest("EQUAL", 2, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already split");
        splitService.undo(o.getId());
        assertThat(storedSplits.get(0).getStatus()).isEqualTo(OrderSplit.UNDONE);
        assertThat(orderService.due(o.getId(), null, null).refusal()).isNull();
        orderService.addLine(o.getId(), new OrderRequest.LineRequest("lager", 1, null, null));
        assertThat(o.getTotal()).isEqualByComparingTo("10000.00");
    }

    @Test
    void byItemASharedBottleIsDividedAndTheServiceChargeFollowsTheItems() {
        // Prices without tax, VAT 7.5 % and a 5 % service charge on the net: the three bills still add up to the whole bill.
        config = new OutletSettings.Settings(true, true, new BigDecimal("500000"), false, new BigDecimal("7.5"), new BigDecimal("5"));
        asBar();
        Order o = tab("lager", "fish", "wine");
        // net 12,500 + VAT 937.50 + service charge 625.00
        assertThat(o.getTotal()).isEqualByComparingTo("14062.50");
        String lager = storedLines.get(0).getId();
        String fish = storedLines.get(1).getId();
        String wine = storedLines.get(2).getId();
        splitService.split(o.getId(), new Bill.SplitRequest("ITEM", null, null, List.of(
                new Bill.PartRequest("Ada", List.of(new Bill.PartLine(lager, 1), new Bill.PartLine(wine, 1))),
                new Bill.PartRequest(null, List.of(new Bill.PartLine(fish, 1), new Bill.PartLine(wine, 1))))));
        List<OrderSplitPart> ps = partsOf(o);
        assertThat(ps).extracting(OrderSplitPart::getLabel).containsExactly("Ada", "Guest 2");
        // Ada: lager 1,500 + half the wine 3,500 = 5,000 of 12,500 → 40 % of the bill; guest 2 the other 60 %.
        assertThat(ps.get(0).getAmount()).isEqualByComparingTo("5625.00");
        assertThat(ps.get(1).getAmount()).isEqualByComparingTo("8437.50");
        assertThat(ps.get(0).getAmount().add(ps.get(1).getAmount())).isEqualByComparingTo(o.getTotal());
        assertThat(ps.get(0).getServiceCharge()).isEqualByComparingTo("250.00");
        assertThat(ps.get(0).getTax().add(ps.get(1).getTax())).isEqualByComparingTo("937.50");
        assertThat(ps.get(0).getItems()).contains("1/2");
    }

    @Test
    void byItemEveryItemMustBeOnABill() {
        asBar();
        Order o = tab("lager", "wine");
        String lager = storedLines.get(0).getId();
        assertThatThrownBy(() -> splitService.split(o.getId(), new Bill.SplitRequest("ITEM", null, null, List.of(
                new Bill.PartRequest("A", List.of(new Bill.PartLine(lager, 1))), new Bill.PartRequest("B", List.of(new Bill.PartLine(lager, 1)))))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Red wine isn't on anyone's bill");
    }

    @Test
    void byAmountTheRestOfTheBillIsTheLastPart() {
        asCashier();
        signIn("bisi", "Bisi Bar", Map.of(Role.BAR, AccessLevel.EDIT));
        Order o = tab("lager", "lager", "wine");
        asCashier();
        splitService.split(o.getId(), new Bill.SplitRequest("AMOUNT", null, List.of(n("2500"), n("4000.50")), null));
        List<OrderSplitPart> ps = partsOf(o);
        assertThat(ps).extracting(OrderSplitPart::getAmount).containsExactly(n("2500.00"), n("4000.50"), n("3499.50"));
        assertThat(ps.get(2).getLabel()).isEqualTo("Rest of the bill");
        assertThatThrownBy(() -> splitService.split(tabAgain().getId(), new Bill.SplitRequest("AMOUNT", null, List.of(n("99999")), null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("more than");
    }

    private Order tabAgain() {
        storedLines.clear();
        signIn("bisi", "Bisi Bar", Map.of(Role.BAR, AccessLevel.EDIT));
        Order o = tab("lager");
        asCashier();
        return o;
    }

    @Test
    void aWaiterSplitsOnlyWhereWaitersTakePayment() {
        asWaiter();
        Order o = tab("lager", "wine");
        assertThatThrownBy(() -> splitService.split(o.getId(), new Bill.SplitRequest("EQUAL", 2, null, null)))
                .isInstanceOf(ForbiddenException.class).hasMessageContaining("cashier splits");
        config = new OutletSettings.Settings(true, true, new BigDecimal("500000"), true, new BigDecimal("7.5"), BigDecimal.ZERO, false, true);
        splitService.split(o.getId(), new Bill.SplitRequest("EQUAL", 2, null, null));
        assertThat(partsOf(o)).hasSize(2);
    }

    @Test
    void onePartGoesOnARoomAndTheRestIsPaidAtTheBar() {
        asBar();
        Order o = tab("lager", "lager", "wine");
        splitService.split(o.getId(), new Bill.SplitRequest("EQUAL", 2, null, null));
        List<OrderSplitPart> ps = partsOf(o);
        when(reservations.findActiveStay("room-7")).thenReturn(Optional.of(new ReservationClient.ActiveStay("booking-7", "Mr Obi", "107", null, null)));
        when(folios.hasOpenFolio("booking-7")).thenReturn(true);
        // The whole order can't go on the room while it is split; a part can.
        assertThatThrownBy(() -> orderService.chargeToRoom(o.getId(), "room-7")).isInstanceOf(BadRequestException.class).hasMessageContaining("is split");
        splitService.chargePartToRoom(o.getId(), ps.get(0).getId(), "room-7");
        assertThat(o.getRoomAmount()).isEqualByComparingTo("5000.00");
        assertThat(o.isSettled()).isFalse();
        assertThat(o.balance()).isEqualByComparingTo("5000.00");
        ArgumentCaptor<ChargePostedEvent> charge = ArgumentCaptor.forClass(ChargePostedEvent.class);
        verify(events).publish(eq(KafkaTopics.CHARGE_POSTED), charge.capture());
        // The folio gets the part without its VAT (348.84 of the bill's 697.67), which travels as the tax.
        assertThat(charge.getValue().getAmount().add(charge.getValue().getTaxAmount())).isEqualByComparingTo("5000.00");
        assertThat(charge.getValue().getTaxAmount()).isEqualByComparingTo(ps.get(0).getTax());

        orderService.applyPayment(o.getId(), "pay-1", "CASH", n("5000.00"), null, ps.get(1).getId(), null);
        assertThat(o.isSettled()).isTrue();
        assertThat(o.isChargedToRoom()).isTrue();
        assertThat(o.getStatus()).isEqualTo(Order.Status.CLOSED);
        assertThat(com.hospio.fnb.rules.SaleBucket.parts(o).paid()).isEqualByComparingTo("5000.00");
        assertThat(com.hospio.fnb.rules.SaleBucket.parts(o).onRoom()).isEqualByComparingTo("5000.00");
        assertThat(com.hospio.fnb.rules.SaleBucket.parts(o).unpaid()).isEqualByComparingTo("0");
    }

    // ---- tips -----------------------------------------------------------------------------------------

    @Test
    void aTipIsKeptApartFromTheSaleAgainstWhoeverServed() {
        asWaiter();
        Order o = tab("lager", "lager");
        asCashier();
        // The guest hands over 3,500 for a 3,000 bill: 500 is the tip.
        orderService.applyPayment(o.getId(), "pay-1", "CASH", n("3500.00"), null, null, n("500.00"));
        assertThat(o.getPaidAmount()).isEqualByComparingTo("3000.00");
        assertThat(o.isPaid()).isTrue();
        assertThat(storedPayments.get(0).getAmount()).isEqualByComparingTo("3000.00");
        assertThat(storedTips).hasSize(1);
        assertThat(storedTips.get(0).getAmount()).isEqualByComparingTo("500.00");
        assertThat(storedTips.get(0).getStaffId()).isEqualTo("wale");
        assertThat(storedTips.get(0).getStaffName()).isEqualTo("Wale Waiter");
    }

    // ---- discounts and requests -----------------------------------------------------------------------

    @Test
    void aCashierGivesTenPercentAndAsksForMore() {
        asBar();
        Order o = tab("lager", "wine"); // 8,500
        asCashier();
        assertThat(saleRequests.discount(o.getId(), new Bill.DiscountRequest(null, n("10"), null, null, "Regular guest"))).isNull();
        assertThat(o.getTotal()).isEqualByComparingTo("7650.00");
        assertThat(o.getDiscount()).isEqualByComparingTo("850.00");
        assertThat(o.getDiscountByName()).isEqualTo("Chika Cashier");
        assertThat(storedLines.get(1).getBillDiscount()).isEqualByComparingTo("700.00");

        // 25 % is over the 10 % limit: nothing changes, a manager is asked.
        SaleRequest asked = saleRequests.discount(o.getId(), new Bill.DiscountRequest(null, n("25"), null, null, "Complaint about service"));
        assertThat(asked).isNotNull();
        assertThat(asked.getKind()).isEqualTo(SaleRequest.Kind.DISCOUNT_BILL);
        assertThat(o.getTotal()).isEqualByComparingTo("7650.00");
        ArgumentCaptor<ApprovalRequestedEvent> approval = ArgumentCaptor.forClass(ApprovalRequestedEvent.class);
        verify(events).publish(eq(KafkaTopics.APPROVAL_REQUESTED), approval.capture());
        assertThat(approval.getValue().getKind()).isEqualTo("DISCOUNT");
        assertThat(approval.getValue().getDescription()).contains("25 % off the bill").contains("Complaint about service");
        // A second request for the same thing waits for the first.
        assertThatThrownBy(() -> saleRequests.discount(o.getId(), new Bill.DiscountRequest(null, n("30"), null, null, "Again")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already waiting");

        asManager();
        saleRequests.decided(asked.getApprovalId(), "APPROVED", "ada", "Ada Manager", null);
        assertThat(asked.getStatus()).isEqualTo(SaleRequest.APPROVED);
        assertThat(o.getTotal()).isEqualByComparingTo("6375.00");
        assertThat(o.getDiscountByName()).isEqualTo("Chika Cashier");
        assertThat(o.getDiscountApprovedByName()).isEqualTo("Ada Manager");
    }

    @Test
    void aWaiterAlwaysAsksAndADeclineChangesNothing() {
        asWaiter();
        Order o = tab("lager", "wine");
        String lager = storedLines.get(0).getId();
        SaleRequest asked = saleRequests.discount(o.getId(), new Bill.DiscountRequest(lager, n("5"), null, null, "Birthday"));
        assertThat(asked).isNotNull();
        saleRequests.decided(asked.getApprovalId(), "DECLINED", "ada", "Ada Manager", "Not today");
        assertThat(asked.getStatus()).isEqualTo(SaleRequest.DECLINED);
        assertThat(asked.getDecisionNote()).isEqualTo("Not today");
        assertThat(o.getTotal()).isEqualByComparingTo("8500.00");
    }

    @Test
    void aComplimentaryItemNeedsAManagerAndAReason() {
        asBar();
        Order o = tab("lager", "wine");
        String lager = storedLines.get(0).getId();
        assertThatThrownBy(() -> saleRequests.discount(o.getId(), new Bill.DiscountRequest(lager, null, null, true, " ")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("reason");
        SaleRequest asked = saleRequests.discount(o.getId(), new Bill.DiscountRequest(lager, null, null, true, "Spilled drink"));
        assertThat(asked.getKind()).isEqualTo(SaleRequest.Kind.COMP_LINE);
        asManager();
        // The manager gives one directly.
        assertThat(saleRequests.discount(o.getId(), new Bill.DiscountRequest(lager, null, null, true, "Spilled drink"))).isNull();
        assertThat(storedLines.get(0).isComplimentary()).isTrue();
        assertThat(storedLines.get(0).getDiscountReason()).isEqualTo("Spilled drink");
        assertThat(o.getTotal()).isEqualByComparingTo("7000.00");
        // Removing it puts the price back.
        discounts.remove(o.getId(), lager);
        assertThat(o.getTotal()).isEqualByComparingTo("8500.00");
        assertThat(storedLines.get(0).isComplimentary()).isFalse();
    }

    @Test
    void aVoidOfSomethingSentIsAskedForAndApprovedInTheRequestersName() {
        asWaiter();
        Order o = tab("lager", "wine");
        String wine = storedLines.get(1).getId();
        // Not sent yet: the waiter takes it off without asking.
        assertThatThrownBy(() -> saleRequests.requestVoid(o.getId(), new Bill.VoidRequest(wine, "Changed mind")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("yourself");
        orderService.send(o.getId());
        assertThatThrownBy(() -> orderService.cancelLine(o.getId(), wine, "Changed mind")).isInstanceOf(ForbiddenException.class);
        SaleRequest asked = saleRequests.requestVoid(o.getId(), new Bill.VoidRequest(wine, "Wrong bottle opened"));
        assertThat(asked.getKind()).isEqualTo(SaleRequest.Kind.VOID_LINE);
        assertThat(asked.getWorth()).isEqualByComparingTo("7000.00");
        asManager();
        saleRequests.decided(asked.getApprovalId(), "APPROVED", "ada", "Ada Manager", null);
        assertThat(storedLines.get(1).getStatus()).isEqualTo(OrderLine.Status.CANCELLED);
        assertThat(storedLines.get(1).getCancelledByName()).isEqualTo("Wale Waiter");
        assertThat(storedLines.get(1).getCancelReason()).isEqualTo("Wrong bottle opened · approved by Ada Manager");
        assertThat(o.getTotal()).isEqualByComparingTo("1500.00");
    }

    @Test
    void voidingASplitSaleUndoesTheSplitButNotOnceAPartIsPaid() {
        asBar();
        Order o = tab("lager", "wine");
        orderService.send(o.getId());
        splitService.split(o.getId(), new Bill.SplitRequest("EQUAL", 2, null, null));
        SaleRequest asked = saleRequests.requestVoid(o.getId(), new Bill.VoidRequest(null, "Guests walked out"));
        asManager();
        saleRequests.decided(asked.getApprovalId(), "APPROVED", "ada", "Ada Manager", null);
        assertThat(o.getStatus()).isEqualTo(Order.Status.CANCELLED);
        assertThat(storedSplits.get(0).getStatus()).isEqualTo(OrderSplit.UNDONE);

        // Another sale, split, one part paid: the void is refused when asked, and an approval that arrives late changes nothing.
        storedLines.clear();
        asBar();
        Order second = tab("lager", "wine");
        orderService.send(second.getId());
        SaleRequest early = saleRequests.requestVoid(second.getId(), new Bill.VoidRequest(null, "Guests walked out"));
        splitService.split(second.getId(), new Bill.SplitRequest("EQUAL", 2, null, null));
        OrderSplitPart first = partsOf(second).get(0);
        orderService.applyPayment(second.getId(), "pay-9", "CASH", first.getAmount(), null, first.getId(), null);
        asManager();
        saleRequests.decided(early.getApprovalId(), "APPROVED", "ada", "Ada Manager", null);
        assertThat(early.getStatus()).isEqualTo(SaleRequest.NOT_APPLIED);
        assertThat(early.getDecisionNote()).contains("already paid");
        assertThat(second.getStatus()).isNotEqualTo(Order.Status.CANCELLED);
    }

    // ---- outlets a person works ----------------------------------------------------------------------

    @Test
    void someoneKeptToOtherOutletsIsRefusedHere() {
        assigned.add(StaffOutlet.builder().userId("wale").outletId("terrace").build());
        asWaiter();
        assertThatThrownBy(() -> tab("lager")).isInstanceOf(ForbiddenException.class).hasMessageContaining("aren't assigned to Main Bar");
        assigned.add(StaffOutlet.builder().userId("wale").outletId("bar").build());
        Order o = tab("lager");
        assertThat(o.getOpenedBy()).isEqualTo("wale");
        // payment-service asks for the payer too.
        assigned.add(StaffOutlet.builder().userId("chika").outletId("terrace").build());
        assertThat(orderService.due(o.getId(), null, "chika").refusal()).contains("aren't assigned to Main Bar");
        assertThat(orderService.due(o.getId(), null, "wale").refusal()).isNull();
        // A manager is never narrowed.
        assigned.add(StaffOutlet.builder().userId("ada").outletId("terrace").build());
        asManager();
        orderService.addLine(o.getId(), new OrderRequest.LineRequest("lager", 1, null, null));
    }
}
