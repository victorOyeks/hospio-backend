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
public class ChangeByPlatformEvent extends HospioEvent {
    private String changeId;
    private String area;
    private String description;
    private java.time.Instant undoUntil;

    @Override
    public String summary() {
        return "Hospio360 changed " + area + ": " + description;
    }
}
