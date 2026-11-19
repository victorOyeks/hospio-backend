package com.hospio.platform.repository;

import com.hospio.platform.entity.BillingStart;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillingStartRepository extends JpaRepository<BillingStart, String> {

    Optional<BillingStart> findByBusinessId(String businessId);

    List<BillingStart> findByChargesStartOn(LocalDate day);
}
