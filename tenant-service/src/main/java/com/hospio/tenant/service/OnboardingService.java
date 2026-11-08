package com.hospio.tenant.service;

import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.HandoverSentEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.tenant.client.IdentityClient;
import com.hospio.tenant.dto.HandoverResponse;
import com.hospio.tenant.dto.OnboardingResponse;
import com.hospio.tenant.dto.OnboardingStepRequest;
import com.hospio.tenant.dto.SetupTemplateRequest;
import com.hospio.tenant.entity.Business;
import com.hospio.tenant.entity.Handover;
import com.hospio.tenant.entity.OnboardingDraft;
import com.hospio.tenant.entity.SetupTemplate;
import com.hospio.tenant.repository.HandoverRepository;
import com.hospio.tenant.repository.OnboardingDraftRepository;
import com.hospio.tenant.repository.SetupTemplateRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform admin Phase 2: the 10-step onboarding wizard (J1-J14), the handover pack (J15-J16) and the
 * owner's acceptance (K3). Setup templates (I3-I4) live here too because onboarding applies them.
 */
@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final OnboardingDraftRepository drafts;
    private final HandoverRepository handovers;
    private final SetupTemplateRepository templates;
    private final BusinessService businessService;
    private final IdentityClient identityClient;
    private final EventPublisher events;

    @Transactional(readOnly = true)
    public List<OnboardingResponse> inProgress() {
        requirePlatform();
        return drafts.findByStatusOrderByUpdatedAtDesc(OnboardingDraft.Status.IN_PROGRESS).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public OnboardingResponse forBusiness(String businessId) {
        requirePlatform();
        return toResponse(drafts.findByBusinessId(businessId).orElseThrow(() -> new ResourceNotFoundException("No onboarding for this business")));
    }

    @Transactional
    public OnboardingResponse saveStep(String businessId, OnboardingStepRequest request) {
        requirePlatform();
        Business business = businessService.find(businessId);
        OnboardingDraft draft = drafts.findByBusinessId(businessId).orElseGet(() -> OnboardingDraft.builder()
                .businessId(businessId).assignedTo(CurrentUser.require().getUserId()).build());
        draft.setStep(request.step());
        draft.setData(request.data());
        if (request.step() >= 10) {
            draft.setStatus(OnboardingDraft.Status.READY_FOR_HANDOVER);
        }
        drafts.save(draft);
        if (business.getStage() == Business.Stage.LEAD) {
            business.setStage(Business.Stage.ONBOARDING);
        }
        return toResponse(draft);
    }

    @Transactional
    public OnboardingResponse reassign(String businessId, String adminId) {
        requirePlatform();
        OnboardingDraft draft = drafts.findByBusinessId(businessId).orElseThrow(() -> new ResourceNotFoundException("No onboarding for this business"));
        draft.setAssignedTo(adminId);
        return toResponse(drafts.save(draft));
    }

    /** J16 · Send handover: create the owner's login (temporary password, shown once) and mark the business handed over. */
    @Transactional
    public HandoverResponse sendHandover(String businessId) {
        requirePlatform();
        Business business = businessService.find(businessId);
        OnboardingDraft draft = drafts.findByBusinessId(businessId).orElseThrow(() -> new BadRequestException("Finish onboarding first"));
        if (draft.getStatus() == OnboardingDraft.Status.IN_PROGRESS) {
            throw new BadRequestException("Finish the go-live checklist before sending the handover");
        }
        IdentityClient.CreatedLogin login = identityClient.createUser(business.getOwnerName(), business.getOwnerEmail(),
                business.getOwnerPhone(), "OWNER", null, businessId);
        businessService.setOwner(businessId, login.userId());
        Handover handover = handovers.findByBusinessId(businessId).orElseGet(() -> Handover.builder().businessId(businessId).build());
        handover.setSentAt(Instant.now());
        handover.setSentBy(CurrentUser.require().getUserId());
        handovers.save(handover);
        draft.setStatus(OnboardingDraft.Status.DONE);
        drafts.save(draft);
        business.setStage(Business.Stage.HANDED_OVER);
        events.publish(KafkaTopics.HANDOVER_SENT, HandoverSentEvent.builder()
                .businessId(businessId).ownerUserId(login.userId()).ownerEmail(business.getOwnerEmail()).build());
        return new HandoverResponse(handover.getId(), businessId, handover.getPackKey(), handover.getSentAt(), handover.getSentBy(),
                handover.getAcceptedAt(), new HandoverResponse.OwnerLogin(login.userId(), business.getOwnerEmail(), login.temporaryPassword(), login.expiresAt()));
    }

    /** K3 · The owner reviews and accepts the handover; the business goes live. */
    @Transactional
    public HandoverResponse accept() {
        HospioUserDetails owner = CurrentUser.require();
        String businessId = owner.getBusinessId();
        if (businessId == null || !owner.has(com.hospio.common.enums.Role.OWNER)) {
            throw new ForbiddenException("Only the owner can accept the handover");
        }
        Handover handover = handovers.findByBusinessId(businessId).orElseThrow(() -> new ResourceNotFoundException("No handover to accept"));
        handover.setAcceptedAt(Instant.now());
        handovers.save(handover);
        Business business = businessService.find(businessId);
        business.setStage(Business.Stage.LIVE);
        return new HandoverResponse(handover.getId(), businessId, handover.getPackKey(), handover.getSentAt(), handover.getSentBy(),
                handover.getAcceptedAt(), null);
    }

    @Transactional(readOnly = true)
    public List<SetupTemplate> templates() {
        return templates.findByActiveTrueOrderByName();
    }

    @Transactional
    public SetupTemplate saveTemplate(String id, SetupTemplateRequest request) {
        requirePlatform();
        SetupTemplate t = id == null ? new SetupTemplate() : templates.findById(id).orElseThrow(() -> new ResourceNotFoundException("Template not found"));
        t.setName(request.name());
        t.setDescription(request.description());
        t.setSettings(request.settings());
        if (request.active() != null) {
            t.setActive(request.active());
        }
        return templates.save(t);
    }

    private OnboardingResponse toResponse(OnboardingDraft d) {
        return new OnboardingResponse(d.getId(), d.getBusinessId(), businessService.find(d.getBusinessId()).getName(), d.getStep(),
                d.getData(), d.getAssignedTo(), d.getStatus(), d.getUpdatedAt());
    }

    private static void requirePlatform() {
        if (!CurrentUser.require().isPlatform()) {
            throw new ForbiddenException("Only H.M.S staff can do this");
        }
    }
}
