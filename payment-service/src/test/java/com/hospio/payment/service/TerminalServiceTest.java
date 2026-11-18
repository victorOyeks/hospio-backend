package com.hospio.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.Role;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.payment.client.TenantClient;
import com.hospio.payment.dto.TerminalRequest;
import com.hospio.payment.entity.PosRoleAssignment;
import com.hospio.payment.entity.PosTerminal;
import com.hospio.payment.repository.PosRoleAssignmentRepository;
import com.hospio.payment.repository.PosTerminalRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A terminal is saved with its hotel's business, also when a platform admin adds it in onboarding (no business of its own). */
class TerminalServiceTest {

    private final PosTerminalRepository terminals = mock(PosTerminalRepository.class);
    private final PosRoleAssignmentRepository assignments = mock(PosRoleAssignmentRepository.class);
    private final TenantClient tenant = mock(TenantClient.class);
    private final TerminalService service = new TerminalService(terminals, assignments, mock(EventPublisher.class), tenant);
    private final TerminalRequest request = new TerminalRequest("Bar test terminal", "logging", "TEST-1");

    @BeforeEach
    void setUp() {
        when(terminals.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(assignments.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static void platformAt(String hotel) {
        TenantContext.set(new Tenant(null, hotel, "admin-1", "Amoria", Set.of(Role.SUPER_ADMIN), JwtUtil.SCOPE_PLATFORM));
    }

    @Test
    void aPlatformAdminsTerminalTakesTheHotelsBusiness() {
        platformAt("h1");
        when(tenant.businessOf("h1")).thenReturn(Optional.of("b1"));

        PosTerminal t = service.save(null, request);

        assertThat(t.getHotelId()).isEqualTo("h1");
        assertThat(t.getBusinessId()).isEqualTo("b1");
    }

    @Test
    void aPlatformAdminCanOnlyAddToAHotelThatExists() {
        platformAt("nope");
        when(tenant.businessOf("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(null, request)).isInstanceOf(ResourceNotFoundException.class).hasMessage("Hotel not found");
        verify(terminals, never()).save(any());
    }

    @Test
    void aHotelUserKeepsTheirOwnBusinessWithoutAskingTenant() {
        TenantContext.set(new Tenant("b2", "h2", "u1", "Mgr", Set.of(Role.MANAGER), JwtUtil.SCOPE_HOTEL));

        PosTerminal t = service.save(null, request);

        assertThat(t.getBusinessId()).isEqualTo("b2");
        assertThat(t.getHotelId()).isEqualTo("h2");
        verify(tenant, never()).businessOf(anyString());
    }

    @Test
    void assigningADeskCarriesTheTerminalsBusinessAndAnotherHotelsTerminalIsNotFound() {
        PosTerminal t = PosTerminal.builder().name("Bar").provider("logging").terminalRef("T").hotelId("h1").businessId("b1").build();
        t.setId("t1");
        when(terminals.findById("t1")).thenReturn(Optional.of(t));
        when(assignments.findByHotelIdAndRole("h1", Role.BAR)).thenReturn(Optional.empty());
        platformAt("h1");

        PosRoleAssignment a = service.assign("t1", Role.BAR);

        assertThat(a.getBusinessId()).isEqualTo("b1");
        assertThat(a.getHotelId()).isEqualTo("h1");
        assertThat(a.getTerminalId()).isEqualTo("t1");

        platformAt("h9");
        assertThatThrownBy(() -> service.assign("t1", Role.BAR)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.retire("t1")).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.save("t1", request)).isInstanceOf(ResourceNotFoundException.class);
    }
}
