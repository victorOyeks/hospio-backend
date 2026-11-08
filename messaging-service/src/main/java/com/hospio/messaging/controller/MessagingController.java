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
import java.util.List;
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

    @GetMapping("/notifications")
    public ApiResponse<List<Notification>> notifications(@RequestParam(defaultValue = "false") boolean unreadOnly) {
        return ApiResponse.ok(notifications.mine(unreadOnly));
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
