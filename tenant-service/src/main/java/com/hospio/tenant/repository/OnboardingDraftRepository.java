package com.hospio.tenant.repository;

import com.hospio.tenant.entity.OnboardingDraft;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingDraftRepository extends JpaRepository<OnboardingDraft, String> {
    org.springframework.data.domain.Page<OnboardingDraft> findByStatus(OnboardingDraft.Status status, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<OnboardingDraft> findAllBy(org.springframework.data.domain.Pageable pageable);

    long countByStatus(OnboardingDraft.Status status);

    Optional<OnboardingDraft> findByBusinessId(String businessId);

    List<OnboardingDraft> findByStatusOrderByUpdatedAtDesc(OnboardingDraft.Status status);

    List<OnboardingDraft> findAllByOrderByUpdatedAtDesc();
}
