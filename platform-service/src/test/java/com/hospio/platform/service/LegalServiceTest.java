package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.platform.entity.LegalAcceptance;
import com.hospio.platform.entity.LegalDocument;
import com.hospio.platform.entity.MarketerProfile;
import com.hospio.platform.repository.LegalAcceptanceRepository;
import com.hospio.platform.repository.LegalDocumentRepository;
import com.hospio.platform.repository.MarketerProfileRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.util.ReflectionTestUtils;

class LegalServiceTest {

    private final LegalDocumentRepository documents = mock(LegalDocumentRepository.class);
    private final LegalAcceptanceRepository acceptances = mock(LegalAcceptanceRepository.class);
    private final MarketerProfileRepository profiles = mock(MarketerProfileRepository.class);
    private final BusinessNames names = mock(BusinessNames.class);
    private final FeeService fees = mock(FeeService.class);
    private final LegalService service = new LegalService(documents, acceptances, profiles, names, fees);
    private final List<LegalDocument> docs = new ArrayList<>();
    private static final Instant PAST = Instant.parse("2026-11-17T23:00:00Z");

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "siteUrl", "https://hospio360.com");
        ReflectionTestUtils.setField(service, "materialNoticeDays", 30);
        for (String[] d : new String[][]{{"TERMS", "TOS-2026-11", "terms"}, {"PRIVACY", "PRIV-2026-11", "privacy"}, {"DPA", "DPA-2026-11", "data-processing"},
                {"DEBIT", "FEES-2026-11", "fees"}, {"DEVICES", "DEV-2026-11", "devices"}, {"RPA", "RPA-2026-11", "referral-partners"},
                {"STAFF", "STAFF-2026-11", "staff-terms"}}) {
            docs.add(doc(d[0], d[1], d[2], PAST));
        }
        when(documents.findAllByOrderByDocKeyAscEffectiveFromDescCreatedAtDesc()).thenAnswer(inv -> sorted());
        when(acceptances.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(names.name("b1")).thenReturn("Royal Suites");
        when(fees.defaultSalesPct()).thenReturn(new BigDecimal("1.5"));
        when(fees.dailySalesPct("b1")).thenReturn(new BigDecimal("1.50"));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private List<LegalDocument> sorted() {
        List<LegalDocument> out = new ArrayList<>(docs);
        out.sort((a, b) -> a.getDocKey().equals(b.getDocKey()) ? b.getEffectiveFrom().compareTo(a.getEffectiveFrom()) : a.getDocKey().compareTo(b.getDocKey()));
        return out;
    }

    private static LegalDocument doc(String key, String version, String slug, Instant from) {
        return LegalDocument.builder().docKey(key).version(version).title(key).slug(slug).effectiveFrom(from).build();
    }

    private static HospioUserDetails signIn(String id, Map<Role, AccessLevel> roles, String business, String scope, String actedBy) {
        HospioUserDetails u = new HospioUserDetails(id, id + "@hospio-qa.test", "QA " + id, business, List.of("h1"), roles, scope, actedBy);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
        return u;
    }

    private static HospioUserDetails owner() {
        return signIn("o1", Map.of(Role.OWNER, AccessLevel.FULL), "b1", JwtUtil.SCOPE_BUSINESS, null);
    }

    @Test
    void whoAcceptsWhat() {
        assertThat(LegalService.requiredKeys(owner())).containsExactly("TERMS", "PRIVACY", "DPA", "DEBIT");
        assertThat(LegalService.requiredKeys(signIn("s1", Map.of(Role.BAR, AccessLevel.VIEW), "b1", JwtUtil.SCOPE_HOTEL, null))).containsExactly("STAFF");
        assertThat(LegalService.requiredKeys(signIn("m1", Map.of(Role.MARKETER, AccessLevel.FULL), null, JwtUtil.SCOPE_MARKETER, null))).containsExactly("RPA");
        assertThat(LegalService.requiredKeys(signIn("a1", Map.of(Role.SUPER_ADMIN, AccessLevel.FULL), null, JwtUtil.SCOPE_PLATFORM, null))).isEmpty();
    }

    @Test
    void anOwnerWhoAcceptedNothingHasFourPending() {
        owner();
        when(acceptances.findByUserIdAndEventOrderByCreatedAtDesc("o1", "ACCEPTED")).thenReturn(List.of());
        LegalService.MyLegal me = service.me();
        assertThat(me.pending()).extracting(LegalService.DocView::key).containsExactly("TERMS", "PRIVACY", "DPA", "DEBIT");
        assertThat(me.pending().get(0).url()).isEqualTo("https://hospio360.com/legal/terms");
        assertThat(me.canAccept()).isTrue();
    }

    @Test
    void aNewVersionInForceMakesItPendingAgainButOneAnnouncedForLaterDoesNot() {
        owner();
        List<LegalAcceptance> mine = new ArrayList<>();
        for (String[] a : new String[][]{{"TERMS", "TOS-2026-11"}, {"PRIVACY", "PRIV-2026-11"}, {"DPA", "DPA-2026-11"}, {"DEBIT", "FEES-2026-11"}}) {
            mine.add(LegalAcceptance.builder().event("ACCEPTED").docKey(a[0]).version(a[1]).userId("o1").build());
        }
        when(acceptances.findByUserIdAndEventOrderByCreatedAtDesc("o1", "ACCEPTED")).thenReturn(mine);
        assertThat(service.me().pending()).isEmpty();

        docs.add(doc("TERMS", "TOS-2026-11b", "terms", Instant.now().minusSeconds(5)));
        docs.add(doc("PRIVACY", "PRIV-2027-01", "privacy", Instant.now().plus(40, ChronoUnit.DAYS)));
        LegalService.MyLegal me = service.me();
        assertThat(me.pending()).extracting(LegalService.DocView::version).containsExactly("TOS-2026-11b");
        assertThat(me.upcoming()).extracting(LegalService.DocView::version).containsExactly("PRIV-2027-01");
    }

    @Test
    void acceptRecordsEachDocumentWithHowAndSkipsWhatIsAlreadyAccepted() {
        owner();
        when(acceptances.existsByUserIdAndDocKeyAndVersionAndEvent("o1", "TERMS", "TOS-2026-11", "ACCEPTED")).thenReturn(true);
        service.accept(new LegalService.AcceptRequest(List.of(new LegalService.AcceptItem("TERMS", "TOS-2026-11"),
                new LegalService.AcceptItem("DEBIT", "FEES-2026-11")), "SELF_SIGN_UP"));
        ArgumentCaptor<LegalAcceptance> saved = ArgumentCaptor.forClass(LegalAcceptance.class);
        verify(acceptances, times(1)).save(saved.capture());
        LegalAcceptance a = saved.getValue();
        assertThat(a.getDocKey()).isEqualTo("DEBIT");
        assertThat(a.getVersion()).isEqualTo("FEES-2026-11");
        assertThat(a.getMethod()).isEqualTo("SELF_SIGN_UP");
        assertThat(a.getEvent()).isEqualTo("ACCEPTED");
        assertThat(a.getRole()).isEqualTo("OWNER");
        assertThat(a.getBusinessName()).isEqualTo("Royal Suites");
        assertThat(a.getUserName()).isEqualTo("QA o1");
    }

    @Test
    void withoutAMethodItIsFirstSignInThenReAcceptance() {
        signIn("s1", Map.of(Role.BAR, AccessLevel.FULL), "b1", JwtUtil.SCOPE_HOTEL, null);
        service.accept(new LegalService.AcceptRequest(List.of(new LegalService.AcceptItem("STAFF", "STAFF-2026-11")), null));
        when(acceptances.existsByUserIdAndDocKeyAndEvent("s1", "STAFF", "ACCEPTED")).thenReturn(true);
        docs.add(doc("STAFF", "STAFF-2027-01", "staff-terms", Instant.now().minusSeconds(1)));
        service.accept(new LegalService.AcceptRequest(List.of(new LegalService.AcceptItem("STAFF", "STAFF-2027-01")), null));
        ArgumentCaptor<LegalAcceptance> saved = ArgumentCaptor.forClass(LegalAcceptance.class);
        verify(acceptances, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(LegalAcceptance::getMethod).containsExactly("FIRST_SIGN_IN", "RE_ACCEPTANCE");
        assertThat(saved.getAllValues().get(0).getHotelId()).isEqualTo("h1");
    }

    @Test
    void anOldVersionADocumentNotOneOfMineOrSupportViewingAsTheOwnerIsRefused() {
        owner();
        assertThatThrownBy(() -> service.accept(new LegalService.AcceptRequest(List.of(new LegalService.AcceptItem("TERMS", "TOS-2025-01")), null)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.accept(new LegalService.AcceptRequest(List.of(new LegalService.AcceptItem("STAFF", "STAFF-2026-11")), null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.accept(new LegalService.AcceptRequest(List.of(new LegalService.AcceptItem("TERMS", "TOS-2026-11")), "BACK_OFFICE")))
                .isInstanceOf(BadRequestException.class);
        signIn("o1", Map.of(Role.OWNER, AccessLevel.VIEW), "b1", JwtUtil.SCOPE_BUSINESS, "admin-1");
        assertThatThrownBy(() -> service.accept(new LegalService.AcceptRequest(List.of(new LegalService.AcceptItem("TERMS", "TOS-2026-11")), null)))
                .isInstanceOf(ForbiddenException.class);
        verify(acceptances, never()).save(any());
    }

    @Test
    void aMarketerAcceptingTheReferralTermsKeepsTheVersionOnTheProfile() {
        signIn("m1", Map.of(Role.MARKETER, AccessLevel.FULL), null, JwtUtil.SCOPE_MARKETER, null);
        MarketerProfile p = MarketerProfile.builder().userId("m1").status("ACTIVE").build();
        when(profiles.findByUserId("m1")).thenReturn(Optional.of(p));
        service.accept(new LegalService.AcceptRequest(List.of(new LegalService.AcceptItem("RPA", "RPA-2026-11")), "PORTAL"));
        assertThat(p.getAgreementVersion()).isEqualTo("RPA-2026-11");
        assertThat(p.getAgreementAcceptedBy()).isEqualTo("m1");
        verify(profiles).save(p);
    }

    @Test
    void linksSharedNeedsAChannelAndWritesOneNoteForEachOwnerDocument() {
        signIn("a1", Map.of(Role.SUPER_ADMIN, AccessLevel.FULL), null, JwtUtil.SCOPE_PLATFORM, null);
        assertThatThrownBy(() -> service.linksShared("b1", new LegalService.LinksSharedRequest("CARRIER_PIGEON", null, null)))
                .isInstanceOf(BadRequestException.class);
        List<LegalService.RecordView> out = service.linksShared("b1", new LegalService.LinksSharedRequest("whatsapp", LocalDate.now(ZoneId.of("Africa/Lagos")), null));
        assertThat(out).hasSize(4).allSatisfy(r -> {
            assertThat(r.event()).isEqualTo("LINKS_SHARED");
            assertThat(r.channel()).isEqualTo("WHATSAPP");
            assertThat(r.userId()).isNull();
        });
    }

    @Test
    void aMaterialChangeNeedsThirtyDaysNotice() {
        signIn("a1", Map.of(Role.SUPER_ADMIN, AccessLevel.FULL), null, JwtUtil.SCOPE_PLATFORM, null);
        when(documents.findByDocKeyAndVersion(anyString(), anyString())).thenReturn(Optional.empty());
        when(documents.save(any())).thenAnswer(inv -> inv.getArgument(0));
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
        assertThatThrownBy(() -> service.publish(new LegalService.PublishRequest("TERMS", "TOS-2027-01", null, today.plusDays(10), true, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("30 days");
        LegalDocument d = service.publish(new LegalService.PublishRequest("terms", "TOS-2027-01", null, today.plusDays(30), true, "Clause 5"));
        assertThat(d.getSlug()).isEqualTo("terms");
        assertThat(d.isMaterial()).isTrue();
        LegalDocument now = service.publish(new LegalService.PublishRequest("STAFF", "STAFF-2026-11b", null, null, false, "Typo"));
        assertThat(now.getEffectiveFrom()).isBeforeOrEqualTo(Instant.now());
        when(documents.findByDocKeyAndVersion(eq("STAFF"), eq("STAFF-2026-11"))).thenReturn(Optional.of(docs.get(0)));
        assertThatThrownBy(() -> service.publish(new LegalService.PublishRequest("STAFF", "STAFF-2026-11", null, null, false, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void csvCellsAreQuotedAndFormulasDefused() {
        assertThat(LegalService.cell("Ada, Lagos")).isEqualTo("\"Ada, Lagos\"");
        assertThat(LegalService.cell("=HYPERLINK(1)")).isEqualTo("'=HYPERLINK(1)");
        assertThat(LegalService.cell(null)).isEmpty();
    }

    @Test
    void theDebitBoxStatesTheBusinessesOwnRateAndTheRecordKeepsIt() {
        owner();
        when(fees.dailySalesPct("b1")).thenReturn(new BigDecimal("1.200"));
        when(acceptances.findByUserIdAndEventOrderByCreatedAtDesc("o1", "ACCEPTED")).thenReturn(List.of());
        LegalService.DocView debit = service.me().pending().stream().filter(d -> d.key().equals("DEBIT")).findFirst().orElseThrow();
        assertThat(debit.ratePct()).isEqualByComparingTo("1.2");
        assertThat(service.me().pending().get(0).ratePct()).isNull();

        service.accept(new LegalService.AcceptRequest(List.of(new LegalService.AcceptItem("DEBIT", "FEES-2026-11", new BigDecimal("1.2"))), "HANDOVER"));
        ArgumentCaptor<LegalAcceptance> saved = ArgumentCaptor.forClass(LegalAcceptance.class);
        verify(acceptances).save(saved.capture());
        assertThat(saved.getValue().getRatePct()).isEqualByComparingTo("1.2");
        assertThat(LegalService.title("DEBIT", saved.getValue().getRatePct())).isEqualTo("Daily debit authority (1.2%)");
        assertThat(LegalService.title("DEBIT", null)).isEqualTo("Daily debit authority (1.5%)");
    }

    @Test
    void aDebitBoxThatShowedAnotherRateOrNoneForABusinessOffTheDefaultIsRefused() {
        owner();
        when(fees.dailySalesPct("b1")).thenReturn(new BigDecimal("1.2"));
        // An older client never sends the rate: its box said 1.5%.
        assertThatThrownBy(() -> service.accept(new LegalService.AcceptRequest(List.of(new LegalService.AcceptItem("DEBIT", "FEES-2026-11")), null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("1.2% of daily sales, not 1.5%");
        assertThatThrownBy(() -> service.accept(new LegalService.AcceptRequest(
                List.of(new LegalService.AcceptItem("DEBIT", "FEES-2026-11", new BigDecimal("2"))), null))).isInstanceOf(ConflictException.class);
        when(fees.dailySalesPct("b1")).thenReturn(null);
        assertThatThrownBy(() -> service.accept(new LegalService.AcceptRequest(
                List.of(new LegalService.AcceptItem("DEBIT", "FEES-2026-11", new BigDecimal("1.5"))), null))).isInstanceOf(ConflictException.class)
                .hasMessageContaining("could not be checked");
        verify(acceptances, never()).save(any());
    }

    @Test
    void atTheDefaultTheBoxSaysTheDefaultAndAnOldClientStillWorks() {
        owner();
        service.accept(new LegalService.AcceptRequest(List.of(new LegalService.AcceptItem("DEBIT", "FEES-2026-11")), "SELF_SIGN_UP"));
        ArgumentCaptor<LegalAcceptance> saved = ArgumentCaptor.forClass(LegalAcceptance.class);
        verify(acceptances).save(saved.capture());
        assertThat(saved.getValue().getRatePct()).isEqualByComparingTo("1.5");
        LegalService.DocView pub = service.publicDocuments().get("current").stream().filter(d -> d.key().equals("DEBIT")).findFirst().orElseThrow();
        assertThat(pub.ratePct()).isEqualByComparingTo("1.5");
    }
}
