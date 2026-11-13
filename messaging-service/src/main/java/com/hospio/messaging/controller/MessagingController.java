package com.hospio.messaging.controller;

import com.hospio.messaging.dto.GuestInbox;
import com.hospio.messaging.service.GuestInboxService;
import org.springframework.web.bind.annotation.DeleteMapping;

import com.hospio.common.response.ApiResponse;
import com.hospio.messaging.dto.MessagingRequests;
import com.hospio.messaging.entity.Conversation;
import com.hospio.messaging.entity.Message;
import com.hospio.messaging.entity.Notification;
import com.hospio.messaging.entity.Template;
import com.hospio.messaging.service.ConversationService;
import com.hospio.messaging.service.NotificationService;
import jakarta.validation.Valid;
import com.hospio.common.paging.Paging;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Notifications (every role's screen and the bell), guest inbox, staff messages, templates. */
@RestController
@RequiredArgsConstructor
public class MessagingController {

    private final NotificationService notifications;
    private final ConversationService conversations;
    private final GuestInboxService guests;
    private final com.hospio.messaging.text.TextSender texts;

    /**
     * Mine. With {@code page} or {@code size}: a page (newest first, {@code from} 90 days back by default) with {@code counts.all} and
     * {@code counts.unread}; filters {@code unread}, {@code kind}, {@code q}, {@code from}, {@code to}. Without either: the old full list
     * (operations scripts read it), {@code unreadOnly} narrowing it.
     */
    /** Which text channels go out here, and whether a code can go on WhatsApp (its template approved), for "Send on WhatsApp instead". */
    @GetMapping("/channels")
    public ApiResponse<java.util.Map<String, Boolean>> channels() {
        return ApiResponse.ok(java.util.Map.of("sms", texts.smsLive(), "whatsapp", texts.live("WHATSAPP"), "whatsappCodes", texts.whatsAppTemplateReady("oyeks_otp")));
    }

    @GetMapping("/notifications")
    public ApiResponse<?> notifications(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                        @RequestParam(required = false) Boolean unread, @RequestParam(required = false) String kind,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        if (!Paging.requested(page, size)) {
            return ApiResponse.ok(notifications.mine(unreadOnly));
        }
        Boolean onlyUnread = unread != null ? unread : (unreadOnly ? Boolean.TRUE : null);
        return ApiResponse.ok(notifications.page(new NotificationService.Filter(onlyUnread, kind, q, from, to), page, size));
    }

    /** The bell's badge: how many of mine are unread. */
    @GetMapping("/notifications/unread-count")
    public ApiResponse<Map<String, Long>> unreadCount() {
        return ApiResponse.ok(Map.of("count", notifications.unreadCount()));
    }

    @PostMapping("/notifications/{id}/read")
    public ApiResponse<Notification> markRead(@PathVariable String id) {
        return ApiResponse.ok("Read", notifications.markRead(id));
    }

    @PostMapping("/notifications/read-all")
    public ApiResponse<Integer> markAllRead() {
        return ApiResponse.ok("All read", notifications.markAllRead());
    }

    /**
     * Guest conversations at the hotel. With {@code page} or {@code size}: a page (newest message first) with counts per tab
     * ({@code waiting, open, mine, done, all}); filters {@code tab} (default open), {@code q}, {@code channel}. Without either:
     * the old open list of a kind (internal callers).
     */
    @GetMapping("/conversations")
    public ApiResponse<?> inbox(@RequestParam(defaultValue = "GUEST") Conversation.Kind kind, @RequestParam(required = false) String tab,
                                @RequestParam(required = false) String q, @RequestParam(required = false) com.hospio.common.enums.NotificationChannel channel,
                                @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        if (!com.hospio.common.paging.Paging.requested(page, size)) {
            return ApiResponse.ok(conversations.inbox(kind));
        }
        return ApiResponse.ok(guests.page(new GuestInboxService.Filter(tab, q, channel), page, size));
    }

    @GetMapping("/conversations/{id}")
    public ApiResponse<GuestInbox.Detail> conversation(@PathVariable String id) {
        return ApiResponse.ok(guests.detail(id));
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<GuestInbox.MessageView>> thread(@PathVariable String id) {
        return ApiResponse.ok(guests.detail(id).messages());
    }

    @PostMapping("/conversations/guest")
    public ApiResponse<GuestInbox.Sent> messageGuest(@Valid @RequestBody GuestInbox.NewMessage request) {
        GuestInbox.Sent sent = guests.message(request);
        return ApiResponse.ok(sentLine(sent), sent);
    }

    @PostMapping("/conversations/staff")
    public ApiResponse<Conversation> messageStaff(@Valid @RequestBody MessagingRequests.StaffMessageRequest request) {
        return ApiResponse.ok("Message sent", conversations.messageStaff(request));
    }

    @PostMapping("/conversations/{id}/reply")
    public ApiResponse<GuestInbox.Sent> reply(@PathVariable String id, @Valid @RequestBody GuestInbox.Reply request) {
        GuestInbox.Sent sent = guests.reply(id, request);
        return ApiResponse.ok(sentLine(sent), sent);
    }

    /** What the guest said by phone, WhatsApp or at the desk, logged by staff. */
    @PostMapping("/conversations/{id}/guest-reply")
    public ApiResponse<GuestInbox.Detail> guestReply(@PathVariable String id, @Valid @RequestBody GuestInbox.GuestReply request) {
        return ApiResponse.ok("Guest's reply added", guests.guestReply(id, request));
    }

    @PutMapping("/conversations/{id}/assign")
    public ApiResponse<GuestInbox.ConversationView> reassign(@PathVariable String id, @Valid @RequestBody GuestInbox.Assign request) {
        GuestInbox.ConversationView v = guests.assign(id, request);
        return ApiResponse.ok("Given to " + v.assignedToName(), v);
    }

    @PostMapping("/conversations/{id}/close")
    public ApiResponse<GuestInbox.ConversationView> close(@PathVariable String id) {
        return ApiResponse.ok("Marked done", guests.done(id));
    }

    @PostMapping("/conversations/{id}/reopen")
    public ApiResponse<GuestInbox.ConversationView> reopen(@PathVariable String id) {
        return ApiResponse.ok("Reopened", guests.reopen(id));
    }

    /** Message templates staff pick from (purpose MESSAGE). */
    @GetMapping("/templates/messages")
    public ApiResponse<List<GuestInbox.TemplateView>> messageTemplates() {
        return ApiResponse.ok(guests.templates());
    }

    @PostMapping("/templates/messages")
    public ApiResponse<GuestInbox.TemplateView> addMessageTemplate(@Valid @RequestBody GuestInbox.TemplateRequest request) {
        return ApiResponse.ok("Template saved", guests.saveTemplate(null, request));
    }

    @PutMapping("/templates/messages/{id}")
    public ApiResponse<GuestInbox.TemplateView> updateMessageTemplate(@PathVariable String id, @Valid @RequestBody GuestInbox.TemplateRequest request) {
        return ApiResponse.ok("Template saved", guests.saveTemplate(id, request));
    }

    @DeleteMapping("/templates/messages/{id}")
    public ApiResponse<Void> deleteMessageTemplate(@PathVariable String id) {
        guests.deleteTemplate(id);
        return ApiResponse.ok("Template deleted", null);
    }

    /** The template with its placeholders filled from a conversation (or a booking). */
    @PostMapping("/templates/{id}/fill")
    public ApiResponse<GuestInbox.Filled> fill(@PathVariable String id, @RequestBody(required = false) GuestInbox.Fill request) {
        return ApiResponse.ok(guests.fill(id, request));
    }

    /** Today's rota at the hotel: who "everyone on shift" reaches. */
    @GetMapping("/shift-notice/recipients")
    public ApiResponse<List<com.hospio.messaging.client.FinanceClient.OnShift>> onShift() {
        return ApiResponse.ok(guests.onShift());
    }

    @PostMapping("/shift-notice")
    public ApiResponse<GuestInbox.ShiftNoticeSent> shiftNotice(@Valid @RequestBody GuestInbox.ShiftNotice request) {
        GuestInbox.ShiftNoticeSent sent = guests.shiftNotice(request);
        return ApiResponse.ok("Sent to " + sent.count() + (sent.count() == 1 ? " person" : " people") + " on shift", sent);
    }

    /** What the hotel sent (email, SMS, WhatsApp) and what the provider answered, newest first (docs/email.md). */
    @GetMapping("/deliveries")
    public ApiResponse<com.hospio.common.response.PageWithCounts<com.hospio.messaging.entity.DeliveryRecord>> deliveries(
            @RequestParam(required = false) String status, @RequestParam(required = false) String channel, @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.ok(guests.deliveryLog(status, channel, q, page, size));
    }

    private static String sentLine(GuestInbox.Sent sent) {
        String ch = sent.message().channel();
        if ("EMAIL".equals(ch)) {
            return "NOT_CONNECTED".equals(sent.message().status()) ? "Saved · email is switched off here" : "Email sent to " + sent.conversation().contact();
        }
        String name = "SMS".equals(ch) ? "SMS" : "WhatsApp";
        return "NOT_CONNECTED".equals(sent.message().status()) ? "Saved · " + name + " is switched off here, so copy the message to the guest"
                : name + " sent to " + sent.conversation().contact();
    }

    @GetMapping("/templates")
    public ApiResponse<List<Template>> templates() {
        return ApiResponse.ok(conversations.templates());
    }

    @PostMapping("/templates")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Template> addTemplate(@Valid @RequestBody MessagingRequests.TemplateRequest request) {
        return ApiResponse.ok("Template saved", conversations.saveTemplate(null, request));
    }

    @PutMapping("/templates/{id}")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Template> updateTemplate(@PathVariable String id, @Valid @RequestBody MessagingRequests.TemplateRequest request) {
        return ApiResponse.ok("Template saved", conversations.saveTemplate(id, request));
    }
}
