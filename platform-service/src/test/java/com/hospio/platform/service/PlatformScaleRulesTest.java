package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.HospioEvent;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.tenancy.TenantBinder;
import com.hospio.platform.entity.FeeStatement;
import com.hospio.platform.entity.Report;
import com.hospio.platform.jobs.ActivityRetentionJob;
import com.hospio.platform.kafka.PlatformEventListener;
import com.hospio.platform.repository.ActivityEntryRepository;
import com.hospio.platform.repository.ReportRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The scale fixes' rules without a database: overdue cutoff, weekly buckets, retention days, the Redis bump after commit, schedule claims. */
class PlatformScaleRulesTest {

    @Test
    void theOverdueCutoffMatchesTheStatusRule() {
        LocalDate today = LocalDate.parse("2026-11-13");
        for (int dueDay = 1; dueDay <= 13; dueDay++) {
            LocalDate due = LocalDate.parse("2026-11-01").plusDays(dueDay - 1);
            FeeStatement s = FeeStatement.builder().feeAmount(new java.math.BigDecimal("100")).paidAmount(java.math.BigDecimal.ZERO).dueOn(due).build();
            boolean overdueByRule = "OVERDUE".equals(FeeService.status(s, today, 5));
            assertThat(due.isBefore(FeeService.overdueCutoff(today, 5))).as("due " + due).isEqualTo(overdueByRule);
        }
    }

    @Test
    void weeklyRowsLandInTheirWeekAndType() {
        List<Object[]> rows = List.of(new Object[]{0, "BookingCreated", 2L}, new Object[]{7, "MessageSent", 1L}, new Object[]{3, "PaymentApproved", 4L},
                new Object[]{8, "BookingCreated", 9L}, new Object[]{1, "RoomCleaned", 9L});
        long[][] w = OverviewService.weekly(rows, 8);
        assertThat(w[0]).containsExactly(2, 0, 0);
        assertThat(w[3]).containsExactly(0, 4, 0);
        assertThat(w[7]).containsExactly(0, 0, 1);
        assertThat(w[1]).containsExactly(0, 0, 0);
    }

    @Test
    void retentionReadsTheSettingAndNeverKeepsLessThanAMonth() {
        assertThat(ActivityRetentionJob.retentionDays(365)).isEqualTo(365);
        assertThat(ActivityRetentionJob.retentionDays("90")).isEqualTo(90);
        assertThat(ActivityRetentionJob.retentionDays(7)).isEqualTo(30);
        assertThat(ActivityRetentionJob.retentionDays(null)).isEqualTo(365);
        assertThat(ActivityRetentionJob.retentionDays("forever")).isEqualTo(365);
    }

    @Test
    void retentionDeletesBatchByBatchUntilNoneAreLeft() {
        ActivityEntryRepository entries = mock(ActivityEntryRepository.class);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.group("security")).thenReturn(Map.of("auditRetentionDays", 200));
        when(entries.deleteOlderThan(any(), anyInt())).thenReturn(5000, 5000, 12, 0);
        Instant before = Instant.now().minus(200, ChronoUnit.DAYS);

        assertThat(new ActivityRetentionJob(entries, settings).run()).isEqualTo(10012);
        org.mockito.ArgumentCaptor<Instant> cutoff = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(entries, times(4)).deleteOlderThan(cutoff.capture(), eq(5000));
        assertThat(cutoff.getValue()).isBetween(before.minusSeconds(5), before.plusSeconds(5));
    }

    @Test
    void theLiveBoardIsBumpedOnlyAfterTheLogCommits() {
        ActivityLogService log = mock(ActivityLogService.class);
        DepartmentsLiveService live = mock(DepartmentsLiveService.class);
        var listener = new PlatformEventListener(log, mock(ApprovalService.class), live, mock(com.hospio.platform.kafka.PlatformDomainEvents.class),
                mock(TenantBinder.class));
        HospioEvent e = NoticeEvent.builder().eventId("e1").businessId("b1").hotelId("h1").kind("X").title("t").build();

        TransactionSynchronizationManager.initSynchronization();
        try {
            listener.onAnyEvent(e, KafkaTopics.NOTICE);
            verify(log).record(KafkaTopics.NOTICE, e);
            verify(live, never()).bump(anyString(), any());
            for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
                s.afterCommit();
            }
            verify(live).bump(KafkaTopics.NOTICE, e);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void aScheduleAnotherInstanceClaimedIsNotSentAgain() {
        ReportRepository reports = mock(ReportRepository.class);
        EventPublisher events = mock(EventPublisher.class);
        DeliveryService deliveries = mock(DeliveryService.class);
        NameResolver names = mock(NameResolver.class);
        when(names.contact(any())).thenReturn(Optional.empty());
        ReportScheduleService service = new ReportScheduleService(reports, events, deliveries, names, mock(BusinessNames.class));
        Instant due = Instant.now().minus(1, ChronoUnit.MINUTES);
        Report taken = Report.builder().businessId("b1").kind("DAILY_SUMMARY").name("Daily summary").status("SCHEDULED").schedule("DAILY")
                .requestedBy("u1").sendAt("07:00").nextRunAt(due).build();
        taken.setId("s1");
        Report mine = Report.builder().businessId("b1").kind("DAILY_SUMMARY").name("Daily summary").status("SCHEDULED").schedule("DAILY")
                .requestedBy("u1").sendAt("07:00").nextRunAt(due).build();
        mine.setId("s2");
        when(reports.findByStatusAndNextRunAtLessThanEqual(eq("SCHEDULED"), any())).thenReturn(List.of(taken, mine));
        when(reports.claim(eq("s1"), eq(due), any(), any())).thenReturn(0);
        when(reports.claim(eq("s2"), eq(due), any(), any())).thenReturn(1);
        List<Report> saved = new ArrayList<>();
        when(reports.saveAndFlush(any())).thenAnswer(inv -> {
            Report r = inv.getArgument(0);
            saved.add(r);
            return r;
        });

        assertThat(service.runDue()).isEqualTo(1);
        assertThat(saved).noneMatch(r -> "s1".equals(r.getScheduleId()) || "s1".equals(r.getId()));
        assertThat(mine.getNextRunAt()).isAfter(Instant.now());
        org.mockito.ArgumentCaptor<NoticeEvent> notice = org.mockito.ArgumentCaptor.forClass(NoticeEvent.class);
        verify(events).publish(eq(KafkaTopics.NOTICE), notice.capture());
        // The scheduled run's notice id is fixed by schedule and period, so a repeat is recognised downstream.
        assertThat(notice.getValue().getEventId()).hasSize(36);
    }
}
