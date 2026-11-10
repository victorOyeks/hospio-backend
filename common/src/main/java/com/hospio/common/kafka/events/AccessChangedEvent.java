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
public class AccessChangedEvent extends HospioEvent {
    private String userId;
    private String userName;
    private String change;
    private String role;
    /** The new level (CHANGE_LEVEL), the last day (SET_UNTIL, EXPIRED) or the reason (PAUSE, REMOVE), when there is one. */
    private String detail;

    @Override
    public String summary() {
        String what = switch (change == null ? "" : change) {
            case "CHANGE_LEVEL" -> "level changed";
            case "PAUSE" -> "paused";
            case "RESUME" -> "resumed";
            case "REMOVE" -> "removed";
            case "SET_UNTIL" -> "end date set";
            case "EXPIRED" -> "ended (temporary access ran out)";
            case "PAUSED_ALL" -> "all access paused";
            case "RESUMED_ALL" -> "all access resumed";
            default -> "access " + change;
        };
        return userName + ": " + (role == null ? "" : role + " ") + what + (detail == null || detail.isBlank() ? "" : " (" + detail + ")");
    }
}
