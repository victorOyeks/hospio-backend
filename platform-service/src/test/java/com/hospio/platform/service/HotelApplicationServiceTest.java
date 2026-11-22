package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.dto.HotelApplicationDtos.ApplicationView;
import com.hospio.platform.dto.HotelApplicationDtos.ApplyRequest;
import com.hospio.platform.dto.HotelApplicationDtos.HotelApplicationEvent;
import com.hospio.platform.dto.HotelApplicationDtos.StatusRequest;
import com.hospio.platform.dto.HotelApplicationDtos.Submission;
import com.hospio.platform.entity.HotelApplication;
import com.hospio.platform.entity.HotelApplicationNote;
import com.hospio.platform.entity.MonthlySales;
import com.hospio.platform.repository.HotelApplicationNoteRepository;
import com.hospio.platform.repository.HotelApplicationRepository;
import com.hospio.platform.settings.GeneralSettings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
 * "Book a demo": what the public form accepts and refuses, the hidden field, the hourly limit, a repeat becoming a note, the
 * marketer's referral, the two emails and the bell; then the admin side: the status flow, notes, assignee, meeting, linking the
 * onboarded business, and who may do what.
 */
class HotelApplicationServiceTest {

    private final List<HotelApplication> table = new ArrayList<>();
    private final List<HotelApplicationNote> timeline = new ArrayList<>();
    private final HotelApplicationRepository rows = mock(HotelApplicationRepository.class);
    private final HotelApplicationNoteRepository notes = mock(HotelApplicationNoteRepository.class);
    private final MarketerService marketers = mock(MarketerService.class);
    private final PlatformSettingsService settings = mock(PlatformSettingsService.class);
    private final DeliveryService deliveries = mock(DeliveryService.class);
    private final IdentityClient identity = mock(IdentityClient.class);
    private final TenantClient tenants = mock(TenantClient.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final ActivityLogService activity = mock(ActivityLogService.class);
    private HotelApplicationService service;

    @BeforeEach
    void setUp() {
        when(rows.saveAndFlush(any())).thenAnswer(i -> {
            HotelApplication a = i.getArgument(0);
            a.setId("app-" + (table.size() + 1));
            a.setCreatedAt(Instant.now());
            table.add(a);
            return a;
        });
        when(rows.save(any())).thenAnswer(i -> i.getArgument(0));
        when(rows.findFirstByEmailIgnoreCaseAndStatusNotIn(anyString(), anyCollection())).thenAnswer(i -> {
            Collection<String> closed = i.getArgument(1);
            return table.stream().filter(a -> ((String) i.getArgument(0)).equalsIgnoreCase(a.getEmail()) && !closed.contains(a.getStatus())).findFirst();
        });
        when(rows.findFirstByPhoneAndStatusNotInOrderByCreatedAtDesc(anyString(), anyCollection())).thenAnswer(i -> {
            Collection<String> closed = i.getArgument(1);
            return table.stream().filter(a -> a.getPhone().equals(i.getArgument(0)) && !closed.contains(a.getStatus())).reduce((x, y) -> y);
        });
        when(rows.findForUpdate(anyString())).thenAnswer(i -> table.stream().filter(a -> a.getId().equals(i.getArgument(0))).findFirst());
        when(rows.findById(anyString())).thenAnswer(i -> table.stream().filter(a -> a.getId().equals(i.getArgument(0))).findFirst());
        when(rows.existsByBusinessId(anyString())).thenAnswer(i -> table.stream().anyMatch(a -> i.getArgument(0).equals(a.getBusinessId())));
        when(notes.save(any())).thenAnswer(i -> {
            HotelApplicationNote n = i.getArgument(0);
            n.setId("n-" + (timeline.size() + 1));
            n.setCreatedAt(Instant.now());
            timeline.add(n);
            return n;
        });
        when(notes.findByApplicationIdOrderByCreatedAtAscIdAsc(anyString()))
                .thenAnswer(i -> timeline.stream().filter(n -> n.getApplicationId().equals(i.getArgument(0))).toList());
        when(settings.general()).thenReturn(new GeneralSettings("Oyeks Global Ltd", "hello@oyeksglobal.com", null, null, "Africa/Lagos"));
        when(identity.platformStaff("SUPER_ADMIN")).thenReturn(List.of(new IdentityClient.Contact("u-super", "Amoria", null, null, null, "ACTIVE", null)));
        when(identity.platformStaff("SUPPORT")).thenReturn(List.of(new IdentityClient.Contact("u-support", "Kola", null, null, null, "ACTIVE", null)));
        when(marketers.referralCode(anyString())).thenReturn(Optional.empty());
        when(marketers.referralCode("HM-ADA01")).thenReturn(Optional.of(new MarketerService.ReferralCodeInfo("mp-1", "HM-ADA01", "Ada Marketer", "ACTIVE", true)));
        when(marketers.referralCode("HM-OLD01")).thenReturn(Optional.of(new MarketerService.ReferralCodeInfo("mp-2", "HM-OLD01", "Old Marketer", "ENDED", false)));
        service = new HotelApplicationService(rows, notes, marketers, settings, deliveries,
                new HotelApplicationLimiter((org.springframework.data.redis.core.StringRedisTemplate) null, 5, 3), identity, tenants, events, activity);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private static ApplyRequest valid(String email) {
        return new ApplyRequest("  Bola   Ade ", "0803 555 0192", email, "QA Palm <b>Suites</b>", "hotel", "Ikeja", "Lagos", 24, "search",
                "morning", "We have 24 rooms\r\n\r\n\r\nand a bar.", null, null, "from_5m_to_20m");
    }

    private static ApplyRequest withRef(String email, String ref) {
        ApplyRequest v = valid(email);
        return new ApplyRequest(v.ownerName(), v.phone(), v.email(), v.businessName(), v.businessType(), v.city(), v.state(), v.rooms(),
                "marketer", v.preferredTime(), v.message(), ref, null, v.monthlySales());
    }

    private void signIn(Role role) {
        HospioUserDetails admin = new HospioUserDetails("u-" + role.name().toLowerCase(), "x@hospio360.com", "Kola Support", null, List.of(),
                Map.of(role, AccessLevel.FULL), JwtUtil.SCOPE_PLATFORM);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
    }

    private HotelApplication submitted() {
        assertThat(service.submit(valid("bola@hospio-qa.test"), "197.210.1.1", "Mozilla/5.0").outcome()).isEqualTo(Submission.RECEIVED);
        return table.get(table.size() - 1);
    }

    // ---- the public form -----------------------------------------------------------------------------

    @Test
    void aValidRequestIsStoredCleanedAndTheApplicantTheSupportInboxAndTheTeamAreTold() {
        HotelApplication a = submitted();

        assertThat(a.getOwnerName()).isEqualTo("Bola Ade");
        assertThat(a.getPhone()).isEqualTo("+2348035550192");
        assertThat(a.getBusinessName()).isEqualTo("QA Palm bSuites/b");
        assertThat(a.getBusinessType()).isEqualTo("HOTEL");
        assertThat(a.getRooms()).isEqualTo(24);
        assertThat(a.getMonthlySales()).isEqualTo("FROM_5M_TO_20M");
        assertThat(a.getHeardFrom()).isEqualTo("SEARCH");
        assertThat(a.getPreferredTime()).isEqualTo("MORNING");
        assertThat(a.getMessage()).isEqualTo("We have 24 rooms\n\nand a bar.");
        assertThat(a.getStatus()).isEqualTo("NEW");
        assertThat(a.getMarketerProfileId()).isNull();

        ArgumentCaptor<Delivery> mail = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveries, times(2)).send(mail.capture());
        assertThat(mail.getAllValues().get(0).to()).isEqualTo("bola@hospio-qa.test");
        assertThat(mail.getAllValues().get(0).text()).contains("Hello Bola,").contains("within 1 working day").contains("Rooms: 24");
        assertThat(mail.getAllValues().get(1).to()).isEqualTo("hello@oyeksglobal.com");
        assertThat(mail.getAllValues().get(1).subject()).isEqualTo("New demo request: QA Palm bSuites/b (Ikeja)");
        assertThat(mail.getAllValues().get(1).text()).contains("Phone: +2348035550192").contains("Heard about us: Search (Google)")
                .contains("Monthly sales (all outlets): ₦5 million – ₦20 million");
        // The applicant's acknowledgement never repeats the band.
        assertThat(mail.getAllValues().get(0).text()).doesNotContain("₦").doesNotContain("sales");

        ArgumentCaptor<NoticeEvent> notice = ArgumentCaptor.forClass(NoticeEvent.class);
        verify(events).publish(eq(KafkaTopics.NOTICE), notice.capture());
        assertThat(notice.getValue().getKind()).isEqualTo("HOTEL_APPLICATION");
        assertThat(notice.getValue().getUserIds()).containsExactly("u-super", "u-support");
        assertThat(notice.getValue().getLink()).isEqualTo("/dashboard/hotel-applications?app=" + a.getId());
    }

    @Test
    void theFormSaysWhatIsWrongFieldByField() {
        Submission s = service.submit(new ApplyRequest("1", "12345", "not-an-email", "!!", "CASINO", "x", "Atlantis", 0, "tv", "midnight",
                "x".repeat(1001), null, null, "lots"), "1.1.1.1", null);

        assertThat(s.outcome()).isEqualTo(Submission.INVALID);
        assertThat(s.errors()).containsOnlyKeys("ownerName", "phone", "email", "businessName", "businessType", "city", "state", "heardFrom",
                "preferredTime", "message", "monthlySales");
        assertThat(s.errors().get("phone")).contains("Nigerian phone number");
        assertThat(table).isEmpty();
        verify(deliveries, never()).send(any(Delivery.class));
    }

    /** The short form (22 Nov 2026): name, phone, business name and type are all a request needs. */
    private static ApplyRequest shortRequest(String phone) {
        return new ApplyRequest("QA Short Demo", phone, "  ", "QA Short Demo Ltd", "HOTEL", "", null, null, "", null, "", null, null, "");
    }

    @Test
    void aShortRequestIsStoredWithTheOptionalFieldsNullAndOnlyTheTeamIsEmailed() {
        assertThat(service.submit(shortRequest("0803 555 0111"), "1.1.1.1", null).outcome()).isEqualTo(Submission.RECEIVED);

        HotelApplication a = table.get(0);
        assertThat(a.getPhone()).isEqualTo("+2348035550111");
        assertThat(a.getEmail()).isNull();
        assertThat(a.getCity()).isNull();
        assertThat(a.getState()).isNull();
        assertThat(a.getHeardFrom()).isNull();
        assertThat(a.getMonthlySales()).isNull();
        assertThat(a.getPreferredTime()).isNull();
        assertThat(a.getMessage()).isNull();

        // No email given: no acknowledgement, only the team's email, with no "(null)" in the subject and "Not given" in the body.
        ArgumentCaptor<Delivery> mail = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveries).send(mail.capture());
        assertThat(mail.getValue().to()).isEqualTo("hello@oyeksglobal.com");
        assertThat(mail.getValue().subject()).isEqualTo("New demo request: QA Short Demo Ltd");
        assertThat(mail.getValue().text()).contains("Email: Not given").contains("Where: Not given").contains("Heard about us: Not given")
                .contains("Monthly sales (all outlets): Not asked").doesNotContain("null");
        ArgumentCaptor<NoticeEvent> notice = ArgumentCaptor.forClass(NoticeEvent.class);
        verify(events).publish(eq(KafkaTopics.NOTICE), notice.capture());
        assertThat(notice.getValue().getBody()).isEqualTo("QA Short Demo of QA Short Demo Ltd (hotel) asked for a demo.");
    }

