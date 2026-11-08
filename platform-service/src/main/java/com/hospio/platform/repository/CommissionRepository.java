package com.hospio.platform.repository;

import com.hospio.platform.entity.Commission;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommissionRepository extends JpaRepository<Commission, String> {
    List<Commission> findByMarketerIdOrderByMonthDesc(String marketerId);

    List<Commission> findByStatusOrderByMonthDesc(String status);
}
