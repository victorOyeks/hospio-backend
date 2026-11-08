package com.hospio.finance.dto;

import com.hospio.finance.entity.ShiftRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public final class FinanceRequests {

    private FinanceRequests() {
    }

    public record OpenTillRequest(@NotBlank String role, @NotNull BigDecimal openingFloat) {
    }

    public record CloseTillRequest(@NotNull BigDecimal countedCash, String note) {
    }

    public record ExpenseRequest(@NotBlank String category, @NotNull BigDecimal amount, @NotBlank String description, String paidFrom,
                                 String receiptKey, LocalDate expenseDate) {
    }

    public record DecisionRequest(@NotBlank String decision, String note) {
    }

    public record BudgetRequest(@NotBlank String category, @NotBlank String month, @NotNull BigDecimal amount) {
    }

    public record ChecklistRequest(String checklist) {
    }

    public record CloseDayRequest(BigDecimal cashCounted, Integer roomsOccupied, String note) {
    }

    public record BankCashRequest(@NotNull BigDecimal amount, String bankAccount, String reference) {
    }

    public record ShiftRequestDto(@NotBlank String userId, String userName, @NotBlank String role, @NotNull LocalDate shiftDate,
                                  @NotNull LocalTime startsAt, @NotNull LocalTime endsAt) {
    }

    public record ShiftChangeRequest(@NotNull ShiftRequest.Kind kind, String withUserId, String reason) {
    }

    public record LeaveRequestDto(@NotNull LocalDate fromDate, @NotNull LocalDate toDate, String reason) {
    }

    public record ClockRequest(String note) {
    }
}
