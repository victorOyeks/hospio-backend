package com.hospio.maintenance.repository;

import com.hospio.maintenance.entity.ScheduledJob;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledJobRepository extends JpaRepository<ScheduledJob, String> {
    List<ScheduledJob> findByHotelIdAndDeletedFalseOrderByNextDue(String hotelId);

    List<ScheduledJob> findByDeletedFalseAndPausedFalseAndNextDueLessThanEqual(LocalDate date);

    List<ScheduledJob> findByHotelIdAndDeletedFalseAndPausedFalseAndNextDueLessThanEqual(String hotelId, LocalDate date);
}
