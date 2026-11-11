package com.hospio.tenant.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.HandoverAcceptedEvent;
import com.hospio.common.kafka.events.HandoverSentEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.tenant.client.IdentityClient;
import com.hospio.tenant.dto.BusinessResponse;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two wizards and the handover. Back office: 10 steps (J1-J14), handover pack (J15-J16), the
 * owner's acceptance (K3). Self-serve: the owner's 4 steps and "All set". Setup templates (I3-I4)
 * live here too because onboarding applies them.
 */
@Service
@RequiredArgsConstructor
public class OnboardingService {

    public static final int BACK_OFFICE_STEPS = 10;
    public static final int SELF_SERVE_STEPS = 4;

    private final OnboardingDraftRepository drafts;
    private final HandoverRepository handovers;
    private final SetupTemplateRepository templates;
    private final BusinessService businessService;
    private final DraftData draftData;
    private final IdentityClient identityClient;
    private final EventPublisher events;
    private final HandoverPack handoverPack;
    private final DeliveryService deliveryService;

    // ---- platform: the board and the 10-step wizard -------------------------------------------------

    @Transactional(readOnly = true)
    public List<OnboardingResponse> list(OnboardingDraft.Status status) {
        requirePlatform();
        return listInternal(status);
    }

    /** One page of the board (latest saved first) with counts per status (and {@code all}) for the tabs and column headers. */
    @Transactional(readOnly = true)
    public com.hospio.common.response.PageWithCounts<OnboardingResponse> page(OnboardingDraft.Status status, Integer page, Integer size) {
        requirePlatform();
        org.springframework.data.domain.Pageable pageable = com.hospio.common.paging.Paging.of(page, size,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "updatedAt").and(
                        org.springframework.data.domain.Sort.by("id")));
        org.springframework.data.domain.Page<OnboardingDraft> result = status == null ? drafts.findAllBy(pageable) : drafts.findByStatus(status, pageable);
        Map<String, String> names = identityClient.names(result.getContent().stream().map(OnboardingDraft::getAssignedTo).filter(a -> a != null).distinct().toList());
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        long all = 0;
        for (OnboardingDraft.Status s : OnboardingDraft.Status.values()) {
            long n = drafts.countByStatus(s);
            counts.put(s.name(), n);
            all += n;
        }
        counts.put("all", all);
        return com.hospio.common.response.PageWithCounts.of(result, d -> toResponse(d, names), counts, Map.of());
    }

    /** The same board for platform-service (stalled-onboarding alerts); the internal key is the guard. */
    @Transactional(readOnly = true)
    public List<OnboardingResponse> listInternal(OnboardingDraft.Status status) {
        List<OnboardingDraft> list = status == null ? drafts.findAllByOrderByUpdatedAtDesc() : drafts.findByStatusOrderByUpdatedAtDesc(status);
        Map<String, String> names = identityClient.names(list.stream().map(OnboardingDraft::getAssignedTo).filter(a -> a != null).toList());
        return list.stream().map(d -> toResponse(d, names)).toList();
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
        if (request.step() == 2 && request.data() != null) {
            checkSecondContact(request.data());
        }
        OnboardingDraft draft = drafts.findByBusinessId(businessId).orElseGet(() -> OnboardingDraft.builder()
                .businessId(businessId).assignedTo(CurrentUser.require().getUserId()).data(draftData.empty()).build());
        save(draft, business, request, BACK_OFFICE_STEPS);
        if (request.step() >= BACK_OFFICE_STEPS && draft.getStatus() == OnboardingDraft.Status.IN_PROGRESS) {
            draft.setStatus(OnboardingDraft.Status.READY_FOR_HANDOVER);
        }
        drafts.save(draft);
        if (business.getStage() == Business.Stage.LEAD) {
            businessService.moveStage(business, Business.Stage.ONBOARDING, null);
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

    /**
     * J16 · Send handover: the owner's login (temporary password and invite link, shown once), the
     * handover record, stage HANDED_OVER, and the handover pack email. Sent again, it re-issues the
     * existing owner's login. {@code deliveries} = identity's (temporary password, invite) + the pack.
     */
    @Transactional
    public HandoverResponse sendHandover(String businessId) {
        requirePlatform();
        Business business = businessService.find(businessId);
        OnboardingDraft draft = drafts.findByBusinessId(businessId).orElseThrow(() -> new BadRequestException("Finish onboarding first"));
        if (draft.getStatus() == OnboardingDraft.Status.IN_PROGRESS) {
            throw new BadRequestException("Finish the go-live checklist before sending the handover");
        }
        // Once the owner has accepted (the business is live), sending again would reset the password they chose.
        boolean accepted = handovers.findByBusinessId(businessId).map(h -> h.getAcceptedAt() != null).orElse(false);
        if (accepted || business.getStage() != Business.Stage.ONBOARDING && business.getStage() != Business.Stage.HANDED_OVER) {
            throw new ConflictException("The owner has already accepted the handover; reset their password from People instead");
        }
        IdentityClient.CreatedLogin login = business.getOwnerUserId() == null
                ? identityClient.createUser(business.getOwnerName(), business.getOwnerEmail(), business.getOwnerPhone(), "OWNER", null, businessId)
                : identityClient.resendInvite(business.getOwnerUserId());
        if (business.getOwnerUserId() == null) {
            businessService.setOwner(businessId, login.userId());
            ObjectNode root = draftData.parse(draft.getData());
            ObjectNode owner = draftData.object();
            owner.put("userId", login.userId());
            owner.put("fullName", business.getOwnerName());
            owner.put("email", business.getOwnerEmail());
            owner.put("role", "OWNER");
            owner.putNull("hotelId");
            draftData.addLogin(root, owner);
            draft.setData(draftData.write(root));
        }
        Handover handover = handovers.findByBusinessId(businessId).orElseGet(() -> Handover.builder().businessId(businessId).build());
        handover.setSentAt(Instant.now());
        handover.setSentBy(CurrentUser.require().getUserId());
        handovers.save(handover);
        draft.setStatus(OnboardingDraft.Status.DONE);
        drafts.save(draft);
        businessService.moveStage(business, Business.Stage.HANDED_OVER, null);
        events.publish(KafkaTopics.HANDOVER_SENT, HandoverSentEvent.builder()
                .businessId(businessId).ownerUserId(login.userId()).ownerEmail(business.getOwnerEmail()).build());
        List<com.hospio.tenant.dto.HotelResponse> hotels = businessService.hotelsOf(businessId);
        List<com.fasterxml.jackson.databind.JsonNode> logins = draftData.logins(draftData.parse(draft.getData()));
        List<Delivery> deliveries = new ArrayList<>(login.deliveries() == null ? List.of() : login.deliveries());
        deliveries.addAll(deliveryService.send(handoverPack.email(businessId, business.getName(), business.getOwnerName(),
                business.getOwnerEmail(), hotels, logins)));
        return new HandoverResponse(handover.getId(), businessId, handover.getPackKey(), handover.getSentAt(), handover.getSentBy(),
                handover.getAcceptedAt(),
                new HandoverResponse.OwnerLogin(login.userId(), business.getOwnerEmail(), login.temporaryPassword(), login.expiresAt(),
                        login.inviteToken(), login.inviteExpiresAt()),
                hotels, logins, deliveries);
    }

    /** K3 · The owner reviews and accepts the handover; the business goes live. */
    @Transactional
    public BusinessResponse accept() {
        HospioUserDetails owner = CurrentUser.require();
        String businessId = owner.getBusinessId();
        if (businessId == null || !owner.has(Role.OWNER)) {
            throw new ForbiddenException("Only the owner can accept the handover");
        }
        Handover handover = handovers.findByBusinessId(businessId).orElseThrow(() -> new ResourceNotFoundException("No handover to accept"));
        if (handover.getAcceptedAt() == null) {
            handover.setAcceptedAt(Instant.now());
            handovers.save(handover);
        }
        Business business = businessService.find(businessId);
        drafts.findByBusinessId(businessId).ifPresent(d -> {
            d.setStatus(OnboardingDraft.Status.DONE);
            drafts.save(d);
        });
        businessService.moveStage(business, Business.Stage.LIVE, null);
        events.publish(KafkaTopics.HANDOVER_ACCEPTED, HandoverAcceptedEvent.builder()
                .businessId(businessId).ownerUserId(owner.getUserId()).businessName(business.getName()).build());
        return businessService.toResponse(business);
    }

    // ---- the owner's self-serve wizard ----------------------------------------------------------------

    @Transactional
    public OnboardingResponse mine() {
        Business business = ownersBusiness();
        return toResponse(draftFor(business));
    }

    @Transactional
    public OnboardingResponse saveMyStep(OnboardingStepRequest request) {
        if (request.step() > SELF_SERVE_STEPS) {
            throw new BadRequestException("The sign-up wizard has " + SELF_SERVE_STEPS + " steps");
        }
        Business business = ownersBusiness();
        OnboardingDraft draft = draftFor(business);
        save(draft, business, request, SELF_SERVE_STEPS);
        return toResponse(drafts.save(draft));
    }

    /** "All set": the business goes live and the wizard is done. */
    @Transactional
    public BusinessResponse completeMine() {
        Business business = ownersBusiness();
        OnboardingDraft draft = draftFor(business);
        draft.setStatus(OnboardingDraft.Status.DONE);
        draft.setStep(SELF_SERVE_STEPS);
        drafts.save(draft);
        if (business.getStage() == Business.Stage.LEAD || business.getStage() == Business.Stage.ONBOARDING
                || business.getStage() == Business.Stage.HANDED_OVER) {
            businessService.moveStage(business, Business.Stage.LIVE, null);
        }
        return businessService.toResponse(business);
    }

    // ---- templates -----------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SetupTemplate> templates() {
        return templates.findByActiveTrueOrderByName();
    }

    @Transactional(readOnly = true)
    public SetupTemplate template(String id) {
        return templates.findById(id).orElseThrow(() -> new ResourceNotFoundException("Template not found"));
    }

    @Transactional
    public SetupTemplate saveTemplate(String id, SetupTemplateRequest request) {
        requirePlatform();
        SetupTemplate t = id == null ? new SetupTemplate() : template(id);
        t.setName(request.name());
        t.setDescription(request.description());
        t.setSettings(request.settings());
        if (request.active() != null) {
            t.setActive(request.active());
        }
        return templates.save(t);
    }

    // ---- shared --------------------------------------------------------------------------------------

    private void save(OnboardingDraft draft, Business business, OnboardingStepRequest request, int totalSteps) {
        ObjectNode root = draftData.parse(draft.getData());
        draftData.saveStep(root, request.step(), request.data(), request.nextAction());
        draft.setData(draftData.write(root));
        draft.setStep(Math.max(draft.getStep(), Math.min(request.step() + 1, totalSteps)));
    }

    /** Step 2's optional second contact: blank is fine, anything typed must be a real name, email or phone. */
    static void checkSecondContact(com.fasterxml.jackson.databind.JsonNode data) {
        Fields.name(text(data, "contactName"), "the second contact's name", false);
        Fields.email(text(data, "contactEmail"), "the second contact's email", false);
        Fields.phone(text(data, "contactPhone"), "the second contact's phone", false);
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode data, String field) {
        com.fasterxml.jackson.databind.JsonNode v = data.path(field);
        return v.isTextual() ? v.asText() : null;
    }

    private Business ownersBusiness() {
        HospioUserDetails caller = CurrentUser.require();
        if (caller.isPlatform() || caller.getBusinessId() == null || !caller.has(Role.OWNER)) {
            throw new ForbiddenException("Only the owner can do this");
        }
        return businessService.find(caller.getBusinessId());
    }

    private OnboardingDraft draftFor(Business business) {
        return drafts.findByBusinessId(business.getId()).orElseGet(() -> drafts.save(OnboardingDraft.builder()
                .businessId(business.getId()).step(1).assignedTo(business.getOwnerUserId()).data(draftData.empty()).build()));
    }

    private static int totalSteps(Business business) {
        return business.getSource() == Business.Source.SELF_SERVE ? SELF_SERVE_STEPS : BACK_OFFICE_STEPS;
    }

    private OnboardingResponse toResponse(OnboardingDraft d) {
        return toResponse(d, d.getAssignedTo() == null ? Map.of() : identityClient.names(List.of(d.getAssignedTo())));
    }

    private OnboardingResponse toResponse(OnboardingDraft d, Map<String, String> assigneeNames) {
        Business business = businessService.find(d.getBusinessId());
        ObjectNode root = draftData.parse(d.getData());
        return new OnboardingResponse(d.getId(), d.getBusinessId(), business.getName(), business.getSource(), d.getStep(),
                totalSteps(business), draftData.completedSteps(root), d.getAssignedTo(), assigneeNames.get(d.getAssignedTo()), d.getStatus(),
                draftData.nextAction(root), d.getCreatedAt(), d.getUpdatedAt(), root);
    }

    private static void requirePlatform() {
        if (!CurrentUser.require().isPlatform()) {
            throw new ForbiddenException("Only Hospio360 staff can do this");
        }
    }
}