    @Test
    void theRequiredFourAreStillRequired() {
        Submission s = service.submit(new ApplyRequest(" ", "", null, "", null, null, null, null, null, null, null, null, null, null), "1.1.1.1", null);
        assertThat(s.outcome()).isEqualTo(Submission.INVALID);
        assertThat(s.errors()).containsOnlyKeys("ownerName", "phone", "businessName", "businessType");
        assertThat(s.errors()).containsEntry("ownerName", "Enter your full name").containsEntry("phone", "Enter your phone number")
                .containsEntry("businessName", "Enter your business name").containsEntry("businessType", "Choose what kind of business it is");
    }

    @Test
    void optionalFieldsAreCheckedWhenFilledWithTheSameMessages() {
        Submission s = service.submit(new ApplyRequest("QA Short Demo", "0803 555 0112", "nope@", "QA Short Demo Ltd", "HOTEL", "#", "Narnia",
                null, "radio", "NIGHT", null, null, null, "MILLIONS"), "1.1.1.1", null);
        assertThat(s.errors()).containsOnlyKeys("email", "city", "state", "heardFrom", "preferredTime", "monthlySales");
        assertThat(s.errors()).containsEntry("email", "Enter a real email address, like ada@example.com")
                .containsEntry("city", "Enter the name of your city").containsEntry("state", "Choose your state")
                .containsEntry("heardFrom", "Tell us how you heard about Hospio360")
                .containsEntry("monthlySales", "Choose your average monthly sales (an estimate is fine)");
        assertThat(table).isEmpty();
    }

