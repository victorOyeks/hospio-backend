package com.hospio.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.common.enums.AccessLevel;
import com.hospio.common.enums.PaymentMethod;
import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.security.JwtUtil;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.payment.client.HotelConfigClient;
import com.hospio.payment.dto.RefundRequestDto;
import com.hospio.payment.entity.PaymentRequest;
import com.hospio.payment.entity.Refund;
import com.hospio.payment.repository.DisputeRepository;
import com.hospio.payment.repository.PaymentRequestRepository;
import com.hospio.payment.repository.RefundRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Refunds are decided once: the payment is locked while refunds are summed, the refund while it is approved or declined. */
class RefundRaceTest {

    private final RefundRepository refunds = mock(RefundRepository.class);
    private final PaymentRequestRepository payments = mock(PaymentRequestRepository.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final HotelConfigClient hotelConfig = mock(HotelConfigClient.class);
    private final RefundService service = new RefundService(refunds, mock(DisputeRepository.class), payments, events, hotelConfig,
            new TransactionTemplate(mock(PlatformTransactionManager.class)));

    @BeforeEach
    void signIn() {
        HospioUserDetails m = new HospioUserDetails("m1", "m@x.ng", "Mgr", "b1", List.of("h1"), Map.of(Role.MANAGER, AccessLevel.FULL), JwtUtil.SCOPE_HOTEL);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(m, null, m.getAuthorities()));
        TenantContext.set(Tenant.fromUser(m, "h1"));
        when(refunds.save(any())).thenAnswer(i -> i.getArgument(0));
        when(hotelConfig.approvalLimits(any())).thenReturn(new HotelConfigClient.ApprovalLimits(new BigDecimal("500000"), false));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    private static Refund refund(Refund.Status status, String amount) {
        Refund r = Refund.builder().paymentId("p1").amount(new BigDecimal(amount)).reason("Overcharged").requestedBy("u1").status(status).build();
        r.setId("r1");
        r.setHotelId("h1");
        return r;
    }

    @Test
    void aRefundRequestLocksThePaymentBeforeSummingWhatIsLeft() {
        PaymentRequest p = PaymentRequest.builder().method(PaymentMethod.CARD).role(Role.BAR).amount(new BigDecimal("10000")).requestedBy("u1")
                .status(PaymentStatus.APPROVED).build();
        p.setId("p1");
        when(payments.lockById("p1")).thenReturn(Optional.of(p));
        when(refunds.findByPaymentId("p1")).thenReturn(List.of(refund(Refund.Status.PENDING_APPROVAL, "7000")));

        assertThatThrownBy(() -> service.request("p1", new RefundRequestDto(new BigDecimal("5000"), "Again")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("left to refund");
        verify(payments, never()).findById(anyString());
    }

    @Test
    void aSecondApprovalFindsItSettledAndPublishesNothing() {
        when(refunds.findById("r1")).thenReturn(Optional.of(refund(Refund.Status.PENDING_APPROVAL, "1000")));
        when(refunds.lockById("r1")).thenReturn(Optional.of(refund(Refund.Status.APPROVED, "1000")));

        assertThatThrownBy(() -> service.approve("r1")).isInstanceOf(BadRequestException.class).hasMessageContaining("already approved");
        verify(events, never()).publish(eq(KafkaTopics.REFUND_APPROVED), any());
    }

    @Test
    void anApprovalSettlesOnce() {
        when(refunds.findById("r1")).thenReturn(Optional.of(refund(Refund.Status.PENDING_APPROVAL, "1000")));
        when(refunds.lockById("r1")).thenReturn(Optional.of(refund(Refund.Status.PENDING_APPROVAL, "1000")));

        Refund r = service.approve("r1");

        assertThat(r.getStatus()).isEqualTo(Refund.Status.APPROVED);
        verify(events).publish(eq(KafkaTopics.REFUND_APPROVED), any());
    }

    @Test
    void declineCannotOverwriteAnApprovedRefund() {
        when(refunds.lockById("r1")).thenReturn(Optional.of(refund(Refund.Status.APPROVED, "1000")));

        assertThatThrownBy(() -> service.decline("r1")).isInstanceOf(BadRequestException.class).hasMessageContaining("already approved");
        verify(refunds, never()).save(any());
    }

    @Test
    void aRefundForTheOwnerKeepsItsApprovalId() {
        when(refunds.findById("r1")).thenReturn(Optional.of(refund(Refund.Status.PENDING_APPROVAL, "900000")));
        Refund locked = refund(Refund.Status.PENDING_APPROVAL, "900000");
        when(refunds.lockById("r1")).thenReturn(Optional.of(locked));

        assertThatThrownBy(() -> service.approve("r1")).isInstanceOf(BadRequestException.class).hasMessageContaining("goes to the owner");
        ArgumentCaptor<Refund> saved = ArgumentCaptor.forClass(Refund.class);
        verify(refunds).save(saved.capture());
        assertThat(saved.getValue().getApprovalId()).isNotNull();
        verify(events).publish(eq(KafkaTopics.APPROVAL_REQUESTED), any());
    }

    @Test
    void theOwnersAnswerOnADecidedRefundChangesNothing() {
        Refund r = refund(Refund.Status.DECLINED, "900000");
        when(refunds.lockByApprovalId("a1")).thenReturn(Optional.of(r));

        assertThat(service.onOwnerDecision("a1", "APPROVED", "o1", "Owner").getStatus()).isEqualTo(Refund.Status.DECLINED);
        verify(events, never()).publish(eq(KafkaTopics.REFUND_APPROVED), any());
    }
}
