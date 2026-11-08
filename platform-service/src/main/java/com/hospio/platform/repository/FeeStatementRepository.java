package com.hospio.platform.repository;

import com.hospio.platform.entity.FeeStatement;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeeStatementRepository extends JpaRepository<FeeStatement, String> {
    List<FeeStatement> findByBusinessIdOrderByMonthDesc(String businessId);

    List<FeeStatement> findByStatusOrderByMonthDesc(String status);

    Optional<FeeStatement> findByBusinessIdAndMonth(String businessId, String month);
}
