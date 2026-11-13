package com.hospio.platform.repository;

import com.hospio.platform.entity.HealthScore;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HealthScoreRepository extends JpaRepository<HealthScore, String> {
    Optional<HealthScore> findByBusinessId(String businessId);

    List<HealthScore> findAllByOrderByScoreAsc();

    /** One row: how many scores are good (≥ good), watch (≥ watch) and at risk, counted in the database. */
    @org.springframework.data.jpa.repository.Query("select sum(case when h.score >= :good then 1 else 0 end), "
            + "sum(case when h.score >= :watch and h.score < :good then 1 else 0 end), sum(case when h.score < :watch then 1 else 0 end) from HealthScore h")
    List<Object[]> buckets(@org.springframework.data.repository.query.Param("good") int good, @org.springframework.data.repository.query.Param("watch") int watch);
}
