package com.hospio.finance.repository;

import com.hospio.finance.entity.Till;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TillRepository extends JpaRepository<Till, String> {
    Optional<Till> findFirstByHotelIdAndRoleAndStatus(String hotelId, String role, Till.Status status);

    List<Till> findByHotelIdAndStatusOrderByOpenedAt(String hotelId, Till.Status status);

    List<Till> findByHotelIdAndOpenedAtAfterOrderByOpenedAtDesc(String hotelId, java.time.Instant since);
}
