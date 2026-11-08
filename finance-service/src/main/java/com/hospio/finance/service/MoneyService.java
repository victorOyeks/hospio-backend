package com.hospio.finance.service;

import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalRequestedEvent;
import com.hospio.common.kafka.events.CashBankedEvent;
import com.hospio.common.kafka.events.DayClosedEvent;
import com.hospio.common.kafka.events.ExpenseRecordedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.finance.dto.FinanceRequests;
import com.hospio.finance.dto.MoneyToday;
import com.hospio.finance.entity.BankDeposit;
import com.hospio.finance.entity.Budget;
import com.hospio.finance.entity.Expense;
import com.hospio.finance.entity.NightAudit;
import com.hospio.finance.entity.RevenueEntry;
import com.hospio.finance.entity.Till;
import com.hospio.finance.entity.TillEntry;
import com.hospio.finance.repository.BankDepositRepository;
import com.hospio.finance.repository.BudgetRepository;
import com.hospio.finance.repository.ExpenseRepository;
import com.hospio.finance.repository.NightAuditRepository;
import com.hospio.finance.repository.RevenueEntryRepository;
import com.hospio.finance.repository.TillEntryRepository;
import com.hospio.finance.repository.TillRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Finance 1.x-4.x: today's money, expenses and approvals, tills and cash, night audit, banking. */
@Service
@RequiredArgsConstructor
public class MoneyService {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final RevenueEntryRepository revenue;
    private final TillRepository tills;
    private final TillEntryRepository tillEntries;
    private final ExpenseRepository expenses;
    private final BudgetRepository budgets;
    private final NightAuditRepository audits;
    private final BankDepositRepository deposits;
    private final EventPublisher events;

    @Value("${hospio.approvals.manager-spend-limit:500000}")
    private BigDecimal managerLimit;

    // ---- revenue from PaymentApproved ---------------------------------------------------------------

    @Transactional
    public void recordPayment(String paymentId, BigDecimal amount, String method, String role, String folioId, Instant at) {
        if (revenue.findByPaymentId(paymentId).isPresent()) {
            return;
        }
        LocalDate date = (at == null ? Instant.now() : at).atZone(LAGOS).toLocalDate();
        revenue.save(RevenueEntry.builder().paymentId(paymentId).businessDate(date).amount(Money.of(amount)).method(method).role(role).folioId(folioId).build());
        if (PaymentMethod.CASH.name().equals(method) && role != null) {
            tills.findFirstByHotelIdAndRoleAndStatus(TenantContext.requireHotel(), role, Till.Status.OPEN).ifPresent(till -> {
                if (tillEntries.findByPaymentId(paymentId).isEmpty()) {
                    tillEntries.save(TillEntry.builder().tillId(till.getId()).paymentId(paymentId).amount(Money.of(amount)).method(method).description("Cash payment").build());
                    till.setCashIn(Money.add(till.getCashIn(), amount));
                    till.setExpectedCash(Money.add(till.getOpeningFloat(), till.getCashIn()));
                    tills.save(till);
                }
            });
        }
    }

