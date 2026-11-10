package com.hospio.messaging.service;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.delivery.DeliveryService;
import com.hospio.common.enums.NotificationChannel;
import com.hospio.common.enums.Role;
import com.hospio.common.exception.BadRequestException;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.exception.ForbiddenException;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.messaging.client.IdentityClient;
import com.hospio.messaging.dto.ThreadDtos;
import com.hospio.messaging.entity.Conversation;
import com.hospio.messaging.entity.Message;
import com.hospio.messaging.repository.ConversationRepository;
import com.hospio.messaging.repository.MessageRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Staff threads (docs/owner-portal.md · Messaging): the owner or a manager writes to one person or to a hotel's
 * managers; the owner can write to every manager at once (one thread each). Only the people in a thread see it and
 * reply; the other side gets an in-app notice that opens it in their Inbox, and with {@code whatsapp} the message goes
 * to their phone too (echoed in the response on staging, where no provider is connected).
 */
@Service
@RequiredArgsConstructor
public class StaffThreadService {

    public static final String PERSON = "PERSON";
    public static final String MANAGERS = "MANAGERS";

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final NotificationService notifications;
    private final IdentityClient identity;
    private final DeliveryService deliveries;

    @Transactional
    public ThreadDtos.ThreadSent start(ThreadDtos.NewThread request) {
        HospioUserDetails me = sender();
        String hotelId = TenantContext.requireHotel();
        boolean toManagers = Boolean.TRUE.equals(request.toHotelManagers());
        List<IdentityClient.Contact> to;
        String recipientName;
        if (toManagers) {
            if (!me.has(Role.OWNER)) {
                throw new ForbiddenException("Only the owner messages a hotel's managers as a group");
            }
            to = managersOf(hotelId, me.getUserId());
            if (to.isEmpty()) {
                throw new BadRequestException("This hotel has no manager to message yet");
            }
            recipientName = "Managers";
        } else {
            if (!StringUtils.hasText(request.toUserId())) {
                throw new BadRequestException("Choose who the message is for");
            }
            if (request.toUserId().equals(me.getUserId())) {
                throw new BadRequestException("You can't message yourself");
            }
            IdentityClient.Contact person = lookup(request.toUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("That person was not found"));
            if (!Objects.equals(person.businessId(), me.getBusinessId())) {
                throw new ForbiddenException("That person is not in your business");
            }
            to = List.of(person);
            recipientName = person.fullName();
        }
        Conversation c = thread(me, hotelId, toManagers ? MANAGERS : PERSON, toManagers ? null : to.get(0).id(), recipientName,
                subject(request.subject(), request.body()), null);
        post(c, me, request.body());
        tell(c, me, to, request.body());
        return new ThreadDtos.ThreadSent(row(c, me), whatsapp(request.whatsapp(), to, me, c, request.body()));
    }

    /**
     * Owner only: one thread per manager across every hotel of the business, tied by a broadcast id. A manager of two
     * hotels gets one thread. Written business-wide so each thread sits under its own hotel whatever X-Hotel-Id was sent.
     */
    public ThreadDtos.BroadcastSent broadcastToManagers(ThreadDtos.Broadcast request) {
        HospioUserDetails me = sender();
        if (!me.has(Role.OWNER)) {
            throw new ForbiddenException("Only the owner can message every manager at once");
        }
        Map<String, Map.Entry<String, IdentityClient.Contact>> managers = new LinkedHashMap<>();
        for (String hotelId : me.getHotelIds()) {
            for (IdentityClient.Contact m : managersOf(hotelId, me.getUserId())) {
                managers.putIfAbsent(m.id(), Map.entry(hotelId, m));
            }
        }
        if (managers.isEmpty()) {
            throw new BadRequestException("None of your hotels has a manager to message yet");
        }
        String broadcastId = UUID.randomUUID().toString();
        String subject = subject(request.subject(), request.body());
        List<ThreadDtos.ThreadRow> rows = new ArrayList<>();
        List<Delivery> sent = new ArrayList<>();
        TenantContext.runWith(Tenant.systemFor(me.getBusinessId(), null, me.getUserId(), me.getFullName()), () -> {
            for (Map.Entry<String, IdentityClient.Contact> e : managers.values()) {
                IdentityClient.Contact manager = e.getValue();
                Conversation c = thread(me, e.getKey(), PERSON, manager.id(), manager.fullName(), subject, broadcastId);
                post(c, me, request.body());
                tell(c, me, List.of(manager), request.body());
                rows.add(row(c, me));
                sent.addAll(whatsapp(request.whatsapp(), List.of(manager), me, c, request.body()));
            }
        });
        return new ThreadDtos.BroadcastSent(rows.size(), rows, sent);
    }

