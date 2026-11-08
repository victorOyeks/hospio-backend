package com.hospio.payment.service;

import com.hospio.common.enums.PaymentStatus;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalRequestedEvent;
import com.hospio.common.kafka.events.RefundApprovedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.common.util.Money;
import com.hospio.payment.dto.DisputeRequest;
import com.hospio.payment.dto.RefundRequestDto;
import com.hospio.payment.dto.ResolveDisputeRequest;
import com.hospio.payment.entity.Dispute;
import com.hospio.payment.entity.PaymentRequest;
import com.hospio.payment.entity.Refund;
import com.hospio.payment.repository.DisputeRepository;
import com.hospio.payment.repository.PaymentRequestRepository;
import com.hospio.payment.repository.RefundRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manager · Payments 3-4: refunds and disputes. Staff ask; a manager approves; a refund over the
 * manager's limit goes to the owner through Approvals.
 */
@Service
@RequiredArgsConstructor
public class RefundService {

    private final RefundRepository refunds;
    private final DisputeRepository disputes;
    private final PaymentRequestRepository payments;
    private final EventPublisher events;

    @Value("${hospio.approvals.manager-spend-limit:500000}")
    private BigDecimal managerLimit;

    @Transactional(readOnly = true)
    public List<Refund> list() {
        return refunds.findByHotelIdOrderByCreatedAtDesc(TenantContext.requireHotel());
    }

    @Transactional
    public Refund request(String paymentId, RefundRequestDto dto) {
        PaymentRequest p = payments.findById(paymentId).orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (p.getStatus() != PaymentStatus.APPROVED) {
            throw new BadRequestException("Only an approved payment can be refunded");
        }
        if (Money.of(dto.amount()).compareTo(p.getAmount()) > 0) {
            throw new BadRequestException("A refund can't be more than the payment of " + p.getAmount());
        }
        return refunds.save(Refund.builder().paymentId(p.getId()).folioId(p.getFolioId()).amount(Money.of(dto.amount()))
                .reason(dto.reason()).requestedBy(CurrentUser.require().getUserId()).build());
    }

    @Transactional
    public Refund approve(String refundId) {
        HospioUserDetails user = CurrentUser.require();
        if (!user.isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager can approve a refund");
        }
        Refund r = find(refundId);
        if (r.getStatus() != Refund.Status.PENDING_APPROVAL) {
            throw new BadRequestException("This refund is already " + r.getStatus().name().toLowerCase().replace('_', ' '));
        }
        if (r.getAmount().compareTo(managerLimit) > 0 && !user.has(com.hospio.common.enums.Role.OWNER) && !user.isPlatform()) {
            if (r.getApprovalId() == null) {
                r.setApprovalId(UUID.randomUUID().toString());
                refunds.save(r);
                events.publish(KafkaTopics.APPROVAL_REQUESTED, ApprovalRequestedEvent.builder().approvalId(r.getApprovalId())
                        .sourceModule("PAYMENT").sourceRef(r.getId()).kind("REFUND").amount(r.getAmount()).requesterId(user.getUserId())
                        .requesterName(user.getFullName()).approverLevel("OWNER").description("Refund of " + r.getAmount() + ": " + r.getReason()).build());
            }
            throw new BadRequestException("A refund over " + managerLimit + " goes to the owner. It has been sent for approval");
        }
        return settle(r, user.getUserId(), user.getFullName());
    }

    @Transactional
    public Refund settle(Refund r, String approvedBy, String approvedByName) {
        r.setStatus(Refund.Status.APPROVED);
        r.setApprovedBy(approvedBy);
        r.setDecidedAt(Instant.now());
        refunds.save(r);
        String bookingId = payments.findById(r.getPaymentId()).map(p -> {
            p.setStatus(PaymentStatus.REFUNDED);
            payments.save(p);
            return p.getBookingId();
        }).orElse(null);
        events.publish(KafkaTopics.REFUND_APPROVED, RefundApprovedEvent.builder().refundId(r.getId()).paymentId(r.getPaymentId())
                .folioId(r.getFolioId()).bookingId(bookingId).amount(r.getAmount()).approvedBy(approvedByName).build());
        return r;
    }

    @Transactional
    public Refund decline(String refundId) {
        if (!CurrentUser.require().isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager can decline a refund");
        }
        Refund r = find(refundId);
        r.setStatus(Refund.Status.DECLINED);
        r.setDecidedAt(Instant.now());
        return refunds.save(r);
    }

    @Transactional
    public Refund onOwnerDecision(String approvalId, String decision, String decidedBy, String decidedByName) {
        Refund r = refunds.findByApprovalId(approvalId).orElse(null);
        if (r == null || r.getStatus() != Refund.Status.PENDING_APPROVAL) {
            return r;
        }
        if ("APPROVED".equals(decision)) {
            return settle(r, decidedBy, decidedByName);
        }
        if ("DECLINED".equals(decision)) {
            r.setStatus(Refund.Status.DECLINED);
            r.setDecidedAt(Instant.now());
            refunds.save(r);
        }
        return r;
    }

    @Transactional(readOnly = true)
    public List<Dispute> disputes() {
        return disputes.findByHotelIdOrderByCreatedAtDesc(TenantContext.requireHotel());
    }

    @Transactional
    public Dispute openDispute(String paymentId, DisputeRequest request) {
        payments.findById(paymentId).orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        return disputes.save(Dispute.builder().paymentId(paymentId).reason(request.reason()).openedBy(CurrentUser.require().getUserId()).build());
    }

    @Transactional
    public Dispute resolveDispute(String disputeId, ResolveDisputeRequest request) {
        if (!CurrentUser.require().isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager can resolve a dispute");
        }
        Dispute d = disputes.findById(disputeId).orElseThrow(() -> new ResourceNotFoundException("Dispute not found"));
        d.setStatus(Dispute.Status.RESOLVED);
        d.setResolution(request.resolution());
        d.setResolvedBy(CurrentUser.require().getUserId());
        d.setResolvedAt(Instant.now());
        return disputes.save(d);
    }

    private Refund find(String id) {
        return refunds.findById(id).orElseThrow(() -> new ResourceNotFoundException("Refund not found"));
    }
}
