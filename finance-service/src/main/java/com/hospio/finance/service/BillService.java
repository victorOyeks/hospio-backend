package com.hospio.finance.service;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ExpenseRecordedEvent;
import com.hospio.common.paging.Paging;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.finance.entity.Bill;
import com.hospio.finance.entity.Expense;
import com.hospio.finance.repository.BillRepository;
import com.hospio.finance.repository.ExpenseRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Bills (Manager · Financials): simple recurring payables. Reading them is for managers, the owner and Finance; adding, changing, paying
 * and cancelling for a manager, the owner or Finance EDIT. Paying one records a paid expense of the same amount and category (from the
 * bank, cash or the till) and, for a weekly or monthly bill, adds the next one.
 */
@Service
@RequiredArgsConstructor
public class BillService {

    private static final Map<String, String> SORTS = Map.of("dueDate", "dueDate", "amount", "amount", "payee", "payee", "status", "status",
            "createdAt", "createdAt");

    private final BillRepository bills;
    private final ExpenseRepository expenses;
    private final ExpenseCategories categories;
    private final MoneyService money;
    private final EventPublisher events;
    private final EntityManager em;

    public record BillRequest(String payee, String category, BigDecimal amount, LocalDate dueDate, String repeats, String note) {
    }

    public record PayRequest(String paidFrom) {
    }

    public record BillView(String id, String payee, String category, String categoryLabel, BigDecimal amount, LocalDate dueDate, String repeats,
                           String status, boolean overdue, String note, Instant paidAt, String paidByName, String paidFrom, String expenseId,
                           String nextBillId, String createdByName, Instant createdAt) {
    }

    public record Paid(BillView bill, Expense expense, BillView next) {
    }

