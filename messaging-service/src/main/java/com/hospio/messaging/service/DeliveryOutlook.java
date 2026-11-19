package com.hospio.messaging.service;

import com.hospio.messaging.billing.CommBilling;
import com.hospio.messaging.text.TextSender;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * What would happen to a guest WhatsApp (or SMS) sent now, worked out with the same rules {@code MailDispatcher} and
 * {@link TextSender} apply when the delivery arrives, so a screen can say honestly what became of it (docs/whatsapp-receipts.md,
 * "What the desk is told"): the hotel's guest messages off or suspended, the business suspended, the channel not connected on
 * this server, the staging allow-list holding the number back, or not a phone number at all. Everything else is QUEUED (the
 * provider still answers later: delivered, read or refused).
 */
@Service
public class DeliveryOutlook {

    /** QUEUED, SKIPPED, NOT_CONNECTED, HELD_BACK or NO_PHONE; {@code guestMessagesOn}: the hotel's guest messages are on and not suspended. */
    public record Outlook(String outcome, String reason, boolean guestMessagesOn) {
    }

    public static final String QUEUED = "QUEUED";
    public static final String SKIPPED = "SKIPPED";
    public static final String NOT_CONNECTED = "NOT_CONNECTED";
    public static final String HELD_BACK = "HELD_BACK";
    public static final String NO_PHONE = "NO_PHONE";

    static final String HELD_BACK_REASON = "Held back: not on this environment's allow-list";
    static final String NO_PHONE_REASON = "No phone number for the guest";
    static final String NOT_CONNECTED_REASON = "WhatsApp is not connected on this server";

    private final CommBilling billing;
    private final ObjectProvider<TextSender> texts;
    private final ObjectProvider<com.hospio.common.suspension.BusinessSuspension> suspension;

    public DeliveryOutlook(CommBilling billing, ObjectProvider<TextSender> texts,
                           ObjectProvider<com.hospio.common.suspension.BusinessSuspension> suspension) {
        this.billing = billing;
        this.texts = texts;
        this.suspension = suspension;
    }

    /** {@code to} may be left out: then only whether the hotel's messages would go (the receipt step's note before paying). */
    public Outlook of(String hotelId, String businessId, String kind, String channel, String to) {
        String k = kind == null || kind.isBlank() ? "RECEIPT" : kind.trim().toUpperCase(Locale.ROOT);
        String c = channel == null || channel.isBlank() ? "WHATSAPP" : channel.trim().toUpperCase(Locale.ROOT);
        CommBilling.Gate gate = billing.gate(hotelId, k, c);
        com.hospio.common.suspension.BusinessSuspension s = suspension.getIfAvailable();
        boolean businessSuspended = s != null && businessId != null && !businessId.isBlank() && CommBilling.BILLABLE_KINDS.contains(k) && s.isSuspended(businessId);
        TextSender t = texts.getIfAvailable();
        boolean live = t != null && t.live(c, k);
        boolean phoneGiven = to != null && !to.isBlank();
        String number = phoneGiven ? TextSender.number(to) : null;
        boolean allowListed = !phoneGiven || (t != null && t.allowListed(to));
        return decide(gate, businessSuspended, live, phoneGiven, number, allowListed);
    }

    /** The rules in the order the dispatcher applies them: no number, the hotel's or business's gate, the channel, the allow-list. */
    static Outlook decide(CommBilling.Gate gate, boolean businessSuspended, boolean live, boolean phoneGiven, String number, boolean allowListed) {
        boolean on = !businessSuspended && (!gate.billable() || gate.allowed());
        if (phoneGiven && (number == null || number.length() < 10)) {
            return new Outlook(NO_PHONE, NO_PHONE_REASON, on);
        }
        if (businessSuspended) {
            return new Outlook(SKIPPED, "Not sent: the business's Hospio360 account is suspended", false);
        }
        if (gate.billable() && !gate.allowed()) {
            return new Outlook(SKIPPED, gate.reason(), false);
        }
        if (!live) {
            return new Outlook(NOT_CONNECTED, NOT_CONNECTED_REASON, on);
        }
        if (phoneGiven && !allowListed) {
            return new Outlook(HELD_BACK, HELD_BACK_REASON, on);
        }
        return new Outlook(QUEUED, null, on);
    }
}
