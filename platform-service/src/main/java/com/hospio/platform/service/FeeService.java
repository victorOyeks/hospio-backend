package com.hospio.platform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.response.PagedResponse;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.util.Money;
import com.hospio.platform.client.PaymentClient;
import com.hospio.platform.client.ReservationClient;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.dto.AdminResponses.FeeLine;
import com.hospio.platform.dto.AdminResponses.FeeStatementResponse;
import com.hospio.platform.dto.AdminResponses.GenerateResult;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.Commission;
import com.hospio.platform.entity.FeePayment;
import com.hospio.platform.entity.FeeRule;
import com.hospio.platform.entity.FeeStatement;
import com.hospio.platform.entity.Marketer;
import com.hospio.platform.repository.CommissionRepository;
import com.hospio.platform.repository.FeePaymentRepository;
import com.hospio.platform.repository.FeeRuleRepository;
import com.hospio.platform.repository.FeeStatementRepository;
import com.hospio.platform.repository.MarketerRepository;
import com.hospio.platform.settings.FeeSettings;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Fees and billing: rules per business (mirrored to tenant's Business.feeModel/feeValue), monthly statements
 * generated from each business's fee model, payments against them, the OVERDUE rule and the statement email.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeeService {

    public static final String PERCENT_OF_PAYMENTS = "PERCENT_OF_PAYMENTS";
    public static final String PER_ROOM_NIGHT = "PER_ROOM_NIGHT";
    public static final String PERCENT_OF_BOOKINGS = "PERCENT_OF_BOOKINGS";
    static final Set<String> OPEN_STATUSES = Set.of("DUE", "PART_PAID", "OVERDUE");
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.of("Africa/Lagos"));
    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final FeeRuleRepository feeRules;
    private final FeeStatementRepository feeStatements;
    private final FeePaymentRepository feePayments;
    private final MarketerRepository marketers;
    private final CommissionRepository commissions;
    private final TenantClient tenant;
    private final PaymentClient payments;
    private final ReservationClient reservations;
    private final PlatformSettingsService settings;
    private final DeliveryService deliveries;
    private final ObjectMapper objectMapper;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    // ---- rules ---------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<FeeRule> rules(String businessId) {
        return feeRules.findByBusinessIdOrderByStartsOnDesc(businessId);
    }

    /** Saves the rule and writes the same model and value onto the business in tenant-service, so the two stay in step. */
    @Transactional
    public FeeRule saveRule(PlatformRequests.FeeRuleRequest request) {
        String model = request.model().toUpperCase(Locale.ROOT);
        if (!Set.of(PERCENT_OF_PAYMENTS, PER_ROOM_NIGHT, PERCENT_OF_BOOKINGS).contains(model)) {
            throw new BadRequestException("Fee model must be PERCENT_OF_PAYMENTS, PER_ROOM_NIGHT or PERCENT_OF_BOOKINGS");
        }
        if (request.value().signum() < 0) {
            throw new BadRequestException("The fee value cannot be negative");
        }
        if (!PER_ROOM_NIGHT.equals(model) && request.value().compareTo(java.math.BigDecimal.valueOf(100)) > 0) {
            throw new BadRequestException("A percentage fee must be between 0 and 100");
        }
        FeeRule rule = feeRules.save(FeeRule.builder().businessId(request.businessId()).model(model).value(request.value())
                .startsOn(request.startsOn()).endsOn(request.endsOn()).build());
        if (!tenant.setFee(request.businessId(), model, request.value())) {
            log.warn("Fee rule {} saved but tenant-service did not take the business's fee model; the Businesses list may show the old one", rule.getId());
        }
        return rule;
    }

    // ---- statements ----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PagedResponse<FeeStatementResponse> statements(String businessId, String status, String month, int page, int size) {
        Specification<FeeStatement> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (StringUtils.hasText(businessId)) {
                ps.add(cb.equal(root.get("businessId"), businessId));
            }
            if (StringUtils.hasText(status)) {
                ps.add(cb.equal(root.get("status"), status.toUpperCase(Locale.ROOT)));
            }
            if (StringUtils.hasText(month)) {
                ps.add(cb.equal(root.get("month"), month));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<FeeStatement> result = feeStatements.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "month").and(Sort.by("businessName"))));
        Map<String, List<FeePayment>> byStatement = new HashMap<>();
        if (!result.isEmpty()) {
            for (FeePayment p : feePayments.findByStatementIdInOrderByPaidOnAsc(result.getContent().stream().map(FeeStatement::getId).toList())) {
                byStatement.computeIfAbsent(p.getStatementId(), k -> new ArrayList<>()).add(p);
            }
        }
        return PagedResponse.of(result, s -> toResponse(s, byStatement.getOrDefault(s.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public FeeStatementResponse statement(String id) {
        FeeStatement s = find(id);
        return toResponse(s, feePayments.findByStatementIdOrderByPaidOnAsc(id));
    }

    /** Statements of one business, for the Money tab and the overview. */
    @Transactional(readOnly = true)
    public List<FeeStatementResponse> statementsOf(String businessId) {
        return feeStatements.findByBusinessIdOrderByMonthDesc(businessId).stream().map(s -> toResponse(s, feePayments.findByStatementIdOrderByPaidOnAsc(s.getId()))).toList();
    }

    /**
     * One statement per LIVE or AT_RISK business (or the one named) for the month, from its fee model:
     * PERCENT_OF_PAYMENTS on approved payments, PER_ROOM_NIGHT on nights sold, PERCENT_OF_BOOKINGS on booking value.
     * A business that already has the month's statement is skipped. Commissions follow; the owner gets the statement.
     */
    public GenerateResult generate(PlatformRequests.GenerateStatementsRequest request) {
        YearMonth ym = parseMonth(request.month());
        FeeSettings fees = settings.fees();
        List<TenantClient.Business> targets = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (StringUtils.hasText(request.businessId())) {
            targets.add(tenant.business(request.businessId()).orElseThrow(() -> new ResourceNotFoundException("Business not found")));
        } else {
            targets.addAll(tenant.businesses("LIVE", null));
            targets.addAll(tenant.businesses("AT_RISK", null));
            if (targets.isEmpty()) {
                notes.add("tenant-service listed no LIVE or AT_RISK businesses (GET /internal/businesses?stage= may not be there yet); name a businessId to generate one");
            }
        }
        int created = 0;
        int skipped = 0;
        for (TenantClient.Business b : targets) {
            if (feeStatements.findByBusinessIdAndMonth(b.id(), ym.toString()).isPresent()) {
                skipped++;
                continue;
            }
            // One transaction per business: one that fails (a service didn't answer) is left for the next run and the rest go on.
            try {
                tx.executeWithoutResult(st -> createFor(b, ym, fees, notes));
                created++;
            } catch (RuntimeException e) {
                skipped++;
                notes.add(b.name() + ": not made this time, run it again (" + e.getMessage() + ")");
                log.warn("Fee statement for {} ({}) not made: {}", b.name(), b.id(), e.getMessage());
            }
        }
        return new GenerateResult(created, skipped, notes);
    }

    /** Manual statement with a given base (kept for the Money tab). */
    @Transactional
    public FeeStatementResponse createManual(PlatformRequests.FeeStatementRequest request) {
        YearMonth ym = parseMonth(request.month());
        TenantClient.Business b = tenant.business(request.businessId()).orElse(null);
        String model = resolveModel(request.businessId(), b);
        BigDecimal value = resolveValue(request.businessId(), b);
        FeeSettings fees = settings.fees();
        FeeStatement s = feeStatements.findByBusinessIdAndMonth(request.businessId(), ym.toString())
                .orElseGet(() -> FeeStatement.builder().businessId(request.businessId()).month(ym.toString()).build());
        s.setBaseAmount(Money.of(request.baseAmount()));
        s.setFeeAmount(computeFee(model, value, request.baseAmount(), 0));
        s.setBusinessName(b == null ? s.getBusinessName() : b.name());
        s.setDueOn(dueOn(ym, fees.dueDay()));
        s.setStatus(status(s, LocalDate.now(LAGOS), fees.graceDays()));
        feeStatements.save(s);
        createCommissions(s, ym);
        return toResponse(s, feePayments.findByStatementIdOrderByPaidOnAsc(s.getId()));
    }

    private void createFor(TenantClient.Business summary, YearMonth ym, FeeSettings fees, List<String> notes) {
        // the stage listing gives summaries; the full business carries the owner email and hotel names the statement needs
        TenantClient.Business b = summary.hotels().isEmpty() ? tenant.business(summary.id()).orElse(summary) : summary;
        String model = resolveModel(b.id(), b);
        BigDecimal value = resolveValue(b.id(), b);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        List<FeeLine> lines = new ArrayList<>();
        BigDecimal base;
        BigDecimal fee;
        Map<String, String> hotelNames = new HashMap<>();
        b.hotels().forEach(h -> hotelNames.put(h.id(), h.name()));
        switch (model) {
            case PERCENT_OF_PAYMENTS -> {
                PaymentClient.Summary paid = payments.summary(b.id(), from, to);
                base = Money.of(paid.gross());
                for (PaymentClient.HotelTotal h : paid.byHotel()) {
                    lines.add(new FeeLine(h.hotelId(), hotelNames.getOrDefault(h.hotelId(), h.hotelId()), Money.of(h.gross()), Money.percent(h.gross(), value)));
                }
                fee = lines.isEmpty() ? Money.percent(base, value) : Money.of(lines.stream().map(FeeLine::fee).reduce(BigDecimal.ZERO, BigDecimal::add));
            }
            case PER_ROOM_NIGHT -> {
                ReservationClient.Nights nights = reservations.nights(b.id(), from, to);
                base = BigDecimal.valueOf(nights.nights());
                fee = computeFee(model, value, null, nights.nights());
                if (nights.nights() == 0) {
                    notes.add(b.name() + ": no room-nights reported for " + ym + " (reservation-service may not have GET /internal/admin/nights yet)");
                }
            }
            case PERCENT_OF_BOOKINGS -> {
                ReservationClient.Nights nights = reservations.nights(b.id(), from, to);
                base = Money.of(nights.value());
                fee = computeFee(model, value, nights.value(), nights.nights());
            }
            default -> throw new BadRequestException("Unknown fee model " + model);
        }
        FeeStatement s = FeeStatement.builder().businessId(b.id()).businessName(b.name()).month(ym.toString()).baseAmount(Money.of(base))
                .feeAmount(fee).dueOn(dueOn(ym, fees.dueDay())).lines(writeLines(lines)).build();
        s.setStatus(status(s, LocalDate.now(LAGOS), fees.graceDays()));
        feeStatements.save(s);
        createCommissions(s, ym);
        sendStatement(s, b);
    }

    /** The fee for a base under a model: % of payments or bookings on the amount, or value × nights for PER_ROOM_NIGHT. */
    public static BigDecimal computeFee(String model, BigDecimal value, BigDecimal baseAmount, long nights) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value;
        return switch (model == null ? "" : model.toUpperCase(Locale.ROOT)) {
            case PER_ROOM_NIGHT -> Money.of(v.multiply(BigDecimal.valueOf(nights)));
            case PERCENT_OF_PAYMENTS, PERCENT_OF_BOOKINGS -> Money.percent(baseAmount, v);
            default -> throw new BadRequestException("Unknown fee model " + model);
        };
    }

    /** Due on the {@code dueDay}th of the month after the statement's month (clamped to that month's length). */
    public static LocalDate dueOn(YearMonth month, int dueDay) {
        YearMonth next = month.plusMonths(1);
        return next.atDay(Math.max(1, Math.min(dueDay, next.lengthOfMonth())));
    }

    /** DUE, PART_PAID, PAID, or OVERDUE once dueOn + grace days has passed with money still owed. */
    public static String status(FeeStatement s, LocalDate today, int graceDays) {
        BigDecimal paid = s.getPaidAmount() == null ? BigDecimal.ZERO : s.getPaidAmount();
        BigDecimal fee = s.getFeeAmount() == null ? BigDecimal.ZERO : s.getFeeAmount();
        if (fee.signum() > 0 && paid.compareTo(fee) >= 0) {
            return "PAID";
        }
        if (fee.signum() == 0) {
            return "PAID";
        }
        if (s.getDueOn() != null && today.isAfter(s.getDueOn().plusDays(graceDays))) {
            return "OVERDUE";
        }
        return paid.signum() > 0 ? "PART_PAID" : "DUE";
    }

    @Transactional
    public FeeStatementResponse recordPayment(String statementId, PlatformRequests.FeePaymentRequest request) {
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new BadRequestException("The amount must be more than zero");
        }
        FeeStatement s = find(statementId);
        HospioUserDetails user = CurrentUser.require();
        feePayments.save(FeePayment.builder().businessId(s.getBusinessId()).statementId(s.getId()).amount(Money.of(request.amount()))
                .paidOn(request.paidOn() == null ? LocalDate.now(LAGOS) : request.paidOn()).reference(request.reference())
                .recordedBy(user.getUserId()).recordedByName(user.getFullName()).build());
        s.setPaidAmount(Money.add(s.getPaidAmount(), request.amount()));
        s.setStatus(status(s, LocalDate.now(LAGOS), settings.fees().graceDays()));
        feeStatements.save(s);
        return toResponse(s, feePayments.findByStatementIdOrderByPaidOnAsc(s.getId()));
    }

    /** Re-issues the FEE_STATEMENT email to the owner. */
    @Transactional
    public List<Delivery> send(String statementId) {
        FeeStatement s = find(statementId);
        TenantClient.Business b = tenant.business(s.getBusinessId()).orElse(null);
        return sendStatement(s, b);
    }

    private List<Delivery> sendStatement(FeeStatement s, TenantClient.Business b) {
        String to = b == null ? null : b.ownerEmail();
        if (!StringUtils.hasText(to)) {
            log.warn("Statement {} for business {} has no owner email to send to", s.getId(), s.getBusinessId());
            return List.of();
        }
        List<Delivery> sent = deliveries.send(Delivery.email(to, "Your Hospio360 fee statement for " + s.getMonth(), statementText(s, b),
                Delivery.Kind.FEE_STATEMENT, s.getId()));
        s.setSentAt(Instant.now());
        feeStatements.save(s);
        return sent;
    }

    String statementText(FeeStatement s, TenantClient.Business b) {
        NumberFormat naira = NumberFormat.getNumberInstance(Locale.UK);
        naira.setMinimumFractionDigits(2);
        naira.setMaximumFractionDigits(2);
        StringBuilder t = new StringBuilder();
        t.append("Hello ").append(b == null || b.ownerName() == null ? "there" : b.ownerName()).append(",\n\n");
        t.append("Here is the Hospio360 fee statement for ").append(s.getBusinessName() == null ? "your business" : s.getBusinessName())
                .append(" for ").append(YearMonth.parse(s.getMonth()).format(DateTimeFormatter.ofPattern("MMMM yyyy"))).append(".\n\n");
        for (FeeLine l : readLines(s.getLines())) {
            t.append("  ").append(l.hotelName()).append(": base NGN ").append(naira.format(l.base())).append(" → fee NGN ").append(naira.format(l.fee())).append('\n');
        }
        t.append("\nBase: NGN ").append(naira.format(s.getBaseAmount())).append('\n');
        t.append("Fee due: NGN ").append(naira.format(s.getFeeAmount())).append('\n');
        if (s.getPaidAmount() != null && s.getPaidAmount().signum() > 0) {
            t.append("Paid so far: NGN ").append(naira.format(s.getPaidAmount())).append('\n');
            t.append("Outstanding: NGN ").append(naira.format(s.outstanding())).append('\n');
        }
        if (s.getDueOn() != null) {
            t.append("Due on: ").append(DAY.format(s.getDueOn().atStartOfDay(LAGOS).toInstant())).append('\n');
        }
        t.append("\nThank you,\nHospio360\n");
        return t.toString();
    }

    /** The nightly job: a DUE or PART_PAID statement past dueOn + grace days becomes OVERDUE. Returns how many flipped. */
    @Transactional
    public int markOverdue() {
        int graceDays = settings.fees().graceDays();
        LocalDate today = LocalDate.now(LAGOS);
        int flipped = 0;
        for (FeeStatement s : feeStatements.findByStatusIn(List.of("DUE", "PART_PAID"))) {
            String next = status(s, today, graceDays);
            if (!next.equals(s.getStatus())) {
                s.setStatus(next);
                feeStatements.save(s);
                flipped++;
            }
        }
        return flipped;
    }

    @Transactional(readOnly = true)
    public List<FeeStatement> overdue() {
        return feeStatements.findByStatusOrderByMonthDesc("OVERDUE");
    }

    /** Sum of what is still owed on statements in the given statuses (dashboard). */
    @Transactional(readOnly = true)
    public BigDecimal outstanding(Set<String> statuses) {
        return Money.of(feeStatements.findByStatusIn(statuses).stream().map(FeeStatement::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    /** Fees billed for a month: the platform's recurring revenue. */
    @Transactional(readOnly = true)
    public BigDecimal billed(YearMonth month) {
        return Money.of(feeStatements.findByMonth(month.toString()).stream().map(FeeStatement::getFeeAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Transactional(readOnly = true)
    public long overdueCount(String businessId) {
        return feeStatements.findByBusinessIdAndStatusIn(businessId, List.of("OVERDUE")).size();
    }

    // ---- commissions on statements -------------------------------------------------------------------

    /** The marketer's share of this month's fee, for every agreement still earning; one per agreement and month. */
    void createCommissions(FeeStatement s, YearMonth ym) {
        for (Marketer m : marketers.findByBusinessId(s.getBusinessId())) {
            if (!m.earnsIn(ym) || commissions.findByMarketerIdAndMonth(m.getId(), ym.toString()).isPresent()) {
                continue;
            }
            BigDecimal gross = Money.percent(s.getFeeAmount(), m.getSharePct());
            BigDecimal wht = Money.percent(gross, m.getWhtPct());
            commissions.save(Commission.builder().businessId(s.getBusinessId()).marketerId(m.getId()).profileId(m.getProfileId()).month(ym.toString())
                    .baseAmount(s.getFeeAmount()).gross(gross).wht(wht).net(gross.subtract(wht)).build());
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private String resolveModel(String businessId, TenantClient.Business b) {
        FeeRule rule = feeRules.findByBusinessIdOrderByStartsOnDesc(businessId).stream().findFirst().orElse(null);
        if (rule != null) {
            return rule.getModel();
        }
        if (b != null && StringUtils.hasText(b.feeModel())) {
            return b.feeModel();
        }
        return settings.fees().model();
    }

    private BigDecimal resolveValue(String businessId, TenantClient.Business b) {
        FeeRule rule = feeRules.findByBusinessIdOrderByStartsOnDesc(businessId).stream().findFirst().orElse(null);
        if (rule != null) {
            return rule.getValue();
        }
        if (b != null && b.feeValue() != null) {
            return b.feeValue();
        }
        FeeSettings fees = settings.fees();
        return PER_ROOM_NIGHT.equals(fees.model()) ? fees.perRoom() : fees.revenuePct();
    }

    private static YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (Exception e) {
            throw new BadRequestException("month must look like 2026-11");
        }
    }

    private FeeStatement find(String id) {
        return feeStatements.findById(id).orElseThrow(() -> new ResourceNotFoundException("Statement not found"));
    }

    FeeStatementResponse toResponse(FeeStatement s, List<FeePayment> payments) {
        return new FeeStatementResponse(s.getId(), s.getBusinessId(), s.getBusinessName(), s.getMonth(), s.getBaseAmount(), s.getFeeAmount(),
                s.getPaidAmount(), s.outstanding(), s.getStatus(), s.getDueOn(), s.getSentAt(), readLines(s.getLines()), payments, s.getCreatedAt(), s.getUpdatedAt());
    }

    List<FeeLine> readLines(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<FeeLine>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    String writeLines(List<FeeLine> lines) {
        try {
            return objectMapper.writeValueAsString(lines);
        } catch (Exception e) {
            return "[]";
        }
    }
}
