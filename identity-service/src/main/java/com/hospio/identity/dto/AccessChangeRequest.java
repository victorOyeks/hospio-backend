package com.hospio.identity.dto;

import com.hospio.common.enums.AccessLevel;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Change a level, pause or resume a role, set or clear its last day ({@code SET_UNTIL} with {@code until}, or
 * none to make it permanent), or remove it. Removing a person's only active role is refused unless
 * {@code allowLastRole} says the caller means to take the person off altogether (Remove from hotel).
 */
public record AccessChangeRequest(@NotNull(message = "Say what to change") Change change, AccessLevel level, String reason,
                                  LocalDate until, Boolean allowLastRole) {

    public AccessChangeRequest(Change change, AccessLevel level, String reason) {
        this(change, level, reason, null, null);
    }

    public enum Change { CHANGE_LEVEL, PAUSE, RESUME, SET_UNTIL, REMOVE }
}
