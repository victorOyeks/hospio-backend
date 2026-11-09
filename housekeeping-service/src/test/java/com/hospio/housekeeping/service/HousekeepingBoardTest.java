package com.hospio.housekeeping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.kafka.EventPublisher;
import com.hospio.housekeeping.client.MaintenanceClient;
import com.hospio.housekeeping.client.PropertyClient;
import com.hospio.housekeeping.client.ReservationClient;
import com.hospio.housekeeping.entity.CleaningTask;
import com.hospio.housekeeping.entity.RoomStatus;
import com.hospio.housekeeping.repository.CleaningTaskRepository;
import com.hospio.housekeeping.repository.LinenRequestRepository;
import com.hospio.housekeeping.repository.LostFoundItemRepository;
import com.hospio.housekeeping.repository.MinibarRestockRepository;
import com.hospio.housekeeping.repository.ProblemReportRepository;
import com.hospio.housekeeping.repository.RoomStatusRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The room board lists every room (CLEAN by default); a stay-over task no longer dirties a room, a departure does. */
class HousekeepingBoardTest {

    private final CleaningTaskRepository tasks = mock(CleaningTaskRepository.class);
    private final RoomStatusRepository statuses = mock(RoomStatusRepository.class);
    private final PropertyClient property = mock(PropertyClient.class);
    private final HousekeepingService service = new HousekeepingService(tasks, statuses, mock(ProblemReportRepository.class), mock(LinenRequestRepository.class),
            mock(MinibarRestockRepository.class), mock(LostFoundItemRepository.class), mock(MaintenanceClient.class), mock(ReservationClient.class), property,
            mock(EventPublisher.class));

    @Test
    void roomsWithNoStatusRowDefaultToCleanAndTheBoardIsInRoomOrder() {
        RoomStatus dirty = RoomStatus.builder().roomId("r2").roomNumber("102").status(RoomStatus.Cleanliness.DIRTY).build();
        when(statuses.findByHotelIdOrderByRoomNumber("h1")).thenReturn(List.of(dirty));
        when(property.rooms("h1")).thenReturn(List.of(new PropertyClient.Room("r10", "110"), new PropertyClient.Room("r2", "102"), new PropertyClient.Room("r1", "101")));

        List<RoomStatus> board = service.roomBoard("h1");

        assertThat(board).extracting(RoomStatus::getRoomNumber).containsExactly("101", "102", "110");
        assertThat(board).extracting(RoomStatus::getStatus).containsExactly(RoomStatus.Cleanliness.CLEAN, RoomStatus.Cleanliness.DIRTY, RoomStatus.Cleanliness.CLEAN);
    }

    @Test
    void theBoardFallsBackToWhatItHasWhenPropertyIsUnreachable() {
        RoomStatus clean = RoomStatus.builder().roomId("r1").roomNumber("101").status(RoomStatus.Cleanliness.INSPECTED).build();
        when(statuses.findByHotelIdOrderByRoomNumber("h1")).thenReturn(List.of(clean));
        when(property.rooms("h1")).thenReturn(List.of());

        assertThat(service.roomBoard("h1")).containsExactly(clean);
    }

    @Test
    void aStayoverTaskLeavesTheRoomAlone() {
        when(tasks.findByRoomIdAndStatusIn(any(), any())).thenReturn(List.of());
        when(tasks.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createTask("r1", "101", CleaningTask.Kind.STAYOVER, LocalDate.now().plusDays(1));

        org.mockito.Mockito.verify(statuses, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void aDepartureTaskMarksTheRoomDirty() {
        when(tasks.findByRoomIdAndStatusIn(any(), any())).thenReturn(List.of());
        when(tasks.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(statuses.findByRoomId("r1")).thenReturn(Optional.empty());

        service.createTask("r1", "101", CleaningTask.Kind.DEPARTURE, LocalDate.now());

        verify(statuses).save(any(RoomStatus.class));
    }
}
