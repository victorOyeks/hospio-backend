package com.hospio.maintenance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.integrations.ObjectStorage;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.maintenance.client.InventoryClient;
import com.hospio.maintenance.client.PropertyClient;
import com.hospio.maintenance.client.ReservationClient;
import com.hospio.maintenance.entity.Repair;
import com.hospio.maintenance.entity.ScheduledJob;
import com.hospio.maintenance.repository.AssetRepository;
import com.hospio.maintenance.repository.RepairRepository;
import com.hospio.maintenance.repository.RepairUpdateRepository;
import com.hospio.maintenance.repository.ScheduledJobRepository;
import com.hospio.maintenance.repository.ScheduledJobRunRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/** The morning job opens one work order per (schedule, due date), however often it runs (docs/laundry-maintenance.md · Preventive). */
class PreventiveScheduleTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    private RepairRepository repairs;
    private ScheduledJobRepository jobs;
    private EventPublisher events;
    private MaintenanceService service;
    private ScheduledJob job;

    @BeforeEach
    void setUp() {
        repairs = mock(RepairRepository.class);
        jobs = mock(ScheduledJobRepository.class);
        events = mock(EventPublisher.class);
        service = new MaintenanceService(repairs, mock(RepairUpdateRepository.class), jobs, mock(ScheduledJobRunRepository.class), mock(AssetRepository.class),
                mock(PropertyClient.class), mock(ReservationClient.class), mock(InventoryClient.class), events, new ObjectMapper(), mock(ObjectStorage.class),
                mock(PlatformTransactionManager.class));
        job = ScheduledJob.builder().businessId("b1").hotelId("h1").title("AC service").everyDays(90).nextDue(TODAY).area("Block A").build();
        job.setId("job-1");
        when(jobs.findById("job-1")).thenReturn(Optional.of(job));
        when(repairs.lastNumber(eq("h1"), anyString())).thenReturn(4);
    }

    @Test
    void aDueScheduleOpensOneWorkOrderAndMovesOn() {
        when(repairs.existsByScheduleIdAndScheduleDue("job-1", TODAY)).thenReturn(false);
        assertThat(service.openFromSchedule("job-1", TODAY)).isTrue();
        ArgumentCaptor<Repair> saved = ArgumentCaptor.forClass(Repair.class);
        verify(repairs).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getScheduleId()).isEqualTo("job-1");
        assertThat(saved.getValue().getScheduleDue()).isEqualTo(TODAY);
        assertThat(saved.getValue().getNumber()).isEqualTo("WO-" + LocalDate.now(MaintenanceService.LAGOS).getYear() + "-00005");
        assertThat(saved.getValue().getSource()).isEqualTo("SCHEDULED");
        assertThat(job.getNextDue()).isEqualTo(TODAY.plusDays(90));
        assertThat(job.getLastOpened()).isEqualTo(TODAY);
    }

    @Test
    void runningAgainForTheSameDateOpensNothing() {
        when(repairs.existsByScheduleIdAndScheduleDue("job-1", TODAY)).thenReturn(true);
        assertThat(service.openFromSchedule("job-1", TODAY)).isFalse();
        verify(repairs, never()).saveAndFlush(any());
        assertThat(job.getNextDue()).isEqualTo(TODAY.plusDays(90));
    }

    @Test
    void aRaceLostOnTheUniqueIndexOpensNothing() {
        when(repairs.existsByScheduleIdAndScheduleDue("job-1", TODAY)).thenReturn(false);
        when(repairs.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("ux_repairs_schedule_due"));
        assertThat(service.openFromSchedule("job-1", TODAY)).isFalse();
    }

    @Test
    void pausedOrNotYetDueOpensNothing() {
        job.setPaused(true);
        assertThat(service.openFromSchedule("job-1", TODAY)).isFalse();
        job.setPaused(false);
        job.setNextDue(TODAY.plusDays(1));
        assertThat(service.openFromSchedule("job-1", TODAY)).isFalse();
        job.setNextDue(TODAY);
        job.setDeleted(true);
        assertThat(service.openFromSchedule("job-1", TODAY)).isFalse();
        verify(repairs, never()).saveAndFlush(any());
    }

    @Test
    void aMissedScheduleOpensOnceAndSkipsAheadPastToday() {
        job.setEveryDays(30);
        job.setNextDue(TODAY.minusDays(65));
        when(repairs.existsByScheduleIdAndScheduleDue(eq("job-1"), any())).thenReturn(false);
        assertThat(service.openFromSchedule("job-1", TODAY)).isTrue();
        assertThat(job.getNextDue()).isEqualTo(TODAY.plusDays(25));
        assertThat(service.openFromSchedule("job-1", TODAY)).isFalse();
    }
}
