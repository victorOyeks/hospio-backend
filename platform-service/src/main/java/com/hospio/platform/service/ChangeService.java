package com.hospio.platform.service;

import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.kafka.EventPublisher;
import com.hospio.common.kafka.KafkaTopics;
import com.hospio.common.kafka.events.ChangeByPlatformEvent;
import com.hospio.common.response.PagedResponse;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.platform.dto.AdminResponses.ChangeRow;
import com.hospio.platform.dto.PlatformRequests;
import com.hospio.platform.entity.PlatformChange;
import com.hospio.platform.repository.PlatformChangeRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Changes H.M.S made inside a business (L1-L3): recorded with a reason, undoable for 7 days by the owner or the platform. */
@Service
@RequiredArgsConstructor
public class ChangeService {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final PlatformChangeRepository changes;
    private final BusinessNames businessNames;
    private final EventPublisher events;
    private final org.springframework.beans.factory.ObjectProvider<SupportService> support;

    @Transactional(readOnly = true)
    public PagedResponse<ChangeRow> search(String businessId, String madeBy, LocalDate from, LocalDate to, int page, int size) {
        Specification<PlatformChange> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (StringUtils.hasText(businessId)) {
                ps.add(cb.equal(root.get("businessId"), businessId));
            }
            if (StringUtils.hasText(madeBy)) {
                ps.add(cb.equal(root.get("madeBy"), madeBy));
            }
            if (from != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay(LAGOS).toInstant()));
            }
            if (to != null) {
                ps.add(cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay(LAGOS).toInstant()));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Page<PlatformChange> result = changes.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.of(result, this::row);
    }

    @Transactional(readOnly = true)
    public List<ChangeRow> recent(int limit) {
        return changes.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit)).stream().map(this::row).toList();
    }

    /** The owner's (or manager's) view of what H.M.S changed in their business. */
    @Transactional(readOnly = true)
    public List<ChangeRow> forCaller(String businessId) {
        HospioUserDetails user = CurrentUser.require();
        String business = user.isPlatform() ? businessId : TenantContext.requireBusiness();
        if (!StringUtils.hasText(business)) {
            throw new BadRequestException("Say which business (businessId)");
        }
        return changes.findByBusinessIdOrderByCreatedAtDesc(business).stream().map(this::row).toList();
    }

    @Transactional
    public PlatformChange record(PlatformRequests.PlatformChangeRequest request) {
        HospioUserDetails user = CurrentUser.require();
        String actor = "H.M.S · " + user.getFullName();
        PlatformChange c = changes.save(PlatformChange.builder().businessId(request.businessId()).hotelId(request.hotelId()).area(request.area())
                .description(request.description()).reason(request.reason()).beforeJson(request.beforeJson()).afterJson(request.afterJson())
                .madeBy(user.getUserId()).madeByName(actor).businessName(businessNames.name(request.businessId()))
                .undoUntil(Instant.now().plus(7, ChronoUnit.DAYS)).build());
        TenantContext.runWith(Tenant.systemFor(request.businessId(), request.hotelId(), user.getUserId(), actor), () ->
                events.publish(KafkaTopics.CHANGE_BY_PLATFORM, ChangeByPlatformEvent.builder().businessId(request.businessId()).hotelId(request.hotelId())
                        .actorId(user.getUserId()).actorName(actor).changeId(c.getId()).area(c.getArea())
                        .description(c.getDescription()).undoUntil(c.getUndoUntil()).build()));
        return c;
    }

    /**
     * Undo within 7 days, by H.M.S: support puts the setting back and marks the change undone here; an owner's request
     * for it (its ticket) is resolved at the same time. Owners ask through {@link OwnerRequestService#requestUndo}.
     */
    @Transactional
    public PlatformChange undo(String id) {
        HospioUserDetails user = CurrentUser.require();
        PlatformChange c = changes.findById(id).orElseThrow(() -> new ResourceNotFoundException("Change not found"));
        if (!user.isPlatform()) {
            // H.M.S puts the setting back; the owner asks for it (POST /changes/{id}/request-undo), which opens a ticket.
            throw new ForbiddenException(user.has(Role.OWNER) && c.getBusinessId().equals(user.getBusinessId())
                    ? "Ask Hospio support to undo it: they put the setting back and tell you"
                    : "Only H.M.S can undo a change by H.M.S");
        }
        if (c.getUndoneAt() != null) {
            throw new BadRequestException("Already undone");
        }
        if (Instant.now().isAfter(c.getUndoUntil())) {
            throw new BadRequestException("The 7-day undo window has passed");
        }
        c.setUndoneAt(Instant.now());
        c.setUndoneBy(user.getUserId());
        PlatformChange saved = changes.save(c);
        if (c.getUndoTicketId() != null) {
            // The owner asked for it: their ticket is answered by this.
            try {
                support.getObject().resolve(c.getUndoTicketId(), new PlatformRequests.ResolveTicketRequest(
                        "The change \"" + c.getDescription() + "\" was undone by " + user.getFullName() + "."));
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(ChangeService.class).warn("Could not resolve undo ticket {}: {}", c.getUndoTicketId(), e.getMessage());
            }
        }
        return saved;
    }

    private ChangeRow row(PlatformChange c) {
        String business = c.getBusinessName() != null ? c.getBusinessName() : businessNames.name(c.getBusinessId());
        return new ChangeRow(c, business, c.getMadeByName(), c.isUndone());
    }
}
