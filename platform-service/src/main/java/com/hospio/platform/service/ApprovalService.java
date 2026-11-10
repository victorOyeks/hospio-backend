package com.hospio.platform.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.ApprovalStatus;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ApprovalAnsweredEvent;
import com.hospio.common.kafka.events.ApprovalDecidedEvent;
import com.hospio.common.kafka.events.ApprovalRequestedEvent;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.client.TenantClient;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.Approval;
import com.hospio.platform.repository.ApprovalRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Approvals: staff → manager (up to their limit) → owner. The requesting module publishes
 * ApprovalRequested; the decision goes back as ApprovalDecided and that module applies it. A question
 * the requester answers comes back as ApprovalAnswered and reopens the approval.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final ApprovalRepository approvals;
    private final EventPublisher events;
    private final NameResolver names;
    private final BusinessNames businesses;
    private final DeliveryService deliveries;

    @Transactional
    public void onRequested(ApprovalRequestedEvent e) {
        if (approvals.existsById(e.getApprovalId())) {
            return;
        }
        Approval a = Approval.builder().businessId(e.getBusinessId()).hotelId(e.getHotelId()).sourceModule(e.getSourceModule()).sourceRef(e.getSourceRef())
                .kind(e.getKind()).amount(e.getAmount()).requesterId(e.getRequesterId()).requesterName(e.getRequesterName())
                .approverLevel(e.getApproverLevel() == null ? "MANAGER" : e.getApproverLevel()).description(e.getDescription())
                .requesterRole(e.getRequesterRole() != null ? e.getRequesterRole() : roleOf(e.getRequesterId()))
                .hotelName(hotelName(e.getBusinessId(), e.getHotelId())).build();
        a.setId(e.getApprovalId());
        approvals.save(a);
    }

    /** The requester answered the manager's question: the approval is waiting again, with the answer on its note. */
    @Transactional
    public void onAnswered(ApprovalAnsweredEvent e) {
        approvals.findById(e.getApprovalId()).ifPresent(a -> {
            if (a.getStatus() != ApprovalStatus.QUESTION) {
                return;
            }
            a.setStatus(ApprovalStatus.PENDING);
            a.setNote(appendNote(a.getNote(), "Answer from " + (e.getAnsweredByName() == null ? "the requester" : e.getAnsweredByName()) + ": " + e.getNote()));
            approvals.save(a);
        });
    }

    @Transactional(readOnly = true)
    public List<Approval> pending() {
        List<Approval> list = approvals.findByBusinessIdAndStatusOrderByCreatedAt(TenantContext.requireBusiness(), ApprovalStatus.PENDING).stream()
                .filter(this::canDecide).toList();
        list.forEach(this::fillMissing);
        return list;
    }

    @Transactional(readOnly = true)
    public List<Approval> all() {
        List<Approval> list = approvals.findByBusinessIdOrderByCreatedAtDesc(TenantContext.requireBusiness());
        list.forEach(this::fillMissing);
        return list;
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
        a.setDeliveries(tellRequester(a, status));
        return a;
    }

    /** A manager hands a manager-level approval to the owner. The owner is told through a fresh ApprovalRequested. */
    @Transactional
    public Approval escalate(String id, String note) {
        HospioUserDetails user = CurrentUser.require();
        Approval a = approvals.findById(id).orElseThrow(() -> new ResourceNotFoundException("Approval not found"));
        if (a.getStatus() != ApprovalStatus.PENDING && a.getStatus() != ApprovalStatus.QUESTION) {
            throw new BadRequestException("Already " + a.getStatus().name().toLowerCase());
        }
        if ("OWNER".equals(a.getApproverLevel())) {
            throw new BadRequestException("This is already waiting for the owner");
        }
        if (!canDecide(a)) {
            throw new ForbiddenException("Only a manager can send this to the owner");
        }
        a.setApproverLevel("OWNER");
        a.setStatus(ApprovalStatus.PENDING);
        a.setNote(appendNote(a.getNote(), "Sent to the owner by " + user.getFullName() + (note == null || note.isBlank() ? "" : ": " + note)));
        approvals.save(a);
        events.publish(KafkaTopics.APPROVAL_REQUESTED, ApprovalRequestedEvent.builder().businessId(a.getBusinessId()).hotelId(a.getHotelId())
                .approvalId(a.getId()).sourceModule(a.getSourceModule()).sourceRef(a.getSourceRef()).kind(a.getKind()).amount(a.getAmount())
                .requesterId(a.getRequesterId()).requesterName(a.getRequesterName()).requesterRole(a.getRequesterRole()).approverLevel("OWNER")
                .description("Escalated by " + user.getFullName() + ": " + a.getDescription()).build());
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

    private List<Delivery> tellRequester(Approval a, ApprovalStatus status) {
        try {
            if (a.getRequesterId() == null) {
                return List.of();
            }
            var contact = names.contact(a.getRequesterId()).orElse(null);
            if (contact == null) {
                return List.of();
            }
            String what = a.getKind().toLowerCase().replace('_', ' ');
            String verdict = switch (status) {
                case APPROVED -> "approved";
                case DECLINED -> "declined";
                default -> "asked a question about";
            };
            String text = a.getDecidedByName() + " " + verdict + " your " + what + (a.getDescription() == null ? "" : " (" + a.getDescription() + ")")
                    + (a.getNote() == null || a.getNote().isBlank() ? "." : ". " + a.getNote());
            String subject = "Your " + what + " was " + (status == ApprovalStatus.APPROVED ? "approved" : status == ApprovalStatus.DECLINED ? "declined" : "queried");
            Delivery d = contact.email() != null ? Delivery.email(contact.email(), subject, text, Delivery.Kind.DECISION, a.getId())
                    : contact.phone() != null ? Delivery.sms(contact.phone(), text, Delivery.Kind.DECISION, a.getId()) : null;
            return d == null ? List.of() : deliveries.send(d);
        } catch (RuntimeException e) {
            log.warn("Could not tell the requester about approval {}: {}", a.getId(), e.getMessage());
            return List.of();
        }
    }

    /** The hotel name and the requester's role on rows saved before they were stored (looked up, not saved). */
    void fillMissing(Approval a) {
        if (a.getHotelName() == null) {
            a.setHotelName(hotelName(a.getBusinessId(), a.getHotelId()));
        }
        if (a.getRequesterRole() == null) {
            a.setRequesterRole(roleOf(a.getRequesterId()));
        }
    }

    private String hotelName(String businessId, String hotelId) {
        try {
            return businesses.business(businessId).flatMap(b -> b.hotels().stream().filter(h -> h.id() != null && h.id().equals(hotelId)).findFirst())
                    .map(TenantClient.Hotel::name).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String roleOf(String userId) {
        try {
            return names.contact(userId).map(c -> c.roles() == null || c.roles().isEmpty() ? null : c.roles().get(0)).map(r -> r.contains(":") ? r.substring(0, r.indexOf(':')) : r).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String appendNote(String existing, String addition) {
        String joined = existing == null || existing.isBlank() ? addition : existing + "\n" + addition;
        return joined.length() > 500 ? joined.substring(joined.length() - 500) : joined;
    }
}
