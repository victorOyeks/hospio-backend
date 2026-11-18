package com.hospio.platform.dto;

import com.hospio.common.kafka.events.HospioEvent;
import com.hospio.platform.dto.AdminResponses.MarketerInvited;
import com.hospio.platform.entity.PartnerApplication;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** "Apply to be a partner" (docs/plan-marketers.md): what the landing form sends and what Marketers · Applications reads. */
public final class PartnerApplicationDtos {

    private PartnerApplicationDtos() {
    }

    /**
     * The public form. {@code website} is the honeypot: people never see the field, so anything in it means a script filled the
     * form. {@code rpaVersion}/{@code privacyVersion}: the document versions the page showed beside the tick box.
     */
    public record ApplyRequest(String fullName, String email, String phone, String city, String state, List<String> businessKinds,
                               String businessCount, String networkNote, String companyName, Boolean acceptedTerms, String rpaVersion,
                               String privacyVersion, String website) {
    }

    /** What the public form is told; the same for a new application, a repeat and a filled honeypot. */
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

    /** One application as the admin sees it. {@code marketerName}: the marketer the approval made. */
    public record ApplicationView(String id, String fullName, String email, String phone, String city, String state, List<String> businessKinds,
                                  String businessCount, String networkNote, String companyName, String status, Instant createdAt,
                                  Instant decidedAt, String decidedByName, String declineReason, boolean declineReasonShared,
                                  String marketerProfileId, String marketerName, String ip, String userAgent, String rpaVersion,
                                  String privacyVersion) {
        public static ApplicationView of(PartnerApplication a, String marketerName) {
            return new ApplicationView(a.getId(), a.getFullName(), a.getEmail(), a.getPhone(), a.getCity(), a.getState(),
                    a.getBusinessKinds() == null || a.getBusinessKinds().isBlank() ? List.of() : List.of(a.getBusinessKinds().split(",")),
                    a.getBusinessCount(), a.getNetworkNote(), a.getCompanyName(), a.getStatus(), a.getCreatedAt(), a.getDecidedAt(),
                    a.getDecidedByName(), a.getDeclineReason(), a.isDeclineReasonShared(), a.getMarketerProfileId(), marketerName, a.getIp(),
                    a.getUserAgent(), a.getRpaVersion(), a.getPrivacyVersion());
        }
    }

    /** Approve: the invite form's values (blank = the application's own; share and months blank = the settings' defaults). */
    public record ApproveRequest(String fullName, String email, String phone, BigDecimal sharePct, Integer months) {
    }

    /** Decline: {@code shareReason} true puts the reason in the applicant's email. */
    public record DeclineRequest(String reason, Boolean shareReason) {
    }

    public record Approved(ApplicationView application, MarketerInvited invited) {
    }

    /** The defaults the approve form opens with, and whether the caller may change them or decide at all. */
    public record ReviewDefaults(BigDecimal sharePct, int months, boolean canDecide, boolean canChangeTerms) {
    }

    /** A decision on an application, for the activity log (Changes by Hospio360 · Admin activity). No business. */
    @Data
    @SuperBuilder
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    public static class PartnerApplicationEvent extends HospioEvent {
        private String applicationId;
        private String applicantName;
        private String decision;
        private String detail;

        @Override
        public String type() {
            return "PartnerApplication" + (decision == null ? "" : decision.charAt(0) + decision.substring(1).toLowerCase(java.util.Locale.ROOT));
        }

        @Override
        public String summary() {
            return "Partner application from " + applicantName + " " + (decision == null ? "" : decision.toLowerCase(java.util.Locale.ROOT))
                    + (detail == null || detail.isBlank() ? "" : ": " + detail);
        }
    }
}
