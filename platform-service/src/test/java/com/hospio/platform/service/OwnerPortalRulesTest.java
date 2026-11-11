package com.hospio.platform.service;

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
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.platform.dto.OwnerRequests;
import com.hospio.platform.entity.PlatformChange;
import com.hospio.platform.entity.SupportTicket;
import com.hospio.platform.repository.PlatformChangeRepository;
import com.hospio.platform.repository.SupportTicketRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Owner portal: asking support to undo a change or transfer ownership, and when scheduled reports go out. */
class OwnerPortalRulesTest {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private final PlatformChangeRepository changes = mock(PlatformChangeRepository.class);
    private final SupportTicketRepository tickets = mock(SupportTicketRepository.class);
    private final BusinessNames names = mock(BusinessNames.class);
    private final OwnerRequestService service = new OwnerRequestService(changes, tickets, names, mock(AlertService.class));
    private PlatformChange change;

    @BeforeEach
    void setUp() {
        change = PlatformChange.builder().businessId("b1").area("Rates").description("Deluxe rate 45,000 → 50,000").reason("Asked by phone")
                .madeByName("Hospio360 · Kemi").undoUntil(Instant.now().plusSeconds(86400)).build();
        change.setId("ch1");
        change.setCreatedAt(Instant.now());
        when(changes.findById("ch1")).thenReturn(Optional.of(change));
        when(tickets.save(any(SupportTicket.class))).thenAnswer(i -> {
            SupportTicket t = i.getArgument(0);
            t.setId("t1");
            t.setCreatedAt(Instant.now());
            return t;
        });
        when(names.name("b1")).thenReturn("Royal Suites Ltd");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signInAs(String business, Role role) {
        HospioUserDetails u = new HospioUserDetails("u1", "godwin@x.ng", "Godwin Kwin", business, List.of("h1"), Map.of(role, AccessLevel.FULL),
                role == Role.OWNER ? JwtUtil.SCOPE_BUSINESS : JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    @Test
    void theOwnerAsksForAnUndoOnceAndATicketCarriesIt() {
        signInAs("b1", Role.OWNER);
        service.requestUndo("ch1", new OwnerRequests.UndoRequest("We never asked for this"));

        assertThat(change.getUndoTicketId()).isEqualTo("t1");
        assertThat(change.getUndoRequestedByName()).isEqualTo("Godwin Kwin");
        assertThat(change.isUndone()).isFalse();
        assertThatThrownBy(() -> service.requestUndo("ch1", null)).isInstanceOf(BadRequestException.class).hasMessageStartingWith("You already asked for this on");
    }

    @Test
    void notAfterSevenDaysNorForAnotherBusinessNorByAManager() {
        signInAs("b1", Role.OWNER);
        change.setUndoUntil(Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> service.requestUndo("ch1", null)).isInstanceOf(BadRequestException.class)
                .hasMessageStartingWith("The 7 days to undo this change have passed");
        signInAs("b2", Role.OWNER);
        assertThatThrownBy(() -> service.requestUndo("ch1", null)).isInstanceOf(ResourceNotFoundException.class);
        signInAs("b1", Role.MANAGER);
        assertThatThrownBy(() -> service.requestUndo("ch1", null)).isInstanceOf(ForbiddenException.class)
                .hasMessage("Only the owner can ask for a change to be undone");
        verify(tickets, never()).save(any());
    }

    @Test
    void aTransferIsNotToYourselfAndOnlyOneAtATime() {
        signInAs("b1", Role.OWNER);
        assertThatThrownBy(() -> service.requestTransfer(new OwnerRequests.OwnershipTransfer("Me", "GODWIN@x.ng", null, null)))
                .isInstanceOf(BadRequestException.class).hasMessage("That is your own email. Enter the new owner's");

        SupportTicket t = service.requestTransfer(new OwnerRequests.OwnershipTransfer("Ngozi Obi", "ngozi@x.ng", "+2348010000009", "Selling"));
        assertThat(t.getCategory()).isEqualTo("OWNERSHIP_TRANSFER");
        assertThat(t.getPriority()).isEqualTo("HIGH");
        assertThat(t.getSubject()).isEqualTo("Ownership transfer · Royal Suites Ltd → ngozi@x.ng");

        when(tickets.findByBusinessIdOrderByCreatedAtDesc("b1")).thenReturn(List.of(t));
        assertThatThrownBy(() -> service.requestTransfer(new OwnerRequests.OwnershipTransfer("Ngozi Obi", "ngozi@x.ng", null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageStartingWith("A transfer request is already with Hospio360 support");
    }

    @Test
    void schedulesRunAtTheNextSendTimeInLagos() {
        ZonedDateTime mondayMorning = ZonedDateTime.of(2026, 11, 9, 6, 0, 0, 0, LAGOS);
        assertThat(ReportScheduleService.nextRun("DAILY", LocalTime.of(7, 0), mondayMorning))
                .isEqualTo(ZonedDateTime.of(2026, 11, 9, 7, 0, 0, 0, LAGOS).toInstant());
        assertThat(ReportScheduleService.nextRun("DAILY", LocalTime.of(5, 0), mondayMorning))
                .isEqualTo(ZonedDateTime.of(2026, 11, 10, 5, 0, 0, 0, LAGOS).toInstant());
        assertThat(ReportScheduleService.nextRun("WEEKLY", LocalTime.of(5, 0), mondayMorning))
                .isEqualTo(ZonedDateTime.of(2026, 11, 16, 5, 0, 0, 0, LAGOS).toInstant());
        assertThat(ReportScheduleService.nextRun("MONTHLY", LocalTime.of(7, 0), mondayMorning))
                .isEqualTo(ZonedDateTime.of(2026, 12, 1, 7, 0, 0, 0, LAGOS).toInstant());
    }

    @Test
    void eachRunCoversTheLastFullPeriod() {
        LocalDate tuesday = LocalDate.of(2026, 11, 10);
        assertThat(ReportScheduleService.period("DAILY", tuesday)).containsExactly(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 9));
        assertThat(ReportScheduleService.period("WEEKLY", tuesday)).containsExactly(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 8));
        assertThat(ReportScheduleService.period("MONTHLY", tuesday)).containsExactly(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThatThrownBy(() -> ReportScheduleService.sendAt("7am")).isInstanceOf(BadRequestException.class)
                .hasMessage("Send time is a time of day like 07:00");
    }
}
