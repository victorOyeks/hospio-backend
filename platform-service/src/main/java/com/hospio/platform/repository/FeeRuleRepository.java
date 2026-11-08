package com.hospio.platform.repository;

import com.hospio.platform.entity.FeeRule;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeeRuleRepository extends JpaRepository<FeeRule, String> {
    List<FeeRule> findByBusinessIdOrderByStartsOnDesc(String businessId);
}
