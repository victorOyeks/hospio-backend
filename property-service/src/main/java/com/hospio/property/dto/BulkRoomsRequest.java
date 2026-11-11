package com.hospio.property.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Up to 500 rooms in one go (onboarding). Numbers already present, or repeated in the request, are skipped and reported;
 * with {@code refuseExisting} the whole request is refused instead (409 naming them) and nothing is created.
 */
public record BulkRoomsRequest(@NotEmpty @Size(max = 500) @Valid List<BulkRoom> rooms, Boolean refuseExisting) {

    public BulkRoomsRequest(List<BulkRoom> rooms) {
        this(rooms, null);
    }


    public record BulkRoom(@NotBlank String number, @NotBlank String roomTypeId, String floor, String areaId) {
    }
}
