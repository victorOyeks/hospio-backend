package com.hospio.finance.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalAnsweredEvent;
import com.hospio.common.kafka.events.ApprovalRequestedEvent;
import com.hospio.common.kafka.events.CashBankedEvent;
import com.hospio.common.kafka.events.ExpenseRecordedEvent;
import com.hospio.common.kafka.events.TillClosedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.finance.client.HotelSettings;
import com.hospio.finance.client.PeerServices;
import com.hospio.finance.dto.FinanceRequests;
import com.hospio.finance.dto.MoneyToday;
import com.hospio.finance.entity.BankDeposit;
import com.hospio.finance.entity.Budget;
import com.hospio.finance.entity.Expense;
import com.hospio.finance.entity.RevenueEntry;
import com.hospio.finance.entity.Till;
import com.hospio.finance.entity.TillEntry;
import com.hospio.finance.repository.BankDepositRepository;
import com.hospio.finance.repository.BudgetRepository;
import com.hospio.finance.repository.ExpenseRepository;
import com.hospio.finance.repository.RevenueEntryRepository;
import com.hospio.finance.repository.TillEntryRepository;
import com.hospio.finance.repository.TillRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Finance 1.x-4.x: today's money, expenses and approvals, tills and cash, banking. (The night audit is {@link NightAuditService}.) */
@Service
@RequiredArgsConstructor
public class MoneyService {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    /** Roles that take cash and so have a till. */
    static final List<Role> PAYING_ROLES = List.of(Role.FRONT_OFFICE, Role.FINANCE, Role.BAR, Role.KITCHEN, Role.LAUNDRY);

    private final RevenueEntryRepository revenue;
    private final TillRepository tills;
    private final TillEntryRepository tillEntries;
    private final ExpenseRepository expenses;
    private final BudgetRepository budgets;
    private final BankDepositRepository deposits;
    private final EventPublisher events;
    private final HotelSettings settings;
    private final PeerServices peers;
    private final Notifier notifier;
    private final ExpenseCategories categories;

    // ---- revenue from PaymentApproved ---------------------------------------------------------------

    /**
     * {@code role} is the payer's desk: cash goes into that desk's open till. {@code desk} (for an order) is whose income it is:
     * Today's money, income by desk and the night audit count an outlet sale as that outlet's income, whoever took it.
     */
    @Transactional
    public void recordPayment(String paymentId, BigDecimal amount, String method, String role, String folioId, Instant at, String guestName,
                              String orderId, String desk) {
        if (revenue.findByPaymentId(paymentId).isPresent()) {
            return;
        }
        LocalDate date = (at == null ? Instant.now() : at).atZone(LAGOS).toLocalDate();
        boolean cash = PaymentMethod.CASH.name().equals(method);
        Optional<Till> till = cash && role != null ? lockOpenTill(TenantContext.requireHotel(), role) : Optional.empty();
        revenue.save(RevenueEntry.builder().paymentId(paymentId).businessDate(date).amount(Money.of(amount)).method(method).role(role).folioId(folioId)
                .orderId(orderId).desk(desk == null || desk.isBlank() ? null : desk).unattachedCash(cash && till.isEmpty()).build());
        till.ifPresent(t -> {
            if (tillEntries.findByPaymentId(paymentId).isEmpty()) {
                addEntry(t, TillEntry.Kind.PAYMENT, Money.of(amount), method, guestName == null || guestName.isBlank() ? "Cash payment" : "Cash payment · " + guestName, null, paymentId);
            }
        });
    }

    /** A cash refund comes out of the drawer of the role that took the payment, when that till is still open. */
    @Transactional
    public void onRefundApproved(String refundId, BigDecimal amount, String method, String role) {
        if (!PaymentMethod.CASH.name().equals(method) || role == null || amount == null || amount.signum() <= 0) {
            return;
        }
        if (tillEntries.findFirstByKindAndRef(TillEntry.Kind.REFUND, refundId).isPresent()) {
            return;
        }
        lockOpenTill(TenantContext.requireHotel(), role).ifPresent(t -> addEntry(t, TillEntry.Kind.REFUND, Money.of(amount).negate(), "CASH", "Cash refund", refundId, null));
    }

