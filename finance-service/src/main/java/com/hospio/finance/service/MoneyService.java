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

    // ---- revenue from PaymentApproved ---------------------------------------------------------------

    @Transactional
    public void recordPayment(String paymentId, BigDecimal amount, String method, String role, String folioId, Instant at) {
        if (revenue.findByPaymentId(paymentId).isPresent()) {
            return;
        }
        LocalDate date = (at == null ? Instant.now() : at).atZone(LAGOS).toLocalDate();
        boolean cash = PaymentMethod.CASH.name().equals(method);
        Optional<Till> till = cash && role != null ? openTill(TenantContext.requireHotel(), role) : Optional.empty();
        revenue.save(RevenueEntry.builder().paymentId(paymentId).businessDate(date).amount(Money.of(amount)).method(method).role(role).folioId(folioId)
                .unattachedCash(cash && till.isEmpty()).build());
        till.ifPresent(t -> {
            if (tillEntries.findByPaymentId(paymentId).isEmpty()) {
                addEntry(t, TillEntry.Kind.PAYMENT, Money.of(amount), method, "Cash payment", null, paymentId);
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
        openTill(TenantContext.requireHotel(), role).ifPresent(t -> addEntry(t, TillEntry.Kind.REFUND, Money.of(amount).negate(), "CASH", "Cash refund", refundId, null));
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
            byRole.merge(e.getRole() == null ? "OTHER" : e.getRole(), e.getAmount(), BigDecimal::add);
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
                Money.of(unattached), unattachedCount, pending);
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
        Expense e = Expense.builder().category(request.category()).amount(Money.of(request.amount())).description(request.description())
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
                    .description(e.getCategory() + " · " + e.getAmount() + " · " + e.getDescription()).build());
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
            Till till = tillForPayOut(user);
            addEntry(till, TillEntry.Kind.EXPENSE, Money.of(e.getAmount()).negate(), "CASH", e.getCategory() + " · " + e.getDescription(), e.getId(), null);
        }
        e.setStatus(Expense.Status.PAID);
        e.setPaidBy(user.getUserId());
        e.setPaidByName(user.getFullName());
        e.setPaidAt(Instant.now());
        return expenses.save(e);
    }

    @Transactional(readOnly = true)
    public List<Budget> budgets(String month) {
        return budgets.findByHotelIdAndMonth(TenantContext.requireHotel(), month);
    }

    @Transactional
    public Budget saveBudget(FinanceRequests.BudgetRequest request) {
        if (!CurrentUser.require().isManagerOrOwner()) {
            throw new ForbiddenException("Budgets are set by managers");
        }
        Budget b = budgets.findByHotelIdAndMonth(TenantContext.requireHotel(), request.month()).stream()
                .filter(x -> x.getCategory().equals(request.category())).findFirst()
                .orElseGet(() -> Budget.builder().category(request.category()).month(request.month()).build());
        b.setAmount(Money.of(request.amount()));
        return budgets.save(b);
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
        return tills.save(Till.builder().role(role.name()).openedBy(user.getUserId()).openedByName(user.getFullName())
                .openingFloat(opening).expectedCash(opening).build());
    }

    @Transactional
    public TillEntry addMovement(String tillId, FinanceRequests.MovementRequest request) {
        HospioUserDetails user = CurrentUser.require();
        Till t = till(tillId);
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
        Till t = till(id);
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
        Till t = till(id);
        if (!TillAccess.canMove(user, roleOf(t))) {
            throw new ForbiddenException("Only a manager or the " + label(roleOf(t)) + " can hand this till over");
        }
        Till closed = close(t, request.countedCash(), request.note(), user, request);
        Till opened = tills.save(Till.builder().role(t.getRole()).openedBy(request.toUserId()).openedByName(request.toUserName())
                .openingFloat(Money.of(request.countedCash())).expectedCash(Money.of(request.countedCash())).build());
        return new Handover(closed, opened);
    }

    private Till close(Till t, BigDecimal counted, String note, HospioUserDetails user, FinanceRequests.HandoverRequest handover) {
        if (t.getStatus() == Till.Status.CLOSED) {
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

    private TillEntry addEntry(Till t, TillEntry.Kind kind, BigDecimal amount, String method, String description, String ref, String paymentId) {
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
    private Till tillForPayOut(HospioUserDetails user) {
        String hotelId = TenantContext.requireHotel();
        for (Role role : PAYING_ROLES) {
            if (user.has(role)) {
                Optional<Till> t = openTill(hotelId, role.name());
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
