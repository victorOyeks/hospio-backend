package com.hospio.finance.repository;

import com.hospio.finance.entity.ClockEntry;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClockEntryRepository extends JpaRepository<ClockEntry, String> {
    Optional<ClockEntry> findFirstByUserIdAndClockOutIsNullOrderByClockInDesc(String userId);
}
