package com.hospio.platform.service;

import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.platform.dto.AdminResponses.ChangeRow;
import com.hospio.platform.dto.OwnerRequests;
import com.hospio.platform.entity.PlatformChange;
import com.hospio.platform.entity.SupportTicket;
import com.hospio.platform.repository.PlatformChangeRepository;
import com.hospio.platform.repository.SupportTicketRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * What an owner asks H.M.S support for from the owner portal, each as a support ticket the admin team works:
 * undoing a change H.M.S made (support puts it back and marks it undone, which resolves the ticket) and handing the
 * business to a new owner. Only the business's owner asks; one open request of each at a time.
 */
@Service
@RequiredArgsConstructor
public class OwnerRequestService {

    public static final String CHANGE_UNDO = "CHANGE_UNDO";
    public static final String OWNERSHIP_TRANSFER = "OWNERSHIP_TRANSFER";
    private static final List<String> OPEN = List.of("OPEN", "IN_PROGRESS");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH).withZone(ZoneId.of("Africa/Lagos"));

    private final PlatformChangeRepository changes;
    private final SupportTicketRepository tickets;
    private final BusinessNames businessNames;
    private final AlertService alerts;

    /** The owner asks support to undo a change by H.M.S, within its 7 days. */
    @Transactional
    public ChangeRow requestUndo(String changeId, OwnerRequests.UndoRequest request) {
        HospioUserDetails owner = owner("ask for a change to be undone");
        PlatformChange c = changes.findById(changeId).filter(x -> owner.getBusinessId().equals(x.getBusinessId()))
                .orElseThrow(() -> new ResourceNotFoundException("That change was not found"));
        if (c.isUndone()) {
            throw new BadRequestException("This change was already undone");
        }
        if (Instant.now().isAfter(c.getUndoUntil())) {
            throw new BadRequestException("The 7 days to undo this change have passed. Open a support ticket if it is still wrong");
        }
        if (c.getUndoRequestedAt() != null) {
            throw new BadRequestException("You already asked for this on " + WHEN.format(c.getUndoRequestedAt()) + ". Support is on it");
        }
        String note = StringUtils.hasText(request == null ? null : request.note()) ? request.note().trim() : null;
        SupportTicket t = tickets.save(SupportTicket.builder().businessId(c.getBusinessId()).hotelId(c.getHotelId()).category(CHANGE_UNDO)
                .subject(cut("Undo a change by H.M.S: " + c.getDescription(), 200))
                .body(cut("The owner asks for this change to be undone.\n\nChange: " + c.getDescription() + "\nArea: " + c.getArea()
                        + "\nMade by: " + c.getMadeByName() + " on " + WHEN.format(c.getCreatedAt()) + "\nReason given: " + c.getReason()
                        + "\nUndo possible until: " + WHEN.format(c.getUndoUntil())
                        + (note == null ? "" : "\n\nFrom the owner: " + note)
                        + "\n\nPut it back, then mark the change undone under Changes by H.M.S: that resolves this ticket.", 2000))
                .priority("HIGH").openedBy(owner.getUserId()).openedByName(owner.getFullName())
                .businessName(businessNames.name(c.getBusinessId())).lastActivityAt(Instant.now()).build());
        c.setUndoRequestedAt(Instant.now());
        c.setUndoRequestedByName(owner.getFullName());
        c.setUndoTicketId(t.getId());
        changes.save(c);
        alerts.refreshTicketUrgent(c.getBusinessId());
        return new ChangeRow(c, c.getBusinessName() != null ? c.getBusinessName() : businessNames.name(c.getBusinessId()), c.getMadeByName(), false);
    }

    /** The owner asks support to hand the business to someone else; support checks and does it. */
    @Transactional
    public SupportTicket requestTransfer(OwnerRequests.OwnershipTransfer request) {
        HospioUserDetails owner = owner("transfer ownership");
        String email = request.newOwnerEmail().trim().toLowerCase(Locale.ROOT);
        if (email.equalsIgnoreCase(owner.getEmail())) {
            throw new BadRequestException("That is your own email. Enter the new owner's");
        }
        Optional<SupportTicket> open = currentTransfer().filter(t -> OPEN.contains(t.getStatus()));
        if (open.isPresent()) {
            throw new BadRequestException("A transfer request is already with H.M.S support (opened " + WHEN.format(open.get().getCreatedAt())
                    + "). They will contact you");
        }
        String business = businessNames.name(owner.getBusinessId());
        String note = StringUtils.hasText(request.note()) ? request.note().trim() : null;
        SupportTicket t = tickets.save(SupportTicket.builder().businessId(owner.getBusinessId()).category(OWNERSHIP_TRANSFER)
                .subject(cut("Ownership transfer · " + (business == null ? "business" : business) + " → " + email, 200))
                .body(cut("The owner, " + owner.getFullName() + " (" + owner.getEmail() + "), asks to hand " + (business == null ? "the business" : business)
                        + " to a new owner.\n\nNew owner: " + request.newOwnerName().trim() + "\nEmail: " + email
                        + (StringUtils.hasText(request.newOwnerPhone()) ? "\nPhone: " + request.newOwnerPhone().trim() : "")
                        + (note == null ? "" : "\n\nFrom the owner: " + note)
                        + "\n\nConfirm with both people before changing the owner.", 2000))
                .priority("HIGH").openedBy(owner.getUserId()).openedByName(owner.getFullName()).businessName(business)
                .lastActivityAt(Instant.now()).build());
        alerts.refreshTicketUrgent(owner.getBusinessId());
        return t;
    }

    /** The latest transfer request of the caller's business, open or not. */
    @Transactional(readOnly = true)
    public Optional<SupportTicket> currentTransfer() {
        HospioUserDetails user = CurrentUser.require();
        if (user.getBusinessId() == null) {
            return Optional.empty();
        }
        return tickets.findByBusinessIdOrderByCreatedAtDesc(user.getBusinessId()).stream()
                .filter(t -> OWNERSHIP_TRANSFER.equals(t.getCategory())).findFirst();
    }

    private static HospioUserDetails owner(String what) {
        HospioUserDetails user = CurrentUser.require();
        if (user.isPlatform() || !user.has(Role.OWNER) || user.getBusinessId() == null || user.isReadOnly()) {
            throw new ForbiddenException("Only the owner can " + what);
        }
        return user;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
