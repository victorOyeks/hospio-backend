package com.hospio.tenant.service;

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
import com.hospio.tenant.dto.OnboardingDraftData;
import com.hospio.tenant.dto.OnboardingLogin;
import com.hospio.tenant.dto.OnboardingResponse;
import com.hospio.tenant.dto.OnboardingStepData;
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
            if (request.isDraft()) {
                checkSecondContactFormat(request.data());
            } else {
                checkSecondContact(request.data());
            }
        }
        checkEmails(request.data());
        if (!request.isDraft()) {
            requireStep(business, request.step());
        }
        OnboardingDraft draft = drafts.findByBusinessId(businessId).orElseGet(() -> OnboardingDraft.builder()
                .businessId(businessId).assignedTo(CurrentUser.require().getUserId()).data(draftData.empty()).build());
        save(draft, business, request, BACK_OFFICE_STEPS);
        if (!request.isDraft() && request.step() >= BACK_OFFICE_STEPS && draft.getStatus() == OnboardingDraft.Status.IN_PROGRESS) {
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
        // The owner's login is made here: the details steps 1, 2 and 4 require must be in place (a business saved before
        // the rules, or changed since, is told what is missing; nothing is changed for it).
        try {
            requireStep(business, 1);
            requireStep(business, 2);
            requireStep(business, 4);
        } catch (BadRequestException e) {
            throw new BadRequestException("Before the handover: " + e.getMessage());
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
            OnboardingDraftData data = draftData.addLogin(draftData.parse(draft.getData()),
                    OnboardingLogin.owner(login.userId(), business.getOwnerName(), business.getOwnerEmail()));
            draft.setData(draftData.write(data));
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
        List<OnboardingLogin> logins = draftData.logins(draftData.parse(draft.getData()));
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
        boolean first = handover.getAcceptedAt() == null;
        if (first) {
            handover.setAcceptedAt(Instant.now());
            handovers.save(handover);
        }
        Business business = businessService.find(businessId);
        drafts.findByBusinessId(businessId).ifPresent(d -> {
            d.setStatus(OnboardingDraft.Status.DONE);
            drafts.save(d);
        });
        businessService.moveStage(business, Business.Stage.LIVE, null);
        if (first) {
            // Told once (docs/onboarding.md, K3): messaging-service puts it in the account manager's and every super admin's
            // bell; the owner gets a short welcome email from Hospio360 (email only, never SMS or WhatsApp).
            events.publish(KafkaTopics.HANDOVER_ACCEPTED, HandoverAcceptedEvent.builder()
                    .businessId(businessId).ownerUserId(owner.getUserId()).businessName(business.getName())
                    .accountManagerId(business.getAccountManagerId()).ownerName(business.getOwnerName()).build());
            String to = org.springframework.util.StringUtils.hasText(business.getOwnerEmail()) ? business.getOwnerEmail() : owner.getEmail();
            if (org.springframework.util.StringUtils.hasText(to)) {
                Delivery welcome = Delivery.email(to.trim(), "Welcome to Hospio360 · " + business.getName() + " is live",
                        welcomeText(business.getOwnerName(), business.getName(), handoverPack.signInUrl(),
                                businessService.hotelsOf(businessId).stream().anyMatch(com.hospio.tenant.dto.HotelResponse::hasAccommodation)),
                        Delivery.Kind.WELCOME, businessId);
                // From Hospio360 itself (no business in the context: the From name is Hospio360's, not the hotel's).
                com.hospio.common.tenancy.TenantContext.runWith(com.hospio.common.tenancy.Tenant.system("handover-accepted"),
                        () -> deliveryService.send(welcome));
            }
        }
        return businessService.toResponse(business);
    }

    /** The owner's welcome after accepting the handover: what's next, in a few lines. */
    static String welcomeText(String ownerName, String businessName, String signInUrl) {
        return welcomeText(ownerName, businessName, signInUrl, true);
    }

    /** {@code rooms}: whether any location has accommodation; a lounge or restaurant owner is asked to check outlets and menus instead. */
    static String welcomeText(String ownerName, String businessName, String signInUrl, boolean rooms) {
        return "Hello " + (org.springframework.util.StringUtils.hasText(ownerName) ? ownerName.trim().split("\\s+")[0] : "there") + ",\n\n"
                + "Thank you for accepting the handover. " + businessName + " is now live on Hospio360.\n\n"
                + "What to do next:\n"
                + (rooms ? "- Sign in and check each hotel's rooms, prices and staff.\n" : "- Sign in and check each location's outlets, menus and staff.\n")
                + "- Ask your managers to sign in and change their temporary passwords.\n"
                + (rooms ? "- Take your first booking or sale; your account manager will check in with you this week.\n\n"
                        : "- Take your first sale; your account manager will check in with you this week.\n\n")
                + "Sign in: " + signInUrl + "\n\n"
                + "Questions? Reply to this email or open Help in the app.";
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
        checkEmails(request.data());
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
        OnboardingDraftData data = draftData.saveStep(draftData.parse(draft.getData()), request.step(), request.data(), request.nextAction(),
                !request.isDraft());
        draft.setData(draftData.write(data));
        if (!request.isDraft()) {
            draft.setStep(Math.max(draft.getStep(), Math.min(request.step() + 1, totalSteps)));
        }
    }

    /**
     * What a back-office step needs before it completes (docs/onboarding.md, "Back-office required fields"): step 1 the
     * business details, step 2 the owner's phone, step 4 an address on every location. Read from the saved business and
     * hotels (the wizard writes them first), so a draft save never meets these.
     */
    void requireStep(Business business, int step) {
        switch (step) {
            case 1 -> Fields.requireBusinessDetails(business);
            case 2 -> Fields.requireOwnerPhone(business);
            case 4 -> {
                List<String> missing = businessService.hotelsOf(business.getId()).stream()
                        .filter(h -> !org.springframework.util.StringUtils.hasText(h.address()))
                        .map(com.hospio.tenant.dto.HotelResponse::name).toList();
                if (!missing.isEmpty()) {
                    throw new BadRequestException("Enter the address of every location: " + String.join(", ", missing)
                            + (missing.size() == 1 ? " has none" : " have none"));
                }
            }
            default -> {
            }
        }
    }

    /**
     * Any email a step carries (the business's, the owner's, the second contact's, a manager's) must be an address: "0000000" or
     * "wuse@" saved here ended up as the hotel's Reply-To and lost its emails (18 Nov 2026). Blank is fine.
     */
    static void checkEmails(OnboardingStepData data) {
        if (data == null) {
            return;
        }
        Fields.email(data.email(), data.fullName() != null ? "the manager's email" : "the business email", false);
        Fields.email(data.ownerEmail(), "the owner's email", false);
        Fields.email(data.contactEmail(), "the second contact's email", false);
    }

    /**
     * Step 2's optional second contact: blank is fine; anything typed must be a real name, email or phone, and a contact
     * needs a name plus a phone or an email.
     */
    static void checkSecondContact(OnboardingStepData data) {
        Fields.secondContact(data.contactName(), data.contactEmail(), data.contactPhone());
    }

    /** A draft save of step 2: only what is typed is checked. */
    static void checkSecondContactFormat(OnboardingStepData data) {
        Fields.name(data.contactName(), "the second contact's name", false);
        Fields.email(data.contactEmail(), "the second contact's email", false);
        Fields.phone(data.contactPhone(), "the second contact's phone", false);
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
        OnboardingDraftData data = draftData.parse(d.getData());
        return new OnboardingResponse(d.getId(), d.getBusinessId(), business.getName(), business.getSource(), d.getStep(),
                totalSteps(business), draftData.completedSteps(data), d.getAssignedTo(), assigneeNames.get(d.getAssignedTo()), d.getStatus(),
                data.nextAction(), d.getCreatedAt(), d.getUpdatedAt(), data);
    }

    private static void requirePlatform() {
        if (!CurrentUser.require().isPlatform()) {
            throw new ForbiddenException("Only Hospio360 staff can do this");
        }
    }
}
