package com.hospio.messaging.repository;

import com.hospio.messaging.entity.Template;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateRepository extends JpaRepository<Template, String> {
    List<Template> findByHotelIdOrderByKey(String hotelId);

    Optional<Template> findByHotelIdAndKeyAndChannelAndActiveTrue(String hotelId, String key, com.hospio.common.enums.NotificationChannel channel);
}
