package com.hospio.platform.repository;

import com.hospio.platform.entity.Alert;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertRepository extends JpaRepository<Alert, String> {
    List<Alert> findByDismissedAtIsNullOrderByOpenedAtDesc();

    /** The newest open alerts, as many as the page asks (the dashboard's ten), without reading every open one. */
    List<Alert> findByDismissedAtIsNullOrderByOpenedAtDesc(org.springframework.data.domain.Pageable page);

    List<Alert> findByBusinessIdOrderByOpenedAtDesc(String businessId);

    List<Alert> findByBusinessIdAndDismissedAtIsNullOrderByOpenedAtDesc(String businessId);

    List<Alert> findAllByOrderByOpenedAtDesc();

    Optional<Alert> findFirstByKindAndBusinessIdAndDismissedAtIsNull(String kind, String businessId);

    Optional<Alert> findFirstByKindAndHotelIdAndDismissedAtIsNull(String kind, String hotelId);

    List<Alert> findByKindAndDismissedAtIsNull(String kind);

    long countByDismissedAtIsNull();
}
