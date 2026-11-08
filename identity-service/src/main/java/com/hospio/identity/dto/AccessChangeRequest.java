package com.hospio.identity.dto;

import com.hospio.common.enums.AccessLevel;
import jakarta.validation.constraints.NotNull;

/** Change a level, pause or resume a role, or remove it. */
public record AccessChangeRequest(@NotNull Change change, AccessLevel level, String reason) {
    public enum Change { CHANGE_LEVEL, PAUSE, RESUME, REMOVE }
}
