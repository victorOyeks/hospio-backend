package com.hospio.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.finance.client.HotelSettings;
import com.hospio.finance.client.PeerServices;
import com.hospio.finance.dto.FinanceRequests;
import com.hospio.finance.dto.NightAuditReport;
import com.hospio.finance.dto.OutletSalesSummary;
import com.hospio.finance.entity.NightAudit;
import com.hospio.finance.entity.Till;
import com.hospio.finance.repository.ExpenseRepository;
import com.hospio.finance.repository.NightAuditRepository;
import com.hospio.finance.repository.TillRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Closing the day refuses while a till is open, while arrivals are unresolved (unless marking no-shows), for a future day and for staff. */
class NightAuditServiceTest {

    private static final LocalDate DAY = LocalDate.now(MoneyService.LAGOS).minusDays(1);
    /** Like Spring Boot's: java.time as ISO text. */
    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    private final NightAuditRepository audits = mock(NightAuditRepository.class);
    private final TillRepository tills = mock(TillRepository.class);
    private final ExpenseRepository expenses = mock(ExpenseRepository.class);
    private final MoneyService money = mock(MoneyService.class);
    private final PeerServices peers = mock(PeerServices.class);
    private final HotelSettings settings = mock(HotelSettings.class);
    private final Notifier notifier = mock(Notifier.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final NightAuditService service = new NightAuditService(audits, tills, expenses, money, peers, settings, notifier, events, MAPPER);

    private void signIn(Role role, AccessLevel level) {
        HospioUserDetails user = new HospioUserDetails("u1", "x@x.ng", "Tunde", "b1", List.of("h1"), Map.of(role, level), JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        TenantContext.set(Tenant.fromUser(user, "h1"));
        when(audits.findByHotelIdAndBusinessDate(any(), any())).thenReturn(Optional.of(NightAudit.builder().businessDate(DAY).build()));
        when(peers.openBalances("h1")).thenReturn(List.of());
        when(peers.roomCount("h1")).thenReturn(120);
    }

    private void day(int arrivalsPending) {
        when(peers.day("h1", DAY)).thenReturn(new PeerServices.Day(3, arrivalsPending, 0, 0, 2, 0, 2, List.of(), List.of(), List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void anOpenTillStopsTheClose() {
        signIn(Role.MANAGER, AccessLevel.FULL);
        day(0);
        when(tills.findByHotelIdAndStatusOrderByOpenedAt("h1", Till.Status.OPEN)).thenReturn(List.of(Till.builder().role("FRONT_OFFICE").build()));

        assertThatThrownBy(() -> service.close(DAY, null)).isInstanceOf(BadRequestException.class).hasMessageContaining("still open");
    }

    @Test
    void unresolvedArrivalsStopTheCloseUnlessMarkedAsNoShows() {
        signIn(Role.MANAGER, AccessLevel.FULL);
        day(2);
        when(tills.findByHotelIdAndStatusOrderByOpenedAt("h1", Till.Status.OPEN)).thenReturn(List.of());
        when(settings.arrivalsResolvedFirst("h1")).thenReturn(true);

        assertThatThrownBy(() -> service.close(DAY, new FinanceRequests.CloseDayRequest(null, null, null, false)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("haven't checked in");
        verify(peers, never()).markNoShows(any(), any());
    }

    @Test
    void aDayThatHasNotHappenedCannotBeClosed() {
        signIn(Role.MANAGER, AccessLevel.FULL);

        assertThatThrownBy(() -> service.close(LocalDate.now(MoneyService.LAGOS).plusDays(1), null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("hasn't happened");
    }

    @Test
    void frontOfficeCannotCloseTheDay() {
        signIn(Role.FRONT_OFFICE, AccessLevel.FULL);

        assertThatThrownBy(() -> service.close(DAY, null)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void openTabsAtTheOutletsStopTheCloseUnlessAManagerCarriesThem() {
        signIn(Role.MANAGER, AccessLevel.FULL);
        day(0);
        when(tills.findByHotelIdAndStatusOrderByOpenedAt("h1", Till.Status.OPEN)).thenReturn(List.of());
        when(peers.openOrders("h1", DAY)).thenReturn(Optional.of(new PeerServices.OpenOrders(3, 0, List.of(new PeerServices.OutletOpen("bar", "Bar", 3, 0)))));

        assertThatThrownBy(() -> service.close(DAY, null)).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("3 open tabs at the Bar").hasMessageContaining("carrying them over");
        assertThatThrownBy(() -> service.close(DAY, new FinanceRequests.CloseDayRequest(null, null, null, false, false))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void aFinanceUserWithoutManagerRightsCannotCarryOpenTabs() {
        signIn(Role.FINANCE, AccessLevel.FULL);
        day(0);
        when(tills.findByHotelIdAndStatusOrderByOpenedAt("h1", Till.Status.OPEN)).thenReturn(List.of());
        when(peers.openOrders("h1", DAY)).thenReturn(Optional.of(new PeerServices.OpenOrders(0, 2, List.of(new PeerServices.OutletOpen("kit", "Restaurant", 0, 2)))));

        assertThatThrownBy(() -> service.close(DAY, new FinanceRequests.CloseDayRequest(null, null, null, false, true))).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void anUnreachableOutletServiceIsNotGuessedAt() {
        signIn(Role.MANAGER, AccessLevel.FULL);
        day(0);
        when(tills.findByHotelIdAndStatusOrderByOpenedAt("h1", Till.Status.OPEN)).thenReturn(List.of());
        when(peers.openOrders("h1", DAY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.close(DAY, null)).isInstanceOf(BadRequestException.class).hasMessageContaining("can't be checked");
    }

    @Test
    void aManagerCarryingOpenTabsClosesTheDayAndTheReportListsEachOne() throws Exception {
        signIn(Role.MANAGER, AccessLevel.FULL);
        day(0);
        when(tills.findByHotelIdAndStatusOrderByOpenedAt("h1", Till.Status.OPEN)).thenReturn(List.of());
        when(peers.openOrders("h1", DAY)).thenReturn(Optional.of(new PeerServices.OpenOrders(1, 0, List.of(new PeerServices.OutletOpen("bar", "Bar", 1, 0,
                List.of(new PeerServices.OpenOrder("B-0004", "TAB", "B2", null, new java.math.BigDecimal("12000"))))))));
        com.hospio.finance.dto.MoneyToday today = mock(com.hospio.finance.dto.MoneyToday.class);
        when(today.revenue()).thenReturn(java.math.BigDecimal.ZERO);
        when(today.byMethod()).thenReturn(Map.of());
        when(today.byRole()).thenReturn(Map.of());
        when(today.unattachedCash()).thenReturn(java.math.BigDecimal.ZERO);
        when(money.today(DAY)).thenReturn(today);
        when(audits.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.close(DAY, new FinanceRequests.CloseDayRequest(null, null, null, false, true));

        var captor = org.mockito.ArgumentCaptor.forClass(NightAudit.class);
        verify(audits).save(captor.capture());
        NightAuditReport report = MAPPER.readValue(captor.getValue().getReport(), NightAuditReport.class);
        org.assertj.core.api.Assertions.assertThat(report.carriedOpenTabs()).isTrue();
        NightAuditReport.CarriedOrder order = report.openOrdersCarried().get(0).orders().get(0);
        org.assertj.core.api.Assertions.assertThat(order.number()).isEqualTo("B-0004");
        org.assertj.core.api.Assertions.assertThat(order.tableNo()).isEqualTo("B2");
        org.assertj.core.api.Assertions.assertThat(order.total()).isEqualByComparingTo("12000");
    }

    /** The stored report keeps the keys, order and nesting the web app reads (they were built by hand before the record). */
    @Test
    @SuppressWarnings("unchecked")
    void theReportKeepsItsKeysAndNesting() throws Exception {
        signIn(Role.MANAGER, AccessLevel.FULL);
        day(0);
        when(tills.findByHotelIdAndStatusOrderByOpenedAt("h1", Till.Status.OPEN)).thenReturn(List.of());
        when(peers.openOrders("h1", DAY)).thenReturn(Optional.of(new PeerServices.OpenOrders(0, 0, List.of())));
        com.hospio.finance.dto.MoneyToday today = mock(com.hospio.finance.dto.MoneyToday.class);
        when(today.revenue()).thenReturn(new java.math.BigDecimal("50000.00"));
        when(today.byMethod()).thenReturn(Map.of("CASH", new java.math.BigDecimal("50000.00")));
        when(today.byRole()).thenReturn(Map.of());
        when(today.unattachedCash()).thenReturn(java.math.BigDecimal.ZERO);
        when(today.outletSales()).thenReturn(new OutletSalesSummary(DAY, List.of(), new OutletSalesSummary.Totals(0, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, 0, Map.of())));
        when(money.today(DAY)).thenReturn(today);
        when(audits.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.close(DAY, new FinanceRequests.CloseDayRequest(null, null, null, false, false));

        var captor = org.mockito.ArgumentCaptor.forClass(NightAudit.class);
        verify(audits).save(captor.capture());
        Map<String, Object> json = MAPPER.readValue(captor.getValue().getReport(), java.util.LinkedHashMap.class);
        assertThat(json.keySet()).containsExactly("date", "revenue", "payments", "revenueByRole", "paymentsCount", "expenses", "expensesByCategory",
                "net", "cash", "occupancy", "guests", "tills", "balancesOwing", "outletSales", "carriedOpenTabs", "closedBy");
        assertThat(json.get("date")).isEqualTo(DAY.toString());
        assertThat((Map<String, Object>) json.get("cash")).containsOnlyKeys("expected", "counted", "variance", "unattached").containsEntry("counted", null);
        assertThat((Map<String, Object>) json.get("occupancy")).containsOnlyKeys("roomsOccupied", "rooms", "pct").containsEntry("rooms", 120);
        assertThat((Map<String, Object>) json.get("guests")).containsOnlyKeys("arrivals", "departures", "inHouse", "noShows", "markedNoShow")
                .containsEntry("arrivals", 3);
        assertThat((Map<String, Object>) json.get("outletSales")).containsOnlyKeys("date", "outlets", "totals").containsEntry("date", DAY.toString());
        assertThat(json.get("carriedOpenTabs")).isEqualTo(false);
    }

    /** A report stored before the typed record (hand-built JSON, numbers in exponent form) still gives the closed day's figures. */
    @Test
    void aReportStoredBeforeTheRecordStillReads() {
        signIn(Role.MANAGER, AccessLevel.FULL);
        String stored = "{\"date\":\"2026-11-01\",\"revenue\":1.2E+5,\"payments\":{\"CASH\":1.2E+5},\"revenueByRole\":{},\"paymentsCount\":4,"
                + "\"expenses\":0,\"expensesByCategory\":{},\"net\":1.2E+5,\"cash\":{\"expected\":0,\"counted\":null,\"variance\":null,\"unattached\":0},"
                + "\"occupancy\":{\"roomsOccupied\":7,\"rooms\":10,\"pct\":70.0},\"guests\":{\"arrivals\":2,\"departures\":1,\"inHouse\":7,\"noShows\":1,"
                + "\"markedNoShow\":0},\"tills\":[{\"role\":\"FRONT_OFFICE\",\"expected\":0,\"counted\":0,\"variance\":0}],\"balancesOwing\":0,"
                + "\"outletSales\":{\"date\":\"2026-11-01\",\"outlets\":[],\"totals\":{\"orders\":0,\"sales\":0,\"paidAtOutlet\":0,\"chargedToRooms\":0,"
                + "\"cancelled\":0,\"byMethod\":{}},\"extra\":1},\"carriedOpenTabs\":false,\"closedBy\":\"Tunde\",\"someOldKey\":[1,2]}";
        NightAudit closed = NightAudit.builder().businessDate(DAY).status(NightAudit.Status.CLOSED).report(stored).build();
        when(audits.findByHotelIdAndBusinessDate(any(), any())).thenReturn(Optional.of(closed));

        var view = service.view(DAY);

        assertThat(view.arrivals()).isEqualTo(2);
        assertThat(view.departures()).isEqualTo(1);
        assertThat(view.inHouse()).isEqualTo(7);
        assertThat(view.noShows()).isEqualTo(1);
        assertThat(view.roomsOccupied()).isEqualTo(7);
        assertThat(view.rooms()).isEqualTo(10);
        assertThat(view.report()).isEqualTo(stored);
        assertThat(service.read(stored).revenue()).isEqualByComparingTo("120000");
        assertThat(service.read("not json").guestsOrEmpty().arrivals()).isZero();
        assertThat(service.read(null).occupancyOrEmpty().rooms()).isZero();
    }
}
