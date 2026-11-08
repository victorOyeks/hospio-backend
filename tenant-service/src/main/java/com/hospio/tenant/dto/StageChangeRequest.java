package com.hospio.tenant.dto;

import com.hospio.tenant.entity.Business;
import jakarta.validation.constraints.NotNull;

public record StageChangeRequest(@NotNull Business.Stage stage, String reason) {
}
