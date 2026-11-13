package com.hospio.messaging.repository;

import com.hospio.messaging.entity.Notification;
import java.time.Instant;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, String>, JpaSpecificationExecutor<Notification> {
    /** One notice per (event, user, role): a second insert of the same is a no-op (uq_notifications_event, messaging-007). 1 if inserted. */
    @Modifying
    @Query(value = "INSERT INTO messaging.notifications (id, business_id, hotel_id, user_id, role, kind, title, body, link, event_id, created_at, updated_at) "
            + "VALUES (:id, :business, :hotel, :user, :role, :kind, :title, :body, :link, :event, :now, :now) "
            + "ON CONFLICT (event_id, (COALESCE(user_id, '')), (COALESCE(role, ''))) WHERE event_id IS NOT NULL DO NOTHING", nativeQuery = true)
    int insertOnce(@Param("id") String id, @Param("business") String businessId, @Param("hotel") String hotelId, @Param("user") String userId,
                   @Param("role") String role, @Param("kind") String kind, @Param("title") String title, @Param("body") String body,
                   @Param("link") String link, @Param("event") String eventId, @Param("now") Instant now);

    /** Mine read: addressed to me, or to a role I hold at this hotel. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Notification n set n.readAt = :now, n.updatedAt = :now where n.readAt is null "
            + "and (n.userId = :user or (n.hotelId = :hotel and n.role in :roles))")
    int markAllRead(@Param("user") String userId, @Param("hotel") String hotelId, @Param("roles") Collection<String> roles, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Notification n set n.readAt = :now, n.updatedAt = :now where n.readAt is null and n.userId = :user")
    int markAllReadForUser(@Param("user") String userId, @Param("now") Instant now);
}
