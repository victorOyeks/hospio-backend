package com.hospio.messaging.text;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Sends SMS and WhatsApp (docs/sms-whatsapp.md). A WhatsApp goes as its approved template when it names one Meta has
 * approved. SMS is only for one-time codes: an SMS goes through Termii ({@link TermiiClient}, DND route, approved sender ID)
 * when it is switched on, else Infobip; and only a code's WhatsApp falls back to SMS when WhatsApp can't take it. Any other
 * WhatsApp without an approved template is not sent, with the reason, and goes once the template is approved.
 *
 * <p>{@code hospio.texts.allow-only} (env {@code TEXTS_ALLOW_ONLY}) is a comma-separated list of numbers: when set, only
 * those receive SMS or WhatsApp and every other number is held back and recorded as such. Staging sets it, so test guests
 * and staff with made-up or real-looking numbers are never texted; production leaves it empty.
 */
@Slf4j
@Component
public class TextSender {

    /** What happened: sent (and how), or not, with the reason. {@code channel} is how it actually went. */
    public record Outcome(boolean sent, String channel, String providerRef, String note) {
    }

    private final InfobipClient infobip;
    private final TermiiClient termii;
    private final boolean smsEnabled;
    private final boolean whatsAppEnabled;
    private final String language;
    private final Set<String> allowOnly;

    public TextSender(InfobipClient infobip, TermiiClient termii,
                      @Value("${hospio.sms.enabled:false}") boolean smsEnabled,
                      @Value("${hospio.whatsapp.enabled:false}") boolean whatsAppEnabled,
                      @Value("${hospio.whatsapp.language:en}") String language,
                      @Value("${hospio.texts.allow-only:}") String allowOnly) {
        this.infobip = infobip;
        this.termii = termii;
        this.smsEnabled = smsEnabled;
        this.whatsAppEnabled = whatsAppEnabled;
        this.language = StringUtils.hasText(language) ? language.trim() : "en";
        this.allowOnly = Arrays.stream(allowOnly == null ? new String[0] : allowOnly.split(","))
                .map(TextSender::number).filter(StringUtils::hasText).collect(Collectors.toUnmodifiableSet());
    }

    /** Whether this channel goes out at all for this kind of message (switched on and the account configured). */
    public boolean live(String channel, String kind) {
        boolean codes = com.hospio.common.delivery.Delivery.smsAllowed(kind);
        return "SMS".equals(channel) ? codes && smsLive()
                : "WHATSAPP".equals(channel) && ((whatsAppEnabled && infobip.whatsAppConfigured()) || (codes && smsLive()));
    }

    /** Whether a code can go on WhatsApp right now: WhatsApp on and the code template approved. */
    public boolean whatsAppTemplateReady(String template) {
        if (!whatsAppEnabled || !infobip.whatsAppConfigured()) {
            return false;
        }
        InfobipClient.TemplateInfo t = infobip.templates().get(template);
        return t != null && t.approved();
    }

    /** SMS can go: switched on, through Termii when it is on, else Infobip. */
    public boolean smsLive() {
        return smsEnabled && (termii.configured() || infobip.configured());
    }

    /**
     * Sends one text. {@code params} already have {@code @hotel} filled in. {@code smsAllowed}: a one-time code, which may go
     * (or fall back) by SMS; nothing else ever does. Never throws.
     */
    public Outcome send(String channel, String to, String text, String template, List<String> params, boolean smsAllowed) {
        return send(channel, to, text, template, params, smsAllowed, null);
    }

    /** As above; {@code headerImage} is this message's banner (the hotel's colours), null for the Hospio360 one. */
    public Outcome send(String channel, String to, String text, String template, List<String> params, boolean smsAllowed,
                        String headerImage) {
        String number = number(to);
        if (!StringUtils.hasText(number) || number.length() < 10) {
            return new Outcome(false, channel, null, "Not a phone number: " + to);
        }
        if (!allowOnly.isEmpty() && !allowOnly.contains(number)) {
            return new Outcome(false, channel, null, "Held back: not on this environment's allow-list (TEXTS_ALLOW_ONLY)");
        }
        String whyNotWhatsApp = null;
        if ("WHATSAPP".equals(channel)) {
            if (!whatsAppEnabled || !infobip.whatsAppConfigured()) {
                whyNotWhatsApp = "WhatsApp is off";
            } else if (!StringUtils.hasText(template)) {
                whyNotWhatsApp = "no WhatsApp template for this message";
            } else {
                InfobipClient.TemplateInfo t = infobip.templates().get(template);
                if (t == null) {
                    whyNotWhatsApp = "template " + template + " doesn't exist yet";
                } else if (!t.approved()) {
                    whyNotWhatsApp = "template " + template + " is " + t.status().toLowerCase();
                } else {
                    InfobipClient.Sent w = infobip.whatsAppTemplate(number, template, StringUtils.hasText(t.language()) ? t.language() : language,
                            params, t, headerImage);
                    if (w.ok()) {
                        return new Outcome(true, "WHATSAPP", "whatsapp:" + w.messageId(), null);
                    }
                    whyNotWhatsApp = "WhatsApp refused it (" + w.error() + ")";
                    log.warn("WhatsApp {} to {} refused: {}; trying SMS", template, number, w.error());
                }
            }
        }
        if (!smsAllowed) {
            return new Outcome(false, channel, null, (whyNotWhatsApp == null ? "" : whyNotWhatsApp + "; ")
                    + "not sent by SMS: SMS is only used for sign-in codes");
        }
        if (!smsLive()) {
            return new Outcome(false, channel, null, (whyNotWhatsApp == null ? "" : whyNotWhatsApp + "; ") + "SMS is off");
        }
        // One page, always (docs/sms-whatsapp.md): GSM characters, 160 at most, links whole.
        String page = com.hospio.common.delivery.SmsText.fit(text);
        boolean viaTermii = termii.configured();
        InfobipClient.Sent s = viaTermii ? termii.sms(number, page) : infobip.sms(number, page);
        if (s.ok()) {
            return new Outcome(true, "SMS", (viaTermii ? "termii:" : "sms:") + s.messageId(),
                    whyNotWhatsApp == null ? null : "Sent by SMS: " + whyNotWhatsApp);
        }
        return new Outcome(false, channel, null, (whyNotWhatsApp == null ? "" : whyNotWhatsApp + "; ") + "SMS refused (" + s.error() + ")");
    }

    /**
     * A phone number as the provider wants it: digits only, with the country code. Nigerian numbers written locally
     * ("0803…", "803…") get 234 in front; anything already international is kept.
     */
    public static String number(String phone) {
        if (phone == null) {
            return "";
        }
        String d = phone.replaceAll("[^0-9]", "");
        if (d.startsWith("00")) {
            d = d.substring(2);
        }
        if (d.length() == 11 && d.startsWith("0")) {
            return "234" + d.substring(1);
        }
        if (d.length() == 10 && d.matches("^[789].*")) {
            return "234" + d;
        }
        return d;
    }
}
