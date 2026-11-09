package com.hospio.housekeeping.kafka;

import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.GuestCheckedInEvent;
import com.hospio.common.kafka.events.GuestCheckedOutEvent;
import com.hospio.common.kafka.events.RoomBackInServiceEvent;
import com.hospio.common.tenancy.TenantBinder;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.housekeeping.entity.CleaningTask;
import com.hospio.housekeeping.service.HousekeepingService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A check-in schedules tomorrow's stay-over clean; a check-out schedules today's departure clean. */
@Component
@RequiredArgsConstructor
public class HousekeepingEventListener {

    private final HousekeepingService housekeeping;
    private final TenantBinder tenant;

    @Transactional
    @KafkaListener(topics = KafkaTopics.GUEST_CHECKED_IN)
    public void onCheckedIn(GuestCheckedInEvent e) {
        tenant.bind(e);
        try {
            if (e.getRoomId() != null) {
                housekeeping.createTask(e.getRoomId(), e.getRoomNumber(), CleaningTask.Kind.STAYOVER, LocalDate.now().plusDays(1));
            }
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.GUEST_CHECKED_OUT)
    public void onCheckedOut(GuestCheckedOutEvent e) {
        tenant.bind(e);
        try {
            if (e.getRoomId() != null) {
                housekeeping.createTask(e.getRoomId(), e.getRoomNumber(), CleaningTask.Kind.DEPARTURE, LocalDate.now());
            }
        } finally {
            TenantContext.clear();
        }
    }

    @Transactional
    @KafkaListener(topics = KafkaTopics.ROOM_BACK_IN_SERVICE)
    public void onBackInService(RoomBackInServiceEvent e) {
        tenant.bind(e);
        try {
            if (e.getRoomId() != null) {
                housekeeping.afterRepair(e.getRoomId(), e.getRoomNumber());
            }
        } finally {
            TenantContext.clear();
        }
    }
}
