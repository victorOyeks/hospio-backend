package com.hospio.platform.repository;

import com.hospio.platform.entity.BillingStartChange;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingStartChangeRepository extends JpaRepository<BillingStartChange, String> {

    List<BillingStartChange> findByBusinessIdOrderByCreatedAtDesc(String businessId);
}
