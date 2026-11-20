package com.hospio.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.tenant.client.IdentityClient;
import com.hospio.tenant.dto.HotelResponse;
import com.hospio.tenant.dto.OnboardingResponse;
import com.hospio.tenant.dto.OnboardingStepData;
import com.hospio.tenant.dto.OnboardingStepRequest;
import com.hospio.tenant.entity.Business;
import com.hospio.tenant.entity.Hotel;
import com.hospio.tenant.entity.OnboardingDraft;
import com.hospio.tenant.repository.HandoverRepository;
import com.hospio.tenant.repository.OnboardingDraftRepository;
import com.hospio.tenant.repository.SetupTemplateRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Back-office required fields (tester report 21 Nov 2026): steps 1, 2 and 4 complete only with the registered name, business
 * type, head office address and city, a business email or phone, the owner's phone and an address on every location; a draft
 * save ("Save and finish later", the board's next action) keeps whatever is there; the handover asks for the same.
 */
class OnboardingRequiredFieldsTest {

    private final OnboardingDraftRepository drafts = mock(OnboardingDraftRepository.class);
    private final BusinessService businesses = mock(BusinessService.class);
    private final IdentityClient identity = mock(IdentityClient.class);
    private final DraftData draftData = new DraftData(new ObjectMapper());
    private final OnboardingService service = new OnboardingService(drafts, mock(HandoverRepository.class),
            mock(SetupTemplateRepository.class), businesses, draftData, identity, mock(EventPublisher.class),
            new HandoverPack("https://web-staging.hospio360.com"), mock(DeliveryService.class));
    private final ObjectMapper om = new ObjectMapper();
    private Business business;
    private OnboardingDraft draft;

    @BeforeEach
    void admin() {
        HospioUserDetails admin = new HospioUserDetails("a1", "qa.support@hospio-qa.test", "QA Support", null, List.of(),
                Map.of(Role.SUPER_ADMIN, AccessLevel.FULL), JwtUtil.SCOPE_PLATFORM);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        business = Business.builder().name("QA Validation Lounge").city("Abuja").ownerName("Tunde Bakare")
                .ownerEmail("qa.owner@hospio-qa.test").stage(Business.Stage.ONBOARDING).build();
        business.setId("biz-1");
        when(businesses.find("biz-1")).thenReturn(business);
        draft = OnboardingDraft.builder().businessId("biz-1").assignedTo("a1").data(draftData.empty()).build();
        when(drafts.findByBusinessId("biz-1")).thenReturn(Optional.of(draft));
        when(drafts.save(any())).thenAnswer(i -> i.getArgument(0));
        when(identity.names(any())).thenReturn(Map.of());
        when(businesses.hotelsOf("biz-1")).thenReturn(List.of());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private OnboardingStepData data(String json) throws Exception {
        return om.readValue(json, OnboardingStepData.class);
    }

    private void completeBusinessDetails() {
        business.setLegalName("QA Validation Lounge Ltd");
        business.setBusinessType("Limited company");
        business.setAddress("Plot 41 Aminu Kano Crescent, Wuse 2");
        business.setEmail("accounts@hospio-qa.test");
    }

    private static HotelResponse hotel(String name, String address) {
        return new HotelResponse("h-" + name, "biz-1", name, "Abuja", address, null, null, "Africa/Lagos", Hotel.Status.ACTIVE, null,
                0, null, null, null, null, Instant.now());
    }

    @Test
    void stepOneNamesEachMissingDetailInTurn() throws Exception {
        OnboardingStepRequest submit = new OnboardingStepRequest(1, data("{\"name\":\"QA Validation Lounge\"}"), null);
        assertThatThrownBy(() -> service.saveStep("biz-1", submit)).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("registered name");
        business.setLegalName("QA Validation Lounge Ltd");
        assertThatThrownBy(() -> service.saveStep("biz-1", submit)).hasMessage("Choose the business type");
        business.setBusinessType("Limited company");
        assertThatThrownBy(() -> service.saveStep("biz-1", submit)).hasMessage("Enter the head office address");
        business.setAddress("Plot 41 Aminu Kano Crescent, Wuse 2");
        assertThatThrownBy(() -> service.saveStep("biz-1", submit)).hasMessageContaining("business email or phone");
        business.setPhone("+234 803 555 0192");
        OnboardingResponse saved = service.saveStep("biz-1", submit);
        assertThat(saved.completedSteps()).containsExactly(1);
    }

    @Test
    void aDraftSaveKeepsAnIncompleteStepWithoutCompletingIt() throws Exception {
        OnboardingResponse saved = service.saveStep("biz-1",
                new OnboardingStepRequest(1, data("{\"name\":\"QA Validation Lounge\"}"), "Finish step 1", true));
        assertThat(saved.completedSteps()).isEmpty();
        assertThat(draft.getStep()).isEqualTo(1);
        assertThat(draftData.parse(draft.getData()).steps()).containsKey("1");
        // A typed value is still checked on a draft save.
        assertThatThrownBy(() -> service.saveStep("biz-1",
                new OnboardingStepRequest(2, data("{\"contactEmail\":\"hauwa@\"}"), null, true)))
                .hasMessageContaining("econd contact's email");
    }

    @Test
    void aDraftSaveOfACompletedStepKeepsItCompleted() throws Exception {
        completeBusinessDetails();
        service.saveStep("biz-1", new OnboardingStepRequest(1, data("{\"name\":\"QA Validation Lounge\"}"), null));
        // The details are cleared later (an old business); the board's next action still saves.
        business.setLegalName(null);
        OnboardingResponse saved = service.saveStep("biz-1",
                new OnboardingStepRequest(1, data("{\"name\":\"QA Validation Lounge\"}"), "Call the owner", true));
        assertThat(saved.completedSteps()).containsExactly(1);
        assertThat(saved.nextAction()).isEqualTo("Call the owner");
    }

    @Test
    void stepTwoNeedsTheOwnersPhoneAndAUsableSecondContact() throws Exception {
        assertThatThrownBy(() -> service.saveStep("biz-1", new OnboardingStepRequest(2, data("{}"), null)))
                .hasMessageContaining("Enter the owner's phone");
        business.setOwnerPhone("0000");
        assertThatThrownBy(() -> service.saveStep("biz-1", new OnboardingStepRequest(2, data("{}"), null)))
                .hasMessageContaining("real phone number");
        business.setOwnerPhone("+234 803 555 0192");
        assertThatThrownBy(() -> service.saveStep("biz-1", new OnboardingStepRequest(2, data("{\"contactPhone\":\"0803 555 0193\"}"), null)))
                .hasMessageContaining("econd contact's name");
        assertThatThrownBy(() -> service.saveStep("biz-1", new OnboardingStepRequest(2, data("{\"contactName\":\"Hadiza Jibrin\"}"), null)))
                .hasMessageContaining("second contact's phone or email");
        assertThatCode(() -> service.saveStep("biz-1", new OnboardingStepRequest(2, data("{}"), null))).doesNotThrowAnyException();
        assertThatCode(() -> service.saveStep("biz-1",
                new OnboardingStepRequest(2, data("{\"contactName\":\"Hadiza Jibrin\",\"contactEmail\":\"hadiza@hospio-qa.test\"}"), null)))
                .doesNotThrowAnyException();
    }

    @Test
    void stepFourNeedsAnAddressOnEveryLocation() throws Exception {
        when(businesses.hotelsOf("biz-1")).thenReturn(List.of(hotel("Wuse", "Plot 41 Aminu Kano Cres"), hotel("Garki", null)));
        assertThatThrownBy(() -> service.saveStep("biz-1", new OnboardingStepRequest(4, data("{}"), null)))
                .hasMessage("Enter the address of every location: Garki has none");
        when(businesses.hotelsOf("biz-1")).thenReturn(List.of(hotel("Wuse", "Plot 41 Aminu Kano Cres")));
        assertThatCode(() -> service.saveStep("biz-1", new OnboardingStepRequest(4, data("{}"), null))).doesNotThrowAnyException();
    }

    @Test
    void theChargesStartDateStaysOptional() throws Exception {
        assertThatCode(() -> service.saveStep("biz-1",
                new OnboardingStepRequest(3, data("{\"feeModel\":\"PERCENT_OF_SALES\",\"feeValue\":1.5}"), null)))
                .doesNotThrowAnyException();
    }

    @Test
    void theHandoverAsksForTheSameDetailsBeforeMakingTheOwnersLogin() {
        draft.setStatus(OnboardingDraft.Status.READY_FOR_HANDOVER);
        assertThatThrownBy(() -> service.sendHandover("biz-1")).isInstanceOf(BadRequestException.class)
                .hasMessageStartingWith("Before the handover: Enter the registered name");
        completeBusinessDetails();
        assertThatThrownBy(() -> service.sendHandover("biz-1")).hasMessageStartingWith("Before the handover: Enter the owner's phone");
        verify(identity, never()).createUser(any(), any(), any(), any(), any(), any());
    }

    @Test
    void fieldRulesForTheSecondContact() {
        assertThatCode(() -> Fields.secondContact(null, " ", "")).doesNotThrowAnyException();
        assertThatThrownBy(() -> Fields.secondContact(null, "hadiza@hospio-qa.test", null)).hasMessageContaining("econd contact's name");
        assertThatThrownBy(() -> Fields.secondContact("Hadiza", "hadiza@", null)).hasMessageContaining("econd contact's email");
    }
}
