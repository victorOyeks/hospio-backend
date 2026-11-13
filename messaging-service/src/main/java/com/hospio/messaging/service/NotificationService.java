package com.hospio.messaging.service;

import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.messaging.entity.Notification;
import com.hospio.messaging.repository.NotificationRepository;
import com.hospio.common.paging.Paging;
import com.hospio.common.response.PageWithCounts;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** In-app notifications: the bell, each role's Notifications screen, the toasts. */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notifications;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager em;

    /**
     * Idempotent per (event, user, role) so a replayed Kafka event never shows twice: with an event id the row is inserted with
     * ON CONFLICT DO NOTHING against the unique index (messaging-007), so two instances handling the same event at once still
     * make one notice, and the losing insert does not abort the caller's transaction. Null when it was already there.
     */
    @Transactional
    public Notification notify(String eventId, String userId, String role, String kind, String title, String body, String link) {
        Notification n = Notification.builder().userId(userId).role(role).kind(kind).title(title).body(body).link(link).eventId(eventId).build();
        if (eventId == null) {
            return notifications.save(n);
        }
        Instant now = Instant.now();
        n.setId(java.util.UUID.randomUUID().toString());
        n.setBusinessId(TenantContext.current().map(t -> t.businessId()).orElse(null));
        n.setHotelId(TenantContext.current().map(t -> t.hotelId()).orElse(null));
        n.setCreatedAt(now);
        n.setUpdatedAt(now);
        int inserted = notifications.insertOnce(n.getId(), n.getBusinessId(), n.getHotelId(), userId, role, kind, title, body, link, eventId, now);
        return inserted == 1 ? n : null;
    }

    @Transactional
    public void notifyRoles(String eventId, String kind, String title, String body, String link, String... roles) {
        for (String role : roles) {
            notify(eventId, null, role, kind, title, body, link);
        }
    }

    /** How many the unpaged list (the bell's dropdown, older screens) returns at most. */
    public static final int MINE_LIMIT = 50;

    /** Mine: addressed to me, plus everything for any role I hold at this hotel; the newest {@link #MINE_LIMIT}, newest first. */
    @Transactional(readOnly = true)
    public List<Notification> mine(boolean unreadOnly) {
        Specification<Notification> spec = unreadOnly ? mineSpec().and(unread(true)) : mineSpec();
        return notifications.findAll(spec, PageRequest.of(0, MINE_LIMIT, NEWEST)).getContent();
    }

    private static final Sort NEWEST = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    /** The filters of the paged list ({@code unread} true or false narrows; null is every one). */
    public record Filter(Boolean unread, String kind, String q, LocalDate from, LocalDate to) {
    }

    /**
     * Mine, a page at a time, newest first: the same rows as {@link #mine} filtered in the database. {@code from} defaults to 90 days back.
     * {@code counts}: {@code all} and {@code unread} over the whole filtered range (without the unread filter).
     */
    @Transactional(readOnly = true)
    public PageWithCounts<Notification> page(Filter f, Integer page, Integer size) {
        Specification<Notification> base = mineSpec().and(filtered(f.kind(), f.q(), Paging.windowFrom(f.from()), f.to()));
        Specification<Notification> spec = f.unread() == null ? base : base.and(unread(f.unread()));
        PageRequest pageable = Paging.of(page, size, NEWEST);
        // One query for both counts (count(*) and the unread ones); the page's total follows from them, so no third count.
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> cq = cb.createQuery(Object[].class);
        Root<Notification> root = cq.from(Notification.class);
        Predicate where = base.toPredicate(root, cq, cb);
        cq.multiselect(cb.count(root), cb.sum(cb.<Long>selectCase().when(cb.isNull(root.get("readAt")), 1L).otherwise(0L)));
        if (where != null) {
            cq.where(where);
        }
        Object[] row = em.createQuery(cq).getSingleResult();
        long all = row[0] == null ? 0 : ((Number) row[0]).longValue();
        long unread = row[1] == null ? 0 : ((Number) row[1]).longValue();
        long total = f.unread() == null ? all : f.unread() ? unread : all - unread;
        List<Notification> content = total <= pageable.getOffset() ? List.of() : pageOf(spec, pageable);
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("all", all);
        counts.put("unread", unread);
        return PageWithCounts.of(new PageImpl<>(content, pageable, total), n -> n, counts, Map.of());
    }

    /** One page of rows without Spring Data's extra count query. */
    private List<Notification> pageOf(Specification<Notification> spec, PageRequest pageable) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Notification> cq = cb.createQuery(Notification.class);
        Root<Notification> root = cq.from(Notification.class);
        Predicate where = spec.toPredicate(root, cq, cb);
        if (where != null) {
            cq.where(where);
        }
        cq.orderBy(cb.desc(root.get("createdAt")), cb.desc(root.get("id")));
        return em.createQuery(cq).setFirstResult((int) pageable.getOffset()).setMaxResults(pageable.getPageSize()).getResultList();
    }

    /** How many of mine are unread, any age: the bell's badge without loading the list. */
    @Transactional(readOnly = true)
    public long unreadCount() {
        return notifications.count(mineSpec().and(unread(true)));
    }

    /** Addressed to me, or to a role I hold at this hotel. */
    private static Specification<Notification> mineSpec() {
        HospioUserDetails user = CurrentUser.require();
        String hotelId = TenantContext.current().map(t -> t.hotelId()).orElse(null);
        List<String> roles = user.roleSet().stream().map(Enum::name).toList();
        return (root, query, cb) -> {
            Predicate toMe = cb.equal(root.get("userId"), user.getUserId());
            if (hotelId == null || roles.isEmpty()) {
                return toMe;
            }
            return cb.or(toMe, cb.and(cb.equal(root.get("hotelId"), hotelId), root.get("role").in(roles)));
        };
    }

    private static Specification<Notification> unread(boolean unread) {
        return (root, query, cb) -> unread ? cb.isNull(root.get("readAt")) : cb.isNotNull(root.get("readAt"));
    }

    private static Specification<Notification> filtered(String kind, String q, LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (StringUtils.hasText(kind)) {
                ps.add(cb.equal(root.get("kind"), kind.trim()));
            }
            if (StringUtils.hasText(q)) {
                String like = Paging.like(q);
                ps.add(cb.or(cb.like(cb.lower(root.get("title")), like, '\\'), cb.like(cb.lower(cb.coalesce(root.get("body"), "")), like, '\\')));
            }
            if (from != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay(Paging.LAGOS).toInstant()));
            }
            if (to != null) {
                ps.add(cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay(Paging.LAGOS).toInstant()));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    @Transactional
    public Notification markRead(String id) {
        Notification n = notifications.findById(id).orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        n.setReadAt(Instant.now());
        return notifications.save(n);
    }

    /** Every unread one of mine (any age) read, in one UPDATE. */
    @Transactional
    public int markAllRead() {
        HospioUserDetails user = CurrentUser.require();
        String hotelId = TenantContext.current().map(t -> t.hotelId()).orElse(null);
        List<String> roles = user.roleSet().stream().map(Enum::name).toList();
        Instant now = Instant.now();
        if (hotelId == null || roles.isEmpty()) {
            return notifications.markAllReadForUser(user.getUserId(), now);
        }
        return notifications.markAllRead(user.getUserId(), hotelId, roles, now);
    }
}