    @Test
    void withNoEmailARepeatIsFoundByPhoneAndBecomesANote() {
        service.submit(shortRequest("0803 555 0113"), "1.1.1.1", null);
        HotelApplication a = table.get(0);
        ApplyRequest v = shortRequest("+234 803 555 0113");
        Submission again = service.submit(new ApplyRequest(v.ownerName(), v.phone(), null, "QA Short Demo Two", "BAR", null, null, null, null,
                null, "Still waiting", null, null, null), "1.1.1.2", null);

        assertThat(again.outcome()).isEqualTo(Submission.RECEIVED);
        assertThat(table).hasSize(1);
        assertThat(timeline).singleElement().satisfies(n -> {
            assertThat(n.getApplicationId()).isEqualTo(a.getId());
            assertThat(n.getBody()).contains("Sent the Book a demo form again").contains("business: QA Short Demo Two").contains("type: Bar")
                    .contains("Still waiting").doesNotContain("monthly sales");
        });
        verify(deliveries, times(1)).send(any(Delivery.class));

        // A short request from a phone whose earlier request is closed is a new one.
        a.setStatus(HotelApplication.DECLINED);
        service.submit(shortRequest("0803 555 0113"), "1.1.1.3", null);
        assertThat(table).hasSize(2);
    }

    @Test
    void withNoEmailTheHourlyLimitCountsThePhone() {
        for (int i = 0; i < 3; i++) {
            service.submit(shortRequest("0803 555 0114"), "7.7.7." + i, null);
        }
        assertThat(service.submit(shortRequest("0803 555 0114"), "7.7.7.99", null).outcome()).isEqualTo(Submission.LIMITED);
        assertThat(service.submit(shortRequest("0803 555 0115"), "7.7.7.98", null).outcome()).isEqualTo(Submission.RECEIVED);
    }

