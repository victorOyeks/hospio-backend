package com.hospio.housekeeping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
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
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.housekeeping.client.MaintenanceClient;
import com.hospio.housekeeping.client.PropertyClient;
import com.hospio.housekeeping.client.ReservationClient;
import com.hospio.housekeeping.dto.TaskRequests;
import com.hospio.housekeeping.entity.CleaningTask;
import com.hospio.housekeeping.entity.LinenRequest;
import com.hospio.housekeeping.entity.ProblemReport;
import com.hospio.housekeeping.repository.CleaningTaskRepository;
import com.hospio.housekeeping.repository.LinenRequestRepository;
import com.hospio.housekeeping.repository.LostFoundItemRepository;
import com.hospio.housekeeping.repository.MinibarRestockRepository;
import com.hospio.housekeeping.repository.ProblemReportRepository;
import com.hospio.housekeeping.repository.RoomStatusRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Report a problem with urgency and out of order, the note for the supervisor, try again at, and linen requests laundry fulfils. */
class ReportNotesAndLinenTest {

    private final CleaningTaskRepository tasks = mock(CleaningTaskRepository.class);
    private final ProblemReportRepository problems = mock(ProblemReportRepository.class);
    private final LinenRequestRepository linen = mock(LinenRequestRepository.class);
    private final MaintenanceClient maintenance = mock(MaintenanceClient.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final HousekeepingService housekeeping = new HousekeepingService(tasks, mock(RoomStatusRepository.class), problems, linen,
            mock(MinibarRestockRepository.class), mock(LostFoundItemRepository.class), maintenance, mock(ReservationClient.class),
            mock(PropertyClient.class), events);

    private CleaningTask task;

    @BeforeEach
    void setUp() {
        TenantContext.set(Tenant.systemFor("b1", "h1", "u-kemi", "Kemi Adebayo"));
        signInAs("u-kemi", Role.HOUSEKEEPING, AccessLevel.EDIT);
        task = CleaningTask.builder().roomId("r1").roomNumber("A101").kind(CleaningTask.Kind.STAYOVER).dueDate(LocalDate.of(2026, 11, 10))
                .status(CleaningTask.Status.IN_PROGRESS).assignedTo("u-kemi").assignedToName("Kemi Adebayo").build();
        task.setId("t1");
        when(tasks.findById("t1")).thenReturn(Optional.of(task));
        when(tasks.save(any())).thenAnswer(i -> i.getArgument(0));
        when(problems.save(any())).thenAnswer(i -> i.getArgument(0));
        when(linen.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static void signInAs(String id, Role role, AccessLevel level) {
        HospioUserDetails u = new HospioUserDetails(id, "x@x.ng", "Kemi Adebayo", "b1", List.of("h1"), Map.of(role, level), JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    // ---- report a problem ---------------------------------------------------------------------------

    @Test
    void nowOpensAnUrgentWorkOrderAndCanTakeTheRoomOutOfOrder() {
        when(maintenance.openRepair("r1", "A101", "AC blowing warm air", null, "URGENT", true)).thenReturn("wo-1");
        ProblemReport r = housekeeping.reportProblem(new TaskRequests.ProblemRequest("r1", "A101", "AC blowing warm air", null, "urgent", true));
        assertThat(r.getPriority()).isEqualTo("URGENT");
        assertThat(r.isOutOfOrder()).isTrue();
        assertThat(r.getRepairId()).isEqualTo("wo-1");
        assertThat(r.getDescription()).isEqualTo("AC blowing warm air");
    }

    @Test
    void outOfOrderNeedsARoomAndAReachableMaintenance() {
        assertThatThrownBy(() -> housekeeping.reportProblem(new TaskRequests.ProblemRequest(null, "Lobby", "Leak", null, "URGENT", true)))
                .isInstanceOf(BadRequestException.class).hasMessage("Only a room can go out of order · pick the room");
        when(maintenance.openRepair(any(), any(), any(), any(), any(), anyBoolean())).thenReturn(null);
        assertThatThrownBy(() -> housekeeping.reportProblem(new TaskRequests.ProblemRequest("r1", "A101", "Leak", null, "URGENT", true)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("the room is still in service");
        verify(problems, never()).save(any());
    }

    @Test
    void maintenancesRefusalComesBackAsItIs() {
        when(maintenance.openRepair(any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new BadRequestException("Room A101 has a guest checked in (Ada Obi) · it can't go out of order"));
        assertThatThrownBy(() -> housekeeping.reportProblem(new TaskRequests.ProblemRequest("r1", "A101", "Leak", null, "URGENT", true)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("guest checked in (Ada Obi)");
        verify(problems, never()).save(any());
    }

    @Test
    void priorityDefaultsToTodayAndRefusesNonsense() {
        assertThat(HousekeepingService.problemPriority(null)).isEqualTo("NORMAL");
        assertThat(HousekeepingService.problemPriority(" low ")).isEqualTo("LOW");
        assertThatThrownBy(() -> HousekeepingService.problemPriority("HIGH")).isInstanceOf(BadRequestException.class)
                .hasMessage("Pick how urgent it is: can wait, today or now");
    }

    // ---- note for the supervisor, try again at ------------------------------------------------------

    @Test
    void theAttendantSavesANoteAndBlankClearsIt() {
        assertThat(housekeeping.note("t1", new TaskRequests.NoteRequest("  Stain near the window  ")).getAttendantNote()).isEqualTo("Stain near the window");
        assertThat(housekeeping.note("t1", new TaskRequests.NoteRequest("  ")).getAttendantNote()).isNull();
    }

    @Test
    void someoneElsesRoomIsRefusedButASupervisorMayWrite() {
        signInAs("u-other", Role.HOUSEKEEPING, AccessLevel.EDIT);
        assertThatThrownBy(() -> housekeeping.note("t1", new TaskRequests.NoteRequest("x")))
                .isInstanceOf(ForbiddenException.class).hasMessage("Room A101 is on someone else's list");
        signInAs("u-grace", Role.HOUSEKEEPING, AccessLevel.FULL);
        assertThat(housekeeping.note("t1", new TaskRequests.NoteRequest("Checked")).getAttendantNote()).isEqualTo("Checked");
    }

    @Test
    void skippingKeepsTheTimeToTryAgain() {
        CleaningTask t = housekeeping.skip("t1", new TaskRequests.SkipRequest("Do not disturb", "15:30"));
        assertThat(t.getStatus()).isEqualTo(CleaningTask.Status.SKIPPED);
        assertThat(t.getRetryAt()).isEqualTo("15:30");
        assertThat(housekeeping.skip("t1", new TaskRequests.SkipRequest("Guest in the room")).getRetryAt()).isNull();
    }

    // ---- linen requests -----------------------------------------------------------------------------

    @Test
    void askingForLinenRecordsWhoAsked() {
        LinenRequest r = housekeeping.askForLinen(new TaskRequests.LinenRequestDto(null, null, "6 × Bath towel"));
        assertThat(r.getRequestedBy()).isEqualTo("u-kemi");
        assertThat(r.getRequestedByName()).isEqualTo("Kemi Adebayo");
        assertThat(r.getStatus()).isEqualTo("REQUESTED");
        verify(events).publish(eq(KafkaTopics.LINEN_REQUESTED), any());
    }

    @Test
    void laundryMarksItSentOnceAndTheAskerIsTold() {
        LinenRequest r = LinenRequest.builder().items("6 × Bath towel").requestedBy("u-kemi").build();
        r.setId("lr1");
        when(linen.findById("lr1")).thenReturn(Optional.of(r));
        signInAs("u-lola", Role.LAUNDRY, AccessLevel.EDIT);
        LinenRequest sent = housekeeping.linenSent("lr1");
        assertThat(sent.getStatus()).isEqualTo("SENT");
        assertThat(sent.getSentAt()).isNotNull();
        assertThat(sent.getSentByName()).isEqualTo("Kemi Adebayo");
        verify(events).publish(eq(KafkaTopics.NOTICE), argThat((NoticeEvent n) -> n.getUserIds().equals(List.of("u-kemi"))
                && n.getTitle().equals("Linen on its way")));
        assertThatThrownBy(() -> housekeeping.linenSent("lr1")).isInstanceOf(BadRequestException.class)
                .hasMessageStartingWith("This request was already sent");
    }
}
