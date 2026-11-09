package com.hospio.platform.repository;

import com.hospio.platform.entity.Commission;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CommissionRepository extends JpaRepository<Commission, String>, JpaSpecificationExecutor<Commission> {
    List<Commission> findByMarketerIdOrderByMonthDesc(String marketerId);

    List<Commission> findByStatusOrderByMonthDesc(String status);

    List<Commission> findByProfileIdOrderByMonthDesc(String profileId);

    List<Commission> findByProfileIdAndMonthOrderByCreatedAtAsc(String profileId, String month);

    Optional<Commission> findByMarketerIdAndMonth(String marketerId, String month);
}
