package com.hospio.platform.repository;

import com.hospio.platform.entity.Referral;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ReferralRepository extends JpaRepository<Referral, String>, JpaSpecificationExecutor<Referral> {
    List<Referral> findByProfileIdOrderBySentAtDesc(String profileId);

    Optional<Referral> findByBusinessId(String businessId);

    long countByProfileId(String profileId);
}
