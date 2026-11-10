package com.hospio.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.reservation.query.BookingListQuery;
import com.hospio.reservation.query.GuestListQuery;
import com.hospio.reservation.repository.BookingRepository;
import com.hospio.reservation.repository.GuestRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** The bookings and guests lists' 400s and who reads which hotel. The SQL is ReservationListQueryTest. */
class ReservationListServiceTest {

    final ReservationListService service = new ReservationListService(mock(BookingListQuery.class), mock(GuestListQuery.class),
            mock(BookingRepository.class), mock(GuestRepository.class));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    static HospioUserDetails as(Map<Role, AccessLevel> roles, String header) {
        HospioUserDetails user = new HospioUserDetails("u1", "u@x.ng", "U", "b1", List.of("h1", "h2"), roles, JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        TenantContext.set(Tenant.fromUser(user, header));
        return user;
    }

    static ReservationListService.BookingParams params(Integer size, String status, String view) {
        return new ReservationListService.BookingParams(0, size, null, null, status, view, null, null, null, null, null, null);
    }

    @Test
    void contractErrors() {
        as(Map.of(Role.FRONT_OFFICE, AccessLevel.EDIT), "h1");
        assertThatThrownBy(() -> service.bookings(params(101, null, null))).isInstanceOf(BadRequestException.class).hasMessage("size is 1 to 100");
        assertThatThrownBy(() -> service.bookings(params(25, "LOST", null))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.bookings(params(25, null, "yesterday"))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.guests(0, 101, null, null, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.guests(0, 25, "phone", null, null)).isInstanceOf(BadRequestException.class);
        assertThat(ReservationListService.statuses("booked, checked_in")).isEqualTo("BOOKED,CHECKED_IN");
    }

    @Test
    void theOwnerReadsEveryHotelWithoutAHeaderOthersNeedOne() {
        HospioUserDetails owner = as(Map.of(Role.OWNER, AccessLevel.FULL), null);
        assertThat(ReservationListService.hotel(owner, null)).isNull();
        assertThat(ReservationListService.hotel(owner, "h2")).isEqualTo("h2");
        HospioUserDetails desk = as(Map.of(Role.FRONT_OFFICE, AccessLevel.EDIT), null);
        assertThatThrownBy(() -> ReservationListService.hotel(desk, "h2")).isInstanceOf(BadRequestException.class);
        HospioUserDetails manager = as(Map.of(Role.MANAGER, AccessLevel.FULL), "h1");
        assertThat(ReservationListService.hotel(manager, "h2")).isEqualTo("h1");
    }
}
