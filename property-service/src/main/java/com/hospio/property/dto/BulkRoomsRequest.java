package com.hospio.property.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Up to 500 rooms in one go (onboarding). Numbers already present are skipped, not rejected. */
public record BulkRoomsRequest(@NotEmpty @Size(max = 500) @Valid List<BulkRoom> rooms) {

    public record BulkRoom(@NotBlank String number, @NotBlank String roomTypeId, String floor, String areaId) {
    }
}
