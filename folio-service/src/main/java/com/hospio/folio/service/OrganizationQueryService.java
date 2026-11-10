package com.hospio.folio.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.paging.Paging;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.folio.dto.OrganizationViews.InvoiceView;
import com.hospio.folio.dto.OrganizationViews.OrganizationSummary;
import com.hospio.folio.dto.OrganizationViews.OrganizationsOverview;
import com.hospio.folio.entity.Folio;
import com.hospio.folio.entity.Invoice;
import com.hospio.folio.entity.Organization;
import com.hospio.folio.entity.OrganizationMember;
import com.hospio.folio.repository.FolioRepository;
import com.hospio.folio.repository.InvoiceRepository;
import com.hospio.folio.repository.OrganizationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The organizations screens' reads, paged and counted in the database (docs/pagination.md): the accounts list with each account's
 * figures, the hotel's overview, one account's stays and invoices, and every invoice of the hotel.
 */
@Service
@RequiredArgsConstructor
public class OrganizationQueryService {

    static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    static final Map<String, String> ORG_SORTS = Map.of("name", "name", "createdAt", "createdAt", "creditLimit", "creditLimit", "status", "status");
    static final Map<String, String> INVOICE_SORTS = Map.of("dueDate", "dueDate", "number", "number", "amount", "amount", "createdAt", "createdAt");
    static final Map<String, String> STAY_SORTS = Map.of("openedAt", "openedAt", "balance", "balance", "guestName", "guestName", "closedAt", "closedAt");
    static final List<String> INVOICE_STATUSES = List.of("OPEN", "OVERDUE", "SENT", "PART_PAID", "PAID", "DRAFT");

    private final OrganizationRepository organizations;
    private final InvoiceRepository invoices;
    private final FolioRepository folios;
    private final EntityManager em;

    // ---- the accounts ---------------------------------------------------------------------------------

    /**
     * One page of the hotel's accounts (A to Z by default), searched by name or contact and filtered by status, each with its figures.
     * {@code counts}: all, ACTIVE, ON_HOLD over the search; {@code totals.outstanding}: owed on the stays of every matching account.
     */
    @Transactional(readOnly = true)
    public PageWithCounts<OrganizationSummary> page(String q, String status, Integer page, Integer size, String sort) {
        String hotelId = TenantContext.requireHotel();
        String st = status(status);
        Sort order = Paging.sort(sort, ORG_SORTS, Sort.by(Sort.Direction.ASC, "name").and(Sort.by("id")));
        Page<Organization> result = organizations.findAll(orgSpec(hotelId, q, st), Paging.of(page, size, order));
        Map<String, Long> counts = new LinkedHashMap<>();
        long active = organizations.count(orgSpec(hotelId, q, "ACTIVE"));
        long onHold = organizations.count(orgSpec(hotelId, q, "ON_HOLD"));
        counts.put("all", organizations.count(orgSpec(hotelId, q, null)));
        counts.put("ACTIVE", active);
        counts.put("ON_HOLD", onHold);
        Specification<Organization> filtered = orgSpec(hotelId, q, st);
        BigDecimal outstanding = sumWhere(Folio.class, (r, cq, cb) -> r.get("organizationId").in(orgIds(cq, cb, filtered)), (r, cb) -> r.get("balance"));
        Map<String, Figures> figures = figures(result.getContent().stream().map(Organization::getId).toList());
        return PageWithCounts.of(result, o -> summary(o, figures.get(o.getId())), counts, Map.of("outstanding", Money.of(outstanding)));
    }

    /** One account with its figures (the account page). */
    @Transactional(readOnly = true)
    public OrganizationSummary one(String id) {
        Organization o = organizations.findById(id).orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        OrganizationSummary s = summary(o, figures(List.of(id)).get(id));
        // The latest overdue invoice, as the account page names it.
        LocalDate today = LocalDate.now(LAGOS);
        Page<Invoice> latest = invoices.findAll(invoiceSpec(o.getHotelId(), id, "OVERDUE", null, today),
                Paging.of(0, 1, Sort.by(Sort.Direction.DESC, "dueDate")));
        Invoice first = latest.isEmpty() ? null : latest.getContent().get(0);
        return new OrganizationSummary(s.organization(), s.outstanding(), s.staffCount(), s.staysThisMonth(), s.stays(), s.openStays(),
                s.runningBalance(), s.invoices(), s.openInvoices(), s.invoicesLeft(), s.overdueInvoices(), s.overdueAmount(),
                first == null ? null : first.getNumber(), first == null ? null : first.getDueDate());
    }