    /** The caller's threads, newest first: across the business, or at one hotel when the request names one. */
    @Transactional(readOnly = true)
    public List<ThreadDtos.ThreadRow> mine() {
        HospioUserDetails me = CurrentUser.require();
        List<String> managed = me.has(Role.MANAGER) && !me.getHotelIds().isEmpty() ? me.getHotelIds() : List.of("-");
        return conversations.threadsFor(me.getUserId(), managed).stream().map(c -> row(c, me)).toList();
    }

    /** Unread messages over all the caller's threads (the Inbox badge in every role's sidebar). */
    @Transactional(readOnly = true)
    public long unreadCount() {
        HospioUserDetails me = CurrentUser.require();
        List<String> managed = me.has(Role.MANAGER) && !me.getHotelIds().isEmpty() ? me.getHotelIds() : List.of("-");
        return messages.unreadInThreads(me.getUserId(), managed, Instant.EPOCH);
    }

    /** {@link #mine} a page at a time (newest message first), searched by {@code q} in the subject, the last message and the names. */
    @Transactional(readOnly = true)
    public com.hospio.common.response.PagedResponse<ThreadDtos.ThreadRow> page(String q, Integer page, Integer size) {
        HospioUserDetails me = CurrentUser.require();
        List<String> managed = me.has(Role.MANAGER) && !me.getHotelIds().isEmpty() ? me.getHotelIds() : List.of("-");
        org.springframework.data.domain.Sort sort = org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "lastMessageAt")
                .and(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id"));
        return com.hospio.common.response.PagedResponse.of(conversations.threadsPage(me.getUserId(), managed,
                StringUtils.hasText(q) ? com.hospio.common.paging.Paging.like(q) : "%", com.hospio.common.paging.Paging.of(page, size, sort)), c -> row(c, me));
    }

    /** One thread with its messages; opening it marks it read for the caller's side. */
    @Transactional
    public ThreadDtos.ThreadDetail open(String id) {
        HospioUserDetails me = CurrentUser.require();
        Conversation c = participantOf(id, me);
        markRead(c, me);
        conversations.save(c);
        List<ThreadDtos.ThreadMessage> list = messages.findByConversationIdOrderByCreatedAt(c.getId()).stream()
                .map(m -> new ThreadDtos.ThreadMessage(m.getId(), m.getSenderId(), m.getSenderName(), m.getBody(), m.getCreatedAt(),
                        me.getUserId().equals(m.getSenderId())))
                .toList();
        return new ThreadDtos.ThreadDetail(row(c, me), list);
    }

