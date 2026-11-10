package com.hospio.payment.service;

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
import com.hospio.payment.query.PaymentListQuery;
import com.hospio.payment.repository.DisputeRepository;
import com.hospio.payment.repository.PaymentRequestRepository;
import com.hospio.payment.repository.RefundRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who sees which payments, and the paging contract's 400s. The SQL itself is PaymentListQueryTest. */
class PaymentListServiceTest {

    final PaymentListService service = new PaymentListService(mock(PaymentListQuery.class), mock(PaymentRequestRepository.class),
            mock(RefundRepository.class), mock(DisputeRepository.class));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    static HospioUserDetails as(String id, Map<Role, AccessLevel> roles, String hotelHeader) {
        HospioUserDetails user = new HospioUserDetails(id, id + "@x.ng", id, "b1", List.of("h1", "h2"), roles, JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        TenantContext.set(Tenant.fromUser(user, hotelHeader));
        return user;
    }

    static PaymentListService.Params size(Integer size) {
        return new PaymentListService.Params(0, size, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void sizeOver100IsABadRequest() {
        as("m1", Map.of(Role.MANAGER, AccessLevel.FULL), "h1");
        assertThatThrownBy(() -> service.payments(size(101))).isInstanceOf(BadRequestException.class).hasMessage("size is 1 to 100");
        assertThatThrownBy(() -> service.payments(new PaymentListService.Params(0, 25, "password", null, null, null, null, null, null, null, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.payments(new PaymentListService.Params(0, 25, null, null, "LOST", null, null, null, null, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("payment status");
    }

    @Test
    void staffSeeWhatTheyTookManagersTheHotelTheOwnerTheBusiness() {
        HospioUserDetails bar = as("u1", Map.of(Role.BAR, AccessLevel.EDIT), "h1");
        PaymentListQuery.Filter f = service.scope(bar, "h2", null, false);
        assertThat(f.hotelId()).isEqualTo("h1");
        assertThat(f.requestedBy()).isEqualTo("u1");

        HospioUserDetails manager = as("m1", Map.of(Role.MANAGER, AccessLevel.FULL), "h1");
        assertThat(service.scope(manager, null, "bar", false).requestedBy()).isNull();
        assertThat(service.scope(manager, null, "bar", false).role()).isEqualTo("BAR");
        assertThat(service.scope(manager, null, null, true).requestedBy()).isEqualTo("m1");

        HospioUserDetails owner = as("o1", Map.of(Role.OWNER, AccessLevel.FULL), null);
        assertThat(service.scope(owner, null, null, false).hotelId()).isNull();
        assertThat(service.scope(owner, "h2", null, false).hotelId()).isEqualTo("h2");
        assertThat(service.scope(owner, null, null, false).businessId()).isEqualTo("b1");
    }

    @Test
    void withoutAHotelOnlyTheOwnerReadsTheBusiness() {
        HospioUserDetails manager = new HospioUserDetails("m1", "m@x.ng", "M", "b1", List.of("h1", "h2"), Map.of(Role.MANAGER, AccessLevel.FULL),
                JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(manager, null, manager.getAuthorities()));
        TenantContext.set(Tenant.fromUser(manager, null));
        assertThatThrownBy(() -> service.scope(manager, "h2", null, false)).isInstanceOf(BadRequestException.class);
    }
}
