package com.hospio.messaging.service;

import com.hospio.common.enums.NotificationChannel;
import com.hospio.common.exception.ResourceNotFoundException;
import com.hospio.common.security.CurrentUser;
import com.hospio.common.security.HospioUserDetails;
import com.hospio.common.tenancy.Tenant;
import com.hospio.common.tenancy.TenantContext;
import com.hospio.messaging.dto.MessagingRequests;
import com.hospio.messaging.entity.Conversation;
import com.hospio.messaging.entity.Message;
import com.hospio.messaging.entity.Template;
import com.hospio.messaging.repository.ConversationRepository;
import com.hospio.messaging.repository.MessageRepository;
import com.hospio.messaging.repository.TemplateRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Guest inbox (Front office 5.2-5.3, Manager · Messaging 1.x), staff messages (2.x), templates (3.1). */
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final TemplateRepository templates;
    private final OutboundService outbound;
    private final NotificationService notifications;
    private final com.hospio.common.delivery.DeliveryService deliveries;

    @Transactional(readOnly = true)
    public List<Conversation> inbox(Conversation.Kind kind) {
        return conversations.findByHotelIdAndKindAndStatusOrderByLastMessageAtDesc(TenantContext.requireHotel(), kind, Conversation.Status.OPEN);
    }

    @Transactional(readOnly = true)
    public List<Message> thread(String conversationId) {
        find(conversationId);
        return messages.findByConversationIdOrderByCreatedAt(conversationId);
    }

    @Transactional
    public Conversation messageStaff(MessagingRequests.StaffMessageRequest request) {
        HospioUserDetails user = CurrentUser.require();
        Conversation c = conversations.save(Conversation.builder().kind(Conversation.Kind.STAFF).channel(NotificationChannel.IN_APP)
                .subject(request.subject()).assignedTo(request.toUserId()).lastMessageAt(Instant.now()).build());
        messages.save(Message.builder().conversationId(c.getId()).direction(Message.Direction.OUT).senderId(user.getUserId())
                .senderName(user.getFullName()).body(request.body()).build());
        if (request.toUserId() != null) {
            notifications.notify(null, request.toUserId(), null, "STAFF_MESSAGE", request.subject(), request.body(), "/messages/" + c.getId());
        } else if (request.toRole() != null) {
            notifications.notifyRoles(null, "STAFF_MESSAGE", request.subject(), request.body(), "/messages/" + c.getId(), request.toRole().toUpperCase());
        }
        return c;
    }

    /** A guest wrote back (provider webhook): file it under their open thread, or open one. */
    @Transactional
    public Conversation inbound(String hotelId, MessagingRequests.InboundRequest request) {
        return TenantContext.runWith(Tenant.systemFor(null, hotelId, null, "inbound"), () -> {
            Conversation c = conversations.findFirstByHotelIdAndGuestContactAndStatus(hotelId, request.from(), Conversation.Status.OPEN)
                    .orElseGet(() -> conversations.save(Conversation.builder().kind(Conversation.Kind.GUEST).guestContact(request.from())
                            .channel(request.channel()).subject("New message").hotelId(hotelId).build()));
            messages.save(Message.builder().conversationId(c.getId()).direction(Message.Direction.IN).body(request.body()).providerRef(request.providerRef()).build());
            c.setLastMessageAt(Instant.now());
            c.setLastDirection("IN");
            c.setLastPreview(request.body().length() > 300 ? request.body().substring(0, 300) : request.body());
            c.setStatus(Conversation.Status.OPEN);
            conversations.save(c);
            notifications.notifyRoles(null, "GUEST_MESSAGE", "Message from " + (c.getGuestName() == null ? request.from() : c.getGuestName()),
                    request.body(), "/messages?open=" + c.getId(), "FRONT_OFFICE", "MANAGER");
            return c;
        });
    }

    @Transactional(readOnly = true)
    public List<Template> templates() {
        return templates.findByHotelIdOrderByKey(TenantContext.requireHotel());
    }

    @Transactional
    public Template saveTemplate(String id, MessagingRequests.TemplateRequest request) {
        Template t = id == null ? new Template() : templates.findById(id).orElseThrow(() -> new ResourceNotFoundException("Template not found"));
        t.setKey(request.key().toUpperCase());
        t.setChannel(request.channel());
        t.setSubject(request.subject());
        t.setBody(request.body());
        if (request.active() != null) {
            t.setActive(request.active());
        }
        return templates.save(t);
    }

    private Conversation find(String id) {
        return conversations.findById(id).orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
    }
}
