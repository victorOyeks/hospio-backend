package com.hospio.platform.repository;

import com.hospio.platform.entity.MarketerProfile;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MarketerProfileRepository extends JpaRepository<MarketerProfile, String>, JpaSpecificationExecutor<MarketerProfile> {
    Optional<MarketerProfile> findByUserId(String userId);

    Optional<MarketerProfile> findByEmailIgnoreCase(String email);

    Optional<MarketerProfile> findByReferralCode(String referralCode);

    List<MarketerProfile> findAllByOrderByFullNameAsc();
}