    /**
     * One page of the hotel's bills. {@code status}: UNPAID, OVERDUE (unpaid and due before today), PAID, CANCELLED; absent = all.
     * {@code q} searches the payee and the note; {@code from}/{@code to} are due dates. Default order: due date ascending (soonest
     * first), then newest recorded. Counts per status plus OVERDUE and all; totals unpaid, overdue and paidThisMonth (Lagos month).
     */
    @Transactional
    public PageWithCounts<BillView> page(String status, String q, LocalDate from, LocalDate to, String sort, Integer page, Integer size) {
        requireRead();
        String hotel = TenantContext.requireHotel();
        LocalDate today = LocalDate.now(MoneyService.LAGOS);
        String st = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
        if (st != null && !List.of("UNPAID", "OVERDUE", "PAID", "CANCELLED", "ALL").contains(st)) {
            throw new BadRequestException("status is one of UNPAID, OVERDUE, PAID, CANCELLED");
        }
        Specification<Bill> base = (r, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(r.get("hotelId"), hotel));
            if (StringUtils.hasText(q)) {
                String like = Paging.like(q);
                ps.add(cb.or(cb.like(cb.lower(r.get("payee")), like, '\\'), cb.like(cb.lower(cb.coalesce(r.get("note"), "")), like, '\\')));
            }
            if (from != null) {
                ps.add(cb.greaterThanOrEqualTo(r.get("dueDate"), from));
            }
            if (to != null) {
                ps.add(cb.lessThanOrEqualTo(r.get("dueDate"), to));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Specification<Bill> spec = st == null || st.equals("ALL") ? base : base.and(status(st, today));
        Sort order = Paging.sort(sort, SORTS, Sort.by(Sort.Direction.ASC, "dueDate").and(Sort.by(Sort.Direction.DESC, "createdAt"))
                .and(Sort.by(Sort.Direction.DESC, "id")));
        Page<Bill> result = bills.findAll(spec, Paging.of(page, size, order));
        Map<String, Specification<Bill>> tabs = new LinkedHashMap<>();
        for (String s : List.of("UNPAID", "OVERDUE", "PAID", "CANCELLED")) {
            tabs.put(s, status(s, today));
        }
        Map<String, Long> counts = TabCounts.of(em, Bill.class, base, tabs);
        YearMonth month = YearMonth.from(today);
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put("unpaid", sum(base.and(status("UNPAID", today))));
        totals.put("overdue", sum(base.and(status("OVERDUE", today))));
        totals.put("paidThisMonth", sum(base.and(status("PAID", today)).and((r, cq, cb) -> cb.between(r.get("paidAt"),
                month.atDay(1).atStartOfDay(MoneyService.LAGOS).toInstant(), month.plusMonths(1).atDay(1).atStartOfDay(MoneyService.LAGOS).toInstant()))));
        Map<String, String> labels = categories.labels(hotel);
        return PageWithCounts.of(result, b -> view(b, labels, today), counts, totals);
    }

    @Transactional
    public BillView get(String id) {
        requireRead();
        return view(find(id));
    }

    @Transactional
    public BillView create(BillRequest request) {
        HospioUserDetails user = requireWrite();
        BillRules.Clean c = clean(request);
        Bill b = bills.save(Bill.builder().payee(c.payee()).category(c.category()).amount(c.amount()).dueDate(c.dueDate()).repeats(c.repeats())
                .note(c.note()).createdByName(user.getFullName()).build());
        return view(b);
    }

    @Transactional
    public BillView update(String id, BillRequest request) {
        requireWrite();
        Bill b = find(id);
        BillRules.requireUnpaid(b, "changed");
        BillRules.Clean c = clean(request);
        b.setPayee(c.payee());
        b.setCategory(c.category());
        b.setAmount(c.amount());
        b.setDueDate(c.dueDate());
        b.setRepeats(c.repeats());
        b.setNote(c.note());
        return view(bills.save(b));
    }

    @Transactional
    public BillView cancel(String id) {
        requireWrite();
        Bill b = find(id);
        BillRules.requireUnpaid(b, "cancelled");
        b.setStatus(Bill.Status.CANCELLED);
        return view(bills.save(b));
    }

    /** Pay a bill: a PAID expense of the same amount and category, today, from the bank, cash or the till; the next one if it repeats. */
    @Transactional
    public Paid pay(String id, PayRequest request) {
        HospioUserDetails user = requireWrite();
        Bill b = find(id);
        BillRules.requireUnpaid(b, "paid");
        String from = BillRules.paidFrom(request == null ? null : request.paidFrom());
        Instant now = Instant.now();
        Expense e = expenses.save(Expense.builder().category(b.getCategory()).amount(b.getAmount()).description(BillRules.expenseDescription(b))
                .paidFrom(from).expenseDate(LocalDate.now(MoneyService.LAGOS)).requestedBy(user.getUserId()).requestedByName(user.getFullName())
                .status(Expense.Status.PAID).paidBy(user.getUserId()).paidByName(user.getFullName()).paidAt(now).build());
        if ("TILL".equals(from)) {
            money.payOutFromTill(e, user);
        }
        events.publish(KafkaTopics.EXPENSE_RECORDED, ExpenseRecordedEvent.builder().expenseId(e.getId()).category(e.getCategory())
                .amount(e.getAmount()).description(e.getDescription()).status(e.getStatus().name()).build());
        b.setStatus(Bill.Status.PAID);
        b.setPaidAt(now);
        b.setPaidBy(user.getUserId());
        b.setPaidByName(user.getFullName());
        b.setPaidFrom(from);
        b.setExpenseId(e.getId());
        Bill next = null;
        LocalDate nextDue = BillRules.nextDue(b.getDueDate(), b.getRepeats());
        if (nextDue != null) {
            next = bills.save(Bill.builder().payee(b.getPayee()).category(b.getCategory()).amount(b.getAmount()).dueDate(nextDue)
                    .repeats(b.getRepeats()).note(b.getNote()).createdByName(b.getCreatedByName()).build());
            b.setNextBillId(next.getId());
        }
        bills.save(b);
        return new Paid(view(b), e, next == null ? null : view(next));
    }

    private BillRules.Clean clean(BillRequest r) {
        if (r == null) {
            throw new BadRequestException("Say who the bill is from");
        }
        BillRules.Clean c = BillRules.check(r.payee(), r.category(), r.amount(), r.dueDate(), r.repeats(), r.note());
        String code = categories.find(TenantContext.requireHotel(), c.category()).filter(x -> x.isActive()).map(x -> x.getCode())
                .orElseThrow(() -> new BadRequestException("That category isn't in this hotel's list"));
        return new BillRules.Clean(c.payee(), code, c.amount(), c.dueDate(), c.repeats(), c.note());
    }

    private Bill find(String id) {
        String hotel = TenantContext.requireHotel();
        return bills.findById(id).filter(b -> hotel.equals(b.getHotelId())).orElseThrow(() -> new ResourceNotFoundException("Bill not found"));
    }

    private BillView view(Bill b) {
        return view(b, categories.labels(b.getHotelId()), LocalDate.now(MoneyService.LAGOS));
    }

    static BillView view(Bill b, Map<String, String> labels, LocalDate today) {
        boolean overdue = b.getStatus() == Bill.Status.UNPAID && b.getDueDate().isBefore(today);
        return new BillView(b.getId(), b.getPayee(), b.getCategory(), ExpenseCategories.labelOf(labels, b.getCategory()), b.getAmount(), b.getDueDate(),
                b.getRepeats().name(), b.getStatus().name(), overdue, b.getNote(), b.getPaidAt(), b.getPaidByName(), b.getPaidFrom(), b.getExpenseId(),
                b.getNextBillId(), b.getCreatedByName(), b.getCreatedAt());
    }

    private static Specification<Bill> status(String status, LocalDate today) {
        return (r, q, cb) -> switch (status) {
            case "OVERDUE" -> cb.and(cb.equal(r.get("status"), Bill.Status.UNPAID), cb.lessThan(r.get("dueDate"), today));
            case "PAID" -> cb.equal(r.get("status"), Bill.Status.PAID);
            case "CANCELLED" -> cb.equal(r.get("status"), Bill.Status.CANCELLED);
            default -> cb.equal(r.get("status"), Bill.Status.UNPAID);
        };
    }

    private BigDecimal sum(Specification<Bill> spec) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<BigDecimal> q = cb.createQuery(BigDecimal.class);
        Root<Bill> r = q.from(Bill.class);
        q.select(cb.coalesce(cb.sum(r.<BigDecimal>get("amount")), BigDecimal.ZERO)).where(spec.toPredicate(r, q, cb));
        BigDecimal v = em.createQuery(q).getSingleResult();
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }

    static void requireRead() {
        HospioUserDetails u = CurrentUser.require();
        if (!(u.isPlatform() || u.isManagerOrOwner() || u.has(Role.FINANCE, AccessLevel.VIEW))) {
            throw new ForbiddenException("Only managers, the owner and Finance can see the hotel's bills");
        }
    }

    static HospioUserDetails requireWrite() {
        HospioUserDetails u = CurrentUser.require();
        if (u.isReadOnly() || !(u.isPlatform() || u.isManagerOrOwner() || u.has(Role.FINANCE, AccessLevel.EDIT))) {
            throw new ForbiddenException("Only a manager, the owner or Finance can add, change or pay bills");
        }
        return u;
    }
}
