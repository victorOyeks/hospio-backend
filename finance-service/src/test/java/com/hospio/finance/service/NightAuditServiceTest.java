package com.hospio.finance.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final NightAuditRepository audits = mock(NightAuditRepository.class);
    private final TillRepository tills = mock(TillRepository.class);
    private final ExpenseRepository expenses = mock(ExpenseRepository.class);
    private final MoneyService money = mock(MoneyService.class);
    private final PeerServices peers = mock(PeerServices.class);
    private final HotelSettings settings = mock(HotelSettings.class);
    private final Notifier notifier = mock(Notifier.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final NightAuditService service = new NightAuditService(audits, tills, expenses, money, peers, settings, notifier, events, new ObjectMapper());

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
}
