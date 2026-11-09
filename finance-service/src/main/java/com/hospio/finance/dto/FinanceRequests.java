package com.hospio.finance.dto;

import com.hospio.finance.entity.ShiftRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

public final class FinanceRequests {

    private FinanceRequests() {
    }

    /** {@code openingFloat} defaults to Hotel setup → Payments → till float. */
    public record OpenTillRequest(@NotBlank String role, BigDecimal openingFloat) {
    }

    /** {@code note} is required when the count differs from what the till should hold. */
    public record CloseTillRequest(@NotNull BigDecimal countedCash, String note) {
    }

    /** {@code kind} is PAY_IN or PAY_OUT. */
    public record MovementRequest(@NotBlank String kind, @NotNull @Positive BigDecimal amount, @NotBlank String reason) {
    }

    /** Close the till with this count and open the next one, in the named person's hands, with the counted cash as its float. */
    public record HandoverRequest(@NotNull BigDecimal countedCash, String note, @NotBlank String toUserId, String toUserName) {
    }

    public record ExpenseRequest(@NotBlank String category, @NotNull BigDecimal amount, @NotBlank String description, String paidFrom,
                                 String receiptKey, LocalDate expenseDate) {
    }

    public record AnswerRequest(@NotBlank String note) {
    }

    public record PaidRequest(String paidFrom) {
    }

    public record DecisionRequest(@NotBlank String decision, String note) {
    }

    public record BudgetRequest(@NotBlank String category, @NotBlank String month, @NotNull BigDecimal amount) {
    }

    public record ChecklistRequest(String checklist) {
    }

    /** {@code markNoShows}: mark the arrivals still BOOKED as no-shows as part of closing the day. */
    public record CloseDayRequest(BigDecimal cashCounted, Integer roomsOccupied, String note, Boolean markNoShows) {
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
