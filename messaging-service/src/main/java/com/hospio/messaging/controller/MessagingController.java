package com.hospio.messaging.controller;

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

    /**
     * Mine. With {@code page} or {@code size}: a page (newest first, {@code from} 90 days back by default) with {@code counts.all} and
     * {@code counts.unread}; filters {@code unread}, {@code kind}, {@code q}, {@code from}, {@code to}. Without either: the old full list
     * (operations scripts read it), {@code unreadOnly} narrowing it.
     */
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

    @GetMapping("/conversations")
    public ApiResponse<List<Conversation>> inbox(@RequestParam(defaultValue = "GUEST") Conversation.Kind kind) {
        return ApiResponse.ok(conversations.inbox(kind));
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<List<Message>> thread(@PathVariable String id) {
        return ApiResponse.ok(conversations.thread(id));
    }

    @PostMapping("/conversations/guest")
    public ApiResponse<Conversation> messageGuest(@Valid @RequestBody MessagingRequests.NewGuestMessageRequest request) {
        return ApiResponse.ok("Message sent", conversations.messageGuest(request));
    }

    @PostMapping("/conversations/staff")
    public ApiResponse<Conversation> messageStaff(@Valid @RequestBody MessagingRequests.StaffMessageRequest request) {
        return ApiResponse.ok("Message sent", conversations.messageStaff(request));
    }

    @PostMapping("/conversations/{id}/reply")
    public ApiResponse<Message> reply(@PathVariable String id, @Valid @RequestBody MessagingRequests.ReplyRequest request) {
        return ApiResponse.ok("Sent", conversations.reply(id, request));
    }

    @PutMapping("/conversations/{id}/assign")
    @PreAuthorize("@access.manager()")
    public ApiResponse<Conversation> reassign(@PathVariable String id, @Valid @RequestBody MessagingRequests.ReassignRequest request) {
        return ApiResponse.ok("Reassigned", conversations.reassign(id, request));
    }

    @PostMapping("/conversations/{id}/close")
    public ApiResponse<Conversation> close(@PathVariable String id) {
        return ApiResponse.ok("Closed", conversations.close(id));
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
