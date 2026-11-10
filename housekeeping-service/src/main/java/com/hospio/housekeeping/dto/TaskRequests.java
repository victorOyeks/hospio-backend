package com.hospio.housekeeping.dto;

import com.hospio.housekeeping.entity.CleaningTask;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Request bodies for the housekeeping and laundry screens. */
public final class TaskRequests {

    private TaskRequests() {
    }

    public record AssignRequest(@NotBlank String roomId, String roomNumber, @NotNull CleaningTask.Kind kind, LocalDate dueDate,
                                String assignedTo, String assignedToName) {
    }

    /** Front desk: mark a room ready (CLEAN or INSPECTED) or send it to housekeeping (DIRTY). */
    public record RoomStatusRequest(@NotNull com.hospio.housekeeping.entity.RoomStatus.Cleanliness status, String note) {
    }

    public record ReassignRequest(@NotBlank String assignedTo, String assignedToName) {
    }

    public record ChecklistRequest(String checklist) {
    }

    public record SkipRequest(@NotBlank String reason) {
    }

    public record InspectRequest(boolean passed, String note) {
    }

    public record ProblemRequest(String roomId, String roomNumber, @NotBlank String description, String photoKey) {
    }

    public record LinenRequestDto(String roomId, String roomNumber, @NotBlank String items) {
    }

    public record MinibarRequest(@NotBlank String roomId, @NotBlank String items, @NotNull BigDecimal total) {
    }

    /**
     * Log a found item: what (required), where (a public area's name, or a room by id and number), who found it (the caller unless named)
     * and whether the front office is told now (default yes). Checks and their sentences are in LostFoundService.
     */
    public record LostFoundRequest(String description, String details, String roomId, String roomNumber, String foundWhere, String photoKey,
                                   String foundById, String foundByName, Boolean tellFrontOffice) {
    }

    /** Back to its owner: who (required), the ID they showed and how it went back (in person, courier, collected by a friend). */
    public record ReturnItemRequest(String returnedTo, String idNote, String how) {
    }

    /** Disposed of after the hold period, with the reason. */
    public record DisposeItemRequest(String reason) {
    }

    public record LaundryOrderRequest(String roomId, String guestName, @NotBlank String items, @NotNull BigDecimal total, Boolean chargeToRoom) {
    }

    public record PocketNoteRequest(@NotBlank String note) {
    }

    public record LinenMovementRequest(@NotNull com.hospio.housekeeping.entity.LinenMovement.Direction direction, @NotBlank String item,
                                       int quantity, String note) {
    }

    public record LinenBatchRequest(@NotNull List<LinenMovementRequest> movements) {
    }

    public record DamagedLinenRequest(@NotBlank String item, int quantity, @NotBlank String reason) {
    }
}
