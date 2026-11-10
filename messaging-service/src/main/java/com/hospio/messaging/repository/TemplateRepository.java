package com.hospio.messaging.repository;

import com.hospio.messaging.entity.Template;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateRepository extends JpaRepository<Template, String> {
    List<Template> findByHotelIdOrderByKey(String hotelId);

    List<Template> findByHotelIdAndPurposeOrderByNameAsc(String hotelId, String purpose);

    boolean existsByHotelIdAndPurposeAndNameIgnoreCaseAndIdNot(String hotelId, String purpose, String name, String id);

    Optional<Template> findByHotelIdAndKeyAndChannelAndActiveTrue(String hotelId, String key, com.hospio.common.enums.NotificationChannel channel);
}
