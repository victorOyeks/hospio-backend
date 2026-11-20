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
    /**
     * Set for a sign-out of one browser (SESSION_FAMILY_ENDED, docs/sign-in.md): only these sessions (the access token's
     * {@code sid}) end; the person's other devices keep working. Empty for every other change, which applies to the person.
     */
    private java.util.List<String> sessionIds;

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
            case "SUSPENDED" -> "suspended";
            case "REINSTATED" -> "reinstated";
            case "PAUSED_UNTIL" -> "paused until the day given";
            case "RESUMED" -> "pause ended";
            case "DETAILS_CHANGED" -> "details changed";
            case "SESSIONS_ENDED" -> "signed out elsewhere";
            case "SESSION_FAMILY_ENDED" -> "signed out on one browser";
            default -> "access " + change;
        };
        return userName + ": " + (role == null ? "" : role + " ") + what + (detail == null || detail.isBlank() ? "" : " (" + detail + ")");
    }
}
