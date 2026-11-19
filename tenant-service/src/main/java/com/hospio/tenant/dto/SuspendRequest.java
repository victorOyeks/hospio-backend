package com.hospio.tenant.dto;

import com.hospio.tenant.entity.Business;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** platform-service suspends a business: why, whether the business sees the note, when the notice went, and who did it. */
public record SuspendRequest(@NotNull Business.SuspensionType reasonType, @NotBlank @Size(max = 500) String reason, Boolean showReason,
                             LocalDate noticeSentOn, String actorId, String actorName) {
}
