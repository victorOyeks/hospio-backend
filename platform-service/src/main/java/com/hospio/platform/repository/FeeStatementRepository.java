package com.hospio.platform.repository;

import com.hospio.platform.entity.FeeStatement;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface FeeStatementRepository extends JpaRepository<FeeStatement, String>, JpaSpecificationExecutor<FeeStatement> {
    List<FeeStatement> findByBusinessIdOrderByMonthDesc(String businessId);

    List<FeeStatement> findByStatusOrderByMonthDesc(String status);

    List<FeeStatement> findByStatusIn(Collection<String> statuses);

    List<FeeStatement> findByMonth(String month);

    List<FeeStatement> findByBusinessIdAndStatusIn(String businessId, Collection<String> statuses);

    Optional<FeeStatement> findByBusinessIdAndMonth(String businessId, String month);
}
