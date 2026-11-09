package com.hospio.platform.repository;

import com.hospio.platform.entity.HealthScore;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HealthScoreRepository extends JpaRepository<HealthScore, String> {
    Optional<HealthScore> findByBusinessId(String businessId);

    List<HealthScore> findAllByOrderByScoreAsc();
}
