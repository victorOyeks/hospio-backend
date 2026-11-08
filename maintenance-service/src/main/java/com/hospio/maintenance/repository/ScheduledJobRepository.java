package com.hospio.maintenance.repository;

import com.hospio.maintenance.entity.ScheduledJob;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledJobRepository extends JpaRepository<ScheduledJob, String> {
    List<ScheduledJob> findByHotelIdAndDeletedFalseOrderByNextDue(String hotelId);

    List<ScheduledJob> findByDeletedFalseAndPausedFalseAndNextDueLessThanEqual(java.time.LocalDate date);
}
