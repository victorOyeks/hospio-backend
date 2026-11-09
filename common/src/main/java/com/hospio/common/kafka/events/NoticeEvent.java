package com.hospio.common.kafka.events;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * An in-app notice for named people and/or roles at the event's hotel (a work order assigned to someone, put on hold, done or
 * overdue; a linen batch sent to laundry). messaging-service turns it into one notification per user and per role.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class NoticeEvent extends HospioEvent {
    private String kind;
    private String title;
    private String body;
    private String link;
    private List<String> userIds;
    private List<String> roles;

    @Override
    public String summary() {
        return title + (body == null ? "" : ": " + body);
    }
}
