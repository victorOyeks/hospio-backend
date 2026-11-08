package com.hospio.messaging.dto;

import com.hospio.common.enums.NotificationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class MessagingRequests {

    private MessagingRequests() {
    }

    public record TemplateRequest(@NotBlank String key, @NotNull NotificationChannel channel, String subject, @NotBlank String body, Boolean active) {
    }

    public record NewGuestMessageRequest(String guestId, String guestName, @NotBlank String guestContact, @NotNull NotificationChannel channel,
                                         String subject, @NotBlank String body) {
    }

    public record StaffMessageRequest(@NotBlank String subject, @NotBlank String body, String toUserId, String toRole) {
    }

    public record ReplyRequest(@NotBlank String body) {
    }

    public record ReassignRequest(@NotBlank String assignedTo, String assignedToName) {
    }

    public record InboundRequest(@NotBlank String from, @NotNull NotificationChannel channel, @NotBlank String body, String providerRef) {
    }
}
