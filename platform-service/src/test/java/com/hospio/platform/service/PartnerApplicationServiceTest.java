package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.NoticeEvent;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.platform.client.IdentityClient;
import com.hospio.platform.dto.AdminResponses.MarketerInvited;
import com.hospio.platform.dto.AdminResponses.MarketerLogin;
import com.hospio.platform.dto.PartnerApplicationDtos.ApplyRequest;
import com.hospio.platform.dto.PartnerApplicationDtos.ApproveRequest;
import com.hospio.platform.dto.PartnerApplicationDtos.Approved;
import com.hospio.platform.dto.PartnerApplicationDtos.DeclineRequest;
import com.hospio.platform.dto.PartnerApplicationDtos.PartnerApplicationEvent;
import com.hospio.platform.dto.PartnerApplicationDtos.Submission;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.LegalDocument;
import com.hospio.platform.entity.MarketerProfile;
import com.hospio.platform.entity.PartnerApplication;
import com.hospio.platform.repository.MarketerProfileRepository;
import com.hospio.platform.repository.PartnerApplicationRepository;
import com.hospio.platform.settings.FeeSettings;
import com.hospio.platform.settings.GeneralSettings;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * "Apply to be a partner": what the public form accepts and refuses, the hidden field, the hourly limit, repeats answered like a
 * new application, and the review (approve runs the usual invite at the settings' share and months; who may decide; decline).
 */
class PartnerApplicationServiceTest {

    private final List<PartnerApplication> table = new ArrayList<>();
    private final PartnerApplicationRepository rows = mock(PartnerApplicationRepository.class);
    private final MarketerProfileRepository profiles = mock(MarketerProfileRepository.class);
    private final MarketerService marketers = mock(MarketerService.class);
    private final PlatformSettingsService settings = mock(PlatformSettingsService.class);
    private final LegalService legal = mock(LegalService.class);
    private final DeliveryService deliveries = mock(DeliveryService.class);
    private final IdentityClient identity = mock(IdentityClient.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final ActivityLogService activity = mock(ActivityLogService.class);
    private PartnerApplicationService service;

    @BeforeEach
    void setUp() {
        when(rows.saveAndFlush(any())).thenAnswer(i -> {
            PartnerApplication a = i.getArgument(0);
            a.setId("app-" + (table.size() + 1));
            table.add(a);
            return a;
        });
        when(rows.save(any())).thenAnswer(i -> i.getArgument(0));
        when(rows.existsByEmailIgnoreCaseAndStatus(anyString(), eq("PENDING"))).thenAnswer(i -> table.stream()
                .anyMatch(a -> a.getEmail().equalsIgnoreCase(i.getArgument(0)) && "PENDING".equals(a.getStatus())));
        when(rows.findForDecision(anyString())).thenAnswer(i -> table.stream().filter(a -> a.getId().equals(i.getArgument(0))).findFirst());
        when(profiles.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());
        FeeSettings fees = mock(FeeSettings.class);
        when(fees.marketerSharePct()).thenReturn(new BigDecimal("20"));
        when(fees.marketerMonths()).thenReturn(12);
        when(settings.fees()).thenReturn(fees);
        when(settings.general()).thenReturn(new GeneralSettings("Oyeks Global Ltd", "hello@oyeksglobal.com", null, null, "Africa/Lagos"));
        when(legal.currentVersion("RPA")).thenReturn("RPA-2026-11");
        when(legal.currentVersion("PRIVACY")).thenReturn("PRIV-2026-11");
        when(legal.allVersions()).thenReturn(List.of(LegalDocument.builder().docKey("RPA").version("RPA-2026-11").build(),
                LegalDocument.builder().docKey("PRIVACY").version("PRIV-2026-11").build()));
        when(identity.platformStaff("SUPER_ADMIN")).thenReturn(List.of(new IdentityClient.Contact("u-super", "Amoria", null, null, null, "ACTIVE", null)));
        when(identity.platformStaff("SUPPORT")).thenReturn(List.of(new IdentityClient.Contact("u-support", "Kola", null, null, null, "ACTIVE", null)));
        service = new PartnerApplicationService(rows, profiles, marketers, settings, legal, deliveries, new PartnerApplicationLimiter((org.springframework.data.redis.core.StringRedisTemplate) null, 3, 3),
                identity, events, activity);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static ApplyRequest valid(String email) {
        return new ApplyRequest("  Ada   Obi ", email, "0803 555 0192", "Ikeja", "Lagos", List.of("lounge", "HOTEL"), "3-5",
                "I run a <b>drinks</b> supply business.\r\n\r\n\r\n\r\nI know many owners.", "Ada Supplies Ltd", true, "RPA-2026-11", "PRIV-1999-01", null);
    }

    private void signIn(Role role) {
        HospioUserDetails admin = new HospioUserDetails("u-" + role.name().toLowerCase(), "x@hospio360.com", "Kola Support", null, List.of(),
                Map.of(role, AccessLevel.FULL), JwtUtil.SCOPE_PLATFORM);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
    }

    @Test
    void aValidApplicationIsStoredCleanedAndTheApplicantAndTheTeamAreTold() {
        Submission s = service.submit(valid("Ada@Hospio-QA.test"), "197.210.1.1", "Mozilla/5.0\n<script>");

        assertThat(s.outcome()).isEqualTo(Submission.RECEIVED);
        assertThat(table).hasSize(1);
        PartnerApplication a = table.get(0);
        assertThat(a.getFullName()).isEqualTo("Ada Obi");
        assertThat(a.getEmail()).isEqualTo("ada@hospio-qa.test");
        assertThat(a.getPhone()).isEqualTo("+2348035550192");
        assertThat(a.getBusinessKinds()).isEqualTo("HOTEL,LOUNGE");
        assertThat(a.getNetworkNote()).isEqualTo("I run a bdrinks/b supply business.\n\nI know many owners.");
        assertThat(a.getStatus()).isEqualTo("PENDING");
        assertThat(a.getIp()).isEqualTo("197.210.1.1");
        assertThat(a.getUserAgent()).isEqualTo("Mozilla/5.0 script");
        // The version the page showed when it is one of ours; otherwise the one in force.
        assertThat(a.getRpaVersion()).isEqualTo("RPA-2026-11");
        assertThat(a.getPrivacyVersion()).isEqualTo("PRIV-2026-11");

        ArgumentCaptor<Delivery> mail = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveries).send(mail.capture());
        assertThat(mail.getValue().channel()).isEqualTo(Delivery.Channel.EMAIL);
        assertThat(mail.getValue().to()).isEqualTo("ada@hospio-qa.test");
        assertThat(mail.getValue().text()).contains("Hello Ada,").contains("within 2 working days").contains("hotels and lounges (3 to 5)");

        ArgumentCaptor<NoticeEvent> notice = ArgumentCaptor.forClass(NoticeEvent.class);
        verify(events).publish(eq(KafkaTopics.NOTICE), notice.capture());
        assertThat(notice.getValue().getUserIds()).containsExactly("u-super", "u-support");
        assertThat(notice.getValue().getLink()).isEqualTo("/dashboard/marketers?tab=applications&app=app-1");
    }

    @Test
    void theFormSaysWhatIsWrongFieldByField() {
        Submission s = service.submit(new ApplyRequest("A", "not-an-email", "12345", "", "Atlantis", List.of("CASINO"), "lots", "x".repeat(501),
                null, false, null, null, ""), "1.1.1.1", null);

        assertThat(s.outcome()).isEqualTo(Submission.INVALID);
        assertThat(s.errors()).containsOnlyKeys("fullName", "email", "phone", "city", "state", "businessKinds", "businessCount", "networkNote",
                "acceptedTerms");
        assertThat(table).isEmpty();
        verify(deliveries, never()).send(any(Delivery.class));
    }

    @Test
    void nigerianNumbersInAnyUsualWritingBecomeOneFormAndOthersAreRefused() {
        assertThat(PartnerApplicationService.nigerianPhone("08035550192")).isEqualTo("+2348035550192");
        assertThat(PartnerApplicationService.nigerianPhone("+234 803 555 0192")).isEqualTo("+2348035550192");
        assertThat(PartnerApplicationService.nigerianPhone("234-803-555-0192")).isEqualTo("+2348035550192");
        assertThat(PartnerApplicationService.nigerianPhone("8035550192")).isEqualTo("+2348035550192");
        assertThat(PartnerApplicationService.nigerianPhone("+44 7700 900123")).isNull();
        assertThat(PartnerApplicationService.nigerianPhone("0803555019")).isNull();
        assertThat(PartnerApplicationService.nigerianPhone("08030000000")).isNull();
        assertThat(PartnerApplicationService.nigerianPhone("0803 555 0192 ext 4")).isNull();
    }

    @Test
    void aFilledHiddenFieldIsToldReceivedAndNothingIsKeptOrSent() {
        ApplyRequest v = valid("bot@hospio-qa.test");
        Submission s = service.submit(new ApplyRequest(v.fullName(), v.email(), v.phone(), v.city(), v.state(), v.businessKinds(), v.businessCount(),
                v.networkNote(), v.companyName(), true, null, null, "https://spam.example"), "1.1.1.1", "bot");

        assertThat(s.outcome()).isEqualTo(Submission.RECEIVED);
        assertThat(table).isEmpty();
        verify(deliveries, never()).send(any(Delivery.class));
        verify(events, never()).publish(anyString(), any());
    }

    @Test
    void aRepeatAndAnExistingMarketerGetTheSameAnswerAndNoSecondRecord() {
        assertThat(service.submit(valid("ada@hospio-qa.test"), "1.1.1.1", null).outcome()).isEqualTo(Submission.RECEIVED);
        assertThat(service.submit(valid("ADA@hospio-qa.test"), "1.1.1.2", null).outcome()).isEqualTo(Submission.RECEIVED);
        when(profiles.findByEmailIgnoreCase("bayo@hospio-qa.test")).thenReturn(Optional.of(MarketerProfile.builder().email("bayo@hospio-qa.test").build()));
        assertThat(service.submit(valid("bayo@hospio-qa.test"), "1.1.1.3", null).outcome()).isEqualTo(Submission.RECEIVED);

        assertThat(table).hasSize(1);
        verify(deliveries).send(any(Delivery.class));
    }

    @Test
    void theFourthFromOneAddressInAnHourIsRefused() {
        for (int i = 1; i <= 3; i++) {
            assertThat(service.submit(valid("p" + i + "@hospio-qa.test"), "9.9.9.9", null).outcome()).isEqualTo(Submission.RECEIVED);
        }
        assertThat(service.submit(valid("p4@hospio-qa.test"), "9.9.9.9", null).outcome()).isEqualTo(Submission.LIMITED);
        assertThat(service.submit(valid("p4@hospio-qa.test"), "9.9.9.10", null).outcome()).isEqualTo(Submission.RECEIVED);
        assertThat(table).hasSize(4);
    }

    @Test
    void theFourthForOneEmailInAnHourIsRefusedWhateverTheAddress() {
        PartnerApplicationLimiter limiter = new PartnerApplicationLimiter((org.springframework.data.redis.core.StringRedisTemplate) null, 3, 3);
        assertThat(limiter.allowEmail("a@b.test")).isTrue();
        assertThat(limiter.allowEmail("a@b.test")).isTrue();
        assertThat(limiter.allowEmail("a@b.test")).isTrue();
        assertThat(limiter.allowEmail("a@b.test")).isFalse();
        assertThat(limiter.allowEmail("c@b.test")).isTrue();
    }

    @Test
    void anInviteRefusedByIdentitySaysWhy() {
        assertThat(IdentityClient.conflictMessage("{\"status\":false,\"message\":\"This phone number is already in use on another account\"}"))
                .isEqualTo("This phone number is already in use on another account");
        assertThat(IdentityClient.conflictMessage("<html>")).isEqualTo("A login with that email already exists");
        assertThat(IdentityClient.conflictMessage(null)).isEqualTo("A login with that email already exists");
    }

    @Test
    void aVisitorsAddressIsBelievedOnlyFromOurOwnSite() {
        SiteCallers site = new SiteCallers("10.9.8.7");
        // The landing site's server (this machine, or one listed) passes the visitor on.
        assertThat(site.visitorAddress("127.0.0.1", "197.210.1.1")).isEqualTo("197.210.1.1");
        assertThat(site.visitorAddress("10.9.8.7", "2001:db8::1")).isEqualTo("2001:db8::1");
        // Anyone else naming an address is counted by their own.
        assertThat(site.visitorAddress("105.113.68.139", "197.210.1.1")).isEqualTo("105.113.68.139");
        // Not an address: ignored.
        assertThat(site.visitorAddress("127.0.0.1", "<script>")).isEqualTo("127.0.0.1");
        assertThat(site.visitorAddress("127.0.0.1", null)).isEqualTo("127.0.0.1");
    }

    @Test
    void supportApprovesWithTheStandardTermsThroughTheUsualInvite() {
        service.submit(valid("ada@hospio-qa.test"), "1.1.1.1", null);
        signIn(Role.SUPPORT);
        MarketerProfile profile = MarketerProfile.builder().fullName("Ada Obi").email("ada@hospio-qa.test").build();
        profile.setId("mk-1");
        when(marketers.invite(any())).thenReturn(new MarketerInvited(profile, new MarketerLogin("u-1", null, null, "tok", null), List.of()));

        Approved done = service.approve("app-1", null);

        ArgumentCaptor<PlatformRequests.MarketerInviteRequest> invite = ArgumentCaptor.forClass(PlatformRequests.MarketerInviteRequest.class);
        verify(marketers).invite(invite.capture());
        assertThat(invite.getValue().fullName()).isEqualTo("Ada Obi");
        assertThat(invite.getValue().email()).isEqualTo("ada@hospio-qa.test");
        assertThat(invite.getValue().phone()).isEqualTo("+2348035550192");
        assertThat(invite.getValue().sharePct()).isEqualByComparingTo("20");
        assertThat(invite.getValue().months()).isEqualTo(12);
        assertThat(done.application().status()).isEqualTo("APPROVED");
        assertThat(done.application().marketerProfileId()).isEqualTo("mk-1");
        assertThat(done.application().decidedByName()).isEqualTo("Kola Support");
        ArgumentCaptor<PartnerApplicationEvent> logged = ArgumentCaptor.forClass(PartnerApplicationEvent.class);
        verify(activity).record(eq(PartnerApplicationService.ACTIVITY_TOPIC), logged.capture());
        assertThat(logged.getValue().getActorName()).isEqualTo("Hospio360 · Kola Support");
        assertThat(logged.getValue().summary()).isEqualTo("Partner application from Ada Obi approved: invited as a marketer at 20% for 12 months (ada@hospio-qa.test)");
        assertThat(logged.getValue().type()).isEqualTo("PartnerApplicationApproved");

        // Decided once.
        assertThatThrownBy(() -> service.approve("app-1", null)).isInstanceOf(BadRequestException.class).hasMessageContaining("already approved by Kola Support");
    }

    @Test
    void supportCannotChangeTheShareAndFinanceCannotDecide() {
        service.submit(valid("ada@hospio-qa.test"), "1.1.1.1", null);
        signIn(Role.SUPPORT);
        assertThatThrownBy(() -> service.approve("app-1", new ApproveRequest(null, null, null, new BigDecimal("30"), 12)))
                .isInstanceOf(ForbiddenException.class).hasMessageContaining("Only a super admin or finance can change the share");
        signIn(Role.FINANCE_ADMIN);
        assertThatThrownBy(() -> service.approve("app-1", null)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.decline("app-1", new DeclineRequest("Not a fit for now", false))).isInstanceOf(ForbiddenException.class);
        verify(marketers, never()).invite(any());
        assertThat(table.get(0).getStatus()).isEqualTo("PENDING");
    }

    @Test
    void aSuperAdminMayApproveWithAnotherShare() {
        service.submit(valid("ada@hospio-qa.test"), "1.1.1.1", null);
        signIn(Role.SUPER_ADMIN);
        MarketerProfile profile = MarketerProfile.builder().fullName("Ada O. Obi").email("ada.obi@hospio-qa.test").build();
        profile.setId("mk-2");
        when(marketers.invite(any())).thenReturn(new MarketerInvited(profile, null, List.of()));

        service.approve("app-1", new ApproveRequest("Ada O. Obi", "Ada.Obi@hospio-qa.test", "", new BigDecimal("15"), 6));

        ArgumentCaptor<PlatformRequests.MarketerInviteRequest> invite = ArgumentCaptor.forClass(PlatformRequests.MarketerInviteRequest.class);
        verify(marketers).invite(invite.capture());
        assertThat(invite.getValue().email()).isEqualTo("ada.obi@hospio-qa.test");
        // The admin emptied the phone box (the number is on another account): invited without one.
        assertThat(invite.getValue().phone()).isNull();
        assertThat(invite.getValue().sharePct()).isEqualByComparingTo("15");
        assertThat(invite.getValue().months()).isEqualTo(6);
    }

    @Test
    void aDeclineNeedsAReasonAndOnlyASharedReasonGoesInTheEmail() {
        service.submit(valid("ada@hospio-qa.test"), "1.1.1.1", null);
        service.submit(valid("bayo@hospio-qa.test"), "1.1.1.2", null);
        signIn(Role.SUPER_ADMIN);
        assertThatThrownBy(() -> service.decline("app-1", new DeclineRequest(" ", false))).isInstanceOf(BadRequestException.class);

        service.decline("app-1", new DeclineRequest("Outside the cities we cover for now", false));
        service.decline("app-2", new DeclineRequest("We are not taking partners in Lagos this quarter", true));

        ArgumentCaptor<Delivery> mail = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveries, org.mockito.Mockito.times(4)).send(mail.capture());
        Delivery kept = mail.getAllValues().get(2);
        Delivery shared = mail.getAllValues().get(3);
        assertThat(kept.text()).contains("not able to take it forward").doesNotContain("Outside the cities");
        assertThat(shared.text()).contains("A note from our team: We are not taking partners in Lagos this quarter");
        assertThat(table.get(0).getStatus()).isEqualTo("DECLINED");
        assertThat(table.get(0).getDeclineReason()).isEqualTo("Outside the cities we cover for now");
        assertThat(table.get(1).isDeclineReasonShared()).isTrue();
    }
}
