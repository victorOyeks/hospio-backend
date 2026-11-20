package com.hospio.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class UserCreatedEvent extends HospioEvent {
    private String userId;
    private String fullName;
    private String email;
    /** What the activity log says instead of "Login created for …", e.g. "Bootstrap super admin created". Usually empty. */
    private String note;

    @Override
    public String summary() {
        return note == null || note.isBlank() ? "Login created for " + fullName : note + " (" + fullName + ")";
    }
}
