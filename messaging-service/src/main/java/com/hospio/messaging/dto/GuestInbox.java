package com.hospio.messaging.dto;

import com.hospio.common.delivery.Delivery;
import com.hospio.common.enums.NotificationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Guest conversations (Manager · Messaging, Front office · Messages), templates and the shift notice. */
public final class GuestInbox {

    private GuestInbox() {
    }

    /**
     * Message a guest. With {@code bookingId} the guest, contact and stay come from the booking (the conversation is the
     * booking's); without it {@code guestName} and the contact for the channel are needed. {@code channel} EMAIL is sent;
     * WHATSAPP and SMS are recorded and shown, not sent yet. {@code guestContact} is the old single field (still read).
     */
    public record NewMessage(String bookingId, String guestId, @Size(max = 150) String guestName, String guestEmail, String guestPhone,
                             String guestContact, NotificationChannel channel, @Size(max = 200) String subject,
                             @NotBlank @Size(max = 4000) String body) {
    }

    /** A reply on an open conversation; {@code channel} switches it (e.g. to email), {@code subject} for email. */
    public record Reply(@NotBlank @Size(max = 4000) String body, NotificationChannel channel, @Size(max = 200) String subject) {
    }

    /** What the guest wrote back by phone, WhatsApp or at the desk, logged by staff. */
    public record GuestReply(@NotBlank @Size(max = 4000) String body) {
    }

    public record Assign(@NotBlank String userId) {
    }

    public record TemplateRequest(@NotBlank @Size(max = 100) String name, @Size(max = 200) String subject,
                                  @NotBlank @Size(max = 4000) String body, NotificationChannel channel) {
    }

    public record Fill(String conversationId, String bookingId) {
    }

    public record Filled(String subject, String body, List<String> missing) {
    }

    public record ShiftNotice(@NotBlank @Size(max = 1000) String body) {
    }

    public record ShiftNoticeSent(int count, List<String> names) {
    }

    public record ConversationView(String id, String guestId, String guestName, String guestEmail, String guestPhone, String contact,
                                   NotificationChannel channel, String subject, String bookingId, String bookingRef, String roomNumber,
                                   String roomType, LocalDate checkIn, LocalDate checkOut, String assignedToId, String assignedToName,
                                   String status, boolean waiting, Instant lastMessageAt, String preview, Instant doneAt, String doneByName) {
    }

    public record MessageView(String id, String direction, String body, String by, Instant at, String channel, String status, String error) {
    }

    public record Detail(ConversationView conversation, List<MessageView> messages) {
    }

    /** After a send: the conversation, the message and what went out (echoed on staging with its status). */
    public record Sent(ConversationView conversation, MessageView message, List<Delivery> deliveries) {
    }

    public record TemplateView(String id, String name, String subject, String body, NotificationChannel channel, String purpose,
                               String key, boolean active, Instant updatedAt) {
    }
}
