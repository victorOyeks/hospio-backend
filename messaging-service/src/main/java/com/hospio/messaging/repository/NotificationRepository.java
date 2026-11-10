package com.hospio.messaging.repository;

import com.hospio.messaging.entity.Notification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, String>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<Notification> {
    List<Notification> findByUserIdOrderByCreatedAtDesc(String userId);

    List<Notification> findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(String userId);

    List<Notification> findByHotelIdAndRoleInOrderByCreatedAtDesc(String hotelId, java.util.Collection<String> roles);

    Optional<Notification> findFirstByEventIdAndUserIdAndRole(String eventId, String userId, String role);
}
