package com.hospio.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * A shared device or a PIN changed (docs/shared-devices.md): SET_UP, CHANGED, REVOKED, LOCKED, PIN_FAILED, PIN_LOCKED,
 * PIN_SET, PIN_RESET. {@code userName} is the person the PIN belongs to; the actor is whoever did it. Only the activity
 * log reads it. A PIN unlock itself is a {@link UserSignedInEvent} with the device in its method.
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class SharedDeviceEvent extends HospioEvent {
    private String deviceId;
    private String deviceName;
    private String action;
    private String userId;
    private String userName;
    private String detail;

    @Override
    public String summary() {
        String device = deviceName == null ? "a shared device" : deviceName;
        String who = userName == null ? "Someone" : userName;
        String actor = getActorName() == null ? who : getActorName();
        String more = detail == null || detail.isBlank() ? "" : " (" + detail + ")";
        return switch (action == null ? "" : action) {
            case "SET_UP" -> actor + " set up " + device + " as a shared device" + more;
            case "CHANGED" -> actor + " changed shared device " + device + more;
            case "REVOKED" -> actor + " removed shared device " + device;
            case "LOCKED" -> who + " locked " + device + more;
            case "PIN_FAILED" -> "Wrong PIN for " + who + " on " + device + more;
            case "PIN_LOCKED" -> who + "'s PIN was locked on " + device + more;
            case "PIN_SET" -> who + " set a PIN" + more;
            case "PIN_RESET" -> actor + " reset " + who + "'s PIN";
            default -> who + ": " + action + " on " + device;
        };
    }
}
