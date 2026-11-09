package com.hospio.platform.dto;

import com.hospio.common.delivery.Delivery;
import com.hospio.platform.entity.Commission;
import com.hospio.platform.entity.FeePayment;
import com.hospio.platform.entity.Marketer;
import com.hospio.platform.entity.MarketerProfile;
import com.hospio.platform.entity.PlatformChange;
import com.hospio.platform.entity.Referral;
import com.hospio.platform.entity.SupportTicket;
import com.hospio.platform.entity.SupportTicketComment;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Response shapes of the admin portal that add to or combine entities. */
public final class AdminResponses {

    private AdminResponses() {
    }

    // ---- fees ----------------------------------------------------------------------------------------

    public record FeeLine(String hotelId, String hotelName, BigDecimal base, BigDecimal fee) {
    }

    public record FeeStatementResponse(String id, String businessId, String businessName, String month, BigDecimal baseAmount, BigDecimal feeAmount,
                                       BigDecimal paidAmount, BigDecimal outstanding, String status, LocalDate dueOn, Instant sentAt,
                                       List<FeeLine> lines, List<FeePayment> payments, Instant createdAt, Instant updatedAt) {
    }

    public record GenerateResult(int created, int skipped, List<String> notes) {
    }

    public record DeliveriesResponse(List<Delivery> deliveries) {
    }

    public record StatementSent(FeeStatementResponse statement, List<Delivery> deliveries) {
    }

    // ---- support -------------------------------------------------------------------------------------

    public record TicketDetail(SupportTicket ticket, List<SupportTicketComment> comments) {
    }

    public record TicketCommentResponse(SupportTicketComment comment, List<Delivery> deliveries) {
    }

    public record TicketResolved(SupportTicket ticket, List<Delivery> deliveries) {
    }

    // ---- marketers -----------------------------------------------------------------------------------

    public record MarketerRow(MarketerProfile profile, int businesses, BigDecimal earnedTotal, BigDecimal due) {
    }

    public record MarketerLogin(String userId, String temporaryPassword, Instant temporaryPasswordExpiresAt, String inviteToken, Instant inviteExpiresAt) {
    }

    public record MarketerInvited(MarketerProfile profile, MarketerLogin login, List<Delivery> deliveries) {
    }

    public record MarketerDetail(MarketerProfile profile, List<Marketer> agreements, List<Referral> referrals, List<CommissionRow> commissions,
                                 BigDecimal earnedTotal, BigDecimal due, BigDecimal paid) {
    }

    public record CommissionRow(Commission commission, String marketerName, String businessName) {
    }

    public record MarketerTotals(BigDecimal earnedTotal, BigDecimal due, BigDecimal paid, int businesses, int referrals) {
    }

    public record MarketerMe(MarketerProfile profile, MarketerTotals totals) {
    }

    public record MarketerBusiness(String id, String name, String city, String stage, int hotels, Instant since, BigDecimal sharePct,
                                   BigDecimal lastMonthShare, String lastMonth) {
    }

    public record CommissionStatement(String month, String marketerName, BigDecimal gross, BigDecimal wht, BigDecimal net, String text) {
    }

    // ---- changes -------------------------------------------------------------------------------------

    public record ChangeRow(PlatformChange change, String businessName, String madeByName, boolean undone) {
    }

    // ---- settings and integrations -------------------------------------------------------------------

    public record Integration(String key, String name, String kind, String status, String detail, Instant lastCheck) {
    }

    // ---- dashboard and business tabs -----------------------------------------------------------------

    public record BusinessCounts(long total, Map<String, Long> byStage) {
    }

    public record HealthBuckets(long good, long watch, long atRisk) {
    }

    public record Overview(BusinessCounts businesses, long hotels, long rooms, BigDecimal mrr, BigDecimal dueFees, BigDecimal overdueFees,
                           long openTickets, long onboardingInProgress, long readyForHandover, List<com.hospio.platform.entity.Alert> alerts,
                           List<ChangeRow> recentChanges, List<com.hospio.platform.entity.ActivityEntry> recentActivity, HealthBuckets health) {
    }

    public record WeekCount(LocalDate weekStart, long bookings, long payments, long messages) {
    }

    public record Usage(String businessId, long activeUsers7d, long activeUsers30d, long bookings30d, long payments30d, long messages30d,
                        List<WeekCount> weeks, Map<String, Boolean> features) {
    }

    public record HotelOverview(String hotelId, String businessId, String name, String managerUserId, String managerName, Integer rooms,
                                Integer occupiedRooms, BigDecimal occupancyPct, Integer arrivalsToday, Integer departuresToday, Integer openFolios,
                                Long paymentsTodayCount, BigDecimal paymentsTodayGross, Map<String, Object> raw) {
    }
}
