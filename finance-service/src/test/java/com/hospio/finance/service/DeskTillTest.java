package com.hospio.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.hospio.finance.entity.Till;
import com.hospio.finance.repository.BankDepositRepository;
import com.hospio.finance.repository.BudgetRepository;
import com.hospio.finance.repository.ExpenseRepository;
import com.hospio.finance.repository.RevenueEntryRepository;
import com.hospio.finance.repository.TillEntryRepository;
import com.hospio.finance.repository.TillRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Bar till and cash (20 Nov): a page reads the till of its own desk, not the first desk the person works; a till left open from an
 * earlier day says so; one person holds one till.
 */
class DeskTillTest {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final TillRepository tills = mock(TillRepository.class);
    private final MoneyService money = new MoneyService(mock(RevenueEntryRepository.class), tills, mock(TillEntryRepository.class), mock(ExpenseRepository.class),
            mock(BudgetRepository.class), mock(BankDepositRepository.class), mock(EventPublisher.class), mock(HotelSettings.class), mock(PeerServices.class),
            mock(Notifier.class), mock(ExpenseCategories.class));

    private void signIn(String id, Map<Role, AccessLevel> roles) {
        HospioUserDetails user = new HospioUserDetails(id, id + "@x.ng", "Segun Ajayi", "b1", List.of("h1"), roles, JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        TenantContext.set(Tenant.fromUser(user, "h1"));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private static Till till(String id, String role, String openedBy, String name, Instant openedAt) {
        Till t = Till.builder().role(role).openedBy(openedBy).openedByName(name).openedAt(openedAt).build();
        t.setId(id);
        t.setHotelId("h1");
        return t;
    }

    @Test
    void theBarPageReadsTheBarTillEvenForSomeoneWhoAlsoWorksTheFrontOffice() {
        signIn("segun", Map.of(Role.FRONT_OFFICE, AccessLevel.EDIT, Role.BAR, AccessLevel.EDIT));
        Till front = till("t-front", "FRONT_OFFICE", "qa-front", "QA PIN Front", Instant.now().minus(Duration.ofHours(2)));
        Till bar = till("t-bar", "BAR", "segun", "Segun Ajayi", Instant.now().minus(Duration.ofHours(1)));
        when(tills.findFirstByHotelIdAndRoleAndStatus("h1", "FRONT_OFFICE", Till.Status.OPEN)).thenReturn(Optional.of(front));
        when(tills.findFirstByHotelIdAndRoleAndStatus("h1", "BAR", Till.Status.OPEN)).thenReturn(Optional.of(bar));

        assertThat(money.myTill("BAR").getId()).isEqualTo("t-bar");
        assertThat(money.myTill("FRONT_OFFICE").getId()).isEqualTo("t-front");
        assertThatThrownBy(() -> money.myTill("KITCHEN")).isInstanceOf(ForbiddenException.class).hasMessage("You don't work the kitchen till");
    }

    @Test
    void aWaiterReadsTheCashiersTill() {
        signIn("w1", Map.of(Role.WAITER, AccessLevel.EDIT));
        Till cashier = till("t-c", "CASHIER", "c1", "Cashier", Instant.now());
        when(tills.findFirstByHotelIdAndRoleAndStatus("h1", "CASHIER", Till.Status.OPEN)).thenReturn(Optional.of(cashier));
        assertThat(money.myTill("WAITER").getId()).isEqualTo("t-c");
    }

    @Test
    void aTillOpenedOnAnEarlierDayAndTwelveHoursAgoIsStaleButANightShiftIsNot() {
        Instant now = ZonedDateTime.of(2026, 11, 20, 12, 0, 0, 0, LAGOS).toInstant();
        assertThat(Till.staleAt(ZonedDateTime.of(2026, 11, 18, 16, 6, 0, 0, LAGOS).toInstant(), now)).isTrue();
        assertThat(Till.staleAt(ZonedDateTime.of(2026, 11, 19, 8, 35, 0, 0, LAGOS).toInstant(), now)).isTrue();
        assertThat(Till.staleAt(ZonedDateTime.of(2026, 11, 20, 7, 0, 0, 0, LAGOS).toInstant(), now)).isFalse();
        Instant twoAm = ZonedDateTime.of(2026, 11, 21, 2, 0, 0, 0, LAGOS).toInstant();
        assertThat(Till.staleAt(ZonedDateTime.of(2026, 11, 20, 18, 0, 0, 0, LAGOS).toInstant(), twoAm)).isFalse();
    }

    @Test
    void theInternalAnswerSaysWhoOpenedTheTillAndWhetherItWasLeftOpen() {
        Instant old = Instant.now().minus(Duration.ofHours(45));
        when(tills.findFirstByHotelIdAndRoleAndStatus("h1", "BAR", Till.Status.OPEN)).thenReturn(Optional.of(till("t-bar", "BAR", "segun", "Segun Ajayi", old)));
        MoneyService.OpenTill t = money.openTillOf("h1", "BAR");
        assertThat(t.open()).isTrue();
        assertThat(t.stale()).isTrue();
        assertThat(t.openedByName()).isEqualTo("Segun Ajayi");
        assertThat(money.openTillOf("h1", "KITCHEN").open()).isFalse();
    }

    @Test
    void onePersonHoldsOneTill() {
        signIn("segun", Map.of(Role.FRONT_OFFICE, AccessLevel.EDIT, Role.BAR, AccessLevel.EDIT));
        when(tills.findFirstByHotelIdAndRoleAndStatus("h1", "BAR", Till.Status.OPEN)).thenReturn(Optional.empty());
        when(tills.findByHotelIdAndStatusOrderByOpenedAt("h1", Till.Status.OPEN))
                .thenReturn(List.of(till("t-front", "FRONT_OFFICE", "segun", "Segun Ajayi", Instant.now().minus(Duration.ofHours(3)))));

        assertThatThrownBy(() -> money.openTill(new FinanceRequests.OpenTillRequest("BAR", new BigDecimal("2000"))))
                .isInstanceOf(BadRequestException.class).hasMessageStartingWith("You already have the front office till open (since ")
                .hasMessageContaining("One person holds one till");
        verify(tills, never()).saveAndFlush(any());
    }

    @Test
    void openingADesksTillThatIsAlreadyOpenNamesWhoHasIt() {
        signIn("segun", Map.of(Role.BAR, AccessLevel.EDIT));
        when(tills.findFirstByHotelIdAndRoleAndStatus("h1", "BAR", Till.Status.OPEN))
                .thenReturn(Optional.of(till("t-bar", "BAR", "qa", "QA PIN Bar", Instant.now().minus(Duration.ofHours(40)))));
        assertThatThrownBy(() -> money.openTill(new FinanceRequests.OpenTillRequest("BAR", new BigDecimal("2000"))))
                .isInstanceOf(BadRequestException.class).hasMessageStartingWith("The bar till is already open (opened by QA PIN Bar, since ")
                .hasMessageEndingWith("it was left open from an earlier day, so count and close it first");
    }
}
