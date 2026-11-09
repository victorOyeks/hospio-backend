package com.hospio.finance.repository;

import com.hospio.finance.entity.ClockEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClockEntryRepository extends JpaRepository<ClockEntry, String> {
    Optional<ClockEntry> findFirstByUserIdAndClockOutIsNullOrderByClockInDesc(String userId);

    List<ClockEntry> findByHotelIdAndClockOutIsNull(String hotelId);

    List<ClockEntry> findByHotelIdAndClockInBetweenOrderByClockIn(String hotelId, java.time.Instant from, java.time.Instant to);

    List<ClockEntry> findByUserIdAndClockInGreaterThanEqualOrderByClockIn(String userId, java.time.Instant since);
}
