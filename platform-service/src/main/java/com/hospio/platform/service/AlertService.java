package com.hospio.platform.service;

import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.entity.Alert;
import com.hospio.platform.entity.FeeStatement;
import com.hospio.platform.entity.SupportTicket;
import com.hospio.platform.repository.ActivityEntryRepository;
import com.hospio.platform.repository.AlertRepository;
import com.hospio.platform.repository.FeeStatementRepository;
import com.hospio.platform.repository.SupportTicketRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * System alerts. Each kind has one open alert per business (or hotel): raising it again refreshes the
 * detail, and the alert is dismissed automatically when its cause is gone. Thresholds are the public
 * constants so the rules can be read and tested in one place.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertService {

    /** Declined card or transfer payments at one hotel within an hour before PAYMENTS_FAILING is raised. */
    public static final int DECLINES_PER_HOUR = 5;
    /** Days an onboarding draft can sit untouched before ONBOARDING_STALLED. */
    public static final int STALLED_DAYS = 7;
    /** Days without any activity in a LIVE business before NO_ACTIVITY. */
    public static final int QUIET_DAYS = 3;
    /** Days without a night audit (DayClosed) in an otherwise active LIVE business before NIGHT_AUDIT_MISSED. */
    public static final int AUDIT_DAYS = 2;

    private final AlertRepository alerts;
    private final ActivityEntryRepository activity;
    private final FeeStatementRepository feeStatements;
    private final SupportTicketRepository tickets;
    private final BusinessNames businessNames;

    // ---- reading and dismissing ----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Alert> list(String businessId, boolean openOnly) {
        if (businessId != null && !businessId.isBlank()) {
            return openOnly ? alerts.findByBusinessIdAndDismissedAtIsNullOrderByOpenedAtDesc(businessId) : alerts.findByBusinessIdOrderByOpenedAtDesc(businessId);
        }
        return openOnly ? alerts.findByDismissedAtIsNullOrderByOpenedAtDesc() : alerts.findAllByOrderByOpenedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<Alert> newestOpen(int limit) {
        return alerts.findByDismissedAtIsNullOrderByOpenedAtDesc(org.springframework.data.domain.PageRequest.of(0, Math.max(limit, 1)));
    }

    @Transactional
    public Alert dismiss(String id) {
        Alert a = alerts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Alert not found"));
        if (a.getDismissedAt() == null) {
            HospioUserDetails user = CurrentUser.require();
            a.setDismissedAt(Instant.now());
            a.setDismissedBy(user.getUserId());
            a.setDismissedByName(user.getFullName());
            alerts.save(a);
        }
        return a;
    }

    // ---- raising and clearing ------------------------------------------------------------------------

    /** Opens the alert, or refreshes the open one of the same kind for the business (and hotel when given). */
    @Transactional
    public Alert raise(String kind, String businessId, String hotelId, String title, String detail, String tone) {
        Optional<Alert> existing = hotelId != null ? alerts.findFirstByKindAndHotelIdAndDismissedAtIsNull(kind, hotelId)
                : alerts.findFirstByKindAndBusinessIdAndDismissedAtIsNull(kind, businessId);
        Alert a = existing.orElseGet(() -> Alert.builder().kind(kind).businessId(businessId).hotelId(hotelId).openedAt(Instant.now()).build());
        a.setTitle(title);
        a.setDetail(detail);
        a.setTone(tone);
        if (a.getBusinessName() == null && businessId != null) {
            a.setBusinessName(businessNames.name(businessId));
        }
        return alerts.save(a);
    }

    /** Dismisses the open alert of that kind for the business as "system": the cause ended. */
    @Transactional
    public void clear(String kind, String businessId) {
        alerts.findFirstByKindAndBusinessIdAndDismissedAtIsNull(kind, businessId).ifPresent(this::autoDismiss);
    }

    @Transactional
    public void clearHotel(String kind, String hotelId) {
        alerts.findFirstByKindAndHotelIdAndDismissedAtIsNull(kind, hotelId).ifPresent(this::autoDismiss);
    }

    private void autoDismiss(Alert a) {
        a.setDismissedAt(Instant.now());
        a.setDismissedBy("system");
        a.setDismissedByName("Cleared automatically");
        alerts.save(a);
    }

    // ---- event-driven rules --------------------------------------------------------------------------

    /** A declined payment: with {@link #DECLINES_PER_HOUR} or more at the hotel in the last hour, the POS is failing. */
    @Transactional
    public void onPaymentDeclined(String businessId, String hotelId) {
        if (hotelId == null) {
            return;
        }
        long declines = activity.countByHotelIdAndEventTypeAndOccurredAtAfter(hotelId, "PaymentDeclined", Instant.now().minus(1, ChronoUnit.HOURS));
        if (paymentsFailing(declines)) {
            raise(Alert.PAYMENTS_FAILING, businessId, hotelId, "Payments failing", declines + " card or transfer payments declined at this hotel in the last hour", "danger");
        }
    }

    /** An approved payment: once the hour's declines fall under the threshold the alert clears. */
    @Transactional
    public void onPaymentApproved(String hotelId) {
        if (hotelId == null) {
            return;
        }
        long declines = activity.countByHotelIdAndEventTypeAndOccurredAtAfter(hotelId, "PaymentDeclined", Instant.now().minus(1, ChronoUnit.HOURS));
        if (!paymentsFailing(declines)) {
            clearHotel(Alert.PAYMENTS_FAILING, hotelId);
        }
    }

    public static boolean paymentsFailing(long declinesInLastHour) {
        return declinesInLastHour >= DECLINES_PER_HOUR;
    }

    /** Urgent open tickets for a business raise TICKET_URGENT; none left clears it. */
    @Transactional
    public void refreshTicketUrgent(String businessId) {
        List<SupportTicket> urgent = tickets.findByBusinessIdAndStatusInOrderByCreatedAtDesc(businessId, List.of("OPEN", "IN_PROGRESS")).stream()
                .filter(t -> "URGENT".equalsIgnoreCase(t.getPriority())).toList();
        if (urgent.isEmpty()) {
            clear(Alert.TICKET_URGENT, businessId);
        } else {
            SupportTicket t = urgent.get(0);
            raise(Alert.TICKET_URGENT, businessId, null, "Urgent ticket: " + t.getSubject(),
                    urgent.size() == 1 ? "One urgent ticket is open" : urgent.size() + " urgent tickets are open", "danger");
        }
    }

    /** Overdue statements for a business raise FEES_OVERDUE; none left clears it. */
    @Transactional
    public void refreshFeesOverdue(String businessId) {
        List<FeeStatement> overdue = feeStatements.findByBusinessIdAndStatusIn(businessId, List.of("OVERDUE"));
        if (overdue.isEmpty()) {
            clear(Alert.FEES_OVERDUE, businessId);
        } else {
            raise(Alert.FEES_OVERDUE, businessId, null, "Fees overdue", overdue.size() + (overdue.size() == 1 ? " statement is" : " statements are") + " past the grace period", "danger");
        }
    }

    // ---- the nightly sweep ---------------------------------------------------------------------------

    /**
     * Every rule in one pass: overdue fees and urgent tickets (from local data), stalled onboarding (from the
     * drafts tenant listed), no activity and missed night audits (for the LIVE businesses tenant listed), and
     * PAYMENTS_FAILING alerts whose hour has long passed.
     */
    @Transactional
    public void sweep(List<TenantClient.Business> live, List<TenantClient.Draft> drafts) {
        Instant now = Instant.now();
        Set<String> withOverdue = new HashSet<>();
        for (FeeStatement s : feeStatements.findByStatusOrderByMonthDesc("OVERDUE")) {
            withOverdue.add(s.getBusinessId());
        }
        for (String b : withOverdue) {
            refreshFeesOverdue(b);
        }
        for (Alert a : alerts.findByKindAndDismissedAtIsNull(Alert.FEES_OVERDUE)) {
            if (!withOverdue.contains(a.getBusinessId())) {
                autoDismiss(a);
            }
        }

        Set<String> withUrgent = new HashSet<>();
        for (SupportTicket t : tickets.findByPriorityAndStatusIn("URGENT", List.of("OPEN", "IN_PROGRESS"))) {
            withUrgent.add(t.getBusinessId());
        }
        for (String b : withUrgent) {
            refreshTicketUrgent(b);
        }
        for (Alert a : alerts.findByKindAndDismissedAtIsNull(Alert.TICKET_URGENT)) {
            if (!withUrgent.contains(a.getBusinessId())) {
                autoDismiss(a);
            }
        }

        Set<String> stalled = new HashSet<>();
        for (TenantClient.Draft d : drafts) {
            if (isStalled(d, now)) {
                stalled.add(d.businessId());
                long days = Duration.between(d.updatedAt(), now).toDays();
                Alert a = raise(Alert.ONBOARDING_STALLED, d.businessId(), null, "Onboarding stalled", "Step " + d.step() + " not touched for " + days + " days", "warning");
                if (a.getBusinessName() == null) {
                    a.setBusinessName(d.businessName());
                    alerts.save(a);
                }
            }
        }
        if (!drafts.isEmpty()) {
            for (Alert a : alerts.findByKindAndDismissedAtIsNull(Alert.ONBOARDING_STALLED)) {
                if (!stalled.contains(a.getBusinessId())) {
                    autoDismiss(a);
                }
            }
        }

        Set<String> quiet = new HashSet<>();
        Set<String> noAudit = new HashSet<>();
        for (TenantClient.Business b : live) {
            // "Any?" questions answered with exists (stops at the first row, served by the business/time and business/type/time indexes).
            boolean recent = activity.existsByBusinessIdAndOccurredAtAfter(b.id(), now.minus(QUIET_DAYS, ChronoUnit.DAYS));
            if (!recent) {
                quiet.add(b.id());
                Alert a = raise(Alert.NO_ACTIVITY, b.id(), null, "No activity", "Nothing happened in this business for " + QUIET_DAYS + " days", "grey");
                if (a.getBusinessName() == null) {
                    a.setBusinessName(b.name());
                    alerts.save(a);
                }
                continue;
            }
            boolean closedLately = activity.existsByBusinessIdAndEventTypeAndOccurredAtAfter(b.id(), "DayClosed", now.minus(AUDIT_DAYS, ChronoUnit.DAYS));
            // "Ever closed a day" only matters when none was closed lately; the log itself keeps auditRetentionDays.
            if (!closedLately && activity.existsByBusinessIdAndEventType(b.id(), "DayClosed")) {
                noAudit.add(b.id());
                raise(Alert.NIGHT_AUDIT_MISSED, b.id(), null, "Night audit missed", "No day has been closed for " + AUDIT_DAYS + " days", "warning");
            }
        }
        if (!live.isEmpty()) {
            for (Alert a : alerts.findByKindAndDismissedAtIsNull(Alert.NO_ACTIVITY)) {
                if (!quiet.contains(a.getBusinessId())) {
                    autoDismiss(a);
                }
            }
            for (Alert a : alerts.findByKindAndDismissedAtIsNull(Alert.NIGHT_AUDIT_MISSED)) {
                if (!noAudit.contains(a.getBusinessId())) {
                    autoDismiss(a);
                }
            }
        }

        for (Alert a : alerts.findByKindAndDismissedAtIsNull(Alert.PAYMENTS_FAILING)) {
            long declines = a.getHotelId() == null ? 0 : activity.countByHotelIdAndEventTypeAndOccurredAtAfter(a.getHotelId(), "PaymentDeclined", now.minus(1, ChronoUnit.HOURS));
            if (!paymentsFailing(declines)) {
                autoDismiss(a);
            }
        }
    }

    public static boolean isStalled(TenantClient.Draft d, Instant now) {
        return d.businessId() != null && "IN_PROGRESS".equals(d.status()) && d.updatedAt() != null
                && Duration.between(d.updatedAt(), now).toDays() >= STALLED_DAYS;
    }
}