    /** The hotel's accounts at a glance, for the KPI cards (not narrowed by the list's search). */
    @Transactional(readOnly = true)
    public OrganizationsOverview overview() {
        String hotelId = TenantContext.requireHotel();
        LocalDate today = LocalDate.now(LAGOS);
        Specification<Organization> all = orgSpec(hotelId, null, null);
        long accounts = organizations.count(all);
        long staff = count(OrganizationMember.class, (r, cq, cb) -> cb.and(cb.isTrue(r.get("active")), r.get("organizationId").in(orgIds(cq, cb, all))));
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(LAGOS).toInstant();
        long stays = count(Folio.class, (r, cq, cb) -> cb.and(r.get("organizationId").in(orgIds(cq, cb, all)),
                cb.greaterThanOrEqualTo(r.get("openedAt"), monthStart)));
        BigDecimal outstanding = sumWhere(Folio.class, (r, cq, cb) -> r.get("organizationId").in(orgIds(cq, cb, all)), (r, cb) -> r.get("balance"));
        Specification<Invoice> open = invoiceSpec(hotelId, null, "OPEN", null, today);
        Specification<Invoice> overdue = invoiceSpec(hotelId, null, "OVERDUE", null, today);
        long openCount = invoices.count(open);
        long overdueCount = invoices.count(overdue);
        BigDecimal overdueAmount = sum(Invoice.class, overdue, (r, cb) -> cb.diff(r.<BigDecimal>get("amount"), r.<BigDecimal>get("paidAmount")));
        List<String> overdueNames = names(cb -> {
            CriteriaQuery<String> cq = cb.createQuery(String.class);
            Root<Organization> o = cq.from(Organization.class);
            Subquery<String> sq = cq.subquery(String.class);
            Root<Invoice> i = sq.from(Invoice.class);
            sq.select(i.get("organizationId")).where(overdue.toPredicate(i, cq, cb));
            cq.select(o.get("name")).where(cb.equal(o.get("hotelId"), hotelId), o.get("id").in(sq)).orderBy(cb.asc(o.get("name")));
            return cq;
        });
        List<String> onHoldNames = names(cb -> {
            CriteriaQuery<String> cq = cb.createQuery(String.class);
            Root<Organization> o = cq.from(Organization.class);
            cq.select(o.get("name")).where(cb.equal(o.get("hotelId"), hotelId), cb.equal(o.get("status"), "ON_HOLD")).orderBy(cb.asc(o.get("name")));
            return cq;
        });
        return new OrganizationsOverview(accounts, staff, stays, Money.of(outstanding), openCount, overdueCount, Money.of(overdueAmount),
                overdueNames, onHoldNames.size(), onHoldNames);
    }

    // ---- invoices ---------------------------------------------------------------------------------------

    /**
     * One page of invoices: the hotel's, or one account's ({@code organizationId}), latest due first. {@code status} is OPEN (not paid),
     * OVERDUE (unpaid past due), SENT, PART_PAID (not yet overdue), PAID or DRAFT; {@code q} searches the number or the account's name.
     * {@code counts} per status over the search; {@code totals}: amount, paid and left over the filtered range.
     */
    @Transactional(readOnly = true)
    public PageWithCounts<InvoiceView> invoices(String organizationId, String status, String q, Integer page, Integer size, String sort) {
        String hotelId = TenantContext.requireHotel();
        if (organizationId != null) {
            organizations.findById(organizationId).orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        }
        LocalDate today = LocalDate.now(LAGOS);
        String st = invoiceStatus(status);
        Specification<Invoice> spec = invoiceSpec(hotelId, organizationId, st, q, today);
        Page<Invoice> result = invoices.findAll(spec, Paging.of(page, size,
                Paging.sort(sort, INVOICE_SORTS, Sort.by(Sort.Direction.DESC, "dueDate").and(Sort.by(Sort.Direction.DESC, "number")).and(Sort.by("id")))));
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("all", invoices.count(invoiceSpec(hotelId, organizationId, null, q, today)));
        for (String s : INVOICE_STATUSES) {
            counts.put(s, invoices.count(invoiceSpec(hotelId, organizationId, s, q, today)));
        }
        BigDecimal amount = sum(Invoice.class, spec, (r, cb) -> r.get("amount"));
        BigDecimal paid = sum(Invoice.class, spec, (r, cb) -> r.get("paidAmount"));
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put("amount", Money.of(amount));
        totals.put("paid", Money.of(paid));
        totals.put("left", Money.of(amount.subtract(paid)));
        Map<String, String> names = new HashMap<>();
        List<String> ids = result.getContent().stream().map(Invoice::getOrganizationId).distinct().toList();
        if (!ids.isEmpty()) {
            organizations.findAllById(ids).forEach(o -> names.put(o.getId(), o.getName()));
        }
        return PageWithCounts.of(result, i -> new InvoiceView(i, names.getOrDefault(i.getOrganizationId(), ""), isOverdue(i, today)), counts, totals);
    }

