package com.hospio.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.integrations.ObjectStorage;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.maintenance.client.InventoryClient;
import com.hospio.maintenance.client.PropertyClient;
import com.hospio.maintenance.client.ReservationClient;
import com.hospio.maintenance.entity.Repair;
import com.hospio.maintenance.repository.AssetRepository;
import com.hospio.maintenance.repository.RepairRepository;
import com.hospio.maintenance.repository.RepairUpdateRepository;
import com.hospio.maintenance.repository.ScheduledJobRepository;
import com.hospio.maintenance.repository.ScheduledJobRunRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

/** The overdue sweep: pages of 500, one notice per work order, none for one another run already flagged. */
class OverdueFlagTest {

    private RepairRepository repairs;
    private EventPublisher events;
    private MaintenanceService service;

    @BeforeEach
    void setUp() {
        repairs = mock(RepairRepository.class);
        events = mock(EventPublisher.class);
        service = new MaintenanceService(repairs, mock(RepairUpdateRepository.class), mock(ScheduledJobRepository.class), mock(ScheduledJobRunRepository.class),
                mock(AssetRepository.class), mock(PropertyClient.class), mock(ReservationClient.class), mock(InventoryClient.class), events, new ObjectMapper(),
                mock(ObjectStorage.class), mock(PlatformTransactionManager.class));
    }

    @Test
    void worksThroughPagesAndNotifiesOnlyForRowsThisRunFlagged() {
        List<Repair> first = IntStream.range(0, MaintenanceService.OVERDUE_PAGE).mapToObj(i -> repair("r" + i)).toList();
        List<Repair> second = List.of(repair("late-1"), repair("taken"));
        when(repairs.findOverdueToNotify(any(), any(), any(Pageable.class))).thenReturn(first, second);
        when(repairs.markOverdueNotified(any())).thenReturn(1);
        when(repairs.markOverdueNotified("taken")).thenReturn(0);

        service.flagOverdue();

        verify(repairs, times(2)).findOverdueToNotify(any(), any(), any(Pageable.class));
        ArgumentCaptor<NoticeEvent> notices = ArgumentCaptor.forClass(NoticeEvent.class);
        verify(events, times(MaintenanceService.OVERDUE_PAGE + 1)).publish(eq(KafkaTopics.NOTICE), notices.capture());
        List<String> titles = new ArrayList<>(notices.getAllValues().stream().map(NoticeEvent::getTitle).toList());
        assertThat(titles).contains("WO-late-1 is overdue").doesNotContain("WO-taken is overdue");
    }

    private static Repair repair(String id) {
        Repair r = Repair.builder().businessId("b1").hotelId("h1").number("WO-" + id).title("Leaking tap").area("Block A")
                .priority(Repair.Priority.HIGH).status(Repair.Status.OPEN).build();
        r.setId(id);
        return r;
    }
}
