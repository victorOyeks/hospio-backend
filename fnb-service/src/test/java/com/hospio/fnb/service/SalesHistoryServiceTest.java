package com.hospio.fnb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.fnb.client.OutletSettings;
import com.hospio.fnb.dto.SaleDetail;
import com.hospio.fnb.dto.SalesHistory;
import com.hospio.fnb.entity.Order;
import com.hospio.fnb.entity.OrderLine;
import com.hospio.fnb.entity.Outlet;
import com.hospio.fnb.repository.MenuItemRepository;
import com.hospio.fnb.repository.OrderLineRepository;
import com.hospio.fnb.repository.OrderPaymentRepository;
import com.hospio.fnb.repository.OrderRepository;
import com.hospio.fnb.repository.OutletRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who sees which outlets, Edit staff starting on their own sales, export rights, and what the detail panel offers. */
class SalesHistoryServiceTest {

    private final OutletRepository outlets = mock(OutletRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderLineRepository lines = mock(OrderLineRepository.class);
    private final OrderPaymentRepository payments = mock(OrderPaymentRepository.class);
    private final MenuItemRepository items = mock(MenuItemRepository.class);
    private final OutletSettings settings = mock(OutletSettings.class);
    private final SalesHistoryQuery query = mock(SalesHistoryQuery.class);
    private final SalesHistoryService service = new SalesHistoryService(outlets, orders, lines, payments, items, settings, query);
    private final Outlet bar = Outlet.builder().name("Bar").kind(Outlet.Kind.BAR).build();
    private final Outlet kitchen = Outlet.builder().name("Restaurant").kind(Outlet.Kind.KITCHEN).build();

    @BeforeEach
    void setUp() {
        bar.setId("bar");
        kitchen.setId("kit");
        when(outlets.findByHotelIdOrderByName("h1")).thenReturn(List.of(bar, kitchen));
        when(query.totals(any())).thenReturn(new SalesHistoryQuery.Money3(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO));
        when(query.earlierUnpaid(any())).thenReturn(new SalesHistoryQuery.Earlier(0, BigDecimal.ZERO, null));
        when(query.pageIds(any(), anyInt(), anyInt())).thenReturn(List.of());
        when(settings.get(any())).thenReturn(OutletSettings.Settings.defaults());
        when(items.findAllById(any())).thenReturn(List.of());
        when(payments.findByOrderIdIn(any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private void signIn(String id, Map<Role, AccessLevel> roles) {
        HospioUserDetails user = new HospioUserDetails(id, "x@x.ng", "Segun Ajayi", "b1", List.of("h1"), roles, JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        TenantContext.set(Tenant.fromUser(user, "h1"));
    }

    private static SalesHistoryService.Params params(String outletId, Boolean mine) {
        return new SalesHistoryService.Params(LocalDate.of(2026, 11, 9), null, outletId, null, null, null, null, mine, null, null, null);
    }

    private SalesHistoryQuery.Filter lastFilter() {
        ArgumentCaptor<SalesHistoryQuery.Filter> f = ArgumentCaptor.forClass(SalesHistoryQuery.Filter.class);
        verify(query, org.mockito.Mockito.atLeastOnce()).totals(f.capture());
        return f.getValue();
    }

    @Test
    void barStaffSeeOnlyTheBarAndCannotAskForTheKitchen() {
        signIn("u1", Map.of(Role.BAR, AccessLevel.FULL));
        SalesHistory h = service.history(params(null, null));
        assertThat(h.outletChoices()).extracting(SalesHistory.OutletRef::id).containsExactly("bar");
        assertThat(lastFilter().outletIds()).containsExactly("bar");
        assertThatThrownBy(() -> service.history(params("kit", null))).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void editStaffStartOnTheirOwnSalesMayWidenAndCannotExport() {
        signIn("u1", Map.of(Role.BAR, AccessLevel.EDIT));
        SalesHistory h = service.history(params(null, null));
        assertThat(h.viewer().mine()).isTrue();
        assertThat(h.viewer().canExport()).isFalse();
        assertThat(h.viewer().actsOnOthers()).isFalse();
        assertThat(lastFilter().attendant()).isEqualTo("u1");

        SalesHistory wide = service.history(params(null, false));
        assertThat(wide.viewer().mine()).isFalse();
        assertThat(lastFilter().attendant()).isNull();
        assertThatThrownBy(() -> service.export(params(null, false))).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void fullAccessSeesTheWholeOutletAndExports() {
        signIn("u1", Map.of(Role.BAR, AccessLevel.FULL));
        SalesHistory h = service.history(params(null, null));
        assertThat(h.viewer().mine()).isFalse();
        assertThat(h.viewer().canExport()).isTrue();
        assertThat(service.export(params(null, null)).csv()).startsWith("Number,Date,Time,Outlet");
    }

    @Test
    void financeSeesEveryOutletAndExportsButActsOnNothing() {
        signIn("f1", Map.of(Role.FINANCE, AccessLevel.FULL));
        SalesHistory h = service.history(params(null, null));
        assertThat(h.outletChoices()).hasSize(2);
        assertThat(h.viewer().management()).isTrue();
        assertThat(h.viewer().canExport()).isTrue();
        assertThat(h.viewer().actsOnOthers()).isFalse();
    }

    @Test
    void theDeskNarrowsToItsOutletsAndBadInputIsRefused() {
        signIn("m1", Map.of(Role.MANAGER, AccessLevel.FULL));
        service.history(new SalesHistoryService.Params(null, null, null, "KITCHEN", null, null, null, null, null, null, null));
        assertThat(lastFilter().outletIds()).containsExactly("kit");
        assertThatThrownBy(() -> service.history(new SalesHistoryService.Params(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 8), null, null, null,
                null, null, null, null, null, null))).isInstanceOf(BadRequestException.class).hasMessageContaining("before the start");
        assertThatThrownBy(() -> service.history(new SalesHistoryService.Params(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 11, 8), null, null, null,
                null, null, null, null, null, null))).isInstanceOf(BadRequestException.class).hasMessageContaining("93 days");
        assertThatThrownBy(() -> service.history(new SalesHistoryService.Params(null, null, null, null, null, "BITCOIN", null, null, null, null,
                null))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.history(new SalesHistoryService.Params(null, null, null, null, null, null, null, null, null, 0, 500)))
                .isInstanceOf(BadRequestException.class);
    }

    private Order unpaidSale(String openedBy, Order.Status status) {
        Order o = Order.builder().outletId("bar").number("B-0001").kind(Order.Kind.TAKEAWAY).status(status).total(new BigDecimal("4500.00"))
                .subtotal(new BigDecimal("4500.00")).openedBy(openedBy).openedByName("Ngozi Eze").build();
        o.setId("o1");
        o.setHotelId("h1");
        when(orders.findById("o1")).thenReturn(Optional.of(o));
        OrderLine l = OrderLine.builder().orderId("o1").menuItemId("m").name("Coca-Cola").quantity(3).unitPrice(new BigDecimal("1500.00"))
                .status(OrderLine.Status.NEW).build();
        when(lines.findByOrderIdOrderByCreatedAt("o1")).thenReturn(List.of(l));
        return o;
    }

    @Test
    void editStaffActOnTheirOwnUnpaidSaleButOnlyLookAtSomeoneElses() {
        signIn("u1", Map.of(Role.BAR, AccessLevel.EDIT));
        unpaidSale("u1", Order.Status.OPEN);
        SaleDetail mine = service.detail("o1");
        assertThat(mine.can().pay()).isTrue();
        assertThat(mine.can().chargeToRoom()).isTrue();
        assertThat(mine.can().cancel()).isTrue();
        assertThat(mine.sale().status()).isEqualTo("TO_PAY");
        assertThat(mine.sale().items()).isEqualTo("3 Coca-Cola");
        assertThat(mine.balance()).isEqualByComparingTo("4500.00");

        unpaidSale("u2", Order.Status.OPEN);
        SaleDetail theirs = service.detail("o1");
        assertThat(theirs.can().pay()).isFalse();
        assertThat(theirs.can().cancel()).isFalse();
        assertThat(theirs.can().note()).contains("Ngozi Eze");

        signIn("u3", Map.of(Role.BAR, AccessLevel.FULL));
        assertThat(service.detail("o1").can().pay()).isTrue();
    }

    @Test
    void kitchenStaffCannotOpenABarSaleAndFinanceOnlyLooks() {
        unpaidSale("u1", Order.Status.OPEN);
        signIn("k1", Map.of(Role.KITCHEN, AccessLevel.FULL));
        assertThatThrownBy(() -> service.detail("o1")).isInstanceOf(ForbiddenException.class);
        signIn("f1", Map.of(Role.FINANCE, AccessLevel.FULL));
        SaleDetail d = service.detail("o1");
        assertThat(d.can().pay()).isFalse();
        assertThat(d.can().note()).isEqualTo("View only");
    }

    @Test
    void aPartPaidSaleCollectsTheBalanceButCannotBeCancelledAndASentOneNeedsAManager() {
        signIn("u1", Map.of(Role.BAR, AccessLevel.FULL));
        Order o = unpaidSale("u1", Order.Status.OPEN);
        o.setPaidAmount(new BigDecimal("1000.00"));
        SaleDetail d = service.detail("o1");
        assertThat(d.balance()).isEqualByComparingTo("3500.00");
        assertThat(d.sale().status()).isEqualTo("PART_PAID");
        assertThat(d.can().pay()).isTrue();
        assertThat(d.can().cancel()).isFalse();

        o.setPaidAmount(BigDecimal.ZERO);
        o.setStatus(Order.Status.SENT);
        assertThat(service.detail("o1").can().cancel()).isFalse();
        signIn("m1", Map.of(Role.MANAGER, AccessLevel.FULL));
        assertThat(service.detail("o1").can().cancel()).isTrue();

        o.setStatus(Order.Status.CANCELLED);
        SaleDetail cancelled = service.detail("o1");
        assertThat(cancelled.can().pay()).isFalse();
        assertThat(cancelled.can().cancel()).isFalse();
    }

    @Test
    void csvCellsAreQuotedAndFormulasDefused() {
        assertThat(SalesHistoryService.cell("3 Coca-Cola, 2 Orange juice")).isEqualTo("\"3 Coca-Cola, 2 Orange juice\"");
        assertThat(SalesHistoryService.cell("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(SalesHistoryService.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(SalesHistoryService.cell(null)).isEmpty();
    }
}
