package com.hospio.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.billing.BillingHold;
import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.suspension.BusinessSuspension;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.MonthlyInvoice;
import com.hospio.platform.entity.SuspensionNotice;
import com.hospio.platform.repository.MonthlyInvoiceRepository;
import com.hospio.platform.repository.SuspensionNoticeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** docs/suspension.md: the notice, who may suspend when, what lifts a suspension by itself and what never does. */
class SuspensionServiceTest {

    static final LocalDate TODAY = LocalDate.now(MonthlyInvoiceService.LAGOS);

    MonthlyInvoiceRepository invoices = mock(MonthlyInvoiceRepository.class);
    SuspensionNoticeRepository notices = mock(SuspensionNoticeRepository.class);
    MonthlyInvoiceService billing = mock(MonthlyInvoiceService.class);
    TenantClient tenant = mock(TenantClient.class);
    DeliveryService deliveries = mock(DeliveryService.class);
    ChangeService changes = mock(ChangeService.class);
    BusinessSuspension cache = mock(BusinessSuspension.class);
    SuspensionService service;

    static final TenantClient.Business B = new TenantClient.Business("b1", "QA Owner B Hotels", "Lagos", "LIVE", "BACK_OFFICE", "o1", "Bola Ade",
            "owner@hospio-qa.test", null, "PERCENT_OF_SALES", new BigDecimal("1.5"), null, null, null, Instant.now(), 1, List.of());

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        service = new SuspensionService(invoices, notices, billing, tenant, deliveries, mock(ObjectProvider.class), changes, mock(BillingHold.class), cache,
                "https://web-staging.hospio360.com", true);
        when(tenant.business("b1")).thenReturn(Optional.of(B));
        when(billing.billable(any(TenantClient.Business.class))).thenReturn(true);
        when(notices.save(any())).thenAnswer(a -> {
            SuspensionNotice n = a.getArgument(0);
            n.setId("n1");
            return n;
        });
        when(notices.findFirstByBusinessIdOrderByCreatedAtDesc("b1")).thenReturn(Optional.empty());
        stage("LIVE", null);
        signIn(Role.SUPER_ADMIN);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(Role role) {
        HospioUserDetails admin = new HospioUserDetails("a1", "admin@hospio360.com", "Amoria", null, List.of(), Map.of(role, AccessLevel.FULL), JwtUtil.SCOPE_PLATFORM);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
    }

    private void stage(String stage, String type) {
        when(tenant.suspensionDetail("b1")).thenReturn(detail(stage, type));
    }

    private static TenantClient.SuspensionDetail detail(String stage, String type) {
        boolean suspended = "SUSPENDED".equals(stage);
        return new TenantClient.SuspensionDetail("b1", "QA Owner B Hotels", stage, suspended ? Instant.now() : null, suspended ? "Unpaid invoices" : null,
                type, false, null, suspended ? "LIVE" : null, suspended ? "Amoria" : null);
    }

    /** One open invoice due {@code daysOverdue} days ago. */
    private void overdue(int... daysOverdue) {
        List<MonthlyInvoice> open = new java.util.ArrayList<>();
        int n = 1;
        for (int d : daysOverdue) {
            MonthlyInvoice i = MonthlyInvoice.builder().businessId("b1").businessName("QA Owner B Hotels").number("HIN-202610-000" + n).month("2026-10")
                    .issuedOn(TODAY.minusDays(d + 7)).dueOn(TODAY.minusDays(d)).graceUntil(TODAY.minusDays(d - 3)).total(new BigDecimal("3000.00"))
                    .paidAmount(BigDecimal.ZERO).status(MonthlyInvoice.OVERDUE).build();
            i.setId("i" + n++);
            open.add(i);
        }
        when(invoices.findByBusinessIdAndStatusIn(eq("b1"), anyList())).thenReturn(open);
        when(invoices.findByStatusIn(anyList())).thenReturn(open);
    }

