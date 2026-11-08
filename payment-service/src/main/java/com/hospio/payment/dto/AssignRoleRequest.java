package com.hospio.payment.dto;

import com.hospio.common.enums.Role;
import jakarta.validation.constraints.NotNull;

public record AssignRoleRequest(@NotNull Role role) {
}