    @Transactional(readOnly = true)
    public MoneyToday today(LocalDate date) {
        String hotelId = TenantContext.requireHotel();
        LocalDate d = date == null ? LocalDate.now(LAGOS) : date;
        List<RevenueEntry> entries = revenue.findByHotelIdAndBusinessDate(hotelId, d);
        Map<String, BigDecimal> byMethod = new LinkedHashMap<>();
        Map<String, BigDecimal> byRole = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (RevenueEntry e : entries) {
            total = total.add(e.getAmount());
            byMethod.merge(e.getMethod(), e.getAmount(), BigDecimal::add);
            byRole.merge(e.getRole() == null ? "OTHER" : e.getRole(), e.getAmount(), BigDecimal::add);
        }
        BigDecimal spent = expenses.findByHotelIdAndExpenseDateBetween(hotelId, d, d).stream()
                .filter(x -> x.getStatus() != Expense.Status.DECLINED).map(Expense::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Till> open = tills.findByHotelIdAndStatusOrderByOpenedAt(hotelId, Till.Status.OPEN);
        BigDecimal cash = open.stream().map(Till::getExpectedCash).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new MoneyToday(Money.of(total), byMethod, byRole, Money.of(spent), Money.of(cash), open.size(), entries.size());
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
    public List<Expense> expenses() {
        return expenses.findByHotelIdOrderByExpenseDateDesc(TenantContext.requireHotel());
    }

    @Transactional
    public Expense recordExpense(FinanceRequests.ExpenseRequest request) {
        HospioUserDetails user = CurrentUser.require();
        Expense e = Expense.builder().category(request.category()).amount(Money.of(request.amount())).description(request.description())
                .paidFrom(request.paidFrom()).receiptKey(request.receiptKey())
                .expenseDate(request.expenseDate() == null ? LocalDate.now(LAGOS) : request.expenseDate())
                .requestedBy(user.getUserId()).requestedByName(user.getFullName()).build();
        boolean needsOwner = e.getAmount().compareTo(managerLimit) > 0 && !user.has(com.hospio.common.enums.Role.OWNER) && !user.isPlatform();
        boolean needsManager = !user.isManagerOrOwner() && !user.isPlatform();
        if (needsOwner || needsManager) {
            e.setStatus(Expense.Status.PENDING_APPROVAL);
            e.setApprovalId(UUID.randomUUID().toString());
        }
        expenses.save(e);
        if (e.getApprovalId() != null) {
            events.publish(KafkaTopics.APPROVAL_REQUESTED, ApprovalRequestedEvent.builder().approvalId(e.getApprovalId()).sourceModule("FINANCE")
                    .sourceRef(e.getId()).kind("EXPENSE").amount(e.getAmount()).requesterId(user.getUserId()).requesterName(user.getFullName())
                    .approverLevel(needsOwner ? "OWNER" : "MANAGER").description(e.getCategory() + " · " + e.getAmount() + " · " + e.getDescription()).build());
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

    @Transactional
    public Expense markPaid(String id) {
        Expense e = expenses.findById(id).orElseThrow(() -> new ResourceNotFoundException("Expense not found"));
        if (e.getStatus() == Expense.Status.PENDING_APPROVAL || e.getStatus() == Expense.Status.DECLINED) {
            throw new BadRequestException("This expense isn't approved");
        }
        e.setStatus(Expense.Status.PAID);
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

    @Transactional(readOnly = true)
    public List<Till> tillsToday() {
        return tills.findByHotelIdAndOpenedAtAfterOrderByOpenedAtDesc(TenantContext.requireHotel(), LocalDate.now(LAGOS).atStartOfDay(LAGOS).toInstant());
    }

    @Transactional
    public Till openTill(FinanceRequests.OpenTillRequest request) {
        HospioUserDetails user = CurrentUser.require();
        String hotelId = TenantContext.requireHotel();
        tills.findFirstByHotelIdAndRoleAndStatus(hotelId, request.role(), Till.Status.OPEN).ifPresent(t -> {
            throw new BadRequestException("The " + request.role().toLowerCase().replace('_', ' ') + " till is already open");
        });
        return tills.save(Till.builder().role(request.role()).openedBy(user.getUserId()).openedByName(user.getFullName())
                .openingFloat(Money.of(request.openingFloat())).expectedCash(Money.of(request.openingFloat())).build());
    }

    @Transactional
    public Till closeTill(String id, FinanceRequests.CloseTillRequest request) {
        Till t = tills.findById(id).orElseThrow(() -> new ResourceNotFoundException("Till not found"));
        if (t.getStatus() == Till.Status.CLOSED) {
            throw new BadRequestException("This till is already closed");
        }
        t.setCountedCash(Money.of(request.countedCash()));
        t.setVariance(Money.subtract(request.countedCash(), t.getExpectedCash()));
        t.setStatus(Till.Status.CLOSED);
        t.setClosedAt(Instant.now());
        t.setClosedBy(CurrentUser.require().getUserId());
        t.setNote(request.note());
        return tills.save(t);
    }

    @Transactional(readOnly = true)
    public List<TillEntry> tillEntries(String tillId) {
        return tillEntries.findByTillIdOrderByCreatedAt(tillId);
    }

    @Transactional
    public BankDeposit bankCash(FinanceRequests.BankCashRequest request) {
        HospioUserDetails user = CurrentUser.require();
        BankDeposit d = deposits.save(BankDeposit.builder().amount(Money.of(request.amount())).bankAccount(request.bankAccount())
                .reference(request.reference()).bankedBy(user.getUserId()).build());
        events.publish(KafkaTopics.CASH_BANKED, CashBankedEvent.builder().amount(d.getAmount()).bankAccount(d.getBankAccount()).bankedBy(user.getFullName()).build());
        return d;
    }

    @Transactional(readOnly = true)
    public List<BankDeposit> deposits() {
        return deposits.findByHotelIdOrderByBankedAtDesc(TenantContext.requireHotel());
    }

    // ---- night audit ---------------------------------------------------------------------------------

    @Transactional
    public NightAudit audit(LocalDate date) {
        String hotelId = TenantContext.requireHotel();
        LocalDate d = date == null ? LocalDate.now(LAGOS) : date;
        return audits.findByHotelIdAndBusinessDate(hotelId, d).orElseGet(() -> audits.save(NightAudit.builder().businessDate(d).build()));
    }

    @Transactional(readOnly = true)
    public List<NightAudit> pastAudits() {
        return audits.findByHotelIdOrderByBusinessDateDesc(TenantContext.requireHotel());
    }

    @Transactional
    public NightAudit saveChecklist(LocalDate date, FinanceRequests.ChecklistRequest request) {
        NightAudit a = audit(date);
        a.setChecklist(request.checklist());
        return audits.save(a);
    }

    @Transactional
    public NightAudit closeDay(LocalDate date, FinanceRequests.CloseDayRequest request) {
        HospioUserDetails user = CurrentUser.require();
        if (!user.isManagerOrOwner() && !user.has(com.hospio.common.enums.Role.FINANCE, com.hospio.common.enums.AccessLevel.FULL)) {
            throw new ForbiddenException("Closing the day needs Finance Full or a manager");
        }
        NightAudit a = audit(date);
        if (a.getStatus() == NightAudit.Status.CLOSED) {
            throw new BadRequestException("This day is already closed");
        }
        String hotelId = TenantContext.requireHotel();
        List<Till> open = tills.findByHotelIdAndStatusOrderByOpenedAt(hotelId, Till.Status.OPEN);
        if (!open.isEmpty()) {
            throw new BadRequestException(open.size() + " till(s) are still open. Count and close them first");
        }
        MoneyToday today = today(a.getBusinessDate());
        a.setRevenue(today.revenue());
        a.setCashCounted(request == null ? null : request.cashCounted());
        BigDecimal cashTaken = today.byMethod().getOrDefault("CASH", BigDecimal.ZERO);
        a.setVariance(request == null || request.cashCounted() == null ? null : Money.subtract(request.cashCounted(), cashTaken));
        a.setRoomsOccupied(request == null ? null : request.roomsOccupied());
        a.setNote(request == null ? null : request.note());
        a.setStatus(NightAudit.Status.CLOSED);
        a.setClosedBy(user.getUserId());
        a.setClosedAt(Instant.now());
        audits.save(a);
        events.publish(KafkaTopics.DAY_CLOSED, DayClosedEvent.builder().auditId(a.getId()).businessDate(a.getBusinessDate()).revenue(a.getRevenue())
                .cashCounted(a.getCashCounted()).variance(a.getVariance()).closedBy(user.getFullName()).build());
        return a;
    }
}