    @Test
    void aFullRequestWithOnlyACityShowsItAloneInWhere() {
        ApplyRequest v = valid("city@hospio-qa.test");
        service.submit(new ApplyRequest(v.ownerName(), v.phone(), v.email(), v.businessName(), v.businessType(), "Ikeja", null, null, null, null,
                null, null, null, null), "1.1.1.1", null);
        ArgumentCaptor<Delivery> mail = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveries, times(2)).send(mail.capture());
        assertThat(mail.getAllValues().get(0).to()).isEqualTo("city@hospio-qa.test");
        assertThat(mail.getAllValues().get(0).text()).contains("Where: Ikeja\n").doesNotContain("null");
        assertThat(mail.getAllValues().get(1).subject()).isEqualTo("New demo request: QA Palm bSuites/b (Ikeja)");
    }

    @Test
    void roomsAreCheckedForAHotelAndDroppedForALounge() {
        ApplyRequest v = valid("bola@hospio-qa.test");
        Submission bad = service.submit(new ApplyRequest(v.ownerName(), v.phone(), v.email(), v.businessName(), "MIXED", v.city(), v.state(),
                9000, v.heardFrom(), null, null, null, null, v.monthlySales()), "1.1.1.1", null);
        assertThat(bad.errors()).containsOnlyKeys("rooms");

        service.submit(new ApplyRequest(v.ownerName(), v.phone(), "lounge@hospio-qa.test", "QA Blue Lounge", "LOUNGE", v.city(), v.state(), 30,
                "social", null, null, null, null, "UNDER_1M"), "1.1.1.2", null);
        assertThat(table.get(0).getRooms()).isNull();
        assertThat(table.get(0).getPreferredTime()).isNull();
    }

    @Test
    void aFilledHiddenFieldIsToldReceivedAndNothingIsKeptOrSent() {
        ApplyRequest v = valid("bot@hospio-qa.test");
        Submission s = service.submit(new ApplyRequest(v.ownerName(), v.phone(), v.email(), v.businessName(), v.businessType(), v.city(),
                v.state(), v.rooms(), v.heardFrom(), null, null, null, "http://spam", v.monthlySales()), "1.1.1.1", null);
        assertThat(s.outcome()).isEqualTo(Submission.RECEIVED);
        assertThat(table).isEmpty();
        verify(deliveries, never()).send(any(Delivery.class));
    }

    @Test
    void aRepeatFromAnOpenRequestsEmailBecomesANoteOnItAndNoSecondRecordOrEmail() {
        HotelApplication a = submitted();
        ApplyRequest v = valid("BOLA@hospio-qa.test");
        Submission again = service.submit(new ApplyRequest(v.ownerName(), "0905 555 0192", v.email(), v.businessName(), v.businessType(),
                v.city(), v.state(), v.rooms(), v.heardFrom(), null, "Please call today", null, null, "ABOVE_20M"), "1.1.1.9", null);

        assertThat(again.outcome()).isEqualTo(Submission.RECEIVED);
        assertThat(table).hasSize(1);
        assertThat(timeline).singleElement().satisfies(n -> {
            assertThat(n.getApplicationId()).isEqualTo(a.getId());
            assertThat(n.getKind()).isEqualTo("SYSTEM");
            assertThat(n.getAuthorName()).isEqualTo("Website");
            assertThat(n.getBody()).contains("Sent the Book a demo form again").contains("phone: +2349055550192").contains("monthly sales: Above ₦20 million")
                    .contains("Please call today");
        });
        verify(deliveries, times(2)).send(any(Delivery.class));
    }

    @Test
    void theSixthFromOneAddressAndTheFourthForOneEmailInAnHourAreRefused() {
        for (int i = 0; i < 5; i++) {
            assertThat(service.submit(valid("a" + i + "@hospio-qa.test"), "9.9.9.9", null).outcome()).isEqualTo(Submission.RECEIVED);
        }
        assertThat(service.submit(valid("a9@hospio-qa.test"), "9.9.9.9", null).outcome()).isEqualTo(Submission.LIMITED);

        for (int i = 0; i < 3; i++) {
            service.submit(valid("same@hospio-qa.test"), "8.8.8." + i, null);
        }
        assertThat(service.submit(valid("same@hospio-qa.test"), "8.8.8.99", null).outcome()).isEqualTo(Submission.LIMITED);
    }

    @Test
    void anActiveMarketersCodeRecordsTheMarketerAndAnInactiveOneIsKeptWithoutOne() {
        service.submit(withRef("ref@hospio-qa.test", " hm-ada01 "), "1.1.1.1", null);
        HotelApplication a = table.get(0);
        assertThat(a.getReferralCode()).isEqualTo("HM-ADA01");
        assertThat(a.getMarketerProfileId()).isEqualTo("mp-1");
        assertThat(a.getMarketerName()).isEqualTo("Ada Marketer");

        service.submit(withRef("old@hospio-qa.test", "HM-OLD01"), "1.1.1.2", null);
        assertThat(table.get(1).getReferralCode()).isEqualTo("HM-OLD01");
        assertThat(table.get(1).getMarketerProfileId()).isNull();

        service.submit(withRef("junk@hospio-qa.test", "<script>"), "1.1.1.3", null);
        assertThat(table.get(2).getReferralCode()).isNull();
    }

    // ---- the admin side ------------------------------------------------------------------------------

    @Test
    void supportMovesARequestThroughTheFlowAndEveryChangeIsOnTheTimelineAndInTheLog() {
        HotelApplication a = submitted();
        signIn(Role.SUPPORT);

        ApplicationView v = service.changeStatus(a.getId(), new StatusRequest("contacted", null, null, "Called, wants a demo Tuesday"));
        assertThat(v.status()).isEqualTo("CONTACTED");
        assertThat(v.notes()).extracting(n -> n.kind() + ":" + n.body())
                .containsExactly("STATUS:New → Contacted", "NOTE:Called, wants a demo Tuesday");
        assertThat(v.notes().get(0).authorName()).isEqualTo("Kola Support");

        assertThatThrownBy(() -> service.changeStatus(a.getId(), new StatusRequest("MEETING_BOOKED", null, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("meeting date and time");
        Instant meeting = Instant.parse("2026-11-24T09:00:00Z");
        v = service.changeStatus(a.getId(), new StatusRequest("MEETING_BOOKED", meeting, null, null));
        assertThat(v.status()).isEqualTo("MEETING_BOOKED");
        assertThat(v.meetingAt()).isEqualTo(meeting);
        assertThat(v.notes().get(2).body()).isEqualTo("Contacted → Meeting booked · meeting Tue 24 Nov 2026, 10:00 AM (Lagos)");

        v = service.changeStatus(a.getId(), new StatusRequest("APPROVED", null, null, null));
        assertThat(v.status()).isEqualTo("APPROVED");

        assertThatThrownBy(() -> service.changeStatus(a.getId(), new StatusRequest("ONBOARDED", null, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Start onboarding");
        assertThatThrownBy(() -> service.changeStatus(a.getId(), new StatusRequest("APPROVED", null, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already Approved");

        ArgumentCaptor<HotelApplicationEvent> logged = ArgumentCaptor.forClass(HotelApplicationEvent.class);
        verify(activity, times(3)).record(eq("hospio-hotel-application"), logged.capture());
        assertThat(logged.getAllValues()).extracting(HotelApplicationEvent::type)
                .containsExactly("HotelApplicationContacted", "HotelApplicationMeetingBooked", "HotelApplicationApproved");
        assertThat(logged.getAllValues().get(0).summary()).isEqualTo("Hotel application from QA Palm bSuites/b: New → Contacted");
    }

    @Test
    void linkingTheOnboardedBusinessMakesItOnboardedOnceAndForGood() {
        HotelApplication a = submitted();
        signIn(Role.SUPPORT);
        when(tenants.business("b-1")).thenReturn(Optional.of(business("b-1", "QA Palm Suites Ltd")));

        assertThatThrownBy(() -> service.link(a.getId(), "b-missing")).isInstanceOf(BadRequestException.class).hasMessage("No business has that id");

        ApplicationView v = service.link(a.getId(), "b-1");
        assertThat(v.status()).isEqualTo("ONBOARDED");
        assertThat(v.businessId()).isEqualTo("b-1");
        assertThat(v.linkedBusinessName()).isEqualTo("QA Palm Suites Ltd");
        assertThat(v.onboardedAt()).isNotNull();
        assertThat(v.notes()).extracting(n -> n.body()).containsExactly("New → Onboarded · business QA Palm Suites Ltd");
        ArgumentCaptor<HotelApplicationEvent> logged = ArgumentCaptor.forClass(HotelApplicationEvent.class);
        verify(activity).record(eq("hospio-hotel-application"), logged.capture());
        assertThat(logged.getValue().getBusinessId()).isEqualTo("b-1");

        // Linking the same business again is a no-op; the status can't move any more; the business can't serve a second application.
        assertThat(service.link(a.getId(), "b-1").status()).isEqualTo("ONBOARDED");
        assertThatThrownBy(() -> service.changeStatus(a.getId(), new StatusRequest("CONTACTED", null, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("onboarded");
        service.submit(valid("second@hospio-qa.test"), "2.2.2.2", null);
        HotelApplication second = table.get(1);
        assertThatThrownBy(() -> service.link(second.getId(), "b-1")).isInstanceOf(BadRequestException.class).hasMessageContaining("another application");
    }

    @Test
    void aDeclineNeedsAReasonCanBeReopenedAndADeclinedRequestCantBeLinked() {
        HotelApplication a = submitted();
        signIn(Role.SUPPORT);

        assertThatThrownBy(() -> service.changeStatus(a.getId(), new StatusRequest("DECLINED", null, " no ", null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Say why");
        ApplicationView v = service.changeStatus(a.getId(), new StatusRequest("DECLINED", null, "Outside our area for now", null));
        assertThat(v.status()).isEqualTo("DECLINED");
        assertThat(v.declineReason()).isEqualTo("Outside our area for now");
        assertThatThrownBy(() -> service.link(a.getId(), "b-1")).isInstanceOf(BadRequestException.class).hasMessageContaining("Reopen");
        // No email to the applicant on a decline: the team tells them on the phone.
        verify(deliveries, times(2)).send(any(Delivery.class));

        v = service.changeStatus(a.getId(), new StatusRequest("NEW", null, null, null));
        assertThat(v.status()).isEqualTo("NEW");
        assertThat(v.declineReason()).isNull();
    }

    @Test
    void financeMayReadButNotChangeAndSupportMayDecline() {
        HotelApplication a = submitted();
        signIn(Role.FINANCE_ADMIN);
        assertThat(service.permissions().canUpdate()).isFalse();
        assertThat(service.permissions().canDecline()).isFalse();
        assertThatThrownBy(() -> service.changeStatus(a.getId(), new StatusRequest("CONTACTED", null, null, null))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.changeStatus(a.getId(), new StatusRequest("DECLINED", null, "Not a fit today", null)))
                .isInstanceOf(ForbiddenException.class).hasMessageContaining("decline");
        assertThatThrownBy(() -> service.addNote(a.getId(), "hello")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.assign(a.getId(), "u-support")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.meeting(a.getId(), Instant.now())).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.link(a.getId(), "b-1")).isInstanceOf(ForbiddenException.class);

        signIn(Role.SUPPORT);
        assertThat(service.permissions().canDecline()).isTrue();
        signIn(Role.SUPER_ADMIN);
        assertThat(service.changeStatus(a.getId(), new StatusRequest("DECLINED", null, "Duplicate of another request", null)).status())
                .isEqualTo("DECLINED");
    }

    @Test
    void notesAreAppendOnlyAndTheAssigneeMustBeSupportOrASuperAdmin() {
        HotelApplication a = submitted();
        signIn(Role.SUPPORT);

        assertThatThrownBy(() -> service.addNote(a.getId(), "   ")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.addNote(a.getId(), "x".repeat(2001))).isInstanceOf(BadRequestException.class);
        service.addNote(a.getId(), "First call: no answer");
        ApplicationView v = service.addNote(a.getId(), "Second call <b>done</b>");
        assertThat(v.notes()).extracting(n -> n.body()).containsExactly("First call: no answer", "Second call bdone/b");

        assertThatThrownBy(() -> service.assign(a.getId(), "u-stranger")).isInstanceOf(BadRequestException.class);
        v = service.assign(a.getId(), "u-support");
        assertThat(v.assigneeId()).isEqualTo("u-support");
        assertThat(v.assigneeName()).isEqualTo("Kola");
        v = service.assign(a.getId(), "");
        assertThat(v.assigneeId()).isNull();
        assertThat(v.notes()).extracting(n -> n.body()).contains("Assigned to Kola", "Unassigned");

        Instant at = Instant.parse("2026-11-25T13:30:00Z");
        v = service.meeting(a.getId(), at);
        assertThat(v.meetingAt()).isEqualTo(at);
        service.changeStatus(a.getId(), new StatusRequest("MEETING_BOOKED", null, null, null));
        assertThatThrownBy(() -> service.meeting(a.getId(), null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void anUnknownStatusFilterIsRefused() {
        assertThatThrownBy(() -> service.list("LOST", null, null, 0, 25)).isInstanceOf(BadRequestException.class);
    }

    // ---- monthly sales -------------------------------------------------------------------------------

    private ApplyRequest withSales(String email, String sales) {
        ApplyRequest v = valid(email);
        return new ApplyRequest(v.ownerName(), v.phone(), v.email(), v.businessName(), v.businessType(), v.city(), v.state(), v.rooms(),
                v.heardFrom(), v.preferredTime(), v.message(), null, null, sales);
    }

    @Test
    void monthlySalesIsOptionalButWhenGivenMustBeOneOfTheFiveBands() {
        for (String missing : new String[] {"LOTS", "5000000", "NOT_ASKED"}) {
            Submission s = service.submit(withSales("none@hospio-qa.test", missing), "1.1.1.1", null);
            assertThat(s.outcome()).isEqualTo(Submission.INVALID);
            assertThat(s.errors()).containsOnlyKeys("monthlySales");
            assertThat(s.errors().get("monthlySales")).isEqualTo("Choose your average monthly sales (an estimate is fine)");
        }
        assertThat(table).isEmpty();
        verify(deliveries, never()).send(any(Delivery.class));

        String[] codes = {"UNDER_1M", "from_1m_to_5m", " FROM_5M_TO_20M ", "ABOVE_20M", "Prefer_Not"};
        for (int i = 0; i < codes.length; i++) {
            assertThat(service.submit(withSales("band" + i + "@hospio-qa.test", codes[i]), "2.2.2." + i, null).outcome()).isEqualTo(Submission.RECEIVED);
        }
        assertThat(table).extracting(HotelApplication::getMonthlySales)
                .containsExactly("UNDER_1M", "FROM_1M_TO_5M", "FROM_5M_TO_20M", "ABOVE_20M", "PREFER_NOT");

        // Left out (the short form): stored null, read "Not asked" like a request from before the question.
        String[] blanks = {null, "", "  "};
        for (int i = 0; i < blanks.length; i++) {
            assertThat(service.submit(withSales("blank" + i + "@hospio-qa.test", blanks[i]), "3.3.3." + i, null).outcome()).isEqualTo(Submission.RECEIVED);
        }
        assertThat(table.subList(5, 8)).extracting(HotelApplication::getMonthlySales).containsOnlyNulls();
    }

    @Test
    void theFiveBandsHaveTheirNairaWordsAndAnOldRequestReadsNotAsked() {
        assertThat(java.util.Arrays.stream(MonthlySales.values()).map(Enum::name))
                .containsExactly("UNDER_1M", "FROM_1M_TO_5M", "FROM_5M_TO_20M", "ABOVE_20M", "PREFER_NOT");
        assertThat(MonthlySales.UNDER_1M.label()).isEqualTo("Under ₦1 million");
        assertThat(MonthlySales.FROM_1M_TO_5M.label()).isEqualTo("₦1 million – ₦5 million");
        assertThat(MonthlySales.FROM_5M_TO_20M.label()).isEqualTo("₦5 million – ₦20 million");
        assertThat(MonthlySales.ABOVE_20M.label()).isEqualTo("Above ₦20 million");
        assertThat(MonthlySales.PREFER_NOT.label()).isEqualTo("Prefer not to say");
        assertThat(MonthlySales.labelOf(null)).isEqualTo("Not asked");

        HotelApplication old = HotelApplication.builder().ownerName("Old Ade").email("old@hospio-qa.test").phone("+2348035550192")
                .businessName("QA Old Inn").businessType("HOTEL").city("Ikeja").state("Lagos").heardFrom("SEARCH").build();
        old.setId("app-old");
        ApplicationView v = ApplicationView.of(old, List.of());
        assertThat(v.monthlySales()).isNull();
        assertThat(v.monthlySalesLabel()).isEqualTo("Not asked");
        // The support email for a request without a band (never sent today, but the words are ready).
        assertThat(service.teamEmail(old)).contains("Monthly sales (all outlets): Not asked");
    }

    @Test
    void theRankInSqlFollowsTheBandsHighestFirstWithPreferNotThenNotAskedLast() {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("WHEN '([A-Z0-9_]+)' THEN (\\d+)").matcher(MonthlySales.RANK_SQL);
        Map<String, Integer> sql = new java.util.LinkedHashMap<>();
        while (m.find()) {
            sql.put(m.group(1), Integer.parseInt(m.group(2)));
        }
        for (MonthlySales b : MonthlySales.values()) {
            assertThat(sql.get(b.name())).as(b.name()).isEqualTo(b.rank());
        }
        assertThat(sql).hasSize(MonthlySales.values().length);
        assertThat(MonthlySales.RANK_SQL).contains("ELSE " + MonthlySales.NOT_ASKED_RANK + " END");
        assertThat(java.util.Arrays.stream(MonthlySales.values()).sorted(java.util.Comparator.comparingInt(MonthlySales::rank)).map(Enum::name))
                .containsExactly("ABOVE_20M", "FROM_5M_TO_20M", "FROM_1M_TO_5M", "UNDER_1M", "PREFER_NOT");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theListSortsByBandHighestFirstOnRequestAndNewestFirstByDefault() {
        when(rows.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(org.springframework.data.domain.Page.empty());
        ArgumentCaptor<org.springframework.data.domain.Pageable> paging = ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);

        service.list(null, null, null, null, "sales", 0, 25);
        service.list(null, null, null, null, null, 0, 25);
        verify(rows, times(2)).findAll(any(org.springframework.data.jpa.domain.Specification.class), paging.capture());

        org.springframework.data.domain.Sort bySales = paging.getAllValues().get(0).getSort();
        assertThat(bySales.toList()).extracting(o -> o.getProperty() + " " + o.getDirection())
                .containsExactly("monthlySalesRank ASC", "createdAt DESC", "id ASC");
        assertThat(paging.getAllValues().get(1).getSort().toList()).extracting(o -> o.getProperty() + " " + o.getDirection())
                .containsExactly("createdAt DESC", "id ASC");
        assertThatThrownBy(() -> service.list(null, null, null, null, "BIGGEST", 0, 25)).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("NEWEST, SALES");
    }

    @Test
    void theBandFilterTakesTheFiveBandsAndNotAskedAndRefusesAnythingElse() {
        assertThat(HotelApplicationService.salesFilter(null)).isNull();
        assertThat(HotelApplicationService.salesFilter(" ")).isNull();
        assertThat(HotelApplicationService.salesFilter("above_20m")).isEqualTo("ABOVE_20M");
        assertThat(HotelApplicationService.salesFilter("PREFER_NOT")).isEqualTo("PREFER_NOT");
        assertThat(HotelApplicationService.salesFilter("not_asked")).isEqualTo("NOT_ASKED");
        assertThatThrownBy(() -> HotelApplicationService.salesFilter("RICH")).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("NOT_ASKED");
        assertThatThrownBy(() -> service.list(null, null, null, "RICH", null, 0, 25)).isInstanceOf(BadRequestException.class);
    }

    private static TenantClient.Business business(String id, String name) {
        return new TenantClient.Business(id, name, "Ikeja", "ONBOARDING", "BACK_OFFICE", null, "Bola Ade", "bola@hospio-qa.test", null,
                "PERCENT_OF_SALES", null, null, null, null, Instant.now(), 0, List.of());
    }
}
