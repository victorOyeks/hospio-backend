package com.hospio.common.delivery;

/**
 * A message the platform would send to a person: a temporary password, an invite link, a phone code, a
 * handover pack, a fee statement. Services build these and hand them to {@link DeliveryService}, which
 * publishes them for messaging-service and, on environments without email or SMS, echoes them back in the
 * API response so the web app can show them on screen.
 */
public record Delivery(Channel channel, String to, String subject, String text, Kind kind, String ref) {

    public enum Channel { EMAIL, SMS, WHATSAPP }

    public enum Kind { TEMP_PASSWORD, INVITE, PHONE_CODE, HANDOVER_PACK, PASSWORD_RESET, FEE_STATEMENT, TICKET_UPDATE, DAILY_REPORT, DECISION, TILL_VARIANCE }

    public static Delivery email(String to, String subject, String text, Kind kind, String ref) {
        return new Delivery(Channel.EMAIL, to, subject, text, kind, ref);
    }

    public static Delivery sms(String to, String text, Kind kind, String ref) {
        return new Delivery(Channel.SMS, to, null, text, kind, ref);
    }
}