    @Test
    void aBusinessIsACandidateFromThirtyDaysOverdue() {
        overdue(29);
        assertThat(service.candidates()).isEmpty();
        assertThat(service.state("b1").candidate()).isFalse();
        overdue(30, 2);
        List<SuspensionService.Candidate> c = service.candidates();
        assertThat(c).hasSize(1);
        assertThat(c.get(0).oldestDaysOverdue()).isEqualTo(30);
        assertThat(c.get(0).overdue()).hasSize(2);
        assertThat(c.get(0).overdueTotal()).isEqualByComparingTo("6000.00");
        assertThat(service.state("b1").candidate()).isTrue();
    }

    @Test
    void theNoticeEmailsTheOwnerNamesTheInvoicesAndRecordsTheDate() {
        overdue(31);
        service.sendNotice("b1", null);
        ArgumentCaptor<SuspensionNotice> saved = ArgumentCaptor.forClass(SuspensionNotice.class);
        verify(notices).save(saved.capture());
        assertThat(saved.getValue().getSentOn()).isEqualTo(TODAY);
        assertThat(saved.getValue().getSuspendOn()).isEqualTo(TODAY.plusDays(7));
        assertThat(saved.getValue().getInvoiceNumbers()).isEqualTo("HIN-202610-0001");
        assertThat(saved.getValue().getOutcome()).isEqualTo("EMAILED");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Delivery>> mail = ArgumentCaptor.forClass(List.class);
        verify(deliveries).send(mail.capture());
        Delivery d = mail.getValue().get(0);
        assertThat(d.to()).isEqualTo("owner@hospio-qa.test");
        assertThat(d.channel()).isEqualTo(Delivery.Channel.EMAIL);
        assertThat(d.subject()).contains("will be suspended on").contains("HIN-202610-0001");
        assertThat(d.text()).contains("7 days' notice").contains("clause 7.3").contains("NGN 3,000.00").contains("hello@oyeksglobal.com")
                .contains("/dashboard/billing");
        verify(changes).recordAs(any(PlatformRequests.PlatformChangeRequest.class), eq("a1"), eq("Amoria"));
    }

