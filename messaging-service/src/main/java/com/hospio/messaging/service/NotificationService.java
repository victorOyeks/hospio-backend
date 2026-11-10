package com.hospio.messaging.service;

import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.messaging.entity.Notification;
import com.hospio.messaging.repository.NotificationRepository;
import com.hospio.common.paging.Paging;
import com.hospio.common.response.PageWithCounts;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
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

    /** Idempotent per (event, user, role) so a replayed Kafka event never shows twice. */
    @Transactional
    public Notification notify(String eventId, String userId, String role, String kind, String title, String body, String link) {
        if (eventId != null && notifications.findFirstByEventIdAndUserIdAndRole(eventId, userId, role).isPresent()) {
            return null;
        }
        return notifications.save(Notification.builder().userId(userId).role(role).kind(kind).title(title).body(body).link(link).eventId(eventId).build());
    }

    @Transactional
    public void notifyRoles(String eventId, String kind, String title, String body, String link, String... roles) {
        for (String role : roles) {
            notify(eventId, null, role, kind, title, body, link);
        }
    }

    /** Mine: addressed to me, plus everything for any role I hold at this hotel. */
    @Transactional(readOnly = true)
    public List<Notification> mine(boolean unreadOnly) {
        HospioUserDetails user = CurrentUser.require();
        List<Notification> out = new ArrayList<>(unreadOnly ? notifications.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(user.getUserId())
                : notifications.findByUserIdOrderByCreatedAtDesc(user.getUserId()));
        TenantContext.current().map(t -> t.hotelId()).ifPresent(hotelId -> {
            List<String> roles = user.roleSet().stream().map(Enum::name).toList();
            notifications.findByHotelIdAndRoleInOrderByCreatedAtDesc(hotelId, roles).stream()
                    .filter(n -> !unreadOnly || n.getReadAt() == null).forEach(out::add);
        });
        out.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        return out;
    }

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
        Page<Notification> result = notifications.findAll(spec, Paging.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))));
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("all", notifications.count(base));
        counts.put("unread", notifications.count(base.and(unread(true))));
        return PageWithCounts.of(result, n -> n, counts, Map.of());
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

    @Transactional
    public int markAllRead() {
        int count = 0;
        for (Notification n : mine(true)) {
            n.setReadAt(Instant.now());
            notifications.save(n);
            count++;
        }
        return count;
    }
}