    @Transactional(readOnly = true)
    public MoneyToday today(LocalDate date) {
        String hotelId = TenantContext.requireHotel();
        LocalDate d = date == null ? LocalDate.now(LAGOS) : date;
        List<RevenueEntry> entries = revenue.findByHotelIdAndBusinessDate(hotelId, d);
        Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
        Map<String, BigDecimal> byRole = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal unattached = BigDecimal.ZERO;
        int unattachedCount = 0;
        for (RevenueEntry e : entries) {
            total = total.add(e.getAmount());
            byMethod.merge(e.getMethod(), e.getAmount(), BigDecimal::add);
            byRole.merge(e.incomeDesk(), e.getAmount(), BigDecimal::add);
            if (e.isUnattachedCash()) {
                unattached = unattached.add(e.getAmount());
                unattachedCount++;
            }
        }
        BigDecimal spent = expenses.findByHotelIdAndExpenseDateBetween(hotelId, d, d).stream()
                .filter(x -> x.getStatus() != Expense.Status.DECLINED).map(Expense::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Till> open = tills.findByHotelIdAndStatusOrderByOpenedAt(hotelId, Till.Status.OPEN);
        BigDecimal cash = open.stream().map(Till::getExpectedCash).reduce(BigDecimal.ZERO, BigDecimal::add);
        Optional<PeerServices.Day> day = peers.daySoft(hotelId, d);
        int rooms = day.isPresent() ? peers.roomCount(hotelId) : -1;
        Double occupancy = day.isPresent() && rooms > 0 ? pct(day.get().roomsOccupied(), rooms) : null;
        Integer pending = peers.pendingApprovals(hotelId);
        if (pending == null) {
            pending = expenses.findByHotelIdAndStatusIn(hotelId, EnumSet.of(Expense.Status.PENDING_APPROVAL, Expense.Status.QUESTION)).size();
        }
        return new MoneyToday(Money.of(total), byMethod, byRole, Money.of(spent), Money.of(cash), open.size(), entries.size(), occupancy,
                day.map(PeerServices.Day::arrivals).orElse(null), day.map(PeerServices.Day::departures).orElse(null), day.map(PeerServices.Day::inHouse).orElse(null),
                Money.of(unattached), unattachedCount, pending, peers.outletSales(hotelId, d).orElse(null));
    }

    static Double pct(int part, int whole) {
        return whole <= 0 ? null : BigDecimal.valueOf(part * 100.0 / whole).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    @Transactional(readOnly = true)
    public Map<String, BigDecimal> summary(LocalDate from, LocalDate to) {
        String hotelId = TenantContext.requireHotel();
        BigDecimal income = revenue.findByHotelIdAndBusinessDateBetween(hotelId, from, to).stream().map(RevenueEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal spent = expenses.findByHotelIdAndExpenseDateBetween(hotelId, from, to).stream()
                .filter(x -> x.getStatus() != Expense.Status.DECLINED).map(Expense::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        out.put("income", Money.of(income));
        out.put("expenses", Money.of(spent));
        out.put("profit", Money.of(income.subtract(spent)));
        return out;
    }

    /** Finance 1.2 · Match a payment: the entries not yet confirmed against the bank. */
    @Transactional
    public RevenueEntry match(String paymentId, boolean matched) {
        RevenueEntry e = revenue.findByPaymentId(paymentId).orElseThrow(() -> new ResourceNotFoundException("Payment not in the ledger"));
        e.setMatched(matched);
        return revenue.save(e);
    }

    // ---- expenses ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Expense> expenses(String status, LocalDate from, LocalDate to) {
        Expense.Status wanted = null;
        if (status != null && !status.isBlank()) {
            try {
                wanted = Expense.Status.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Unknown status " + status);
            }
        }
        Expense.Status s = wanted;
        return expenses.findByHotelIdOrderByExpenseDateDesc(TenantContext.requireHotel()).stream()
                .filter(e -> s == null || e.getStatus() == s)
                .filter(e -> from == null || !e.getExpenseDate().isBefore(from))
                .filter(e -> to == null || !e.getExpenseDate().isAfter(to)).toList();
    }

    @Transactional
    public Expense recordExpense(FinanceRequests.ExpenseRequest request) {
        HospioUserDetails user = CurrentUser.require();
        String hotelId = TenantContext.requireHotel();
        if (!Money.isPositive(request.amount())) {
            throw new BadRequestException("Enter an amount above zero");
        }
        Expense e = Expense.builder().category(categories.normalise(hotelId, request.category())).amount(Money.of(request.amount())).description(request.description())
                .paidFrom(request.paidFrom()).receiptKey(request.receiptKey())
                .expenseDate(request.expenseDate() == null ? LocalDate.now(LAGOS) : request.expenseDate())
                .requestedBy(user.getUserId()).requestedByName(user.getFullName()).build();
        HotelSettings.Limits limits = settings.limits(hotelId);
        ExpenseRouting.Caller caller = user.has(Role.OWNER) || user.isPlatform() ? ExpenseRouting.Caller.OWNER
                : user.isManagerOrOwner() ? ExpenseRouting.Caller.MANAGER : ExpenseRouting.Caller.STAFF;
        ExpenseRouting.Route route = ExpenseRouting.route(e.getAmount(), limits.managerExpenseLimit(), limits.ownerExpenseLimit(), caller);
        if (route.needsApproval()) {
            e.setStatus(Expense.Status.PENDING_APPROVAL);
            e.setApprovalId(UUID.randomUUID().toString());
            e.setApproverLevel(route.approverLevel());
        }
        expenses.save(e);
        if (e.getApprovalId() != null) {
            events.publish(KafkaTopics.APPROVAL_REQUESTED, ApprovalRequestedEvent.builder().approvalId(e.getApprovalId()).sourceModule("FINANCE")
                    .sourceRef(e.getId()).kind("EXPENSE").amount(e.getAmount()).requesterId(user.getUserId()).requesterName(user.getFullName())
                    .requesterRole(roleLabel(user)).approverLevel(route.approverLevel())
                    .description(labelOf(hotelId, e.getCategory()) + " · " + e.getDescription()).build());
        }
        events.publish(KafkaTopics.EXPENSE_RECORDED, ExpenseRecordedEvent.builder().expenseId(e.getId()).category(e.getCategory())
                .amount(e.getAmount()).description(e.getDescription()).status(e.getStatus().name()).build());
        return e;
    }

    @Transactional
    public void onExpenseDecided(String approvalId, String decision, String decidedBy, String note) {
        expenses.findByApprovalId(approvalId).ifPresent(e -> {
            if (e.getStatus() != Expense.Status.PENDING_APPROVAL && e.getStatus() != Expense.Status.QUESTION) {
                return;
            }
            e.setStatus(switch (decision) {
                case "APPROVED" -> Expense.Status.APPROVED;
                case "DECLINED" -> Expense.Status.DECLINED;
                default -> Expense.Status.QUESTION;
            });
            e.setDecidedBy(decidedBy);
            e.setDecisionNote(note);
            expenses.save(e);
        });
    }

    /** The requester answers the question on their expense; the approval goes back to waiting through ApprovalAnswered. */
    @Transactional
    public Expense answer(String id, String note) {
        HospioUserDetails user = CurrentUser.require();
        Expense e = expenses.findById(id).orElseThrow(() -> new ResourceNotFoundException("Expense not found"));
        if (e.getRequestedBy() == null || !e.getRequestedBy().equals(user.getUserId())) {
            throw new ForbiddenException("Only the person who raised this expense can answer");
        }
        if (e.getStatus() != Expense.Status.QUESTION || e.getApprovalId() == null) {
            throw new BadRequestException("Nobody is waiting for an answer on this expense");
        }
        e.setStatus(Expense.Status.PENDING_APPROVAL);
        String joined = (e.getDecisionNote() == null ? "" : e.getDecisionNote() + "\n") + "Answer: " + note;
        e.setDecisionNote(joined.length() > 500 ? joined.substring(joined.length() - 500) : joined);
        expenses.save(e);
        events.publish(KafkaTopics.APPROVAL_ANSWERED, ApprovalAnsweredEvent.builder().approvalId(e.getApprovalId()).sourceModule("FINANCE")
                .sourceRef(e.getId()).answeredBy(user.getUserId()).answeredByName(user.getFullName()).note(note).build());
        return e;
    }

    @Transactional
    public Expense markPaid(String id, String paidFromOverride) {
        HospioUserDetails user = CurrentUser.require();
        Expense e = expenses.findById(id).orElseThrow(() -> new ResourceNotFoundException("Expense not found"));
        if (e.getStatus() == Expense.Status.PAID) {
            throw new BadRequestException("This expense is already paid");
        }
        if (e.getStatus() != Expense.Status.APPROVED && e.getStatus() != Expense.Status.RECORDED) {
            throw new BadRequestException("This expense isn't approved");
        }
        if (paidFromOverride != null && !paidFromOverride.isBlank()) {
            e.setPaidFrom(paidFromOverride.trim().toUpperCase());
        }
        if ("TILL".equals(e.getPaidFrom())) {
            payOutFromTill(e, user);
        }
        e.setStatus(Expense.Status.PAID);
        e.setPaidBy(user.getUserId());
        e.setPaidByName(user.getFullName());
        e.setPaidAt(Instant.now());
        return expenses.save(e);
    }

    /**
     * The cash for a paid expense leaves a till: the payer's own open till, or the hotel's only open one. Used when an expense or a bill
     * is paid from the till.
     */
    void payOutFromTill(Expense e, HospioUserDetails user) {
        Till till = tillForPayOut(user);
        addEntry(till, TillEntry.Kind.EXPENSE, Money.of(e.getAmount()).negate(), "CASH", labelOf(e.getHotelId(), e.getCategory()) + " · " + e.getDescription(),
                e.getId(), null);
    }

    /** The hotel's label for a stored category (its list, else the code read as words). */
    String labelOf(String hotelId, String category) {
        return hotelId == null ? categoryLabel(category) : ExpenseCategories.labelOf(categories.labels(hotelId), category);
    }

    /** Expenses that count as spent against a budget: recorded, approved or paid (not waiting, questioned or declined). */
    static final List<Expense.Status> SPENT = List.of(Expense.Status.RECORDED, Expense.Status.APPROVED, Expense.Status.PAID);

    @Transactional(readOnly = true)
    public List<Budget> budgets(String month) {
        return budgets.findByHotelIdAndMonth(TenantContext.requireHotel(), checkMonth(month).toString());
    }

    @Transactional
    public Budget saveBudget(FinanceRequests.BudgetRequest request) {
        HospioUserDetails user = CurrentUser.require();
        if (user.isReadOnly() || !(user.isManagerOrOwner() || user.isPlatform())) {
            throw new ForbiddenException("Budgets are set by managers");
        }
        String hotel = TenantContext.requireHotel();
        String month = checkMonth(request.month()).toString();
        if (request.amount() == null || request.amount().signum() < 0) {
            throw new BadRequestException("A budget can't be below zero");
        }
        String code = categories.find(hotel, request.category()).filter(c -> c.isActive())
                .map(com.hospio.finance.entity.ExpenseCategory::getCode)
                .orElseThrow(() -> new BadRequestException("That category isn't in this hotel's list"));
        Budget b = budgets.findByHotelIdAndMonth(hotel, month).stream()
                .filter(x -> ExpenseCategories.codeOf(x.getCategory()).equals(code)).findFirst()
                .orElseGet(() -> Budget.builder().category(code).month(month).build());
        b.setCategory(code);
        b.setAmount(Money.of(request.amount()));
        return budgets.save(b);
    }

    /** Budget against actual for a month: every category's budget and what was spent (recorded, approved or paid expenses). */
    @Transactional
    public BudgetMath.Overview budgetOverview(String month) {
        String hotel = TenantContext.requireHotel();
        java.time.YearMonth m = checkMonth(month);
        Map<String, BigDecimal> set = new LinkedHashMap<>();
        budgets.findByHotelIdAndMonth(hotel, m.toString()).forEach(b -> set.merge(b.getCategory(), b.getAmount(), BigDecimal::add));
        Map<String, BigDecimal> spent = new LinkedHashMap<>();
        for (Object[] row : expenses.spendByCategory(hotel, m.atDay(1), m.atEndOfMonth(), SPENT)) {
            spent.merge((String) row[0], (BigDecimal) row[1], BigDecimal::add);
        }
        return BudgetMath.overview(m.toString(), categories.all(hotel), set, spent);
    }

    /** yyyy-MM, or a 400 that says so. */
    static java.time.YearMonth checkMonth(String month) {
        if (month == null || !month.trim().matches("\\d{4}-\\d{2}")) {
            throw new BadRequestException("Month is yyyy-mm");
        }
        try {
            return java.time.YearMonth.parse(month.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new BadRequestException("Month is yyyy-mm");
        }
    }

    // ---- tills and cash ------------------------------------------------------------------------------

    /** Tills opened today plus any still open from an earlier day, newest first, each with its entry count. */
    @Transactional(readOnly = true)
    public List<Till> tillsToday() {
        String hotelId = TenantContext.requireHotel();
        Map<String, Till> byId = new LinkedHashMap<>();
        tills.findByHotelIdAndOpenedAtAfterOrderByOpenedAtDesc(hotelId, LocalDate.now(LAGOS).atStartOfDay(LAGOS).toInstant()).forEach(t -> byId.put(t.getId(), t));
        tills.findByHotelIdAndStatusOrderByOpenedAt(hotelId, Till.Status.OPEN).forEach(t -> byId.putIfAbsent(t.getId(), t));
        List<Till> list = byId.values().stream().sorted((a, b) -> b.getOpenedAt().compareTo(a.getOpenedAt())).toList();
        fillEntryCounts(list);
        return list;
    }

    /** The open till of the caller's paying role, or null. */
    @Transactional(readOnly = true)
    public Till myTill() {
        HospioUserDetails user = CurrentUser.require();
        String hotelId = TenantContext.requireHotel();
        for (Role role : PAYING_ROLES) {
            if (user.has(role)) {
                Optional<Till> t = tills.findFirstByHotelIdAndRoleAndStatus(hotelId, role.name(), Till.Status.OPEN);
                if (t.isPresent()) {
                    fillEntryCounts(List.of(t.get()));
                    return t.get();
                }
            }
        }
        return null;
    }

    /** The tills of the caller's paying role(s) closed in the last 7 days, newest first (the desk's "Past tills"). */
    @Transactional(readOnly = true)
    public List<Till> myClosedTills() {
        HospioUserDetails user = CurrentUser.require();
        String hotelId = TenantContext.requireHotel();
        Instant since = LocalDate.now(LAGOS).minusDays(7).atStartOfDay(LAGOS).toInstant();
        List<Till> list = tills.findByHotelIdAndClosedAtBetween(hotelId, since, Instant.now().plusSeconds(60)).stream()
                .filter(t -> PAYING_ROLES.stream().anyMatch(r -> user.has(r) && r.name().equals(t.getRole())))
                .sorted((a, b) -> b.getClosedAt().compareTo(a.getClosedAt()))
                .toList();
        fillEntryCounts(list);
        return list;
    }

    @Transactional
    public Till openTill(FinanceRequests.OpenTillRequest request) {
        HospioUserDetails user = CurrentUser.require();
        String hotelId = TenantContext.requireHotel();
        Role role = payingRole(request.role());
        if (!TillAccess.canOpenOrClose(user, role)) {
            throw new ForbiddenException("Only a manager, finance or the " + label(role) + " can open this till");
        }
        openTill(hotelId, role.name()).ifPresent(t -> {
            throw new BadRequestException("The " + label(role) + " till is already open");
        });
        BigDecimal opening = request.openingFloat() == null ? settings.tillFloat(hotelId) : Money.of(request.openingFloat());
        if (opening.signum() < 0) {
            throw new BadRequestException("The opening float can't be negative");
        }
        try {
            // Two people opening the same till at once: the partial unique index (one OPEN till per hotel and role) refuses the second.
            return tills.saveAndFlush(Till.builder().role(role.name()).openedBy(user.getUserId()).openedByName(user.getFullName())
                    .openingFloat(opening).expectedCash(opening).build());
        } catch (DataIntegrityViolationException e) {
            throw new BadRequestException("The " + label(role) + " till is already open");
        }
    }

    @Transactional
    public TillEntry addMovement(String tillId, FinanceRequests.MovementRequest request) {
        HospioUserDetails user = CurrentUser.require();
        Till t = lockTill(tillId);
        if (!TillAccess.canMove(user, roleOf(t))) {
            throw new ForbiddenException("Only a manager or the " + label(roleOf(t)) + " can move cash in this till");
        }
        if (t.getStatus() != Till.Status.OPEN) {
            throw new BadRequestException("This till is closed");
        }
        TillEntry.Kind kind;
        try {
            kind = TillEntry.Kind.valueOf(request.kind().trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("The movement is either PAY_IN or PAY_OUT");
        }
        if (kind != TillEntry.Kind.PAY_IN && kind != TillEntry.Kind.PAY_OUT) {
            throw new BadRequestException("The movement is either PAY_IN or PAY_OUT");
        }
        BigDecimal amount = Money.of(request.amount());
        return addEntry(t, kind, kind == TillEntry.Kind.PAY_OUT ? amount.negate() : amount, "CASH", request.reason().trim(), null, null);
    }

    @Transactional
    public Till closeTill(String id, FinanceRequests.CloseTillRequest request) {
        HospioUserDetails user = CurrentUser.require();
        Till t = lockTill(id);
        if (!TillAccess.canOpenOrClose(user, roleOf(t))) {
            throw new ForbiddenException("Only a manager, finance or the " + label(roleOf(t)) + " can close this till");
        }
        return close(t, request.countedCash(), request.note(), user, null);
    }

    public record Handover(Till closed, Till opened) {
    }

    @Transactional
    public Handover handover(String id, FinanceRequests.HandoverRequest request) {
        HospioUserDetails user = CurrentUser.require();
        Till t = lockTill(id);
        if (!TillAccess.canMove(user, roleOf(t))) {
            throw new ForbiddenException("Only a manager or the " + label(roleOf(t)) + " can hand this till over");
        }
        Till closed = close(t, request.countedCash(), request.note(), user, request);
        tills.flush(); // the close is written before the new till: only one till of the role may be OPEN at a time
        Till opened = tills.save(Till.builder().role(t.getRole()).openedBy(request.toUserId()).openedByName(request.toUserName())
                .openingFloat(Money.of(request.countedCash())).expectedCash(Money.of(request.countedCash())).build());
        return new Handover(closed, opened);
    }

    private Till close(Till t, BigDecimal counted, String note, HospioUserDetails user, FinanceRequests.HandoverRequest handover) {
        if (!Till.Status.OPEN.name().equals(tills.lockStatus(t.getId()).orElse(null))) {
            throw new BadRequestException("This till is already closed");
        }
        recompute(t);
        BigDecimal variance = TillMath.variance(counted, t.getExpectedCash());
        if (variance.signum() != 0 && (note == null || note.isBlank())) {
            throw new BadRequestException("The count is " + variance.abs().toPlainString() + (variance.signum() < 0 ? " short" : " over") + ". Add a note saying why");
        }
        t.setCountedCash(Money.of(counted));
        t.setVariance(variance);
        t.setStatus(Till.Status.CLOSED);
        t.setClosedAt(Instant.now());
        t.setClosedBy(user.getUserId());
        t.setClosedByName(user.getFullName());
        t.setNote(note);
        if (handover != null) {
            t.setHandedOverTo(handover.toUserId());
            t.setHandedOverToName(handover.toUserName());
        }
        tills.save(t);
        events.publish(KafkaTopics.TILL_CLOSED, TillClosedEvent.builder().tillId(t.getId()).role(t.getRole()).expectedCash(t.getExpectedCash())
                .countedCash(t.getCountedCash()).variance(variance).build());
        if (variance.signum() != 0) {
            String subject = "Till variance: " + label(roleOf(t)) + " " + (variance.signum() < 0 ? "short " : "over ") + variance.abs().toPlainString();
            String text = "The " + label(roleOf(t)) + " till closed by " + user.getFullName() + " should hold " + t.getExpectedCash().toPlainString() + " but "
                    + t.getCountedCash().toPlainString() + " was counted (" + (variance.signum() < 0 ? "short " : "over ") + variance.abs().toPlainString() + "). Note: " + note;
            List<Delivery> sent = notifier.toManagers(t.getHotelId() == null ? TenantContext.requireHotel() : t.getHotelId(), subject, text, Delivery.Kind.TILL_VARIANCE, t.getId());
            t.setDeliveries(sent);
        }
        return t;
    }

    @Transactional(readOnly = true)
    public List<TillEntry> tillEntries(String tillId) {
        Till t = till(tillId);
        if (!TillAccess.canSee(CurrentUser.require(), roleOf(t))) {
            throw new ForbiddenException("You can't see this till");
        }
        return tillEntries.findByTillIdOrderByCreatedAt(tillId);
    }

    @Transactional
    public BankDeposit bankCash(FinanceRequests.BankCashRequest request) {
        HospioUserDetails user = CurrentUser.require();
        BankDeposit d = deposits.save(BankDeposit.builder().amount(Money.of(request.amount())).bankAccount(request.bankAccount())
                .reference(request.reference()).bankedBy(user.getUserId()).bankedByName(user.getFullName()).build());
        events.publish(KafkaTopics.CASH_BANKED, CashBankedEvent.builder().amount(d.getAmount()).bankAccount(d.getBankAccount()).bankedBy(user.getFullName()).build());
        return d;
    }

    @Transactional(readOnly = true)
    public List<BankDeposit> deposits() {
        return deposits.findByHotelIdOrderByBankedAtDesc(TenantContext.requireHotel());
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private Optional<Till> openTill(String hotelId, String role) {
        return tills.findFirstByHotelIdAndRoleAndStatus(hotelId, role, Till.Status.OPEN);
    }

    /** The role's open till, locked until commit (for writing to it). */
    private Optional<Till> lockOpenTill(String hotelId, String role) {
        return tills.lockOpen(hotelId, role).stream().findFirst();
    }

    /**
     * The till row is locked (and its status read as committed) before the entry goes in and the totals are recomputed: two entries at
     * once no longer overwrite each other's totals, and nothing lands on a till that was closed a moment ago.
     */
    private TillEntry addEntry(Till t, TillEntry.Kind kind, BigDecimal amount, String method, String description, String ref, String paymentId) {
        if (!Till.Status.OPEN.name().equals(tills.lockStatus(t.getId()).orElse(null))) {
            throw new BadRequestException("This till is closed");
        }
        HospioUserDetails user = CurrentUser.get().orElse(null);
        TillEntry entry = tillEntries.save(TillEntry.builder().tillId(t.getId()).kind(kind).paymentId(paymentId).amount(amount).method(method)
                .description(description).ref(ref).createdBy(user == null ? null : user.getUserId()).createdByName(user == null ? null : user.getFullName()).build());
        recompute(t);
        tills.save(t);
        return entry;
    }

    private void recompute(Till t) {
        List<BigDecimal> amounts = tillEntries.findByTillIdOrderByCreatedAt(t.getId()).stream().map(TillEntry::getAmount).toList();
        t.setCashIn(TillMath.cashIn(amounts));
        t.setCashOut(TillMath.cashOut(amounts));
        t.setExpectedCash(TillMath.expected(t.getOpeningFloat(), amounts));
    }

    private void fillEntryCounts(List<Till> list) {
        if (list.isEmpty()) {
            return;
        }
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : tillEntries.countByTill(list.stream().map(Till::getId).toList())) {
            counts.put((String) row[0], (Long) row[1]);
        }
        list.forEach(t -> t.setEntries(counts.getOrDefault(t.getId(), 0L)));
    }

    /** The till a cash expense comes out of: the caller's own paying role, or the only till open at the hotel. */
    /** SUPPLIES → Supplies, FOOD_AND_DRINK → Food and drink: how a category reads to people. */
    static String categoryLabel(String category) {
        if (category == null || category.isBlank()) {
            return "Expense";
        }
        String words = category.trim().replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private Till tillForPayOut(HospioUserDetails user) {
        String hotelId = TenantContext.requireHotel();
        for (Role role : PAYING_ROLES) {
            if (user.has(role)) {
                Optional<Till> t = lockOpenTill(hotelId, role.name());
                if (t.isPresent()) {
                    return t.get();
                }
            }
        }
        List<Till> open = tills.findByHotelIdAndStatusOrderByOpenedAt(hotelId, Till.Status.OPEN);
        if (open.size() == 1) {
            return open.get(0);
        }
        throw new BadRequestException(open.isEmpty() ? "There is no open till to pay this from" : "Say which till to pay this from: open your own till first");
    }

    private Till till(String id) {
        return tills.findById(id).orElseThrow(() -> new ResourceNotFoundException("Till not found"));
    }

    private Till lockTill(String id) {
        return tills.lockById(id).orElseThrow(() -> new ResourceNotFoundException("Till not found"));
    }

    private static Role roleOf(Till t) {
        try {
            return Role.valueOf(t.getRole());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Role payingRole(String name) {
        try {
            Role r = Role.valueOf(name.trim().toUpperCase());
            if (PAYING_ROLES.contains(r)) {
                return r;
            }
        } catch (IllegalArgumentException ignored) {
            // falls through to the message
        }
        throw new BadRequestException("A till belongs to one of: " + PAYING_ROLES.stream().map(Role::name).toList());
    }

    private static String label(Role role) {
        return role == null ? "till" : role.name().toLowerCase().replace('_', ' ');
    }

    static String roleLabel(HospioUserDetails user) {
        if (user.has(Role.OWNER)) {
            return Role.OWNER.name();
        }
        if (user.has(Role.MANAGER)) {
            return Role.MANAGER.name();
        }
        return user.roleSet().stream().map(Role::name).sorted().findFirst().orElse(null);
    }

    /** Who may open, move cash in, close or look at a till of a role. */
    static final class TillAccess {

        private TillAccess() {
        }

        static boolean canOpenOrClose(HospioUserDetails u, Role role) {
            return !u.isReadOnly() && (u.isPlatform() || u.isManagerOrOwner() || u.has(Role.FINANCE, AccessLevel.EDIT) || (role != null && u.has(role)));
        }

        static boolean canMove(HospioUserDetails u, Role role) {
            return !u.isReadOnly() && (u.isPlatform() || u.isManagerOrOwner() || (role != null && u.has(role)));
        }

        static boolean canSee(HospioUserDetails u, Role role) {
            return u.isPlatform() || u.isManagerOrOwner() || u.has(Role.FINANCE, AccessLevel.VIEW) || (role != null && u.has(role));
        }
    }
}
