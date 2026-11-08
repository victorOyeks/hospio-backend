package com.hospio.platform.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ChangeByPlatformEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.Commission;
import com.hospio.platform.entity.FeeRule;
import com.hospio.platform.entity.FeeStatement;
import com.hospio.platform.entity.HealthScore;
import com.hospio.platform.entity.Marketer;
import com.hospio.platform.entity.PlatformChange;
import com.hospio.platform.entity.Report;
import com.hospio.platform.entity.SupportTicket;
import com.hospio.platform.repository.ActivityEntryRepository;
import com.hospio.platform.repository.CommissionRepository;
import com.hospio.platform.repository.FeeRuleRepository;
import com.hospio.platform.repository.FeeStatementRepository;
import com.hospio.platform.repository.HealthScoreRepository;
import com.hospio.platform.repository.MarketerRepository;
import com.hospio.platform.repository.PlatformChangeRepository;
import com.hospio.platform.repository.ReportRepository;
import com.hospio.platform.repository.SupportTicketRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Platform admin: fees & billing (D), marketers (E), support (G), health (L7), edits as H.M.S (L1-L3), reports (7). */
@Service
@RequiredArgsConstructor
public class PlatformAdminService {

    private final FeeRuleRepository feeRules;
    private final FeeStatementRepository feeStatements;
    private final MarketerRepository marketers;
    private final CommissionRepository commissions;
    private final SupportTicketRepository tickets;
    private final HealthScoreRepository healthScores;
    private final PlatformChangeRepository changes;
    private final ReportRepository reports;
    private final ActivityEntryRepository activity;
    private final EventPublisher events;

