package com.hospio.platform.repository;

import com.hospio.platform.entity.Report;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportRepository extends JpaRepository<Report, String> {
    List<Report> findByBusinessIdOrderByCreatedAtDesc(String businessId);

    List<Report> findByBusinessIdAndStatusAndKindAndRequestedBy(String businessId, String status, String kind, String requestedBy);

    List<Report> findByStatusAndNextRunAtLessThanEqual(String status, java.time.Instant now);
}
