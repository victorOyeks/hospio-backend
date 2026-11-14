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

    /** Clock-ins still open that started before {@code before} (the forgotten clock-out check; every hotel). */
    List<ClockEntry> findByClockOutIsNullAndClockInBefore(java.time.Instant before);

    List<ClockEntry> findByUserIdAndClockInBetween(String userId, java.time.Instant from, java.time.Instant to);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update ClockEntry c set c.remindedAt = :at where c.id = :id and c.remindedAt is null and c.clockOut is null")
    int markReminded(@org.springframework.data.repository.query.Param("id") String id, @org.springframework.data.repository.query.Param("at") java.time.Instant at);

    /** Closes a forgotten clock-in (only if still open). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update ClockEntry c set c.clockOut = :out, c.note = :note, c.updatedAt = :at where c.id = :id and c.clockOut is null")
    int autoClose(@org.springframework.data.repository.query.Param("id") String id, @org.springframework.data.repository.query.Param("out") java.time.Instant out,
                  @org.springframework.data.repository.query.Param("note") String note, @org.springframework.data.repository.query.Param("at") java.time.Instant at);
}