    // ---- fees ----------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<FeeRule> feeRules(String businessId) {
        requirePlatform();
        return feeRules.findByBusinessIdOrderByStartsOnDesc(businessId);
    }

    @Transactional
    public FeeRule saveFeeRule(PlatformRequests.FeeRuleRequest request) {
        requirePlatform();
        return feeRules.save(FeeRule.builder().businessId(request.businessId()).model(request.model()).value(request.value())
                .startsOn(request.startsOn()).endsOn(request.endsOn()).build());
    }

    /** D4 · A statement for a month: the fee on the base (guest payments or room-nights the rule names). */
    @Transactional
    public FeeStatement createStatement(PlatformRequests.FeeStatementRequest request) {
        requirePlatform();
        FeeRule rule = feeRules.findByBusinessIdOrderByStartsOnDesc(request.businessId()).stream().findFirst()
                .orElseThrow(() -> new BadRequestException("No fee rule for this business"));
        BigDecimal fee = rule.getModel().startsWith("PERCENT") ? Money.percent(request.baseAmount(), rule.getValue())
                : Money.of(request.baseAmount().multiply(rule.getValue()));
        FeeStatement s = feeStatements.findByBusinessIdAndMonth(request.businessId(), request.month())
                .orElseGet(() -> FeeStatement.builder().businessId(request.businessId()).month(request.month()).build());
        s.setBaseAmount(Money.of(request.baseAmount()));
        s.setFeeAmount(fee);
        feeStatements.save(s);
        for (Marketer m : marketers.findByBusinessId(request.businessId())) {
            if (!"ACTIVE".equals(m.getStatus()) || m.getStartedOn() == null || m.getStartedOn().plusMonths(m.getMonths()).isBefore(LocalDate.now())) {
                continue;
            }
            BigDecimal gross = Money.percent(fee, m.getSharePct());
            BigDecimal wht = Money.percent(gross, m.getWhtPct());
            commissions.save(Commission.builder().businessId(request.businessId()).marketerId(m.getId()).month(request.month())
                    .baseAmount(fee).gross(gross).wht(wht).net(gross.subtract(wht)).build());
        }
        return s;
    }

    @Transactional(readOnly = true)
    public List<FeeStatement> statements(String businessId) {
        return businessId == null ? feeStatements.findByStatusOrderByMonthDesc("DUE") : feeStatements.findByBusinessIdOrderByMonthDesc(businessId);
    }

    @Transactional
    public FeeStatement recordFeePayment(String statementId, PlatformRequests.FeePaymentRequest request) {
        requirePlatform();
        FeeStatement s = feeStatements.findById(statementId).orElseThrow(() -> new ResourceNotFoundException("Statement not found"));
        s.setPaidAmount(Money.add(s.getPaidAmount(), request.amount()));
        s.setStatus(s.getPaidAmount().compareTo(s.getFeeAmount()) >= 0 ? "PAID" : "PART_PAID");
        return feeStatements.save(s);
    }

    // ---- marketers -----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Marketer> marketers() {
        requirePlatform();
        return marketers.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<Marketer> myReferrals() {
        return marketers.findByUserId(CurrentUser.require().getUserId());
    }

    @Transactional
    public Marketer addMarketer(PlatformRequests.MarketerRequest request) {
        requirePlatform();
        return marketers.save(Marketer.builder().businessId(request.businessId()).userId(request.userId()).fullName(request.fullName())
                .sharePct(request.sharePct() == null ? new BigDecimal("20") : request.sharePct()).months(request.months() == null ? 24 : request.months()).build());
    }

    @Transactional
    public Marketer setMarketerStatus(String id, String status) {
        requirePlatform();
        Marketer m = marketers.findById(id).orElseThrow(() -> new ResourceNotFoundException("Marketer not found"));
        m.setStatus(status.toUpperCase());
        if ("ACTIVE".equals(m.getStatus()) && m.getStartedOn() == null) {
            m.setStartedOn(LocalDate.now());
        }
        if ("ENDED".equals(m.getStatus())) {
            m.setEndedOn(LocalDate.now());
        }
        return marketers.save(m);
    }

    @Transactional(readOnly = true)
    public List<Commission> commissions(String marketerId) {
        return marketerId == null ? commissions.findByStatusOrderByMonthDesc("DUE") : commissions.findByMarketerIdOrderByMonthDesc(marketerId);
    }

    @Transactional
    public Commission payCommission(String id) {
        requirePlatform();
        Commission c = commissions.findById(id).orElseThrow(() -> new ResourceNotFoundException("Commission not found"));
        c.setStatus("PAID");
        c.setPaidAt(Instant.now());
        return commissions.save(c);
    }

    // ---- support -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SupportTicket> tickets(String status) {
        HospioUserDetails user = CurrentUser.require();
        return user.isPlatform() ? tickets.findByStatusOrderByCreatedAtDesc(status == null ? "OPEN" : status)
                : tickets.findByBusinessIdOrderByCreatedAtDesc(TenantContext.requireBusiness());
    }

    @Transactional
    public SupportTicket openTicket(PlatformRequests.TicketRequest request) {
        HospioUserDetails user = CurrentUser.require();
        String businessId = user.isPlatform() ? request.businessId() : TenantContext.requireBusiness();
        if (businessId == null) {
            throw new BadRequestException("Say which business this is about");
        }
        return tickets.save(SupportTicket.builder().businessId(businessId).subject(request.subject()).body(request.body())
                .priority(request.priority() == null ? "NORMAL" : request.priority()).openedBy(user.getUserId()).build());
    }

    @Transactional
    public SupportTicket resolveTicket(String id, PlatformRequests.ResolveTicketRequest request) {
        requirePlatform();
        SupportTicket t = tickets.findById(id).orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
        t.setStatus("RESOLVED");
        t.setResolution(request.resolution());
        t.setResolvedAt(Instant.now());
        return tickets.save(t);
    }

    // ---- health --------------------------------------------------------------------------------------

    /** L7: activity in the last 30 days, open tickets and unpaid fees, in one number. */
    @Transactional
    public HealthScore computeHealth(String businessId) {
        requirePlatform();
        long actions = activity.countByBusinessIdAndOccurredAtAfter(businessId, Instant.now().minus(30, ChronoUnit.DAYS));
        long openTickets = tickets.findByBusinessIdOrderByCreatedAtDesc(businessId).stream().filter(t -> "OPEN".equals(t.getStatus())).count();
        long dueStatements = feeStatements.findByBusinessIdOrderByMonthDesc(businessId).stream().filter(s -> !"PAID".equals(s.getStatus())).count();
        int score = (int) Math.max(0, Math.min(100, 40 + Math.min(actions, 300) / 5 - openTickets * 5 - dueStatements * 10));
        HealthScore h = healthScores.findByBusinessId(businessId).orElseGet(() -> HealthScore.builder().businessId(businessId).build());
        h.setScore(score);
        h.setFactors("{\"actions30d\":" + actions + ",\"openTickets\":" + openTickets + ",\"dueStatements\":" + dueStatements + "}");
        h.setComputedAt(Instant.now());
        return healthScores.save(h);
    }

    // ---- changes by H.M.S ----------------------------------------------------------------------------

    @Transactional
    public PlatformChange recordChange(PlatformRequests.PlatformChangeRequest request) {
        HospioUserDetails user = CurrentUser.require();
        requirePlatform();
        PlatformChange c = changes.save(PlatformChange.builder().businessId(request.businessId()).hotelId(request.hotelId()).area(request.area())
                .description(request.description()).reason(request.reason()).beforeJson(request.beforeJson()).afterJson(request.afterJson())
                .madeBy(user.getUserId()).madeByName("H.M.S · " + user.getFullName()).undoUntil(Instant.now().plus(7, ChronoUnit.DAYS)).build());
        TenantContext.runWith(Tenant.systemFor(request.businessId(), request.hotelId(), user.getUserId(), "H.M.S · " + user.getFullName()), () ->
                events.publish(KafkaTopics.CHANGE_BY_PLATFORM, ChangeByPlatformEvent.builder().businessId(request.businessId()).hotelId(request.hotelId())
                        .actorId(user.getUserId()).actorName("H.M.S · " + user.getFullName()).changeId(c.getId()).area(c.getArea())
                        .description(c.getDescription()).undoUntil(c.getUndoUntil()).build()));
        return c;
    }

    @Transactional(readOnly = true)
    public List<PlatformChange> changes(String businessId) {
        HospioUserDetails user = CurrentUser.require();
        return changes.findByBusinessIdOrderByCreatedAtDesc(user.isPlatform() ? businessId : TenantContext.requireBusiness());
    }

    /** Owner section 16.3 · Undo a change, within 7 days. The undo itself is applied by the area's service from before_json. */
    @Transactional
    public PlatformChange undo(String id) {
        HospioUserDetails user = CurrentUser.require();
        if (!user.has(com.hospio.common.enums.Role.OWNER)) {
            throw new ForbiddenException("Only the owner can undo a change by H.M.S");
        }
        PlatformChange c = changes.findById(id).orElseThrow(() -> new ResourceNotFoundException("Change not found"));
        if (c.getUndoneAt() != null) {
            throw new BadRequestException("Already undone");
        }
        if (Instant.now().isAfter(c.getUndoUntil())) {
            throw new BadRequestException("The 7-day undo window has passed");
        }
        c.setUndoneAt(Instant.now());
        return changes.save(c);
    }

    // ---- reports -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Report> reports() {
        return reports.findByBusinessIdOrderByCreatedAtDesc(TenantContext.requireBusiness());
    }

    @Transactional
    public Report requestReport(PlatformRequests.ReportRequest request) {
        return reports.save(Report.builder().kind(request.kind().toUpperCase()).periodFrom(request.periodFrom()).periodTo(request.periodTo())
                .schedule(request.schedule()).recipients(request.recipients()).status(request.schedule() == null ? "READY" : "SCHEDULED")
                .requestedBy(CurrentUser.require().getUserId()).build());
    }

    private static void requirePlatform() {
        if (!CurrentUser.require().isPlatform()) {
            throw new ForbiddenException("Only H.M.S staff can do this");
        }
    }
}