    // ---- stays ------------------------------------------------------------------------------------------

    /**
     * One page of an account's stays (its folios), latest opened first; {@code from}/{@code to} are Lagos days of opening, {@code status}
     * OPEN or CLOSED (closed with or without a balance), {@code q} the guest or room. {@code counts}: all, OPEN, CLOSED;
     * {@code totals}: balance (still owed) and charges over the filtered range.
     */
    @Transactional(readOnly = true)
    public PageWithCounts<Folio> stays(String organizationId, String status, String q, LocalDate from, LocalDate to, Integer page, Integer size,
                                       String sort) {
        organizations.findById(organizationId).orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        if (st != null && !st.equals("OPEN") && !st.equals("CLOSED")) {
            throw new BadRequestException("status is OPEN or CLOSED");
        }
        Specification<Folio> spec = staySpec(organizationId, st, q, from, to);
        Page<Folio> result = folios.findAll(spec, Paging.of(page, size,
                Paging.sort(sort, STAY_SORTS, Sort.by(Sort.Direction.DESC, "openedAt").and(Sort.by("id")))));
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("all", folios.count(staySpec(organizationId, null, q, from, to)));
        counts.put("OPEN", folios.count(staySpec(organizationId, "OPEN", q, from, to)));
        counts.put("CLOSED", folios.count(staySpec(organizationId, "CLOSED", q, from, to)));
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        totals.put("balance", Money.of(sum(Folio.class, spec, (r, cb) -> r.get("balance"))));
        totals.put("charges", Money.of(sum(Folio.class, spec, (r, cb) -> r.get("totalCharges"))));
        return PageWithCounts.of(result, f -> f, counts, totals);
    }

    // ---- specifications ---------------------------------------------------------------------------------

