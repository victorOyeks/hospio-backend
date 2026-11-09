package com.hospio.maintenance.dto;

import com.hospio.maintenance.entity.Repair;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class MaintenanceRequests {

    private MaintenanceRequests() {
    }

    /** A new work order: a room or an area, what kind of fault, how urgent. */
    public record RepairRequest(String roomId, String roomNumber, @Size(max = 100, message = "Keep the place under 100 characters") String area,
                                @NotBlank(message = "Say what is wrong") @Size(max = 150, message = "Keep the title under 150 characters") String title,
                                @Size(max = 1000, message = "Keep the description under 1000 characters") String description,
                                Repair.Priority priority, String category, String source, String photoKey, String assignedTo, String assignedToName) {
    }

    public record AssignRequest(@NotBlank(message = "Pick who does it") String assignedTo, String assignedToName) {
    }

    public record UpdateRequest(@NotBlank(message = "Write the update") @Size(max = 1000, message = "Keep the update under 1000 characters") String note) {
    }

    public record ReasonRequest(String reason) {
    }

    public record PartLine(String itemId, BigDecimal quantity) {
    }

    public record CompleteRequest(String note, Integer labourMinutes, List<PartLine> parts, String vendorName, BigDecimal vendorCost) {
    }

    public record OutOfOrderRequest(String reason, LocalDate until) {
    }

    public record AssetRequest(String name, String category, String roomId, String area, Boolean active) {
    }

    public record ScheduledJobRequest(String title, String description, String category, Repair.Priority priority, Integer everyDays,
                                      LocalDate nextDue, String assetId, String roomId, String area, String assignedTo, String assignedToName,
                                      Boolean paused) {
    }

    /** Kept for the internal route housekeeping uses to open a work order from a reported problem. */
    public record DoneRequest(String note) {
    }
}
