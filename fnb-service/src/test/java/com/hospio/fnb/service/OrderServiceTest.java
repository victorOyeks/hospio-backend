package com.hospio.fnb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.OrderReadyEvent;
import com.hospio.common.kafka.events.OrderSettledEvent;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.fnb.client.FolioClient;
import com.hospio.fnb.client.OutletSettings;
import com.hospio.fnb.client.PropertyClient;
import com.hospio.fnb.client.ReservationClient;
import com.hospio.fnb.dto.OrderRequest;
import com.hospio.fnb.dto.OrderResponse;
import com.hospio.fnb.entity.HappyHour;
import com.hospio.fnb.entity.MenuItem;
import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.OrderLine;
import com.hospio.fnb.entity.OrderPayment;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.repository.DiningTableRepository;
import com.hospio.fnb.repository.HappyHourRepository;
import com.hospio.fnb.repository.MenuItemRepository;
import com.hospio.fnb.repository.OrderLineRepository;
import com.hospio.fnb.repository.OrderPaymentRepository;
import com.hospio.fnb.repository.OrderRepository;
import com.hospio.fnb.repository.OutletRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
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

/** Tab limit, happy-hour pricing, charge to room checks, payments closing orders once, line status roll-up and who may do what. */
class OrderServiceTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderLineRepository lines = mock(OrderLineRepository.class);
    private final OrderPaymentRepository orderPayments = mock(OrderPaymentRepository.class);
    private final MenuItemRepository items = mock(MenuItemRepository.class);
    private final OutletRepository outlets = mock(OutletRepository.class);
    private final HappyHourRepository happyHours = mock(HappyHourRepository.class);
    private final ReservationClient reservations = mock(ReservationClient.class);
    private final FolioClient folios = mock(FolioClient.class);
    private final PropertyClient properties = mock(PropertyClient.class);
    private final OutletSettings settings = mock(OutletSettings.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final OrderService service = new OrderService(orders, lines, orderPayments, mock(DiningTableRepository.class), items, outlets, happyHours,
            reservations, folios, properties, settings, events);

    private final List<OrderLine> storedLines = new ArrayList<>();
    private final List<OrderPayment> storedPayments = new ArrayList<>();
    private final Outlet bar = Outlet.builder().name("Bar").kind(Outlet.Kind.BAR).storeId("store-bar").build();
    private final MenuItem lager = MenuItem.builder().outletId("bar").category("Beer").name("Star lager").price(new BigDecimal("1500.00"))
            .recipeJson("[{\"stockItemId\":\"item-lager\",\"quantity\":1}]").build();
    private OutletSettings.Settings config = new OutletSettings.Settings(true, true, new BigDecimal("5000"), true, new BigDecimal("7.5"));

    private static final ZonedDateTime MONDAY_6PM = LocalDate.of(2026, 11, 9).atTime(18, 0).atZone(com.hospio.fnb.rules.Days.LAGOS);

    @BeforeEach
    void setUp() {
        bar.setId("bar");
        lager.setId("lager");
        when(outlets.findById("bar")).thenReturn(Optional.of(bar));
        when(outlets.lockById("bar")).thenReturn(Optional.of(bar));
        when(items.findById("lager")).thenReturn(Optional.of(lager));
        when(settings.get(any())).thenAnswer(inv -> config);
        when(happyHours.findByOutletIdAndActiveTrue("bar")).thenReturn(List.of());
        when(orders.save(any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId(UUID.randomUUID().toString());
            }
            o.setHotelId("h1");
            return o;
        });
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
        service.useClock(() -> MONDAY_6PM);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private void signIn(Map<Role, AccessLevel> roles) {
        HospioUserDetails user = new HospioUserDetails("u1", "x@x.ng", "Segun", "b1", List.of("h1"), roles, JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        TenantContext.set(Tenant.fromUser(user, "h1"));
    }

    private OrderRequest tab(String... itemIds) {
        List<OrderRequest.LineRequest> l = new ArrayList<>();
        for (String id : itemIds) {
            l.add(new OrderRequest.LineRequest(id, 1, null, null));
        }
        return new OrderRequest("bar", Order.Kind.TAB, null, null, "Mr Ade", null, l);
    }

    private Order savedOrder(Order.Kind kind, String total) {
        Order o = Order.builder().outletId("bar").number("B-0001").kind(kind).total(new BigDecimal(total)).openedBy("u1").build();
        o.setId("order-1");
        o.setHotelId("h1");
        when(orders.findById("order-1")).thenReturn(Optional.of(o));
        return o;
    }

    private OrderLine line(OrderLine.Status status) {
        OrderLine l = OrderLine.builder().orderId("order-1").menuItemId("lager").name("Star lager").quantity(1).unitPrice(new BigDecimal("1500.00"))
                .taxRate(new BigDecimal("7.5")).status(status).build();
        l.setId("line-" + (storedLines.size() + 1));
        storedLines.add(l);
        return l;
    }

    // ---- pricing -----------------------------------------------------------------------------------------------

    @Test
    void anOrderIsPricedWithTheHotelsVatExtractedWhenPricesIncludeTax() {
        signIn(Map.of(Role.BAR, AccessLevel.EDIT));

        OrderResponse r = service.create(new OrderRequest("bar", Order.Kind.TAKEAWAY, null, null, null, null, List.of(new OrderRequest.LineRequest("lager", 2, null, null))));

        assertThat(r.total()).isEqualByComparingTo("3000.00");
        assertThat(r.tax()).isEqualByComparingTo("209.30");
        assertThat(r.subtotal()).isEqualByComparingTo("2790.70");
        assertThat(r.number()).startsWith("B-");
    }

    @Test
    void anOrderIsPricedWithTaxOnTopWhenPricesExcludeIt() {
        config = new OutletSettings.Settings(true, true, new BigDecimal("5000"), false, new BigDecimal("10"));
        signIn(Map.of(Role.BAR, AccessLevel.EDIT));

        OrderResponse r = service.create(new OrderRequest("bar", Order.Kind.TAKEAWAY, null, null, null, null, List.of(new OrderRequest.LineRequest("lager", 2, null, null))));

        assertThat(r.subtotal()).isEqualByComparingTo("3000.00");
        assertThat(r.tax()).isEqualByComparingTo("300.00");
        assertThat(r.total()).isEqualByComparingTo("3300.00");
    }

    @Test
    void happyHourDiscountsLinesAddedDuringItAndTheLineShowsIt() {
        HappyHour hh = HappyHour.builder().name("Happy hour").fromTime(LocalTime.of(17, 0)).toTime(LocalTime.of(19, 0)).days("MON,TUE,WED,THU,FRI")
                .discountPct(new BigDecimal("20")).build();
        when(happyHours.findByOutletIdAndActiveTrue("bar")).thenReturn(List.of(hh));
        signIn(Map.of(Role.BAR, AccessLevel.EDIT));

        OrderResponse r = service.create(new OrderRequest("bar", Order.Kind.TAKEAWAY, null, null, null, null, List.of(new OrderRequest.LineRequest("lager", 2, null, null))));

        assertThat(r.lines().get(0).getDiscount()).isEqualByComparingTo("600.00");
        assertThat(r.discount()).isEqualByComparingTo("600.00");
        assertThat(r.total()).isEqualByComparingTo("2400.00");

        service.useClock(() -> LocalDate.of(2026, 11, 9).atTime(19, 30).atZone(com.hospio.fnb.rules.Days.LAGOS));
        storedLines.clear();
        OrderResponse after = service.create(new OrderRequest("bar", Order.Kind.TAKEAWAY, null, null, null, null, List.of(new OrderRequest.LineRequest("lager", 2, null, null))));
        assertThat(after.total()).isEqualByComparingTo("3000.00");
    }

    // ---- tabs ------------------------------------------------------------------------------------------------

    @Test
    void aTabRefusesADrinkThatWouldPassTheLimitAndTellsTheManager() {
        signIn(Map.of(Role.BAR, AccessLevel.EDIT));
        Order tab = savedOrder(Order.Kind.TAB, "4500.00");
        line(OrderLine.Status.SENT);
        line(OrderLine.Status.SENT);
        line(OrderLine.Status.SENT);

        assertThatThrownBy(() -> service.addLine("order-1", new OrderRequest.LineRequest("lager", 1, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessage("Tab limit ₦5,000 reached · settle or ask the manager");
        verify(events).publish(eq(KafkaTopics.TAB_LIMIT_REACHED), any());
        assertThat(storedLines).hasSize(3);
    }

    @Test
    void aTabTakesADrinkUnderTheLimitAndAManagerMayGoOver() {
        signIn(Map.of(Role.BAR, AccessLevel.EDIT));
        savedOrder(Order.Kind.TAB, "1500.00");
        line(OrderLine.Status.SENT);

        OrderResponse r = service.addLine("order-1", new OrderRequest.LineRequest("lager", 1, null, null));
        assertThat(r.lines()).hasSize(2);

        line(OrderLine.Status.SENT);
        line(OrderLine.Status.SENT);
        signIn(Map.of(Role.MANAGER, AccessLevel.FULL));
        assertThat(service.addLine("order-1", new OrderRequest.LineRequest("lager", 1, null, null)).lines()).hasSize(5);
    }

    @Test
    void tabsSwitchedOffRefusesANewTab() {
        config = new OutletSettings.Settings(true, false, new BigDecimal("5000"), true, new BigDecimal("7.5"));
        signIn(Map.of(Role.BAR, AccessLevel.EDIT));

        assertThatThrownBy(() -> service.create(tab("lager"))).isInstanceOf(BadRequestException.class).hasMessageContaining("Tabs are switched off");
        verify(orders, never()).save(any());
    }

    @Test
    void aQuantityBelowOneIsRefusedNotTurnedIntoOne() {
        signIn(Map.of(Role.BAR, AccessLevel.EDIT));

        for (int q : new int[] {0, -2}) {
            assertThatThrownBy(() -> service.create(new OrderRequest("bar", Order.Kind.TAKEAWAY, null, null, null, null, List.of(new OrderRequest.LineRequest("lager", q, null, null)))))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("at least 1");
        }
        verify(orders, never()).save(any());
    }

    // ---- who may do what -------------------------------------------------------------------------------------

    @Test
    void theKitchenCannotTakeAnOrderAtTheBarButFrontOfficeCanRoomService() {
        signIn(Map.of(Role.KITCHEN, AccessLevel.EDIT));
        assertThatThrownBy(() -> service.create(tab("lager"))).isInstanceOf(ForbiddenException.class);

        signIn(Map.of(Role.FRONT_OFFICE, AccessLevel.EDIT));
        assertThatThrownBy(() -> service.create(tab("lager"))).isInstanceOf(ForbiddenException.class);
        when(reservations.findActiveStay("room-1")).thenReturn(Optional.of(new ReservationClient.ActiveStay("bk-1", "Ada", "204", null, null)));
        OrderResponse r = service.create(new OrderRequest("bar", null, null, "room-1", null, null, List.of(new OrderRequest.LineRequest("lager", 1, null, null))));
        assertThat(r.kind()).isEqualTo(Order.Kind.ROOM_SERVICE);
        assertThat(r.roomNumber()).isEqualTo("204");
    }

    // ---- charge to room --------------------------------------------------------------------------------------

    @Test
    void chargingToARoomIsRefusedWhenTheHotelSwitchedItOffOrNobodyIsThereOrThereIsNoOpenBill() {
        signIn(Map.of(Role.FRONT_OFFICE, AccessLevel.EDIT));
        savedOrder(Order.Kind.ROOM_SERVICE, "3000.00");
        line(OrderLine.Status.SENT);

        config = new OutletSettings.Settings(false, true, new BigDecimal("5000"), true, new BigDecimal("7.5"));
        assertThatThrownBy(() -> service.chargeToRoom("order-1", "room-1")).isInstanceOf(BadRequestException.class).hasMessageContaining("switched off");

        config = new OutletSettings.Settings(true, true, new BigDecimal("5000"), true, new BigDecimal("7.5"));
        when(reservations.findActiveStay("room-1")).thenReturn(Optional.empty());
        when(properties.roomNumber("room-1")).thenReturn("204");
        assertThatThrownBy(() -> service.chargeToRoom("order-1", "room-1")).isInstanceOf(BadRequestException.class).hasMessage("Nobody is checked in to room 204");

        when(reservations.findActiveStay("room-1")).thenReturn(Optional.of(new ReservationClient.ActiveStay("bk-1", "Ada", "204", null, null)));
        when(folios.hasOpenFolio("bk-1")).thenReturn(false);
        assertThatThrownBy(() -> service.chargeToRoom("order-1", "room-1")).isInstanceOf(BadRequestException.class).hasMessage("Room 204 has no open bill");
        verify(events, never()).publish(eq(KafkaTopics.CHARGE_POSTED), any());
    }

    @Test
    void aChargeToTheRoomSettlesTheOrderPostsTheChargeAndDrawsStock() {
        signIn(Map.of(Role.FRONT_OFFICE, AccessLevel.EDIT));
        Order o = savedOrder(Order.Kind.ROOM_SERVICE, "1500.00");
        o.setSubtotal(new BigDecimal("1395.35"));
        o.setTax(new BigDecimal("104.65"));
        o.setDiscount(BigDecimal.ZERO);
        line(OrderLine.Status.NEW);
        when(reservations.findActiveStay("room-1")).thenReturn(Optional.of(new ReservationClient.ActiveStay("bk-1", "Ada", "204", null, null)));
        when(folios.hasOpenFolio("bk-1")).thenReturn(true);

        OrderResponse r = service.chargeToRoom("order-1", "room-1");

        assertThat(r.chargedToRoom()).isTrue();
        assertThat(r.settledBy()).isEqualTo("ROOM");
        assertThat(r.status()).isEqualTo(Order.Status.SENT);
        verify(events).publish(eq(KafkaTopics.CHARGE_POSTED), any());
        ArgumentCaptor<OrderSettledEvent> settled = ArgumentCaptor.forClass(OrderSettledEvent.class);
        verify(events).publish(eq(KafkaTopics.ORDER_SETTLED), settled.capture());
        assertThat(settled.getValue().getStoreId()).isEqualTo("store-bar");
        assertThat(settled.getValue().getLines().get(0).getRecipe().get(0).getStockItemId()).isEqualTo("item-lager");
    }

    // ---- payments ----------------------------------------------------------------------------------------------

    @Test
    void aPaymentInFullClosesTheOrderOnceHoweverOftenItIsDelivered() {
        signIn(Map.of(Role.BAR, AccessLevel.EDIT));
        Order tab = savedOrder(Order.Kind.TAB, "3000.00");
        line(OrderLine.Status.SENT);

        service.applyPayment("order-1", "pay-1", "CARD", new BigDecimal("3000.00"));
        service.applyPayment("order-1", "pay-1", "CARD", new BigDecimal("3000.00"));

        assertThat(tab.isPaid()).isTrue();
        assertThat(tab.getStatus()).isEqualTo(Order.Status.CLOSED);
        assertThat(tab.getSettledBy()).isEqualTo("PAYMENT");
        assertThat(tab.getPaidAmount()).isEqualByComparingTo("3000.00");
        assertThat(storedPayments).hasSize(1);
        verify(events).publish(eq(KafkaTopics.ORDER_SETTLED), any());
    }

    @Test
    void aSplitPaymentClosesTheOrderOnlyWhenTheSecondPartLands() {
        signIn(Map.of(Role.BAR, AccessLevel.EDIT));
        Order o = savedOrder(Order.Kind.TAKEAWAY, "3000.00");
        line(OrderLine.Status.SENT);

        service.applyPayment("order-1", "pay-1", "CARD", new BigDecimal("2000.00"));
        assertThat(o.isPaid()).isFalse();
        assertThat(o.getStatus()).isEqualTo(Order.Status.OPEN);

        service.applyPayment("order-1", "pay-2", "CASH", new BigDecimal("1000.00"));
        assertThat(o.isPaid()).isTrue();
        assertThat(o.getStatus()).isEqualTo(Order.Status.CLOSED);
        assertThat(storedPayments).extracting(OrderPayment::getMethod).containsExactly("CARD", "CASH");
    }

    // ---- line status ---------------------------------------------------------------------------------------------

    @Test
    void theOrderIsReadyWhenTheLastLineIsMarkedReadyAndTheOpenerIsTold() {
        signIn(Map.of(Role.BAR, AccessLevel.EDIT));
        Order o = savedOrder(Order.Kind.DINE_IN, "3000.00");
        o.setStatus(Order.Status.SENT);
        OrderLine first = line(OrderLine.Status.SENT);
        OrderLine second = line(OrderLine.Status.SENT);

        assertThat(service.setLineStatus("order-1", first.getId(), OrderLine.Status.READY).status()).isEqualTo(Order.Status.SENT);
        verify(events, never()).publish(eq(KafkaTopics.ORDER_READY), any());

        assertThat(service.setLineStatus("order-1", second.getId(), OrderLine.Status.READY).status()).isEqualTo(Order.Status.READY);
        ArgumentCaptor<OrderReadyEvent> ready = ArgumentCaptor.forClass(OrderReadyEvent.class);
        verify(events).publish(eq(KafkaTopics.ORDER_READY), ready.capture());
        assertThat(ready.getValue().getOpenedBy()).isEqualTo("u1");

        service.setLineStatus("order-1", first.getId(), OrderLine.Status.SERVED);
        assertThat(service.setLineStatus("order-1", second.getId(), OrderLine.Status.SERVED).status()).isEqualTo(Order.Status.SERVED);
    }

    @Test
    void aLineThatWasNotSentCannotBeMarkedReady() {
        signIn(Map.of(Role.BAR, AccessLevel.EDIT));
        savedOrder(Order.Kind.DINE_IN, "1500.00");
        OrderLine l = line(OrderLine.Status.NEW);

        assertThatThrownBy(() -> service.setLineStatus("order-1", l.getId(), OrderLine.Status.READY)).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Send the ticket first");
    }

    // ---- cancelling ----------------------------------------------------------------------------------------------

    @Test
    void aSentLineNeedsAManagerOrFullAccessToCancelButAnUnsentOneDoesNot() {
        signIn(Map.of(Role.BAR, AccessLevel.EDIT));
        savedOrder(Order.Kind.DINE_IN, "3000.00");
        OrderLine sent = line(OrderLine.Status.SENT);
        OrderLine fresh = line(OrderLine.Status.NEW);

        assertThatThrownBy(() -> service.cancelLine("order-1", sent.getId(), "wrong drink")).isInstanceOf(ForbiddenException.class);
        service.cancelLine("order-1", fresh.getId(), "guest changed mind");
        assertThat(fresh.getStatus()).isEqualTo(OrderLine.Status.CANCELLED);
        assertThat(fresh.getCancelReason()).isEqualTo("guest changed mind");

        signIn(Map.of(Role.BAR, AccessLevel.FULL));
        service.cancelLine("order-1", sent.getId(), "wrong drink");
        assertThat(sent.getStatus()).isEqualTo(OrderLine.Status.CANCELLED);
    }

    @Test
    void anOrderWithSentLinesNeedsAManagerToCancelAndASettledOneCannotBeCancelled() {
        signIn(Map.of(Role.BAR, AccessLevel.EDIT));
        Order o = savedOrder(Order.Kind.DINE_IN, "1500.00");
        o.setStatus(Order.Status.SENT);
        line(OrderLine.Status.SENT);

        assertThatThrownBy(() -> service.cancel("order-1", "walked out")).isInstanceOf(ForbiddenException.class);
        signIn(Map.of(Role.MANAGER, AccessLevel.FULL));
        assertThat(service.cancel("order-1", "walked out").status()).isEqualTo(Order.Status.CANCELLED);

        Order paid = savedOrder(Order.Kind.DINE_IN, "1500.00");
        paid.setPaid(true);
        assertThatThrownBy(() -> service.cancel("order-1", "oops")).isInstanceOf(BadRequestException.class).hasMessageContaining("settled");
    }
}
