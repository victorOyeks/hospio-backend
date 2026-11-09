package com.hospio.maintenance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class MaintenanceViews {

    private MaintenanceViews() {
    }

    public record PartView(String itemId, String name, String unit, BigDecimal quantity, BigDecimal unitCost, BigDecimal value) {
    }

    public record UpdateView(String id, Instant at, String byName, String note) {
    }

    public record RepairView(String id, String number, String roomId, String roomNumber, String area, String category, String priority,
                             String status, String title, String description, String source, String reportedBy, String reportedByName,
                             String reportedByRole, String assignedTo, String assignedToName, Instant dueAt, boolean overdue, Instant startedAt,
                             String holdReason, Instant doneAt, String completionNote, Integer labourMinutes, List<PartView> parts,
                             BigDecimal partsCost, String vendorName, BigDecimal vendorCost, BigDecimal totalCost, boolean roomOutOfOrder,
                             LocalDate oooUntil, int reopenCount, String reopenReason, String cancelReason, boolean hasPhoto, String assetId,
                             String assetName, String scheduleId, Instant createdAt, List<UpdateView> updates) {
    }

    public record CategoryCost(String category, long count, BigDecimal cost) {
    }

    public record Summary(LocalDate from, LocalDate to, long done, long open, long overdue, BigDecimal partsCost, BigDecimal vendorCost,
                          long labourMinutes, BigDecimal totalCost, List<CategoryCost> byCategory) {
    }

    public record AssetView(String id, String name, String category, String roomId, String roomNumber, String area, boolean active,
                            long openWorkOrders) {
    }

    public record JobView(String id, String title, String description, String category, String priority, int everyDays, LocalDate nextDue,
                          LocalDate lastDone, LocalDate lastOpened, String assetId, String assetName, String roomId, String roomNumber, String area,
                          String assignedTo, String assignedToName, boolean paused) {
    }

    public record RunResult(int opened) {
    }
}
