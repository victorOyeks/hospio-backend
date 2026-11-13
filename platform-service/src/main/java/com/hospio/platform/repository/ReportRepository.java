package com.hospio.platform.repository;

import com.hospio.platform.entity.Report;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, String> {
    List<Report> findByBusinessIdOrderByCreatedAtDesc(String businessId);

    List<Report> findByBusinessIdAndStatusAndKindAndRequestedBy(String businessId, String status, String kind, String requestedBy);

    List<Report> findByStatusAndNextRunAtLessThanEqual(String status, java.time.Instant now);

    /**
     * Claims a due schedule for one run: moves next_run_at on only if it still holds the time this instance read. With several instances
     * running the job, exactly one gets 1 back and sends the report; the others get 0 and skip it.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("update Report r set r.nextRunAt = :next, r.lastRunAt = :now, r.updatedAt = :now "
            + "where r.id = :id and r.status = 'SCHEDULED' and r.nextRunAt = :old")
    int claim(@org.springframework.data.repository.query.Param("id") String id, @org.springframework.data.repository.query.Param("old") java.time.Instant old,
              @org.springframework.data.repository.query.Param("next") java.time.Instant next, @org.springframework.data.repository.query.Param("now") java.time.Instant now);
}
