package com.hospio.platform.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Owner portal requests to H.M.S support and report schedules (docs/owner-portal.md). */
public final class OwnerRequests {

    private OwnerRequests() {
    }

    public record UndoRequest(@Size(max = 1000) String note) {
    }

    public record OwnershipTransfer(@NotBlank @Size(max = 150) String newOwnerName, @NotBlank @Email @Size(max = 255) String newOwnerEmail,
                                    @Size(max = 30) String newOwnerPhone, @Size(max = 1000) String note) {
    }

    /**
     * A report sent on a schedule: {@code every} DAILY (yesterday), WEEKLY (last Monday to Sunday, on Mondays) or MONTHLY
     * (last month, on the 1st) at {@code sendAt} Lagos time; {@code hotelId} null for every hotel; {@code managers} also
     * sends each hotel's managers their own hotel.
     */
    public record ReportEmail(@NotBlank String kind, @NotBlank String from, @NotBlank String to, String hotelId) {
    }

    public record ReportSchedule(@NotBlank String kind, @NotBlank String every, @NotBlank String sendAt, String hotelId, Boolean managers) {
    }
}
