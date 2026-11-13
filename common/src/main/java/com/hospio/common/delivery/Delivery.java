package com.hospio.common.delivery;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A message the platform sends to a person: a temporary password, an invite link, a phone code, a handover pack, a fee
 * statement, a guest message. Services build these and hand them to {@link DeliveryService}, which publishes them for
 * messaging-service (the one sender: email goes out through SMTP there, SMS and WhatsApp are not connected yet) and, on
 * environments with the echo on, returns them in the API response so the web app can show them on screen.
 *
 * <p>{@code status} is filled by {@link DeliveryService} for the echo: {@code EMAIL_SENT} (an email, on its way to the
 * address whatever the domain) or {@code NOT_CONNECTED} (SMS and WhatsApp, or email switched off). {@code hotelId} / {@code businessId} name the hotel the message is from when the caller's own
 * context does not (an invite made business-wide by the owner); the From name is that hotel's name. See docs/email.md.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Delivery(Channel channel, String to, String subject, String text, Kind kind, String ref, String status,
                       @JsonIgnore String hotelId, @JsonIgnore String businessId,
                       @JsonIgnore String template, @JsonIgnore java.util.List<String> params) {

    public enum Channel { EMAIL, SMS, WHATSAPP }

    public enum Kind {
        TEMP_PASSWORD, INVITE, PHONE_CODE, HANDOVER_PACK, PASSWORD_RESET, FEE_STATEMENT, TICKET_UPDATE, DAILY_REPORT, DECISION,
        TILL_VARIANCE, RECEIPT, FOLIO, STATEMENT, PAYMENT_REMINDER, PURCHASE_ORDER, MESSAGE, BOOKING_CONFIRMATION, REPORT, NOTICE,
        BOOKING_CHANGED, BOOKING_CANCELLED
    }

    /** The echo's status values (see the class comment). */
    public static final String EMAIL_SENT = "EMAIL_SENT";
    public static final String NOT_CONNECTED = "NOT_CONNECTED";
    /** An SMS handed to the SMS provider (docs/sms-whatsapp.md). */
    public static final String SMS_SENT = "SMS_SENT";
    /** A WhatsApp handed to the sender: its approved template, else the same text by SMS. */
    public static final String WHATSAPP_SENT = "WHATSAPP_SENT";

    public Delivery(Channel channel, String to, String subject, String text, Kind kind, String ref) {
        this(channel, to, subject, text, kind, ref, null, null, null, null, null);
    }

    public Delivery(Channel channel, String to, String subject, String text, Kind kind, String ref, String status,
                    String hotelId, String businessId) {
        this(channel, to, subject, text, kind, ref, status, hotelId, businessId, null, null);
    }

    public static Delivery email(String to, String subject, String text, Kind kind, String ref) {
        return new Delivery(Channel.EMAIL, to, subject, text, kind, ref);
    }

    public static Delivery sms(String to, String text, Kind kind, String ref) {
        return new Delivery(Channel.SMS, to, null, text, kind, ref);
    }

    public static Delivery whatsApp(String to, String subject, String text, Kind kind, String ref) {
        return new Delivery(Channel.WHATSAPP, to, subject, text, kind, ref);
    }

    /** The same message, from this hotel (its name is the From name, its email the Reply-To). Nulls keep the caller's context. */
    public Delivery from(String businessId, String hotelId) {
        return new Delivery(channel, to, subject, text, kind, ref, status, hotelId, businessId, template, params);
    }

    public Delivery withStatus(String status) {
        return new Delivery(channel, to, subject, text, kind, ref, status, hotelId, businessId, template, params);
    }

    /**
     * The approved WhatsApp template this message goes as, with its placeholder values in order ({@code @hotel} is filled
     * with the sending hotel's name by messaging-service). Without an approved template a WhatsApp goes as an SMS of
     * {@code text}, so the text must always read well on its own.
     */
    public Delivery withTemplate(String template, java.util.List<String> params) {
        return new Delivery(channel, to, subject, text, kind, ref, status, hotelId, businessId, template, params);
    }
}
