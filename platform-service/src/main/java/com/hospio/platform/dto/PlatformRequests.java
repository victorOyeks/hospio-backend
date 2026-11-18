package com.hospio.platform.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public final class PlatformRequests {

    private PlatformRequests() {
    }

    public record DecisionRequest(@NotBlank String decision, String note) {
    }

    public record EscalateRequest(String note) {
    }

    public record ReportRequest(@NotBlank String kind, LocalDate periodFrom, LocalDate periodTo, String schedule, String recipients) {
    }

    // ---- fees ----------------------------------------------------------------------------------------

    public record FeeRuleRequest(@NotBlank String businessId, @NotBlank String model, @NotNull BigDecimal value, @NotNull LocalDate startsOn, LocalDate endsOn) {
    }

    /** Manual statement for one business (kept for the Money tab's "add statement"); generate is the usual path. */
    public record FeeStatementRequest(@NotBlank String businessId, @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String month, @NotNull BigDecimal baseAmount) {
    }

    public record GenerateStatementsRequest(@NotBlank @Pattern(regexp = "\\d{4}-\\d{2}") String month, String businessId) {
    }

    public record FeePaymentRequest(@NotNull BigDecimal amount, LocalDate paidOn, String reference) {
    }

    // ---- marketers -----------------------------------------------------------------------------------

    public record MarketerRequest(@NotBlank String businessId, @NotBlank String userId, @NotBlank String fullName, BigDecimal sharePct, Integer months) {
    }

    public record MarketerInviteRequest(@NotBlank String fullName, @NotBlank @Email String email, String phone, BigDecimal sharePct, Integer months) {
    }

    public record MarketerUpdateRequest(BigDecimal sharePct, Integer months, String status) {
    }

    public record ReferralRequest(@NotBlank @Size(max = 150) String hotelName, String ownerName, String phone, String email, String city, @Size(max = 1000) String note) {
    }

    public record ReferralStageRequest(@NotBlank String stage, String note) {
    }

    /** {@code note}: why saved bank details are changing (required for a change, not the first save). */
    public record BankRequest(@NotBlank @Size(max = 100) String bankName, @NotBlank @Size(max = 30) String bankAccount,
                              @NotBlank @Size(max = 150) String bankAccountName, @Size(max = 500) String note) {
    }

    public record AgreementRequest(@NotBlank String version) {
    }

    public record StatementQueryRequest(@NotBlank @Size(max = 1000) String note) {
    }

    public record StatementApproveRequest(@Size(max = 1000) String answer) {
    }

    public record StatementPaymentRequest(@NotBlank @Size(max = 100) String reference, LocalDate paidOn, BigDecimal amount) {
    }

    public record BusinessMarketerRequest(String profileId, @Size(max = 300) String reason) {
    }

    public record FeeRefundRequest(BigDecimal amount, @Size(max = 300) String reason) {
    }

    public record PayCommissionRequest(String reference, LocalDate paidOn) {
    }

    // ---- support -------------------------------------------------------------------------------------

    public record TicketRequest(@NotBlank @Size(max = 200) String subject, @Size(max = 2000) String body, String priority, String businessId, String hotelId) {
    }

    public record AssignTicketRequest(@NotBlank String adminId) {
    }

    public record TicketPriorityRequest(@NotBlank String priority) {
    }

    public record TicketCommentRequest(@NotBlank @Size(max = 4000) String body, boolean internal) {
    }

    public record ResolveTicketRequest(@NotBlank String resolution) {
    }

    // ---- changes -------------------------------------------------------------------------------------

    public record PlatformChangeRequest(@NotBlank String businessId, String hotelId, @NotBlank String area, @NotBlank String description,
                                        @NotBlank @Size(min = 8, max = 500) String reason, String beforeJson, String afterJson) {
    }
}
