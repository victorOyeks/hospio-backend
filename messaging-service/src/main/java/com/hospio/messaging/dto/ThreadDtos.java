package com.hospio.messaging.dto;

import com.hospio.common.delivery.Delivery;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Staff threads (Owner portal · Messaging, the Inbox every role has): requests and what comes back. */
public final class ThreadDtos {

    private ThreadDtos() {
    }

    /**
     * A new thread at the hotel in X-Hotel-Id: to one person ({@code toUserId}) or to the hotel's managers
     * ({@code toHotelManagers}, owner only). {@code whatsapp} also sends it to their phones.
     */
    public record NewThread(String toUserId, Boolean toHotelManagers, @Size(max = 200) String subject,
                            @NotBlank @Size(max = 4000) String body, Boolean whatsapp) {
    }

    /** Owner only: one thread per manager of every hotel of the business. */
    public record Broadcast(@Size(max = 200) String subject, @NotBlank @Size(max = 4000) String body, Boolean whatsapp) {
    }

    public record Reply(@NotBlank @Size(max = 4000) String body) {
    }

    /**
     * One thread from the caller's side: {@code withName} is the other side (the person, "Managers", or who started it),
     * {@code mine} whether the caller started it, {@code unread} messages from the other side the caller has not opened.
     */
    public record ThreadRow(String id, String hotelId, String subject, String audience, String startedById, String startedByName,
                            String recipientId, String recipientName, boolean mine, String withName, Instant lastMessageAt,
                            String lastPreview, long unread, String broadcastId, Instant createdAt) {
    }

    public record ThreadMessage(String id, String senderId, String senderName, String body, Instant createdAt, boolean mine) {
    }

    public record ThreadDetail(ThreadRow thread, List<ThreadMessage> messages) {
    }

    /** A thread started (or a reply) and, on staging, the WhatsApp messages that would have gone out. */
    public record ThreadSent(ThreadRow thread, List<Delivery> deliveries) {
    }

    public record BroadcastSent(int count, List<ThreadRow> threads, List<Delivery> deliveries) {
    }
}
