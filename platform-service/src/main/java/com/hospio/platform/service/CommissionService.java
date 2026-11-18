package com.hospio.platform.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.platform.entity.Commission;
import com.hospio.platform.entity.DailyFee;
import com.hospio.platform.entity.Marketer;
import com.hospio.platform.entity.MarketerProfile;
import com.hospio.platform.entity.MarketerStatement;
import com.hospio.platform.repository.CommissionRepository;
import com.hospio.platform.repository.DailyFeeRepository;
import com.hospio.platform.repository.MarketerProfileRepository;
import com.hospio.platform.repository.MarketerRepository;
import com.hospio.platform.repository.MarketerStatementRepository;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Marketer commission on collected daily fees (docs/plan-marketers.md).
 *
 * <ul>
 *   <li><b>Accrual</b>: each time a daily fee debit collects money, the platform part it collected (never the device share; data
 *   plans and message charges are not on the daily fee at all) earns the business's agreement {@code sharePct} % (20), less
 *   {@code whtPct} % WHT (5), when the fee day is covered by the agreement and inside its window: 12 months from the business's first
 *   collected fee. One line per debit reference and running total, so a debit never earns twice and reruns are safe.</li>
 *   <li><b>Clawback</b>: a refund or reversal of a collected fee is a negative line on the next statement.</li>
 *   <li><b>Statements</b>: on the 5th, one per marketer for the month before, with every line not on a statement yet and what earlier
 *   statements under the minimum (NGN 5,000) carried in. 7 days to query; finance approves, pays by bank transfer outside Hospio360
 *   and records the payment; the marketer is emailed.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionService {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    static final int QUERY_DAYS = 7;
    static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.UK);
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);
    public static final String QUERY_ALERT = "MARKETER_QUERY";

    private final CommissionRepository commissions;
    private final MarketerRepository agreements;
    private final MarketerProfileRepository profiles;
    private final MarketerStatementRepository statements;
    private final DailyFeeRepository fees;
    private final PlatformSettingsService settings;
    private final DeliveryService deliveries;
    private final AlertService alerts;
    private final BusinessNames businessNames;
    private final TransactionTemplate tx;

    // ---- accrual -------------------------------------------------------------------------------------

    /**
     * After a debit answered: accrue on what the day's platform fee collection grew by. Never throws (the debit is already done);
     * a failure is logged and the next run for the same day ({@link #accrue}) picks the difference up.
     */
    public void onCollected(DailyFee f, String reference) {
        try {
            TenantContext.runWith(Tenant.system("commission"), () -> {
                tx.executeWithoutResult(st -> accrue(fees.findById(f.getId()).orElse(f), reference, LocalDate.now(LAGOS)));
                return null;
            });
        } catch (DataIntegrityViolationException dup) {
            log.info("Commission for daily fee {} ({}) already accrued", f.getId(), reference);
        } catch (RuntimeException e) {
            log.error("Commission for daily fee {} ({}) not accrued: {}", f.getId(), reference, e.getMessage(), e);
        }
    }

    /** The line for what {@code f} collected of its platform fee beyond what already earned; null when nothing (more) earns. */
    Commission accrue(DailyFee f, String reference, LocalDate on) {
        BigDecimal earnedBase = BigDecimal.ZERO;
        for (Commission c : commissions.findBySourceIdOrderByCreatedAtAsc(f.getId())) {
            if (Commission.DAILY_FEE.equals(c.getSource())) {
                earnedBase = earnedBase.add(c.getBaseAmount());
            }
        }
        BigDecimal platformCollected = f.platformPaid();
        BigDecimal base = Money.of(platformCollected.subtract(earnedBase));
        if (base.signum() <= 0) {
            return null;
        }
        Marketer m = agreementFor(f.getBusinessId(), f.getFeeDate());
        if (m == null) {
            return null;
        }
        LocalDate anchor = fees.firstCollectedDate(f.getBusinessId());
        if (anchor == null || anchor.isAfter(f.getFeeDate())) {
            anchor = f.getFeeDate();
        }
        if (m.getWindowStart() == null || anchor.isBefore(m.getWindowStart())) {
            m.setWindowStart(anchor);
            m.setWindowEnd(anchor.plusMonths(m.getMonths()));
            agreements.save(m);
        }
        if (!f.getFeeDate().isBefore(m.getWindowEnd())) {
            log.info("Daily fee {} of {} is after {}'s window (ended {}): no commission", f.getFeeDate(), f.getBusinessId(), m.getFullName(), m.getWindowEnd());
            return null;
        }
        String ref = (reference == null ? "DF-" + f.getId() : reference) + ":" + Money.of(platformCollected).toPlainString();
        if (commissions.findBySourceAndSourceRef(Commission.DAILY_FEE, ref).isPresent()) {
            return null;
        }
        BigDecimal gross = Money.percent(base, m.getSharePct());
        BigDecimal wht = Money.percent(gross, m.getWhtPct());
        Commission c = commissions.save(Commission.builder().businessId(f.getBusinessId()).hotelId(f.getHotelId()).marketerId(m.getId())
                .profileId(m.getProfileId()).month(YearMonth.from(on).toString()).baseAmount(base).gross(gross).wht(wht).net(Money.of(gross.subtract(wht)))
                .status(Commission.ACCRUED).source(Commission.DAILY_FEE).sourceId(f.getId()).sourceRef(ref).feeDate(f.getFeeDate()).earnedOn(on)
                .sharePct(m.getSharePct()).whtPct(m.getWhtPct())
                .note(f.getCarriedIn().signum() > 0 ? "Includes fee carried from earlier days" : null).build());
        log.info("Commission {} for {} on {} ({} of fee {} collected)", c.getNet(), m.getFullName(), f.getFeeDate(), base, f.getFeeDate());
        return c;
    }

    /** The agreement covering the fee day: started on or before it, not ended before it (newest first). */
    Marketer agreementFor(String businessId, LocalDate day) {
        return agreements.findByBusinessId(businessId).stream().filter(m -> m.covers(day))
                .max(Comparator.comparing(Marketer::getStartedOn)).orElse(null);
    }

    // ---- refunds and clawback ------------------------------------------------------------------------

    public record RefundResult(String dailyFeeId, BigDecimal refunded, BigDecimal platformCollected, Commission clawback) {
    }

    /**
     * Records that Hospio360 gave back {@code amount} of what a day collected (a provider reversal or a refund to the hotel). The
     * commission earned on it is clawed back with a negative line on the marketer's next statement.
     */
    public RefundResult refund(String dailyFeeId, BigDecimal amount, String reason) {
        if (amount == null || amount.signum() <= 0) {
            throw new BadRequestException("Enter the amount given back");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BadRequestException("Say why (provider reversal, refund to the hotel, ...)");
        }
        BigDecimal amt = Money.of(amount);
        return TenantContext.runWith(Tenant.system("commission"), () -> tx.execute(st -> {
            DailyFee f = fees.findById(dailyFeeId).orElseThrow(() -> new ResourceNotFoundException("Daily fee not found"));
            BigDecimal left = f.platformPaid().subtract(f.getRefunded());
            if (amt.compareTo(left) > 0) {
                throw new BadRequestException("At most NGN " + Money.of(left).toPlainString() + " of this day's platform fee can be given back");
            }
            f.setRefunded(Money.of(f.getRefunded().add(amt)));
            f.setRefundNote(reason.trim().length() > 300 ? reason.trim().substring(0, 300) : reason.trim());
            fees.save(f);
            return new RefundResult(f.getId(), f.getRefunded(), f.platformPaid(), clawback(f, amt, reason.trim(), LocalDate.now(LAGOS)));
        }));
    }

    /** The negative line for {@code amount} given back of {@code f}, up to what earned and isn't clawed back yet; null when nothing earned. */
    Commission clawback(DailyFee f, BigDecimal amount, String reason, LocalDate on) {
        BigDecimal earned = BigDecimal.ZERO;
        BigDecimal clawed = BigDecimal.ZERO;
        Commission first = null;
        for (Commission c : commissions.findBySourceIdOrderByCreatedAtAsc(f.getId())) {
            if (Commission.DAILY_FEE.equals(c.getSource())) {
                earned = earned.add(c.getBaseAmount());
                first = first == null ? c : first;
            } else if (Commission.CLAWBACK.equals(c.getSource())) {
                clawed = clawed.add(c.getBaseAmount().negate());
            }
        }
        BigDecimal base = Money.of(amount.min(earned.subtract(clawed)));
        if (first == null || base.signum() <= 0) {
            return null;
        }
        BigDecimal gross = Money.percent(base, first.getSharePct()).negate();
        BigDecimal wht = Money.percent(gross.negate(), first.getWhtPct()).negate();
        return commissions.save(Commission.builder().businessId(f.getBusinessId()).hotelId(f.getHotelId()).marketerId(first.getMarketerId())
                .profileId(first.getProfileId()).month(YearMonth.from(on).toString()).baseAmount(base.negate()).gross(gross).wht(wht)
                .net(Money.of(gross.subtract(wht))).status(Commission.ACCRUED).source(Commission.CLAWBACK).sourceId(f.getId())
                .sourceRef("REFUND:" + f.getId() + ":" + f.getRefunded().toPlainString()).feeDate(f.getFeeDate()).earnedOn(on)
                .sharePct(first.getSharePct()).whtPct(first.getWhtPct()).note(reason.length() > 300 ? reason.substring(0, 300) : reason).build());
    }

    // ---- statements ----------------------------------------------------------------------------------

    public record StatementRun(String month, int made, int carriedOver, int skipped, List<String> notes) {
    }

    /**
     * Makes each marketer's statement for {@code month} (the 5th's job does the month before): every line up to that month not on a
     * statement yet, plus what earlier statements under the minimum carried in. A marketer who already has that month's statement is
     * skipped (a line that arrives later goes on the next one).
     */
    public StatementRun makeStatements(YearMonth month) {
        return TenantContext.runWith(Tenant.system("commission-statements"), () -> {
            String m = month.toString();
            Set<String> who = new LinkedHashSet<>(commissions.profilesWithOpenLines(m));
            who.addAll(statements.profilesWithCarry(m));
            int made = 0;
            int carried = 0;
            int skipped = 0;
            List<String> notes = new ArrayList<>();
            for (String profileId : who) {
                try {
                    MarketerStatement s = tx.execute(st -> makeOne(profileId, m));
                    if (s == null) {
                        skipped++;
                        continue;
                    }
                    made++;
                    if (MarketerStatement.CARRIED_OVER.equals(s.getStatus())) {
                        carried++;
                    }
                    sendReady(s);
                } catch (RuntimeException e) {
                    skipped++;
                    notes.add(profileId + ": " + e.getMessage());
                    log.error("Marketer statement {} for {} not made: {}", m, profileId, e.getMessage(), e);
                }
            }
            log.info("Marketer statements {}: {} made ({} carried over), {} skipped", m, made, carried, skipped);
            return new StatementRun(m, made, carried, skipped, notes);
        });
    }

    MarketerStatement makeOne(String profileId, String month) {
        if (statements.findByProfileIdAndMonth(profileId, month).isPresent()) {
            return null;
        }
        MarketerProfile p = profiles.findById(profileId).orElse(null);
        if (p == null) {
            return null;
        }
        List<Commission> lines = commissions.findByProfileIdAndStatementIdIsNullAndSourceIsNotNullAndMonthLessThanEqualOrderByEarnedOnAscCreatedAtAsc(profileId, month);
        List<MarketerStatement> carriedIn = statements.findByProfileIdAndStatusAndCarriedToIdIsNullAndMonthLessThan(profileId, MarketerStatement.CARRIED_OVER, month);
        if (lines.isEmpty() && carriedIn.isEmpty()) {
            return null;
        }
        return build(p, month, lines, carriedIn);
    }

    /**
     * The ENDED marketer's final statement, made when they are ended: every line not on a statement yet (any month) and every statement
     * carried over under the minimum, as a DRAFT approved and paid whatever the amount. Dated this month, or the month after the
     * marketer's latest statement when that one is already made. Null when nothing is open. Emails "statement ready".
     */
    public MarketerStatement makeFinalStatement(String profileId) {
        MarketerStatement s = asSystem(() -> tx.execute(st -> makeFinal(profileId, LocalDate.now(LAGOS))));
        if (s != null) {
            log.info("Final statement {} for ended marketer {}: payable {}", s.getMonth(), profileId, s.getPayable());
            asSystem(() -> {
                sendReady(s);
                return null;
            });
        }
        return s;
    }

    MarketerStatement makeFinal(String profileId, LocalDate today) {
        MarketerProfile p = profiles.findById(profileId).orElse(null);
        if (p == null) {
            return null;
        }
        List<Commission> lines = new ArrayList<>(commissions.findByProfileIdAndStatementIdIsNullAndSourceIsNotNull(profileId));
        lines.sort(Comparator.comparing(Commission::getEarnedOn, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Commission::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        List<MarketerStatement> carriedIn = statements.findByProfileIdAndStatusAndCarriedToIdIsNull(profileId, MarketerStatement.CARRIED_OVER);
        if (lines.isEmpty() && carriedIn.isEmpty()) {
            return null;
        }
        String month = YearMonth.from(today).toString();
        for (MarketerStatement prev : statements.findByProfileIdOrderByMonthDesc(profileId)) {
            if (prev.getMonth().compareTo(month) >= 0) {
                month = YearMonth.parse(prev.getMonth()).plusMonths(1).toString();
            }
            break;
        }
        return build(p, month, lines, carriedIn);
    }

    /**
     * The statement for {@code lines} and {@code carriedIn}: CARRIED_OVER under the minimum, else DRAFT. An ENDED marketer's is never
     * carried over while something is payable: it is their final statement, paid whatever the amount.
     */
    private MarketerStatement build(MarketerProfile p, String month, List<Commission> lines, List<MarketerStatement> carriedIn) {
        String profileId = p.getId();
        BigDecimal base = BigDecimal.ZERO;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal claw = BigDecimal.ZERO;
        BigDecimal wht = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        for (Commission c : lines) {
            base = base.add(c.getBaseAmount());
            if (c.getGross().signum() >= 0) {
                gross = gross.add(c.getGross());
            } else {
                claw = claw.add(c.getGross());
            }
            wht = wht.add(c.getWht());
            net = net.add(c.getNet());
        }
        BigDecimal carry = carriedIn.stream().map(MarketerStatement::getPayable).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal payable = Money.of(net.add(carry));
        BigDecimal minimum = minimumPayout();
        boolean last = "ENDED".equals(p.getStatus()) && payable.signum() > 0;
        boolean carryOver = !last && payable.compareTo(minimum) < 0;
        MarketerStatement s = statements.save(MarketerStatement.builder().profileId(profileId).month(month)
                .status(carryOver ? MarketerStatement.CARRIED_OVER : MarketerStatement.DRAFT).finalStatement(last).lineCount(lines.size())
                .feesCollected(Money.of(base)).gross(Money.of(gross)).clawback(Money.of(claw)).wht(Money.of(wht)).net(Money.of(net))
                .carriedIn(Money.of(carry)).payable(payable).minimumPayout(minimum)
                .queryUntil(Instant.now().plus(QUERY_DAYS, ChronoUnit.DAYS)).build());
        for (Commission c : lines) {
            c.setStatementId(s.getId());
            c.setStatus(Commission.ON_STATEMENT);
        }
        commissions.saveAll(lines);
        for (MarketerStatement prev : carriedIn) {
            prev.setCarriedToId(s.getId());
        }
        statements.saveAll(carriedIn);
        return s;
    }

    BigDecimal minimumPayout() {
        BigDecimal min = settings.fees().marketerMinPayout();
        return Money.of(min == null ? new BigDecimal("5000") : min);
    }

    private void sendReady(MarketerStatement s) {
        MarketerProfile p = profiles.findById(s.getProfileId()).orElse(null);
        if (p == null || !StringUtils.hasText(p.getEmail())) {
            return;
        }
        NumberFormat n = naira();
        String month = YearMonth.parse(s.getMonth()).format(MONTH);
        StringBuilder t = new StringBuilder("Hello ").append(p.getFullName()).append(",\n\n")
                .append("Your Hospio360 commission statement for ").append(month).append(" is ready in the marketer portal.\n\n")
                .append("Platform fees collected: NGN ").append(n.format(s.getFeesCollected())).append('\n')
                .append("Commission: NGN ").append(n.format(s.getGross().add(s.getClawback()))).append('\n')
                .append("WHT: NGN ").append(n.format(s.getWht())).append('\n');
        if (s.getCarriedIn().signum() != 0) {
            t.append("Carried from earlier months: NGN ").append(n.format(s.getCarriedIn())).append('\n');
        }
        t.append("To be paid: NGN ").append(n.format(s.getPayable())).append("\n\n");
        if (s.isFinalStatement()) {
            t.append("This is your final statement now that your referral partnership has ended. It is paid whatever the amount ")
                    .append("(the minimum payout doesn't apply). If something looks wrong, raise a query in the portal by ")
                    .append(DAY.format(s.getQueryUntil().atZone(LAGOS))).append(".\n");
        } else if (MarketerStatement.CARRIED_OVER.equals(s.getStatus())) {
            t.append("This is under the NGN ").append(n.format(s.getMinimumPayout()))
                    .append(" minimum payout, so it rolls over to your next statement.\n");
        } else {
            t.append("If something looks wrong, raise a query in the portal by ").append(DAY.format(s.getQueryUntil().atZone(LAGOS)))
                    .append(". Once approved it is paid by bank transfer by the 15th.\n");
        }
        t.append("\nHospio360\n");
        deliveries.send(Delivery.email(p.getEmail(), "Hospio360 · your commission statement for " + month, t.toString(),
                Delivery.Kind.STATEMENT, s.getId()));
    }

    // ---- query, approve, pay -------------------------------------------------------------------------

    /** The marketer queries a DRAFT statement within 7 days; Hospio360 sees it as an alert and on the statement. */
    public StatementDetail query(MarketerProfile p, String statementId, String note) {
        if (!StringUtils.hasText(note) || note.trim().length() < 5) {
            throw new BadRequestException("Say what looks wrong (at least a few words)");
        }
        return asSystem(() -> {
            MarketerStatement s = tx.execute(st -> {
                MarketerStatement x = find(statementId);
                if (!x.getProfileId().equals(p.getId())) {
                    throw new ResourceNotFoundException("Statement not found");
                }
                if (!MarketerStatement.DRAFT.equals(x.getStatus())) {
                    throw new BadRequestException(MarketerStatement.QUERIED.equals(x.getStatus()) ? "You already queried this statement"
                            : "Only a statement waiting for approval can be queried");
                }
                if (x.getQueryUntil() != null && Instant.now().isAfter(x.getQueryUntil())) {
                    throw new BadRequestException("The 7 days to query this statement ended on " + DAY.format(x.getQueryUntil().atZone(LAGOS)));
                }
                x.setStatus(MarketerStatement.QUERIED);
                x.setQueryNote(note.trim().length() > 1000 ? note.trim().substring(0, 1000) : note.trim());
                x.setQueriedAt(Instant.now());
                return statements.save(x);
            });
            alerts.raise(QUERY_ALERT, null, null, "Marketer statement queried",
                    p.getFullName() + " queried the " + YearMonth.parse(s.getMonth()).format(MONTH) + " statement: " + s.getQueryNote(), "warning");
            return detail(s);
        });
    }

    /** Finance approves a DRAFT or QUERIED statement (with an answer to the query); the bank details must be in and checked. */
    public StatementDetail approve(String statementId, String answer) {
        HospioUserDetails user = CurrentUser.require();
        return asSystem(() -> tx.execute(st -> {
            MarketerStatement s = find(statementId);
            if (!MarketerStatement.DRAFT.equals(s.getStatus()) && !MarketerStatement.QUERIED.equals(s.getStatus())) {
                throw new BadRequestException("Only a draft or queried statement can be approved (this one is " + s.getStatus().toLowerCase(Locale.ROOT).replace('_', ' ') + ")");
            }
            if (MarketerStatement.QUERIED.equals(s.getStatus()) && !StringUtils.hasText(answer)) {
                throw new BadRequestException("Answer the marketer's query before approving");
            }
            MarketerProfile p = profiles.findById(s.getProfileId()).orElseThrow(() -> new ResourceNotFoundException("Marketer not found"));
            if (!StringUtils.hasText(p.getBankAccount()) || !StringUtils.hasText(p.getBankName()) || !StringUtils.hasText(p.getBankAccountName())) {
                throw new BadRequestException(p.getFullName() + " hasn't added bank details yet");
            }
            if (p.getBankCheckedAt() == null) {
                throw new BadRequestException("Check " + p.getFullName() + "'s bank details first (marketer page → Bank details → Mark checked)");
            }
            if (StringUtils.hasText(answer)) {
                s.setQueryAnswer(answer.trim().length() > 1000 ? answer.trim().substring(0, 1000) : answer.trim());
            }
            s.setStatus(MarketerStatement.APPROVED);
            s.setApprovedAt(Instant.now());
            s.setApprovedBy(user.getUserId());
            s.setApprovedByName(user.getFullName());
            s.setBankName(p.getBankName());
            s.setBankAccount(p.getBankAccount());
            s.setBankAccountName(p.getBankAccountName());
            statements.save(s);
            if (s.getQueriedAt() != null) {
                alerts.list(null, true).stream().filter(a -> QUERY_ALERT.equals(a.getKind())).forEach(a -> alerts.dismiss(a.getId()));
            }
            return detail(s);
        }));
    }

    /** Finance records the bank transfer that paid an APPROVED statement (the whole payable); the marketer is emailed. */
    public StatementDetail recordPayment(String statementId, String reference, LocalDate paidOn, BigDecimal amount) {
        HospioUserDetails user = CurrentUser.require();
        if (!StringUtils.hasText(reference)) {
            throw new BadRequestException("Enter the bank transfer reference");
        }
        LocalDate today = LocalDate.now(LAGOS);
        if (paidOn == null || paidOn.isAfter(today)) {
            throw new BadRequestException("Enter the date the transfer was made (today or earlier)");
        }
        MarketerStatement paid = asSystem(() -> tx.execute(st -> {
            MarketerStatement s = find(statementId);
            if (!MarketerStatement.APPROVED.equals(s.getStatus())) {
                throw new BadRequestException(MarketerStatement.PAID.equals(s.getStatus()) ? "This statement is already paid" : "Approve the statement before recording its payment");
            }
            if (amount == null || Money.of(amount).compareTo(s.getPayable()) != 0) {
                throw new BadRequestException("The amount must be the statement's NGN " + s.getPayable().toPlainString() + " (the whole statement is paid at once)");
            }
            s.setStatus(MarketerStatement.PAID);
            s.setPaidAmount(Money.of(amount));
            s.setPaidReference(reference.trim());
            s.setPaidOn(paidOn);
            s.setPaidRecordedAt(Instant.now());
            s.setPaidRecordedBy(user.getUserId());
            s.setPaidRecordedName(user.getFullName());
            statements.save(s);
            List<Commission> lines = commissions.findByStatementIdOrderByEarnedOnAscCreatedAtAsc(s.getId());
            for (Commission c : lines) {
                c.setStatus("PAID");
                c.setPaidAt(Instant.now());
                c.setPaidOn(paidOn);
                c.setPaidReference(reference.trim());
            }
            commissions.saveAll(lines);
            // statements carried into this one are paid with it
            for (MarketerStatement prev : statements.findByProfileIdOrderByMonthDesc(s.getProfileId())) {
                if (s.getId().equals(prev.getCarriedToId())) {
                    for (Commission c : commissions.findByStatementIdOrderByEarnedOnAscCreatedAtAsc(prev.getId())) {
                        c.setStatus("PAID");
                        c.setPaidAt(Instant.now());
                        c.setPaidOn(paidOn);
                        c.setPaidReference(reference.trim());
                        commissions.save(c);
                    }
                }
            }
            return s;
        }));
        asSystem(() -> {
            sendPaid(paid);
            return null;
        });
        return asSystem(() -> detail(find(statementId)));
    }

    private void sendPaid(MarketerStatement s) {
        MarketerProfile p = profiles.findById(s.getProfileId()).orElse(null);
        if (p == null || !StringUtils.hasText(p.getEmail())) {
            return;
        }
        NumberFormat n = naira();
        String month = YearMonth.parse(s.getMonth()).format(MONTH);
        String account = s.getBankAccount() == null ? "" : s.getBankAccount();
        String masked = account.length() > 4 ? "******" + account.substring(account.length() - 4) : account;
        String text = "Hello " + p.getFullName() + ",\n\nYou've been paid. Hospio360 sent NGN " + n.format(s.getPaidAmount())
                + " for your " + month + " commission statement to " + s.getBankName() + " " + masked + " (" + s.getBankAccountName() + ").\n\n"
                + "Transfer date: " + DAY.format(s.getPaidOn()) + "\nReference: " + s.getPaidReference() + "\n"
                + "WHT deducted: NGN " + n.format(s.getWht()) + "\n\nThe statement is in the marketer portal under Commissions.\n\nHospio360\n";
        deliveries.send(Delivery.email(p.getEmail(), "Hospio360 · you've been paid: " + month + " commission", text, Delivery.Kind.NOTICE,
                s.getId() + ":paid"));
        tx.executeWithoutResult(st -> {
            MarketerStatement x = find(s.getId());
            x.setPaidEmailSentAt(Instant.now());
            statements.save(x);
        });
    }

    // ---- reading -------------------------------------------------------------------------------------

    public record LineView(String id, String businessId, String businessName, String hotelId, String source, LocalDate feeDate, LocalDate earnedOn,
                           String month, BigDecimal feeCollected, BigDecimal sharePct, BigDecimal gross, BigDecimal whtPct, BigDecimal wht,
                           BigDecimal net, String status, String note) {
    }

    public record BusinessGroup(String businessId, String businessName, BigDecimal feesCollected, BigDecimal gross, BigDecimal wht, BigDecimal net,
                                int lines) {
    }

    public record StatementRow(String id, String profileId, String marketerName, String marketerEmail, String month, String status, int lineCount,
                               BigDecimal feesCollected, BigDecimal gross, BigDecimal clawback, BigDecimal wht, BigDecimal net, BigDecimal carriedIn,
                               BigDecimal payable, BigDecimal minimumPayout, String carriedToMonth, Instant queryUntil, boolean canQuery,
                               String queryNote, Instant queriedAt, String queryAnswer, Instant approvedAt, String approvedByName,
                               BigDecimal paidAmount, String paidReference, LocalDate paidOn, String paidRecordedName, Instant paidEmailSentAt,
                               String bankName, String bankAccount, String bankAccountName, boolean bankChecked, Instant createdAt,
                               boolean finalStatement) {
    }

    public record StatementDetail(StatementRow statement, List<BusinessGroup> businesses, List<LineView> lines, List<StatementRow> carriedFrom) {
    }

    public record StatementList(List<StatementRow> statements, Map<String, Long> counts, Map<String, BigDecimal> totals) {
    }

    /** Statements, newest month first, by month, status and marketer; counts per status and payable totals per status. */
    public StatementList list(String month, String status, String profileId) {
        return asSystem(() -> {
            Map<String, MarketerProfile> people = new HashMap<>();
            List<StatementRow> rows = new ArrayList<>();
            Map<String, Long> counts = new LinkedHashMap<>();
            Map<String, BigDecimal> totals = new LinkedHashMap<>();
            for (String s : List.of(MarketerStatement.DRAFT, MarketerStatement.QUERIED, MarketerStatement.APPROVED, MarketerStatement.PAID,
                    MarketerStatement.CARRIED_OVER)) {
                counts.put(s, 0L);
                totals.put(s, BigDecimal.ZERO.setScale(2));
            }
            counts.put("all", 0L);
            for (MarketerStatement s : statements.findAll(Sort.by(Sort.Direction.DESC, "month").and(Sort.by("createdAt")))) {
                if (StringUtils.hasText(month) && !month.equals(s.getMonth()) || StringUtils.hasText(profileId) && !profileId.equals(s.getProfileId())) {
                    continue;
                }
                counts.merge(s.getStatus(), 1L, Long::sum);
                counts.merge("all", 1L, Long::sum);
                totals.merge(s.getStatus(), s.getPayable(), BigDecimal::add);
                if (StringUtils.hasText(status) && !status.equalsIgnoreCase(s.getStatus())) {
                    continue;
                }
                rows.add(row(s, people));
            }
            return new StatementList(rows, counts, totals);
        });
    }

    public StatementDetail detail(String id) {
        return asSystem(() -> detail(find(id)));
    }

    /** One marketer's statements (the portal), newest first. */
    public List<StatementRow> forProfile(String profileId) {
        return asSystem(() -> {
            Map<String, MarketerProfile> people = new HashMap<>();
            return statements.findByProfileIdOrderByMonthDesc(profileId).stream().map(s -> row(s, people)).toList();
        });
    }

    /** A statement of this marketer, or not found. */
    public StatementDetail detailFor(MarketerProfile p, String id) {
        return asSystem(() -> {
            MarketerStatement s = find(id);
            if (!s.getProfileId().equals(p.getId())) {
                throw new ResourceNotFoundException("Statement not found");
            }
            return detail(s);
        });
    }

    /** Lines not on a statement yet: what goes on the next one. */
    public List<LineView> openLines(String profileId) {
        return asSystem(() -> {
            Map<String, String> names = new HashMap<>();
            return commissions.findByProfileIdAndStatementIdIsNullAndSourceIsNotNull(profileId).stream()
                    .sorted(Comparator.comparing(Commission::getEarnedOn).thenComparing(Commission::getCreatedAt)).map(c -> line(c, names)).toList();
        });
    }

    /** The statements as CSV (finance's export): one row per statement. */
    public String csv(String month, String status) {
        StatementList l = list(month, status, null);
        StringBuilder t = new StringBuilder("Month,Marketer,Email,Status,Lines,Fees collected,Commission,Clawback,WHT,Net,Carried in,Payable,"
                + "Bank,Account number,Account name,Approved by,Paid on,Paid amount,Reference\n");
        for (StatementRow r : l.statements()) {
            t.append(String.join(",", csv(r.month()), csv(r.marketerName()), csv(r.marketerEmail()), csv(r.status()), String.valueOf(r.lineCount()),
                    plain(r.feesCollected()), plain(r.gross()), plain(r.clawback()), plain(r.wht()), plain(r.net()), plain(r.carriedIn()),
                    plain(r.payable()), csv(r.bankName()), csv(r.bankAccount()), csv(r.bankAccountName()), csv(r.approvedByName()),
                    r.paidOn() == null ? "" : r.paidOn().toString(), plain(r.paidAmount()), csv(r.paidReference()))).append('\n');
        }
        return t.toString();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        String safe = v.startsWith("=") || v.startsWith("+") || v.startsWith("-") || v.startsWith("@") ? "'" + v : v;
        return safe.contains(",") || safe.contains("\"") || safe.contains("\n") ? "\"" + safe.replace("\"", "\"\"") + "\"" : safe;
    }

    private StatementDetail detail(MarketerStatement s) {
        Map<String, String> names = new HashMap<>();
        List<LineView> lines = commissions.findByStatementIdOrderByEarnedOnAscCreatedAtAsc(s.getId()).stream().map(c -> line(c, names)).toList();
        Map<String, List<LineView>> byBusiness = new LinkedHashMap<>();
        lines.forEach(l -> byBusiness.computeIfAbsent(l.businessId(), k -> new ArrayList<>()).add(l));
        List<BusinessGroup> groups = new ArrayList<>();
        byBusiness.forEach((id, ls) -> groups.add(new BusinessGroup(id, ls.get(0).businessName(),
                Money.of(ls.stream().map(LineView::feeCollected).reduce(BigDecimal.ZERO, BigDecimal::add)),
                Money.of(ls.stream().map(LineView::gross).reduce(BigDecimal.ZERO, BigDecimal::add)),
                Money.of(ls.stream().map(LineView::wht).reduce(BigDecimal.ZERO, BigDecimal::add)),
                Money.of(ls.stream().map(LineView::net).reduce(BigDecimal.ZERO, BigDecimal::add)), ls.size())));
        Map<String, MarketerProfile> people = new HashMap<>();
        List<StatementRow> from = statements.findByProfileIdOrderByMonthDesc(s.getProfileId()).stream()
                .filter(x -> s.getId().equals(x.getCarriedToId())).map(x -> row(x, people)).toList();
        return new StatementDetail(row(s, people), groups, lines, from);
    }

    private LineView line(Commission c, Map<String, String> names) {
        String name = names.computeIfAbsent(c.getBusinessId(), id -> {
            String n = businessNames.name(id);
            return n == null ? "" : n;
        });
        return new LineView(c.getId(), c.getBusinessId(), name.isEmpty() ? null : name, c.getHotelId(), c.getSource(), c.getFeeDate(), c.getEarnedOn(),
                c.getMonth(), c.getBaseAmount(), c.getSharePct(), c.getGross(), c.getWhtPct(), c.getWht(), c.getNet(), c.getStatus(), c.getNote());
    }

    private StatementRow row(MarketerStatement s, Map<String, MarketerProfile> people) {
        MarketerProfile p = people.computeIfAbsent(s.getProfileId(), id -> profiles.findById(id).orElse(null));
        String carriedTo = s.getCarriedToId() == null ? null : statements.findById(s.getCarriedToId()).map(MarketerStatement::getMonth).orElse(null);
        boolean canQuery = MarketerStatement.DRAFT.equals(s.getStatus()) && s.getQueryUntil() != null && Instant.now().isBefore(s.getQueryUntil());
        boolean paidOrApproved = MarketerStatement.APPROVED.equals(s.getStatus()) || MarketerStatement.PAID.equals(s.getStatus());
        return new StatementRow(s.getId(), s.getProfileId(), p == null ? null : p.getFullName(), p == null ? null : p.getEmail(), s.getMonth(),
                s.getStatus(), s.getLineCount(), s.getFeesCollected(), s.getGross(), s.getClawback(), s.getWht(), s.getNet(), s.getCarriedIn(),
                s.getPayable(), s.getMinimumPayout(), carriedTo, s.getQueryUntil(), canQuery, s.getQueryNote(), s.getQueriedAt(), s.getQueryAnswer(),
                s.getApprovedAt(), s.getApprovedByName(), s.getPaidAmount(), s.getPaidReference(), s.getPaidOn(), s.getPaidRecordedName(),
                s.getPaidEmailSentAt(), paidOrApproved ? s.getBankName() : p == null ? null : p.getBankName(),
                paidOrApproved ? s.getBankAccount() : p == null ? null : p.getBankAccount(),
                paidOrApproved ? s.getBankAccountName() : p == null ? null : p.getBankAccountName(), p != null && p.getBankCheckedAt() != null,
                s.getCreatedAt(), s.isFinalStatement());
    }

    private MarketerStatement find(String id) {
        return statements.findById(id).orElseThrow(() -> new ResourceNotFoundException("Statement not found"));
    }

    private static NumberFormat naira() {
        NumberFormat n = NumberFormat.getNumberInstance(Locale.UK);
        n.setMinimumFractionDigits(2);
        n.setMaximumFractionDigits(2);
        return n;
    }

    private static <T> T asSystem(Supplier<T> work) {
        return TenantContext.runWith(Tenant.system("commission"), work);
    }
}
