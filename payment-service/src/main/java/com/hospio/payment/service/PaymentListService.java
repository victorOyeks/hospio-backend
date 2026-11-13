package com.hospio.payment.service;

import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.paging.Paging;
import com.hospio.common.response.PageWithCounts;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.payment.dto.DisputeRow;
import com.hospio.payment.dto.PaymentResponse;
import com.hospio.payment.dto.RefundRow;
import com.hospio.payment.entity.Dispute;
import com.hospio.payment.entity.PaymentRequest;
import com.hospio.payment.entity.Refund;
import com.hospio.payment.query.PaymentListQuery;
import com.hospio.payment.repository.DisputeRepository;
import com.hospio.payment.repository.PaymentRequestRepository;
import com.hospio.payment.repository.RefundRepository;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The paged Payments lists (docs/pagination.md): payments with tab counts and the money above the list, refunds and disputes with the
 * payment's guest and amount on each row. Staff see what they took; managers the hotel's; the owner without X-Hotel-Id the whole
 * business ({@code hotelId} narrows to one hotel).
 */
@Service
@RequiredArgsConstructor
public class PaymentListService {

    /** The payments list's query string. Dates are Lagos days, both inclusive; {@code status} may list several (comma separated). */
    public record Params(Integer page, Integer size, String sort, String q, String status, String method, String role, LocalDate from,
                         LocalDate to, Boolean mine, String hotelId, Boolean today) {
    }

    private static final Set<String> TO_VERIFY = Set.of("PENDING", "PROCESSING", "NO_RESPONSE");

    private final PaymentListQuery query;
    private final PaymentRequestRepository payments;
    private final RefundRepository refunds;
    private final DisputeRepository disputes;

    @Transactional(readOnly = true)
    public PageWithCounts<PaymentResponse> payments(Params p) {
        HospioUserDetails user = CurrentUser.require();
        int page = Paging.page(p.page());
        int size = Paging.size(p.size());
        Sort.Order order = order(p.sort());
        String column = PaymentListQuery.SORTS.get(order.getProperty());
        PaymentListQuery.Filter scope = scope(user, p.hotelId(), p.role(), false);
        LocalDate fromDay = Paging.windowFrom(p.from());
        if (p.to() != null && p.to().isBefore(fromDay)) {
            throw new BadRequestException("from is after to");
        }
        LocalDate today = LocalDate.now(Paging.LAGOS);
        PaymentListQuery.Window todayWindow = new PaymentListQuery.Window(start(today), start(today.plusDays(1)));
        // The base: scope, search, method and dates. The tabs (status, mine, today) narrow the rows but not the counts.
        PaymentListQuery.Filter base = new PaymentListQuery.Filter(scope.businessId(), scope.hotelId(), scope.requestedBy(), blank(p.q()),
                null, method(p.method()), scope.role(), start(fromDay), p.to() == null ? null : start(p.to().plusDays(1)));
        boolean onlyToday = Boolean.TRUE.equals(p.today());
        PaymentListQuery.Filter f = new PaymentListQuery.Filter(base.businessId(), base.hotelId(),
                Boolean.TRUE.equals(p.mine()) ? user.getUserId() : base.requestedBy(), base.q(), statuses(p.status()), base.method(), base.role(),
                onlyToday ? later(base.from(), todayWindow.from()) : base.from(), onlyToday ? earlier(base.to(), todayWindow.to()) : base.to());

        long total = query.count(f);
        List<String> ids = query.ids(f, column, order.isAscending(), page, size);
        Map<String, PaymentRequest> byId = payments.findAllById(ids).stream().collect(Collectors.toMap(PaymentRequest::getId, Function.identity()));
        List<PaymentResponse> content = ids.stream().map(byId::get).filter(java.util.Objects::nonNull).map(PaymentResponse::of).toList();

        Map<String, Long> counts = new LinkedHashMap<>(query.countsByStatus(base));
        counts.put("toVerify", TO_VERIFY.stream().mapToLong(s -> counts.getOrDefault(s, 0L)).sum());
        counts.put("attention", counts.getOrDefault("DECLINED", 0L) + counts.getOrDefault("NO_RESPONSE", 0L) + counts.getOrDefault("PROCESSING", 0L));
        counts.put("today", query.countWhere(base, new PaymentListQuery.Window(later(base.from(), todayWindow.from()),
                earlier(base.to(), todayWindow.to())), null));
        counts.put("mine", query.countWhere(base, null, user.getUserId()));
        Map<String, BigDecimal> totals = new LinkedHashMap<>(query.approved(base));
        totals.putAll(query.period(scope, todayWindow, new PaymentListQuery.Window(start(today.withDayOfMonth(1)), start(today.plusDays(1)))));

        int pages = (int) Math.ceil(total / (double) size);
        return new PageWithCounts<>(content, page, size, total, pages, page == 0, page + 1 >= pages, content.isEmpty(), counts, totals);
    }

