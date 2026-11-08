package com.hospio.tenant.repository;

import com.hospio.tenant.entity.OnboardingDraft;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingDraftRepository extends JpaRepository<OnboardingDraft, String> {
    Optional<OnboardingDraft> findByBusinessId(String businessId);

    List<OnboardingDraft> findByStatusOrderByUpdatedAtDesc(OnboardingDraft.Status status);

    List<OnboardingDraft> findAllByOrderByUpdatedAtDesc();
}