    @Transactional
    public ThreadDtos.ThreadSent reply(String id, ThreadDtos.Reply request) {
        HospioUserDetails me = CurrentUser.require();
        Conversation c = participantOf(id, me);
        post(c, me, request.body());
        markRead(c, me);
        conversations.save(c);
        List<IdentityClient.Contact> other;
        if (me.getUserId().equals(c.getCreatedBy())) {
            other = MANAGERS.equals(c.getAudience()) ? managersOf(c.getHotelId(), me.getUserId())
                    : lookup(c.getAssignedTo()).map(List::of).orElse(List.of());
        } else {
            other = List.of(new IdentityClient.Contact(c.getCreatedBy(), c.getCreatedByName(), null, null, c.getBusinessId(), null, List.of()));
        }
        tell(c, me, other, request.body());
        return new ThreadDtos.ThreadSent(row(c, me), List.of());
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private static HospioUserDetails sender() {
        HospioUserDetails me = CurrentUser.require();
        if (me.isPlatform() || me.getBusinessId() == null) {
            throw new ForbiddenException("Staff messages are between people of one business");
        }
        if (!me.isManagerOrOwner()) {
            throw new ForbiddenException("Only a manager or the owner can start a staff message");
        }
        return me;
    }

    /** Starter, the person it went to, or a manager of the hotel when it went to the hotel's managers. */
    private Conversation participantOf(String id, HospioUserDetails me) {
        Conversation c = conversations.findById(id).filter(x -> x.getKind() == Conversation.Kind.STAFF && x.getAudience() != null)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
        boolean starter = me.getUserId().equals(c.getCreatedBy());
        boolean person = PERSON.equals(c.getAudience()) && me.getUserId().equals(c.getAssignedTo());
        boolean manager = MANAGERS.equals(c.getAudience()) && me.has(Role.MANAGER) && me.getHotelIds().contains(c.getHotelId());
        if (!starter && !person && !manager) {
            throw new ForbiddenException("This conversation is between other people");
        }
        return c;
    }

    private Conversation thread(HospioUserDetails me, String hotelId, String audience, String toUserId, String recipientName,
                                String subject, String broadcastId) {
        Instant now = Instant.now();
        return conversations.saveAndFlush(Conversation.builder().businessId(me.getBusinessId()).hotelId(hotelId)
                .kind(Conversation.Kind.STAFF).channel(NotificationChannel.IN_APP).subject(subject)
                .createdBy(me.getUserId()).createdByName(me.getFullName()).audience(audience).assignedTo(toUserId)
                .assignedToName(recipientName).recipientName(recipientName).broadcastId(broadcastId)
                .lastMessageAt(now).creatorReadAt(now).build());
    }

    private void post(Conversation c, HospioUserDetails me, String body) {
        messages.saveAndFlush(Message.builder().businessId(c.getBusinessId()).hotelId(c.getHotelId()).conversationId(c.getId())
                .direction(Message.Direction.OUT).senderId(me.getUserId()).senderName(me.getFullName()).body(body.trim()).build());
        c.setLastMessageAt(Instant.now());
        c.setLastPreview(preview(body));
        conversations.saveAndFlush(c);
    }

    private void tell(Conversation c, HospioUserDetails me, List<IdentityClient.Contact> people, String body) {
        for (IdentityClient.Contact p : people) {
            if (p.id() != null && !p.id().equals(me.getUserId())) {
                notifications.notify(null, p.id(), null, "STAFF_MESSAGE", "Message from " + me.getFullName(), preview(body),
                        "/inbox?open=" + c.getId());
            }
        }
    }

    private List<Delivery> whatsapp(Boolean wanted, List<IdentityClient.Contact> people, HospioUserDetails me, Conversation c, String body) {
        if (!Boolean.TRUE.equals(wanted)) {
            return List.of();
        }
        List<Delivery> out = new ArrayList<>();
        for (IdentityClient.Contact p : people) {
            if (StringUtils.hasText(p.phone())) {
                out.add(new Delivery(Delivery.Channel.WHATSAPP, p.phone(), c.getSubject(),
                        "Message from " + me.getFullName() + " on Hospio360: " + body.trim() + "\nReply in the app: Inbox",
                        Delivery.Kind.MESSAGE, c.getId()));
            }
        }
        return deliveries.send(out);
    }

    private List<IdentityClient.Contact> managersOf(String hotelId, String exceptUserId) {
        try {
            return identity.withRole(hotelId, Role.MANAGER.name()).stream()
                    .filter(m -> m.id() != null && !m.id().equals(exceptUserId))
                    .filter(m -> m.status() == null || "ACTIVE".equals(m.status()))
                    .toList();
        } catch (RuntimeException e) {
            throw new ExternalServiceException("Could not look up the hotel's managers right now. Try again", e);
        }
    }

    private java.util.Optional<IdentityClient.Contact> lookup(String userId) {
        if (userId == null) {
            return java.util.Optional.empty();
        }
        try {
            return identity.contact(userId);
        } catch (RuntimeException e) {
            throw new ExternalServiceException("Could not look up that person right now. Try again", e);
        }
    }

    private static void markRead(Conversation c, HospioUserDetails me) {
        if (me.getUserId().equals(c.getCreatedBy())) {
            c.setCreatorReadAt(Instant.now());
        } else {
            c.setRecipientReadAt(Instant.now());
        }
    }

    private ThreadDtos.ThreadRow row(Conversation c, HospioUserDetails me) {
        boolean mine = me.getUserId().equals(c.getCreatedBy());
        Instant since = mine ? c.getCreatorReadAt() : c.getRecipientReadAt();
        long unread = messages.unread(c.getId(), me.getUserId(), since == null ? Instant.EPOCH : since);
        return new ThreadDtos.ThreadRow(c.getId(), c.getHotelId(), c.getSubject(), c.getAudience(), c.getCreatedBy(), c.getCreatedByName(),
                c.getAssignedTo(), c.getRecipientName(), mine, mine ? c.getRecipientName() : c.getCreatedByName(), c.getLastMessageAt(),
                c.getLastPreview(), unread, c.getBroadcastId(), c.getCreatedAt());
    }

    static String subject(String subject, String body) {
        if (StringUtils.hasText(subject)) {
            return subject.trim();
        }
        String b = body == null ? "" : body.trim().replaceAll("\\s+", " ");
        return b.length() <= 60 ? b : b.substring(0, 57) + "…";
    }

    static String preview(String body) {
        String b = body == null ? "" : body.trim().replaceAll("\\s+", " ");
        return b.length() <= 280 ? b : b.substring(0, 277) + "…";
    }
}
