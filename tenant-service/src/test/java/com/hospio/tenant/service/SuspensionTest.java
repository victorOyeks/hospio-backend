package com.hospio.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.tenant.client.IdentityClient;
import com.hospio.tenant.dto.BusinessResponse;
import com.hospio.tenant.dto.StageChangeRequest;
import com.hospio.tenant.dto.SuspendRequest;
import com.hospio.tenant.dto.SuspensionStatus;
import com.hospio.tenant.entity.Business;
import com.hospio.tenant.repository.BusinessRepository;
import com.hospio.tenant.repository.HotelRepository;
import com.hospio.tenant.repository.OnboardingDraftRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** docs/suspension.md: what a suspension records, what the business is shown, and the stage a reactivation puts back. */
class SuspensionTest {

    private final BusinessRepository businesses = mock(BusinessRepository.class);
    private final BusinessService service = new BusinessService(businesses, mock(HotelRepository.class), mock(OnboardingDraftRepository.class),
            new DraftData(new ObjectMapper()), mock(EventPublisher.class), mock(IdentityClient.class), mock(com.hospio.tenant.client.PlatformClient.class));
    private final Business business = Business.builder().name("QA Hotels").stage(Business.Stage.AT_RISK).build();

    @BeforeEach
    void setUp() {
        business.setId("b1");
        when(businesses.findById("b1")).thenReturn(Optional.of(business));
        when(businesses.save(any())).thenAnswer(a -> a.getArgument(0));
        HospioUserDetails admin = new HospioUserDetails("a1", "amoria@hospio360.com", "Amoria", null, List.of(),
                Map.of(Role.SUPER_ADMIN, AccessLevel.FULL), JwtUtil.SCOPE_PLATFORM);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void suspendingKeepsWhyTheNoticeDateAndTheStageToPutBack() {
        BusinessResponse r = service.suspend("b1", new SuspendRequest(Business.SuspensionType.UNPAID_INVOICE, "Invoice HIN-202610-0002 unpaid", false,
                LocalDate.of(2026, 11, 10), "a1", "Amoria"));
        assertThat(r.stage()).isEqualTo(Business.Stage.SUSPENDED);
        assertThat(r.stageBeforeSuspension()).isEqualTo(Business.Stage.AT_RISK);
        assertThat(r.suspensionType()).isEqualTo(Business.SuspensionType.UNPAID_INVOICE);
        assertThat(r.suspensionNoticeSentOn()).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(r.suspendedAt()).isNotNull();
        // The note is not shown to the business unless the admin chose to.
        SuspensionStatus shown = service.suspension("b1");
        assertThat(shown.suspended()).isTrue();
        assertThat(shown.reasonType()).isEqualTo("UNPAID_INVOICE");
        assertThat(shown.reason()).isNull();
        assertThatThrownBy(() -> service.suspend("b1", new SuspendRequest(Business.SuspensionType.OTHER, "Second time round", true, null, "a1", "Amoria")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already suspended");
    }

    @Test
    void theNoteIsShownWhenTheAdminChoseTo() {
        service.suspend("b1", new SuspendRequest(Business.SuspensionType.OTHER, "Closed at the owner's request", true, null, "a1", "Amoria"));
        assertThat(service.suspension("b1").reason()).isEqualTo("Closed at the owner's request");
    }

    @Test
    void reactivatingPutsThePreviousStageBackAndClearsTheSuspension() {
        service.suspend("b1", new SuspendRequest(Business.SuspensionType.OTHER, "Terms breach under review", true, null, "a1", "Amoria"));
        BusinessResponse r = service.reactivate("b1");
        assertThat(r.stage()).isEqualTo(Business.Stage.AT_RISK);
        assertThat(r.suspendedAt()).isNull();
        assertThat(r.suspensionType()).isNull();
        assertThat(r.suspensionReason()).isNull();
        assertThat(r.stageBeforeSuspension()).isNull();
        assertThat(service.suspension("b1")).isEqualTo(SuspensionStatus.NONE);
        assertThatThrownBy(() -> service.reactivate("b1")).isInstanceOf(BadRequestException.class).hasMessageContaining("isn't suspended");
    }

    @Test
    void onlyALiveOrAtRiskBusinessCanBeSuspended() {
        business.setStage(Business.Stage.ONBOARDING);
        assertThatThrownBy(() -> service.suspend("b1", new SuspendRequest(Business.SuspensionType.OTHER, "Not live yet at all", false, null, "a1", "Amoria")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Only a Live or At risk business");
    }

    @Test
    void changeStageNoLongerSuspendsOrReactivates() {
        assertThatThrownBy(() -> service.changeStage("b1", new StageChangeRequest(Business.Stage.SUSPENDED, "Unpaid invoice")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Use Suspend business");
        business.setStage(Business.Stage.SUSPENDED);
        assertThatThrownBy(() -> service.changeStage("b1", new StageChangeRequest(Business.Stage.LIVE, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Use Reactivate");
    }
}
