package com.hospio.platform.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.response.PagedResponse;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.client.IdentityClient;
import com.hospio.platform.dto.AdminResponses.TicketCommentResponse;
import com.hospio.platform.dto.AdminResponses.TicketDetail;
import com.hospio.platform.dto.AdminResponses.TicketResolved;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.SupportTicket;
import com.hospio.platform.entity.SupportTicketComment;
import com.hospio.platform.repository.SupportTicketCommentRepository;
import com.hospio.platform.repository.SupportTicketRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Support tickets: hotels open them, H.M.S assigns, comments, resolves. Updates for the opener go out as TICKET_UPDATE. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupportService {

    static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    static final List<String> OPEN = List.of("OPEN", "IN_PROGRESS");

    private final SupportTicketRepository tickets;
    private final SupportTicketCommentRepository comments;
    private final NameResolver names;
    private final BusinessNames businessNames;
    private final AlertService alerts;
    private final DeliveryService deliveries;

    // ---- platform ------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PagedResponse<SupportTicket> search(String status, String priority, String businessId, String assignedTo, String q, int page, int size) {
        Specification<SupportTicket> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (StringUtils.hasText(status)) {
                if ("OPEN".equalsIgnoreCase(status)) {
                    ps.add(root.get("status").in(OPEN));
                } else {
                    ps.add(cb.equal(root.get("status"), status.toUpperCase(Locale.ROOT)));
                }
            }
            if (StringUtils.hasText(priority)) {
                ps.add(cb.equal(root.get("priority"), priority.toUpperCase(Locale.ROOT)));
            }
            if (StringUtils.hasText(businessId)) {
                ps.add(cb.equal(root.get("businessId"), businessId));
            }
            if (StringUtils.hasText(assignedTo)) {
                ps.add(cb.equal(root.get("assignedTo"), assignedTo));
            }
            if (StringUtils.hasText(q)) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("subject")), like), cb.like(cb.lower(cb.coalesce(root.get("businessName"), "")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("openedByName"), "")), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<SupportTicket> result = tickets.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.of(result, t -> t);
    }

    @Transactional(readOnly = true)
    public TicketDetail get(String id) {
        SupportTicket t = find(id);
        HospioUserDetails user = CurrentUser.require();
        List<SupportTicketComment> list = comments.findByTicketIdOrderByCreatedAtAsc(id);
        if (!user.isPlatform()) {
            list = list.stream().filter(c -> !c.isInternal()).toList();
        }
        return new TicketDetail(t, list);
    }

    /** A hotel user opens one for their business; the platform opens one on a business's behalf. */
    @Transactional
    public SupportTicket open(PlatformRequests.TicketRequest request) {
        HospioUserDetails user = CurrentUser.require();
        String businessId = user.isPlatform() ? request.businessId() : TenantContext.requireBusiness();
        if (!StringUtils.hasText(businessId)) {
            throw new BadRequestException("Say which business this is about");
        }
        String hotelId = request.hotelId() != null ? request.hotelId() : user.isPlatform() ? null : TenantContext.current().map(t -> t.hotelId()).orElse(null);
        String priority = priority(request.priority());
        SupportTicket t = tickets.save(SupportTicket.builder().businessId(businessId).hotelId(hotelId).subject(request.subject()).body(request.body())
                .priority(priority).openedBy(user.getUserId()).openedByName(user.isPlatform() ? "H.M.S · " + user.getFullName() : user.getFullName())
                .businessName(businessNames.name(businessId)).lastActivityAt(Instant.now()).build());
        if ("URGENT".equals(priority)) {
            alerts.refreshTicketUrgent(businessId);
        }
        return t;
    }

    @Transactional
    public SupportTicket assign(String id, PlatformRequests.AssignTicketRequest request) {
        SupportTicket t = find(id);
        t.setAssignedTo(request.adminId());
        t.setAssignedToName(names.name(request.adminId()).orElse(null));
        if ("OPEN".equals(t.getStatus())) {
            t.setStatus("IN_PROGRESS");
        }
        t.setLastActivityAt(Instant.now());
        return tickets.save(t);
    }

    @Transactional
    public SupportTicket setPriority(String id, PlatformRequests.TicketPriorityRequest request) {
        SupportTicket t = find(id);
        t.setPriority(priority(request.priority()));
        t.setLastActivityAt(Instant.now());
        tickets.save(t);
        alerts.refreshTicketUrgent(t.getBusinessId());
        return t;
    }

    /** A comment; one that is not internal is sent to whoever opened the ticket. */
    @Transactional
    public TicketCommentResponse comment(String id, PlatformRequests.TicketCommentRequest request) {
        SupportTicket t = find(id);
        HospioUserDetails user = CurrentUser.require();
        boolean internal = request.internal() && user.isPlatform();
        SupportTicketComment c = comments.save(SupportTicketComment.builder().businessId(t.getBusinessId()).hotelId(t.getHotelId()).ticketId(t.getId())
                .authorId(user.getUserId()).authorName(user.isPlatform() ? "H.M.S · " + user.getFullName() : user.getFullName())
                .body(request.body()).internal(internal).build());
        t.setLastActivityAt(Instant.now());
        tickets.save(t);
        List<Delivery> sent = internal || !user.isPlatform() ? List.of()
                : notifyOpener(t, "Update on your ticket: " + t.getSubject(),
                "Hello,\n\n" + user.getFullName() + " from Hospio360 support wrote on your ticket \"" + t.getSubject() + "\":\n\n"
                        + request.body() + "\n\nReply from the Support page in your Hospio360 app.\n");
        return new TicketCommentResponse(c, sent);
    }

    @Transactional
    public TicketResolved resolve(String id, PlatformRequests.ResolveTicketRequest request) {
        SupportTicket t = find(id);
        t.setStatus("RESOLVED");
        t.setResolution(request.resolution());
        t.setResolvedAt(Instant.now());
        t.setLastActivityAt(Instant.now());
        tickets.save(t);
        alerts.refreshTicketUrgent(t.getBusinessId());
        List<Delivery> sent = notifyOpener(t, "Your ticket was resolved: " + t.getSubject(),
                "Hello,\n\nYour ticket \"" + t.getSubject() + "\" has been resolved.\n\nResolution:\n" + request.resolution()
                        + "\n\nIf this is not sorted, reopen it from the Support page in your Hospio360 app.\n");
        return new TicketResolved(t, sent);
    }

    @Transactional
    public SupportTicket reopen(String id) {
        SupportTicket t = find(id);
        t.setStatus("OPEN");
        t.setResolvedAt(null);
        t.setLastActivityAt(Instant.now());
        tickets.save(t);
        alerts.refreshTicketUrgent(t.getBusinessId());
        return t;
    }

    // ---- hotel users ---------------------------------------------------------------------------------

    /** The platform's list is paged above; hotel users (owner, manager) see their own business here. */
    @Transactional(readOnly = true)
    public List<SupportTicket> mine(String status) {
        HospioUserDetails user = CurrentUser.require();
        if (user.isPlatform()) {
            return tickets.findByStatusOrderByCreatedAtDesc(status == null ? "OPEN" : status.toUpperCase(Locale.ROOT));
        }
        String business = TenantContext.requireBusiness();
        return status == null ? tickets.findByBusinessIdOrderByCreatedAtDesc(business)
                : tickets.findByBusinessIdAndStatusInOrderByCreatedAtDesc(business, List.of(status.toUpperCase(Locale.ROOT)));
    }

    @Transactional(readOnly = true)
    public long openCount() {
        return tickets.countByStatusIn(OPEN);
    }

    @Transactional(readOnly = true)
    public long openCount(String businessId) {
        return tickets.countByBusinessIdAndStatusIn(businessId, OPEN);
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /** The opener's email; for a ticket H.M.S opened on a business's behalf, the business owner's. */
    private List<Delivery> notifyOpener(SupportTicket t, String subject, String text) {
        Optional<IdentityClient.Contact> opener = names.contact(t.getOpenedBy());
        String to = opener.filter(c -> c.businessId() != null && StringUtils.hasText(c.email())).map(IdentityClient.Contact::email)
                .orElseGet(() -> businessNames.business(t.getBusinessId()).map(b -> b.ownerEmail()).orElse(null));
        if (!StringUtils.hasText(to)) {
            log.warn("Ticket {} has nobody to notify (opener {} unknown, no owner email)", t.getId(), t.getOpenedBy());
            return List.of();
        }
        return deliveries.send(Delivery.email(to, subject, text, Delivery.Kind.TICKET_UPDATE, t.getId()));
    }

    private static String priority(String raw) {
        String p = raw == null ? "NORMAL" : raw.toUpperCase(Locale.ROOT);
        if (!PRIORITIES.contains(p)) {
            throw new BadRequestException("Priority must be LOW, NORMAL, HIGH or URGENT");
        }
        return p;
    }

    private SupportTicket find(String id) {
        return tickets.findById(id).orElseThrow(() -> new ResourceNotFoundException("Ticket not found"));
    }
}
