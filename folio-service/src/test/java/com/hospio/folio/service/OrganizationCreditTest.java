package com.hospio.folio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalRequestedEvent;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.folio.config.ApprovalLimits;
import com.hospio.folio.dto.OrganizationRequest;
import com.hospio.folio.entity.Organization;
import com.hospio.folio.repository.FolioRepository;
import com.hospio.folio.repository.InvoiceRepository;
import com.hospio.folio.repository.OrganizationMemberRepository;
import com.hospio.folio.repository.OrganizationPaymentRepository;
import com.hospio.folio.repository.OrganizationRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** Owner portal · Financials: a credit limit over the owner's threshold waits for the owner; the answer applies it or not. */
class OrganizationCreditTest {

    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final ApprovalLimits limits = mock(ApprovalLimits.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final OrganizationService service = new OrganizationService(organizations, mock(OrganizationMemberRepository.class),
            mock(FolioRepository.class), mock(InvoiceRepository.class), mock(OrganizationPaymentRepository.class), limits, events);
    private Organization shell;

    @BeforeEach
    void setUp() {
        when(limits.getOrgCreditOwnerLimit()).thenReturn(new BigDecimal("1000000"));
        shell = Organization.builder().name("Shell Nigeria").creditLimit(new BigDecimal("800000")).build();
        shell.setId("org1");
        when(organizations.findById("org1")).thenReturn(Optional.of(shell));
        when(organizations.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signInAs(Role role) {
        HospioUserDetails u = new HospioUserDetails("u-" + role, "x@x.ng", role == Role.OWNER ? "Godwin Kwin" : "Tunde Bakare", "b1",
                List.of("h1"), Map.of(role, AccessLevel.FULL), role == Role.OWNER ? JwtUtil.SCOPE_BUSINESS : JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, u.getAuthorities()));
    }

    private static OrganizationRequest limit(String amount) {
        return new OrganizationRequest("Shell Nigeria", "Ada", null, null, null, new BigDecimal(amount), null, null, null, "Renewed");
    }

    @Test
    void aManagerAskingOverTheThresholdSavesTheRestAndSendsTheLimitToTheOwner() {
        signInAs(Role.MANAGER);
        Organization saved = service.save("org1", limit("1500000"));

        assertThat(saved.getCreditLimit()).isEqualByComparingTo("800000");
        assertThat(saved.getRequestedCreditLimit()).isEqualByComparingTo("1500000");
        assertThat(saved.getCreditApprovalId()).isNotNull();
        assertThat(saved.getAgreementNotes()).isEqualTo("Renewed");
        assertThat(saved.isCreditSentToOwner()).isTrue();
        ArgumentCaptor<ApprovalRequestedEvent> asked = ArgumentCaptor.forClass(ApprovalRequestedEvent.class);
        verify(events).publish(eq(KafkaTopics.APPROVAL_REQUESTED), asked.capture());
        assertThat(asked.getValue().getKind()).isEqualTo("CREDIT_LIMIT");
        assertThat(asked.getValue().getApproverLevel()).isEqualTo("OWNER");
        assertThat(asked.getValue().getSourceModule()).isEqualTo("FOLIO");
        assertThat(asked.getValue().getDescription()).isEqualTo("Shell Nigeria · credit limit ₦800,000 → ₦1,500,000");
    }

    @Test
    void aSecondDifferentRequestWaitsForTheFirstAnswer() {
        signInAs(Role.MANAGER);
        service.save("org1", limit("1500000"));
        assertThatThrownBy(() -> service.save("org1", limit("2000000"))).isInstanceOf(BadRequestException.class)
                .hasMessage("A credit limit of ₦1,500,000 for Shell Nigeria is already waiting for the owner. Wait for their answer first");
    }

    @Test
    void underTheThresholdOrByTheOwnerItAppliesAtOnce() {
        signInAs(Role.MANAGER);
        assertThat(service.save("org1", limit("900000")).getCreditLimit()).isEqualByComparingTo("900000");
        signInAs(Role.OWNER);
        assertThat(service.save("org1", limit("5000000")).getCreditLimit()).isEqualByComparingTo("5000000");
        verify(events, never()).publish(eq(KafkaTopics.APPROVAL_REQUESTED), any());
    }

    @Test
    void theOwnersAnswerAppliesOrKeepsTheOldLimit() {
        signInAs(Role.MANAGER);
        service.save("org1", limit("1500000"));
        String approval = shell.getCreditApprovalId();
        when(organizations.findByCreditApprovalId(approval)).thenReturn(Optional.of(shell));

        service.applyCreditDecision(approval, "QUESTION");
        assertThat(shell.getRequestedCreditLimit()).isEqualByComparingTo("1500000");

        service.applyCreditDecision(approval, "APPROVED");
        assertThat(shell.getCreditLimit()).isEqualByComparingTo("1500000");
        assertThat(shell.getCreditApprovalId()).isNull();
        assertThat(shell.getRequestedCreditLimit()).isNull();
    }

    @Test
    void declinedKeepsTheLimitAndNegativeIsRefused() {
        signInAs(Role.MANAGER);
        service.save("org1", limit("1500000"));
        String approval = shell.getCreditApprovalId();
        when(organizations.findByCreditApprovalId(approval)).thenReturn(Optional.of(shell));
        service.applyCreditDecision(approval, "DECLINED");
        assertThat(shell.getCreditLimit()).isEqualByComparingTo("800000");
        assertThat(shell.getCreditApprovalId()).isNull();
        assertThatThrownBy(() -> service.save("org1", limit("-5"))).isInstanceOf(BadRequestException.class)
                .hasMessage("A credit limit can't be negative");
    }
}
