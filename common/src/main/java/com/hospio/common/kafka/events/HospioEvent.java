package com.hospio.common.kafka.events;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Base of every event: who, where and when. {@link com.hospio.common.kafka.EventPublisher} fills the
 * tenant fields from the current context, so producers only set their own payload.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class HospioEvent {

    private String eventId;
    private String businessId;
    private String hotelId;
    private String actorId;
    private String actorName;
    private Instant occurredAt;

    /** Short name used in the activity log and notifications, e.g. GuestCheckedIn. */
    public String type() {
        String name = getClass().getSimpleName();
        return name.endsWith("Event") ? name.substring(0, name.length() - 5) : name;
    }

    /** One line a manager can read in the activity log. */
    public abstract String summary();
}