    /** The hotel's refunds, newest first, with the payment's guest and amount; {@code status} narrows, counts are per status. */
    @Transactional(readOnly = true)
    public PageWithCounts<RefundRow> refunds(Integer page, Integer size, String status) {
        String hotel = TenantContext.requireHotel();
        Refund.Status st = status == null || status.isBlank() ? null : parse(Refund.Status.class, status);
        Specification<Refund> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("hotelId"), hotel));
            if (st != null) {
                ps.add(cb.equal(root.get("status"), st));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<Refund> result = refunds.findAll(spec, Paging.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
        Map<String, PaymentRequest> pays = paymentsOf(result.getContent().stream().map(Refund::getPaymentId).toList());
        Map<String, Long> counts = new LinkedHashMap<>();
        BigDecimal pendingAmount = BigDecimal.ZERO;
        long all = 0;
        for (Refund.Status s : Refund.Status.values()) {
            long n = refunds.count((root, q, cb) -> cb.and(cb.equal(root.get("hotelId"), hotel), cb.equal(root.get("status"), s)));
            counts.put(s.name(), n);
            all += n;
        }
        counts.put("all", all);
        for (Refund r : refunds.findAll((root, q, cb) -> cb.and(cb.equal(root.get("hotelId"), hotel),
                cb.equal(root.get("status"), Refund.Status.PENDING_APPROVAL)))) {
            pendingAmount = pendingAmount.add(r.getAmount());
        }
        return PageWithCounts.of(result, r -> RefundRow.of(r, pays.get(r.getPaymentId())), counts, Map.of("pending", pendingAmount));
    }

    /**
     * The unpaged form (scripts and other callers), bounded like the other recent lists (docs/pagination.md): the hotel's refunds asked for
     * in the last 90 days plus any still waiting for approval however old, newest first, with the payment's guest and amount.
     */
    @Transactional(readOnly = true)
    public List<RefundRow> allRefunds() {
        String hotel = TenantContext.requireHotel();
        Instant since = Paging.windowFrom(null).atStartOfDay(Paging.LAGOS).toInstant();
        List<Refund> list = refunds.findAll((root, q, cb) -> cb.and(cb.equal(root.get("hotelId"), hotel),
                        cb.or(cb.greaterThanOrEqualTo(root.get("createdAt"), since), cb.equal(root.get("status"), Refund.Status.PENDING_APPROVAL))),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        Map<String, PaymentRequest> pays = paymentsOf(list.stream().map(Refund::getPaymentId).toList());
        return list.stream().map(r -> RefundRow.of(r, pays.get(r.getPaymentId()))).toList();
    }

    @Transactional(readOnly = true)
    public PageWithCounts<DisputeRow> disputes(Integer page, Integer size, String status) {
        String hotel = TenantContext.requireHotel();
        Dispute.Status st = status == null || status.isBlank() ? null : parse(Dispute.Status.class, status);
        Specification<Dispute> spec = (root, q, cb) -> st == null ? cb.equal(root.get("hotelId"), hotel)
                : cb.and(cb.equal(root.get("hotelId"), hotel), cb.equal(root.get("status"), st));
        Page<Dispute> result = disputes.findAll(spec, Paging.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
        Map<String, PaymentRequest> pays = paymentsOf(result.getContent().stream().map(Dispute::getPaymentId).toList());
        Map<String, Long> counts = new LinkedHashMap<>();
        long all = 0;
        for (Dispute.Status s : Dispute.Status.values()) {
            long n = disputes.count((root, q, cb) -> cb.and(cb.equal(root.get("hotelId"), hotel), cb.equal(root.get("status"), s)));
            counts.put(s.name(), n);
            all += n;
        }
        counts.put("all", all);
        return PageWithCounts.of(result, d -> DisputeRow.of(d, pays.get(d.getPaymentId())), counts, Map.of());
    }

    /** The unpaged form, bounded the same way: disputes opened in the last 90 days plus any still open, newest first. */
    @Transactional(readOnly = true)
    public List<DisputeRow> allDisputes() {
        String hotel = TenantContext.requireHotel();
        Instant since = Paging.windowFrom(null).atStartOfDay(Paging.LAGOS).toInstant();
        List<Dispute> list = disputes.findAll((root, q, cb) -> cb.and(cb.equal(root.get("hotelId"), hotel),
                        cb.or(cb.greaterThanOrEqualTo(root.get("createdAt"), since), cb.equal(root.get("status"), Dispute.Status.OPEN))),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        Map<String, PaymentRequest> pays = paymentsOf(list.stream().map(Dispute::getPaymentId).toList());
        return list.stream().map(d -> DisputeRow.of(d, pays.get(d.getPaymentId()))).toList();
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /**
     * Who the caller may see: the X-Hotel-Id hotel (or, for the owner without one, the whole business, narrowed by {@code hotelId});
     * staff only what they took (as does {@code mine}); {@code role} narrows to one desk.
     */
    PaymentListQuery.Filter scope(HospioUserDetails user, String hotelId, String role, boolean mine) {
        Tenant t = TenantContext.require();
        String business = TenantContext.requireBusiness();
        String hotel = t.hotelId();
        if (hotel == null) {
            if (!user.has(Role.OWNER)) {
                throw new BadRequestException("Choose a hotel first (X-Hotel-Id header)");
            }
            hotel = blank(hotelId);
        }
        String by = !user.isManagerOrOwner() || mine ? user.getUserId() : null;
        String r = blank(role) == null ? null : parse(Role.class, role).name();
        return new PaymentListQuery.Filter(business, hotel, by, null, null, null, r, null, null);
    }

    private Map<String, PaymentRequest> paymentsOf(List<String> ids) {
        return payments.findAllById(ids.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(PaymentRequest::getId, Function.identity()));
    }

    static Sort.Order order(String sort) {
        Sort s = Paging.sort(sort, Map.of("createdAt", "createdAt", "amount", "amount", "guestName", "guestName", "status", "status",
                "method", "method"), Sort.by(Sort.Direction.DESC, "createdAt"));
        return s.iterator().next();
    }

    static String statuses(String status) {
        if (blank(status) == null) {
            return null;
        }
        return Arrays.stream(status.split(",")).map(String::trim).filter(x -> !x.isEmpty()).map(x -> parse(PaymentStatus.class, x).name())
                .distinct().collect(Collectors.joining(","));
    }

    static String method(String method) {
        return blank(method) == null ? null : parse(PaymentMethod.class, method).name();
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown " + type.getSimpleName().replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT)
                    + ": " + value);
        }
    }

    private static Instant later(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }

    private static Instant earlier(Instant a, Instant b) {
        return a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
    }

    private static Instant start(LocalDate day) {
        return day.atStartOfDay(Paging.LAGOS).toInstant();
    }

    private static String blank(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
