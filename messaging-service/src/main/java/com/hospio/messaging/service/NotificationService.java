package com.hospio.messaging.service;

import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.messaging.entity.Notification;
import com.hospio.messaging.repository.NotificationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
