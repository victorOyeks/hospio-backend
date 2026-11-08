package com.hospio.housekeeping.repository;

import com.hospio.housekeeping.entity.ProblemReport;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemReportRepository extends JpaRepository<ProblemReport, String> {
    List<ProblemReport> findByHotelIdOrderByCreatedAtDesc(String hotelId);
}
