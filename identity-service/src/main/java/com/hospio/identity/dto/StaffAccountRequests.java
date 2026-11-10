package com.hospio.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Owner portal · Staff (and the manager's Staff screen): a person's details, suspension and timed pause. */
public final class StaffAccountRequests {

    private StaffAccountRequests() {
    }

    /** Only the fields sent change; a blank phone or job title clears it. */
    public record Details(@Size(max = 150) String fullName, @Size(max = 30) String phone, @Email @Size(max = 255) String email,
                          @Size(max = 100) String jobTitle) {
    }

    public record Suspend(@Size(max = 500) String reason) {
    }

    /** Paused for {@code days} Lagos days counting today; back on its own after the last one. */
    public record Pause(@NotNull @Min(1) @Max(90) Integer days, @Size(max = 500) String reason) {
    }
}