    static Specification<Organization> orgSpec(String hotelId, String q, String status) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("hotelId"), hotelId));
            if (status != null) {
                ps.add(cb.equal(root.get("status"), status));
            }
            if (StringUtils.hasText(q)) {
                String like = Paging.like(q);
                ps.add(cb.or(likeOf(cb, root.get("name"), like), likeOf(cb, root.get("contactName"), like),
                        likeOf(cb, root.get("contactPhone"), like), likeOf(cb, root.get("contactEmail"), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    static Specification<Invoice> invoiceSpec(String hotelId, String organizationId, String status, String q, LocalDate today) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("hotelId"), hotelId));
            if (organizationId != null) {
                ps.add(cb.equal(root.get("organizationId"), organizationId));
            }
            Predicate overdue = cb.and(root.get("status").in(Invoice.Status.SENT, Invoice.Status.PART_PAID, Invoice.Status.OVERDUE),
                    cb.lessThan(root.get("dueDate"), today), cb.lessThan(root.<BigDecimal>get("paidAmount"), root.<BigDecimal>get("amount")));
            if (status != null) {
                switch (status) {
                    case "OPEN" -> ps.add(cb.not(root.get("status").in(Invoice.Status.PAID, Invoice.Status.DRAFT)));
                    case "OVERDUE" -> ps.add(overdue);
                    case "SENT" -> ps.add(cb.and(cb.equal(root.get("status"), Invoice.Status.SENT), cb.not(overdue)));
                    case "PART_PAID" -> ps.add(cb.and(cb.equal(root.get("status"), Invoice.Status.PART_PAID), cb.not(overdue)));
                    default -> ps.add(cb.equal(root.get("status"), Invoice.Status.valueOf(status)));
                }
            }
            if (StringUtils.hasText(q)) {
                String like = Paging.like(q);
                Subquery<String> named = query.subquery(String.class);
                Root<Organization> o = named.from(Organization.class);
                named.select(o.get("id")).where(cb.equal(o.get("hotelId"), hotelId), likeOf(cb, o.get("name"), like));
                ps.add(cb.or(likeOf(cb, root.get("number"), like), root.get("organizationId").in(named)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    static Specification<Folio> staySpec(String organizationId, String status, String q, LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("organizationId"), organizationId));
            if ("OPEN".equals(status)) {
                ps.add(cb.equal(root.get("status"), Folio.Status.OPEN));
            } else if ("CLOSED".equals(status)) {
                ps.add(cb.notEqual(root.get("status"), Folio.Status.OPEN));
            }
            if (from != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("openedAt"), from.atStartOfDay(LAGOS).toInstant()));
            }
            if (to != null) {
                ps.add(cb.lessThan(root.get("openedAt"), to.plusDays(1).atStartOfDay(LAGOS).toInstant()));
            }
            if (StringUtils.hasText(q)) {
                String like = Paging.like(q);
                ps.add(cb.or(likeOf(cb, root.get("guestName"), like), likeOf(cb, root.get("roomNumber"), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    private static Predicate likeOf(CriteriaBuilder cb, Expression<String> field, String like) {
        return cb.like(cb.lower(field), like, '\\');
    }

    static boolean isOverdue(Invoice i, LocalDate today) {
        return (i.getStatus() == Invoice.Status.SENT || i.getStatus() == Invoice.Status.PART_PAID || i.getStatus() == Invoice.Status.OVERDUE)
                && i.getDueDate().isBefore(today) && i.getPaidAmount().compareTo(i.getAmount()) < 0;
    }

    private static String status(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        String s = status.trim().toUpperCase(Locale.ROOT);
        if (!s.equals("ACTIVE") && !s.equals("ON_HOLD")) {
            throw new BadRequestException("status is ACTIVE or ON_HOLD");
        }
        return s;
    }

    private static String invoiceStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        String s = status.trim().toUpperCase(Locale.ROOT);
        if (!INVOICE_STATUSES.contains(s)) {
            throw new BadRequestException("status is one of " + String.join(", ", INVOICE_STATUSES));
        }
        return s;
    }

    // ---- figures per account ----------------------------------------------------------------------------

    record Figures(BigDecimal outstanding, long staff, long staysThisMonth, long stays, long openStays, BigDecimal running, long invoices,
                   long openInvoices, BigDecimal invoicesLeft, long overdue, BigDecimal overdueAmount) {
        static final Figures NONE = new Figures(BigDecimal.ZERO, 0, 0, 0, 0, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO, 0, BigDecimal.ZERO);
    }

    private static OrganizationSummary summary(Organization o, Figures f) {
        Figures x = f == null ? Figures.NONE : f;
        return new OrganizationSummary(o, Money.of(x.outstanding()), x.staff(), x.staysThisMonth(), x.stays(), x.openStays(), Money.of(x.running()),
                x.invoices(), x.openInvoices(), Money.of(x.invoicesLeft()), x.overdue(), Money.of(x.overdueAmount()), null, null);
    }

    /** Every figure for the given accounts in three grouped queries (stays, staff, invoices). */
    Map<String, Figures> figures(Collection<String> ids) {
        Map<String, Figures> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        LocalDate today = LocalDate.now(LAGOS);
        Instant monthStart = today.withDayOfMonth(1).atStartOfDay(LAGOS).toInstant();
        Map<String, Object[]> stays = new HashMap<>();
        for (Object[] r : em.createQuery("select f.organizationId, coalesce(sum(f.balance), 0), count(f), "
                        + "sum(case when f.openedAt >= :month then 1 else 0 end), sum(case when f.status = :open then 1 else 0 end), "
                        + "coalesce(sum(case when f.status = :open then f.balance else 0 end), 0) "
                        + "from Folio f where f.organizationId in :ids group by f.organizationId", Object[].class)
                .setParameter("month", monthStart).setParameter("open", Folio.Status.OPEN).setParameter("ids", ids).getResultList()) {
            stays.put((String) r[0], r);
        }
        Map<String, Long> staff = new HashMap<>();
        for (Object[] r : em.createQuery("select m.organizationId, count(m) from OrganizationMember m where m.organizationId in :ids "
                + "and m.active = true group by m.organizationId", Object[].class).setParameter("ids", ids).getResultList()) {
            staff.put((String) r[0], ((Number) r[1]).longValue());
        }
        Map<String, Object[]> inv = new HashMap<>();
        for (Object[] r : em.createQuery("select i.organizationId, count(i), "
                        + "sum(case when i.status <> :paid and i.status <> :draft then 1 else 0 end), "
                        + "coalesce(sum(case when i.status <> :paid and i.status <> :draft then i.amount - i.paidAmount else 0 end), 0), "
                        + "sum(case when i.status in :unpaid and i.dueDate < :today and i.paidAmount < i.amount then 1 else 0 end), "
                        + "coalesce(sum(case when i.status in :unpaid and i.dueDate < :today and i.paidAmount < i.amount "
                        + "then i.amount - i.paidAmount else 0 end), 0) "
                        + "from Invoice i where i.organizationId in :ids group by i.organizationId", Object[].class)
                .setParameter("paid", Invoice.Status.PAID).setParameter("draft", Invoice.Status.DRAFT)
                .setParameter("unpaid", List.of(Invoice.Status.SENT, Invoice.Status.PART_PAID, Invoice.Status.OVERDUE))
                .setParameter("today", today).setParameter("ids", ids).getResultList()) {
            inv.put((String) r[0], r);
        }
        for (String id : ids) {
            Object[] s = stays.get(id);
            Object[] i = inv.get(id);
            out.put(id, new Figures(s == null ? BigDecimal.ZERO : dec(s[1]), staff.getOrDefault(id, 0L), s == null ? 0 : num(s[3]),
                    s == null ? 0 : num(s[2]), s == null ? 0 : num(s[4]), s == null ? BigDecimal.ZERO : dec(s[5]), i == null ? 0 : num(i[1]),
                    i == null ? 0 : num(i[2]), i == null ? BigDecimal.ZERO : dec(i[3]), i == null ? 0 : num(i[4]),
                    i == null ? BigDecimal.ZERO : dec(i[5])));
        }
        return out;
    }

    private static long num(Object o) {
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static BigDecimal dec(Object o) {
        return o == null ? BigDecimal.ZERO : o instanceof BigDecimal b ? b : new BigDecimal(o.toString());
    }

    // ---- criteria helpers ---------------------------------------------------------------------------------

    @FunctionalInterface
    interface Where<E> {
        Predicate on(Root<E> root, CriteriaQuery<?> query, CriteriaBuilder cb);
    }

    private static Subquery<String> orgIds(CriteriaQuery<?> query, CriteriaBuilder cb, Specification<Organization> spec) {
        Subquery<String> sq = query.subquery(String.class);
        Root<Organization> o = sq.from(Organization.class);
        sq.select(o.get("id")).where(spec.toPredicate(o, query, cb));
        return sq;
    }

    private <E> BigDecimal sum(Class<E> type, Specification<E> spec, BiFunction<Root<E>, CriteriaBuilder, Expression<BigDecimal>> what) {
        return sumWhere(type, spec::toPredicate, what);
    }

    private <E> BigDecimal sumWhere(Class<E> type, Where<E> where, BiFunction<Root<E>, CriteriaBuilder, Expression<BigDecimal>> what) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<BigDecimal> cq = cb.createQuery(BigDecimal.class);
        Root<E> r = cq.from(type);
        cq.select(cb.coalesce(cb.sum(what.apply(r, cb)), BigDecimal.ZERO)).where(where.on(r, cq, cb));
        BigDecimal v = em.createQuery(cq).getSingleResult();
        return v == null ? BigDecimal.ZERO : v;
    }

    private <E> long count(Class<E> type, Where<E> where) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> cq = cb.createQuery(Long.class);
        Root<E> r = cq.from(type);
        cq.select(cb.count(r)).where(where.on(r, cq, cb));
        return em.createQuery(cq).getSingleResult();
    }

    private List<String> names(Function<CriteriaBuilder, CriteriaQuery<String>> build) {
        return em.createQuery(build.apply(em.getCriteriaBuilder())).getResultList();
    }
}
