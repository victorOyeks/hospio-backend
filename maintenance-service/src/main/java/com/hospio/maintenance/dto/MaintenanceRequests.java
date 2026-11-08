package com.hospio.maintenance.dto;

import com.hospio.maintenance.entity.Repair;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class MaintenanceRequests {

    private MaintenanceRequests() {
    }

    public record RepairRequest(String roomId, String roomNumber, String area, @NotBlank String title, String description,
                                Repair.Priority priority, String source, String photoKey) {
    }

    public record AssignRequest(@NotBlank String assignedTo, String assignedToName) {
    }

    public record UpdateRequest(@NotBlank String note, String photoKey, Repair.Status status) {
    }

    public record DoneRequest(String note) {
    }

    public record OutOfOrderRequest(@NotBlank String reason, LocalDate until) {
    }

    public record PartsRequest(@NotBlank String partName, int quantity, BigDecimal estimatedCost) {
    }

    public record ScheduledJobRequest(@NotBlank String title, String description, String area, int everyDays, LocalDate firstDue,
                                      String assignedTo, String assignedToName) {
    }

    public record JobDoneRequest(String note) {
    }

    public record SparePartRequest(@NotBlank String name, Integer quantity, Integer minQuantity, BigDecimal unitCost) {
    }
}
