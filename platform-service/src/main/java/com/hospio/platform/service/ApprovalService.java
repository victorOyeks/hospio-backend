package com.hospio.platform.service;

import com.hospio.common.enums.ApprovalStatus;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalDecidedEvent;
import com.hospio.common.kafka.events.ApprovalRequestedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.Approval;
import com.hospio.platform.repository.ApprovalRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Approvals: staff → manager (up to their limit) → owner. The requesting module publishes
 * ApprovalRequested; the decision goes back as ApprovalDecided and that module applies it.
 */
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final ApprovalRepository approvals;
    private final EventPublisher events;

    @Transactional
    public void onRequested(ApprovalRequestedEvent e) {
        if (approvals.existsById(e.getApprovalId())) {
            return;
        }
        Approval a = Approval.builder().businessId(e.getBusinessId()).hotelId(e.getHotelId()).sourceModule(e.getSourceModule()).sourceRef(e.getSourceRef())
                .kind(e.getKind()).amount(e.getAmount()).requesterId(e.getRequesterId()).requesterName(e.getRequesterName())
                .approverLevel(e.getApproverLevel() == null ? "MANAGER" : e.getApproverLevel()).description(e.getDescription()).build();
        a.setId(e.getApprovalId());
        approvals.save(a);
    }

    @Transactional(readOnly = true)
    public List<Approval> pending() {
        return approvals.findByBusinessIdAndStatusOrderByCreatedAt(TenantContext.requireBusiness(), ApprovalStatus.PENDING).stream()
                .filter(this::canDecide).toList();
    }

    @Transactional(readOnly = true)
    public List<Approval> all() {
        return approvals.findByBusinessIdOrderByCreatedAtDesc(TenantContext.requireBusiness());
    }

    @Transactional
    public Approval decide(String id, PlatformRequests.DecisionRequest request) {
        HospioUserDetails user = CurrentUser.require();
        Approval a = approvals.findById(id).orElseThrow(() -> new ResourceNotFoundException("Approval not found"));
        if (a.getStatus() != ApprovalStatus.PENDING && a.getStatus() != ApprovalStatus.QUESTION) {
            throw new BadRequestException("Already " + a.getStatus().name().toLowerCase());
        }
        if (!canDecide(a)) {
            throw new ForbiddenException("This one is for the " + a.getApproverLevel().toLowerCase());
        }
        ApprovalStatus status = switch (request.decision().toUpperCase()) {
            case "APPROVED", "APPROVE" -> ApprovalStatus.APPROVED;
            case "DECLINED", "DECLINE" -> ApprovalStatus.DECLINED;
            default -> ApprovalStatus.QUESTION;
        };
        a.setStatus(status);
        a.setDecidedBy(user.getUserId());
        a.setDecidedByName(user.getFullName());
        a.setNote(request.note());
        a.setDecidedAt(Instant.now());
        approvals.save(a);
        events.publish(KafkaTopics.APPROVAL_DECIDED, ApprovalDecidedEvent.builder().businessId(a.getBusinessId()).hotelId(a.getHotelId())
                .approvalId(a.getId()).sourceModule(a.getSourceModule()).sourceRef(a.getSourceRef()).kind(a.getKind()).decision(status.name())
                .decidedBy(user.getUserId()).decidedByName(user.getFullName()).note(request.note()).build());
        return a;
    }

    private boolean canDecide(Approval a) {
        HospioUserDetails user = CurrentUser.require();
        if (user.isPlatform()) {
            return false;
        }
        if ("OWNER".equals(a.getApproverLevel())) {
            return user.has(Role.OWNER);
        }
        return user.isManagerOrOwner();
    }
}
