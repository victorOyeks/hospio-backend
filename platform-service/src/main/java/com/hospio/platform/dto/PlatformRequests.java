package com.hospio.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class PlatformRequests {

    private PlatformRequests() {
    }

    public record DecisionRequest(@NotBlank String decision, String note) {
    }

    public record ReportRequest(@NotBlank String kind, LocalDate periodFrom, LocalDate periodTo, String schedule, String recipients) {
    }

    public record FeeRuleRequest(@NotBlank String businessId, @NotBlank String model, @NotNull BigDecimal value, @NotNull LocalDate startsOn, LocalDate endsOn) {
    }

    public record FeeStatementRequest(@NotBlank String businessId, @NotBlank String month, @NotNull BigDecimal baseAmount) {
    }

    public record FeePaymentRequest(@NotNull BigDecimal amount) {
    }

    public record MarketerRequest(@NotBlank String businessId, @NotBlank String userId, @NotBlank String fullName, BigDecimal sharePct, Integer months) {
    }

    public record TicketRequest(@NotBlank String subject, String body, String priority, String businessId) {
    }

    public record ResolveTicketRequest(@NotBlank String resolution) {
    }

    public record PlatformChangeRequest(@NotBlank String businessId, String hotelId, @NotBlank String area, @NotBlank String description,
                                        @NotBlank String reason, String beforeJson, String afterJson) {
    }
}
