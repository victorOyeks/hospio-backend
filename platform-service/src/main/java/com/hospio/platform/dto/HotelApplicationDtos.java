package com.hospio.platform.dto;

import com.hospio.common.kafka.events.HospioEvent;
import com.hospio.platform.entity.HotelApplication;
import com.hospio.platform.entity.HotelApplicationNote;
import com.hospio.platform.entity.MonthlySales;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** "Book a demo" (docs/onboarding.md, "Applications"): what the landing form sends and what Hotel applications reads and changes. */
public final class HotelApplicationDtos {

    private HotelApplicationDtos() {
    }

    /**
     * The public form. {@code website} is the honeypot: people never see the field, so anything in it means a script filled the
     * form. {@code referralCode}: the marketer's code from the link (?ref=), optional. {@code monthlySales}: a
     * {@link com.hospio.platform.entity.MonthlySales} band, required.
     */
    public record ApplyRequest(String ownerName, String phone, String email, String businessName, String businessType, String city,
                               String state, Integer rooms, String heardFrom, String preferredTime, String message, String referralCode,
                               String website, String monthlySales) {
    }

    /** What the public form is told; the same for a new request, a repeat and a filled honeypot. */
    public record Received(boolean received, String message) {
    }

    /** How a submission ended: RECEIVED (also a repeat or a honeypot), INVALID with a message per field, or LIMITED. */
    public record Submission(String outcome, Map<String, String> errors) {
        public static final String RECEIVED = "RECEIVED";
        public static final String INVALID = "INVALID";
        public static final String LIMITED = "LIMITED";

        public static Submission received() {
            return new Submission(RECEIVED, Map.of());
        }
    }

    /** One line of the timeline. */
    public record NoteView(String id, String kind, String body, String authorId, String authorName, Instant createdAt) {
        public static NoteView of(HotelApplicationNote n) {
            return new NoteView(n.getId(), n.getKind(), n.getBody(), n.getAuthorId(), n.getAuthorName(), n.getCreatedAt());
        }
    }

    /**
     * One application as the admin sees it; {@code notes} only on the detail (empty in the list). {@code monthlySales}: the band's
     * code, null when not asked; {@code monthlySalesLabel}: its words ("Not asked" when null).
     */
    public record ApplicationView(String id, String ownerName, String email, String phone, String businessName, String businessType,
                                  String city, String state, Integer rooms, String monthlySales, String monthlySalesLabel, String heardFrom, String preferredTime, String message,
                                  String referralCode, String marketerProfileId, String marketerName, String status, Instant statusChangedAt,
                                  String assigneeId, String assigneeName, Instant meetingAt, String declineReason, String businessId,
                                  String linkedBusinessName, Instant onboardedAt, Instant createdAt, String ip, String userAgent,
                                  List<NoteView> notes) {
        public static ApplicationView of(HotelApplication a, List<NoteView> notes) {
            return new ApplicationView(a.getId(), a.getOwnerName(), a.getEmail(), a.getPhone(), a.getBusinessName(), a.getBusinessType(),
                    a.getCity(), a.getState(), a.getRooms(), a.getMonthlySales(), MonthlySales.labelOf(a.getMonthlySales()), a.getHeardFrom(), a.getPreferredTime(), a.getMessage(), a.getReferralCode(),
                    a.getMarketerProfileId(), a.getMarketerName(), a.getStatus(), a.getStatusChangedAt(), a.getAssigneeId(),
                    a.getAssigneeName(), a.getMeetingAt(), a.getDeclineReason(), a.getBusinessId(), a.getLinkedBusinessName(),
                    a.getOnboardedAt(), a.getCreatedAt(), a.getIp(), a.getUserAgent(), notes == null ? List.of() : notes);
        }
    }

    /**
     * Move the application: {@code status} one of NEW, CONTACTED, MEETING_BOOKED, APPROVED or DECLINED (ONBOARDED comes from linking
     * the business). MEETING_BOOKED needs a meeting time (here or already set); DECLINED needs a reason. {@code note}: optional,
     * added to the timeline with the change.
     */
    public record StatusRequest(String status, Instant meetingAt, String reason, String note) {
    }

    public record NoteRequest(String text) {
    }

    /** {@code assigneeId}: a super admin or support admin; null or blank unassigns. */
    public record AssignRequest(String assigneeId) {
    }

    /** {@code meetingAt}: null clears it. */
    public record MeetingRequest(Instant meetingAt) {
    }

    /** The business the team created from this application with the back-office wizard. */
    public record LinkRequest(String businessId) {
    }

    /** What the screen may offer this admin. */
    public record Permissions(boolean canUpdate, boolean canDecline) {
    }

    /** A change on an application, for the activity log (Changes by Hospio360 · Admin activity). No business until linked. */
    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    public static class HotelApplicationEvent extends HospioEvent {
        private String applicationId;
        private String applicantBusiness;
        private String change;
        private String detail;

        @Override
        public String type() {
            return "HotelApplication" + (change == null ? "" : java.util.Arrays.stream(change.toLowerCase(java.util.Locale.ROOT).split("_"))
                    .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1)).reduce("", String::concat));
        }

        @Override
        public String summary() {
            return "Hotel application from " + applicantBusiness + ": " + (detail == null ? "" : detail);
        }
    }
}
