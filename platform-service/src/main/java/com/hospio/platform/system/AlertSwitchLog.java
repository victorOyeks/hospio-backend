package com.hospio.platform.system;

import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.HospioEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.platform.service.ActivityLogService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Writes a switch of the System health alerts (on/off, email, bell) to the activity log as the Hospio360 person who made it, so it
 * shows in Changes by Hospio360 → Activity. Limits changes are not logged here (they are settings like any other).
 */
@Slf4j
@Component
public class AlertSwitchLog {

    private final ActivityLogService activity;

    public AlertSwitchLog(ActivityLogService activity) {
        this.activity = activity;
    }

    /** The log line's event: no business (platform-wide), the actor and what changed. */
    @Data
    @SuperBuilder
    @NoArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    public static class AlertsSwitched extends HospioEvent {
        private String change;

        @Override
        public String summary() {
            return "System health alerts: " + change;
        }
    }

    /** before/after: the switches in force (alertsOn already resolved against the environment's default). */
    public void record(boolean onBefore, boolean emailBefore, boolean bellBefore, boolean onAfter, boolean emailAfter, boolean bellAfter) {
        List<String> parts = new ArrayList<>();
        if (onBefore != onAfter) {
            parts.add(onAfter ? "switched on" : "switched off (nothing emailed or sent to the bell)");
        }
        if (emailBefore != emailAfter) {
            parts.add("email " + (emailAfter ? "on" : "off"));
        }
        if (bellBefore != bellAfter) {
            parts.add("bell " + (bellAfter ? "on" : "off"));
        }
        if (parts.isEmpty()) {
            return;
        }
        try {
            var user = CurrentUser.get().orElse(null);
            String name = "Hospio360 · " + (user == null || user.getFullName() == null ? "Hospio360" : user.getFullName());
            activity.record(KafkaTopics.SETTING_CHANGED, AlertsSwitched.builder().eventId(UUID.randomUUID().toString()).occurredAt(Instant.now())
                    .actorId(user == null ? "system" : user.getUserId()).actorName(name).change(String.join(", ", parts)).build());
        } catch (RuntimeException e) {
            log.warn("System health alert switch not logged: {}", e.getMessage());
        }
    }

    public static boolean or(Boolean v, boolean d) {
        return v == null ? d : v;
    }
}
