package com.hospio.housekeeping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.hospio.common.integrations.ObjectStorage;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.housekeeping.client.HotelConfigClient;
import com.hospio.housekeeping.client.MaintenanceClient;
import com.hospio.housekeeping.client.PropertyClient;
import com.hospio.housekeeping.client.ReservationClient;
import com.hospio.housekeeping.dto.LostFoundView;
import com.hospio.housekeeping.dto.TaskRequests;
import com.hospio.housekeeping.entity.CleaningTask;
import com.hospio.housekeeping.entity.LostFoundItem;
import com.hospio.housekeeping.entity.RoomStatus;
import com.hospio.housekeeping.repository.CleaningTaskRepository;
import com.hospio.housekeeping.repository.LinenRequestRepository;
import com.hospio.housekeeping.repository.LostFoundItemRepository;
import com.hospio.housekeeping.repository.MinibarRestockRepository;
import com.hospio.housekeeping.repository.ProblemReportRepository;
import com.hospio.housekeeping.repository.RoomStatusRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Inspection pass / send back with a reason (the attendant is told); lost & found log, tell, return and dispose after the hold. */
class InspectAndLostFoundTest {

    private final CleaningTaskRepository tasks = mock(CleaningTaskRepository.class);
    private final RoomStatusRepository statuses = mock(RoomStatusRepository.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final HousekeepingService housekeeping = new HousekeepingService(tasks, statuses, mock(ProblemReportRepository.class),
            mock(LinenRequestRepository.class), mock(MinibarRestockRepository.class), mock(LostFoundItemRepository.class), mock(MaintenanceClient.class),
            mock(ReservationClient.class), mock(PropertyClient.class), events);

    private final LostFoundItemRepository items = mock(LostFoundItemRepository.class);
    private final HotelConfigClient hotelConfig = mock(HotelConfigClient.class);
    /** Noon in Lagos on Tue 10 Nov 2026. */
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T11:00:00Z"), ZoneOffset.UTC);
    private final LostFoundService lostFound = new LostFoundService(items, events, mock(ObjectStorage.class), hotelConfig, clock);

    private CleaningTask task;

    @BeforeEach
    void setUp() {
        TenantContext.set(Tenant.systemFor("b1", "h1", "u-grace", "Grace Okafor"));
        signInAs(Role.HOUSEKEEPING, AccessLevel.FULL);
        task = CleaningTask.builder().roomId("r1").roomNumber("A101").kind(CleaningTask.Kind.DEPARTURE).dueDate(LocalDate.of(2026, 11, 10))
                .status(CleaningTask.Status.DONE).assignedTo("u-kemi").assignedToName("Kemi Adebayo").build();
        task.setId("t1");
        when(tasks.findById("t1")).thenReturn(Optional.of(task));
        when(tasks.save(any())).thenAnswer(i -> i.getArgument(0));
        when(statuses.findByRoomId("r1")).thenReturn(Optional.empty());
        when(items.save(any())).thenAnswer(i -> i.getArgument(0));
        when(hotelConfig.lostFoundHoldDays(any())).thenReturn(90);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static void signInAs(Role role, AccessLevel level) {
        HospioUserDetails u = new HospioUserDetails("u-" + role, "x@x.ng", "Grace Okafor", "b1", List.of("h1"), Map.of(role, level), JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    // ---- inspection ---------------------------------------------------------------------------------

    @Test
    void aPassMakesTheRoomReady() {
        CleaningTask t = housekeeping.inspect("t1", new TaskRequests.InspectRequest(true, null));
        assertThat(t.getStatus()).isEqualTo(CleaningTask.Status.INSPECTED);
        verify(statuses).save(argThat(rs -> rs.getStatus() == RoomStatus.Cleanliness.INSPECTED));
        verify(events).publish(eq(KafkaTopics.ROOM_CLEANED), any());
        verify(events, never()).publish(eq(KafkaTopics.NOTICE), any());
    }

    @Test
    void aFailSendsTheRoomBackWithTheReasonAndTellsTheAttendant() {
        CleaningTask t = housekeeping.inspect("t1", new TaskRequests.InspectRequest(false, "  Mirror smudged  "));
        assertThat(t.getStatus()).isEqualTo(CleaningTask.Status.SENT_BACK);
        assertThat(t.getInspectionNote()).isEqualTo("Mirror smudged");
        verify(statuses).save(argThat(rs -> rs.getStatus() == RoomStatus.Cleanliness.DIRTY));
        verify(events).publish(eq(KafkaTopics.NOTICE), argThat((NoticeEvent n) -> n.getUserIds().equals(List.of("u-kemi"))
                && n.getTitle().equals("Room A101 sent back") && n.getBody().equals("Mirror smudged")));
    }

    @Test
    void aFailNeedsAReason() {
        assertThatThrownBy(() -> housekeeping.inspect("t1", new TaskRequests.InspectRequest(false, " ")))
                .isInstanceOf(BadRequestException.class).hasMessage("Say what needs doing again");
        assertThat(task.getStatus()).isEqualTo(CleaningTask.Status.DONE);
    }

    @Test
    void onlyAFinishedRoomIsInspectedAndOnlyBySupervisors() {
        task.setStatus(CleaningTask.Status.IN_PROGRESS);
        assertThatThrownBy(() -> housekeeping.inspect("t1", new TaskRequests.InspectRequest(true, null)))
                .isInstanceOf(BadRequestException.class).hasMessage("Only a finished room can be inspected");
        task.setStatus(CleaningTask.Status.DONE);
        signInAs(Role.HOUSEKEEPING, AccessLevel.EDIT);
        assertThatThrownBy(() -> housekeeping.inspect("t1", new TaskRequests.InspectRequest(true, null)))
                .isInstanceOf(ForbiddenException.class).hasMessage("Only a housekeeping supervisor can do this");
    }

    // ---- lost & found -------------------------------------------------------------------------------

    private LostFoundItem held(String foundAt) {
        LostFoundItem i = LostFoundItem.builder().hotelId("h1").businessId("b1").description("Black charger").foundWhere("Room A101")
                .foundAt(Instant.parse(foundAt)).build();
        i.setId("lf1");
        when(items.findById("lf1")).thenReturn(Optional.of(i));
        return i;
    }

    @Test
    void loggingInARoomNamesItAndTellsTheFrontOffice() {
        LostFoundView v = lostFound.log(new TaskRequests.LostFoundRequest("Black charger", "Anker", "r1", "A101", null, null, null, null, null));
        assertThat(v.foundWhere()).isEqualTo("Room A101");
        assertThat(v.foundByName()).isEqualTo("Grace Okafor");
        assertThat(v.status()).isEqualTo("FOUND");
        assertThat(v.frontOfficeToldAt()).isNotNull();
        assertThat(v.disposableFrom()).isEqualTo(LocalDate.of(2027, 2, 8));
        verify(events).publish(eq(KafkaTopics.NOTICE), argThat((NoticeEvent n) -> n.getRoles().equals(List.of("FRONT_OFFICE"))
                && n.getTitle().equals("Found in Room A101: Black charger") && n.getBody().startsWith("Logged by Grace Okafor")));
    }

    @Test
    void loggingNeedsWhatAndWhereAndCanSkipTheNotice() {
        assertThatThrownBy(() -> lostFound.log(new TaskRequests.LostFoundRequest(" ", null, null, null, "Lobby", null, null, null, null)))
                .hasMessage("Say what was found");
        assertThatThrownBy(() -> lostFound.log(new TaskRequests.LostFoundRequest("Wallet", null, null, null, " ", null, null, null, null)))
                .hasMessage("Say where it was found");
        LostFoundView v = lostFound.log(new TaskRequests.LostFoundRequest("Wallet", null, null, null, "Lobby", null, "u-kemi", "Kemi Adebayo", false));
        assertThat(v.foundByName()).isEqualTo("Kemi Adebayo");
        assertThat(v.frontOfficeToldAt()).isNull();
        verify(events, never()).publish(eq(KafkaTopics.NOTICE), any());
    }

    @Test
    void returningRecordsWhoWhatIdAndOnlyOnce() {
        held("2026-11-09T10:00:00Z");
        assertThatThrownBy(() -> lostFound.returnToOwner("lf1", new TaskRequests.ReturnItemRequest(" ", null, null))).hasMessage("Say who it was returned to");
        LostFoundView v = lostFound.returnToOwner("lf1", new TaskRequests.ReturnItemRequest("Ada Obi", "NIN ending 4471", "In person"));
        assertThat(v.status()).isEqualTo("RETURNED");
        assertThat(v.returnedByName()).isEqualTo("Grace Okafor");
        assertThat(v.returnIdNote()).isEqualTo("NIN ending 4471");
        assertThatThrownBy(() -> lostFound.returnToOwner("lf1", new TaskRequests.ReturnItemRequest("Ada Obi", null, null)))
                .hasMessage("Black charger was already returned");
        assertThatThrownBy(() -> lostFound.tellFrontOffice("lf1")).hasMessage("Black charger was already returned");
    }

    @Test
    void disposalWaitsForTheHoldPeriodAndNeedsAReasonAndASupervisor() {
        held("2026-10-01T10:00:00Z");
        assertThatThrownBy(() -> lostFound.dispose("lf1", new TaskRequests.DisposeItemRequest("Unclaimed")))
                .isInstanceOf(BadRequestException.class).hasMessage("Items are kept 90 days; this one can be disposed of from Wed 30 Dec");
        when(hotelConfig.lostFoundHoldDays(any())).thenReturn(30);
        assertThatThrownBy(() -> lostFound.dispose("lf1", new TaskRequests.DisposeItemRequest(" "))).hasMessage("Say why it is being disposed of");
        signInAs(Role.HOUSEKEEPING, AccessLevel.EDIT);
        assertThatThrownBy(() -> lostFound.dispose("lf1", new TaskRequests.DisposeItemRequest("Unclaimed")))
                .isInstanceOf(ForbiddenException.class).hasMessage("Only a supervisor or manager can dispose of an item");
        signInAs(Role.HOUSEKEEPING, AccessLevel.FULL);
        LostFoundView v = lostFound.dispose("lf1", new TaskRequests.DisposeItemRequest("Unclaimed after 30 days"));
        assertThat(v.status()).isEqualTo("DISPOSED");
        assertThat(v.disposeReason()).isEqualTo("Unclaimed after 30 days");
        assertThatThrownBy(() -> lostFound.dispose("lf1", new TaskRequests.DisposeItemRequest("Again"))).hasMessage("Black charger was already disposed of");
    }

    @Test
    void anItemOfAnotherHotelIsNotFound() {
        LostFoundItem other = held("2026-11-09T10:00:00Z");
        other.setHotelId("h2");
        assertThatThrownBy(() -> lostFound.tellFrontOffice("lf1")).hasMessage("That item is not in this hotel's lost & found");
    }
}