    @Test
    void aNoticeNeedsThirtyDaysOverdueAndSevenDaysAhead() {
        overdue(12);
        assertThatThrownBy(() -> service.sendNotice("b1", null)).isInstanceOf(BadRequestException.class).hasMessageContaining("30 days");
        overdue(40);
        assertThatThrownBy(() -> service.sendNotice("b1", new SuspensionService.NoticeRequest(TODAY.plusDays(3))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("at least 7 days' notice");
    }

    @Test
    void aFinanceAdminSuspendsOnlyByTheTermsASuperAdminIsWarned() {
        overdue(40);
        when(tenant.suspend(any(), any(), any(), anyBoolean(), any(), any(), any())).thenReturn(detail("SUSPENDED", "UNPAID_INVOICE"));
        signIn(Role.FINANCE_ADMIN);
        // No notice recorded, or one sent 2 days ago: refused for finance.
        assertThatThrownBy(() -> service.suspend("b1", new SuspensionService.SuspendRequest("UNPAID_INVOICE", "Invoice unpaid 40 days", false, false, null)))
                .isInstanceOf(ForbiddenException.class).hasMessageContaining("Only a super admin");
        assertThatThrownBy(() -> service.suspend("b1", new SuspensionService.SuspendRequest("UNPAID_INVOICE", "Invoice unpaid 40 days", false, true, TODAY.minusDays(2))))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.suspend("b1", new SuspensionService.SuspendRequest("OTHER", "Fraud reported by a guest", false, null, null)))
                .isInstanceOf(ForbiddenException.class).hasMessageContaining("other than unpaid invoices");
        verify(tenant, never()).suspend(any(), any(), any(), anyBoolean(), any(), any(), any());
        // By the Terms: fine, no warnings.
        SuspensionService.State ok = service.suspend("b1", new SuspensionService.SuspendRequest("UNPAID_INVOICE", "Invoice unpaid 40 days", true, true, TODAY.minusDays(7)));
        assertThat(ok.warnings()).isEmpty();
        verify(tenant).suspend("b1", "UNPAID_INVOICE", "Invoice unpaid 40 days", true, TODAY.minusDays(7), "a1", "Amoria");
        verify(cache).forget("b1");

        signIn(Role.SUPER_ADMIN);
        SuspensionService.State warned = service.suspend("b1", new SuspensionService.SuspendRequest("UNPAID_INVOICE", "Invoice unpaid 40 days", false, false, null));
        assertThat(warned.warnings()).anyMatch(w -> w.contains("No 7 days' notice"));
    }

    @Test
    void suspendingNeedsATypeANoteAndForUnpaidInvoiceSomethingOverdue() {
        overdue();
        assertThatThrownBy(() -> service.suspend("b1", new SuspensionService.SuspendRequest(null, "A note that is long", false, null, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.suspend("b1", new SuspensionService.SuspendRequest("OTHER", "short", false, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("at least 8 characters");
        assertThatThrownBy(() -> service.suspend("b1", new SuspensionService.SuspendRequest("UNPAID_INVOICE", "Nothing is owed here", false, true, TODAY.minusDays(9))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("no overdue invoice");
    }

    @Test
    void stagingNeverSuspendsABusinessThatIsNotQa() {
        when(billing.billable(any(TenantClient.Business.class))).thenReturn(false);
        overdue(40);
        assertThatThrownBy(() -> service.suspend("b1", new SuspensionService.SuspendRequest("OTHER", "Should never happen here", false, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Staging only suspends QA businesses");
        assertThatThrownBy(() -> service.sendNotice("b1", null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void payingEveryOverdueInvoiceLiftsAnUnpaidInvoiceSuspensionOnly() {
        when(tenant.reactivate("b1")).thenReturn(detail("LIVE", null));
        // Still one overdue: stays suspended.
        stage("SUSPENDED", "UNPAID_INVOICE");
        overdue(35);
        assertThat(service.afterPayment("b1")).isFalse();
        // Suspended for another reason: paying never lifts it.
        stage("SUSPENDED", "OTHER");
        overdue();
        assertThat(service.afterPayment("b1")).isFalse();
        verify(tenant, never()).reactivate(any());
        // Unpaid invoice and nothing overdue left: back by itself, the owner is told, the change is recorded as automatic.
        stage("SUSPENDED", "UNPAID_INVOICE");
        assertThat(service.afterPayment("b1")).isTrue();
        verify(tenant).reactivate("b1");
        verify(cache).forget("b1");
        verify(deliveries).send(anyList());
        verify(changes).recordAs(any(PlatformRequests.PlatformChangeRequest.class), eq("system"), eq("automatic"));
        // Not suspended: nothing to do.
        stage("LIVE", null);
        assertThat(service.afterPayment("b1")).isFalse();
    }

    @Test
    void onlyASuperAdminReactivatesASuspensionForAnotherReason() {
        when(tenant.reactivate("b1")).thenReturn(detail("LIVE", null));
        stage("SUSPENDED", "OTHER");
        signIn(Role.FINANCE_ADMIN);
        assertThatThrownBy(() -> service.reactivate("b1", new SuspensionService.ReactivateRequest("Owner settled the matter")))
                .isInstanceOf(ForbiddenException.class);
        signIn(Role.SUPER_ADMIN);
        assertThatThrownBy(() -> service.reactivate("b1", new SuspensionService.ReactivateRequest("ok"))).isInstanceOf(BadRequestException.class);
        assertThat(service.reactivate("b1", new SuspensionService.ReactivateRequest("Owner settled the matter")).stage()).isEqualTo("LIVE");
        stage("LIVE", null);
        assertThatThrownBy(() -> service.reactivate("b1", new SuspensionService.ReactivateRequest("Owner settled the matter")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("isn't suspended");
    }
}
