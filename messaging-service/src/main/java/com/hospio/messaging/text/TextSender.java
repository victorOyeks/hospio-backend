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
 * approved; otherwise (no template, not approved yet, or WhatsApp refused it) the same text goes as an SMS, so the person
 * still hears. When a template is approved later it is used from then on with no change here.
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
    private final boolean smsEnabled;
    private final boolean whatsAppEnabled;
    private final String language;
    private final Set<String> allowOnly;

    public TextSender(InfobipClient infobip,
                      @Value("${hospio.sms.enabled:false}") boolean smsEnabled,
                      @Value("${hospio.whatsapp.enabled:false}") boolean whatsAppEnabled,
                      @Value("${hospio.whatsapp.language:en}") String language,
                      @Value("${hospio.texts.allow-only:}") String allowOnly) {
        this.infobip = infobip;
        this.smsEnabled = smsEnabled;
        this.whatsAppEnabled = whatsAppEnabled;
        this.language = StringUtils.hasText(language) ? language.trim() : "en";
        this.allowOnly = Arrays.stream(allowOnly == null ? new String[0] : allowOnly.split(","))
                .map(TextSender::number).filter(StringUtils::hasText).collect(Collectors.toUnmodifiableSet());
    }

    /** Whether this channel goes out at all (switched on and the account configured). */
    public boolean live(String channel) {
        return "SMS".equals(channel) ? smsEnabled && infobip.configured()
                : "WHATSAPP".equals(channel) && ((whatsAppEnabled && infobip.whatsAppConfigured()) || (smsEnabled && infobip.configured()));
    }

    /** Whether a code can go on WhatsApp right now: WhatsApp on and the code template approved. */
    public boolean whatsAppTemplateReady(String template) {
        if (!whatsAppEnabled || !infobip.whatsAppConfigured()) {
            return false;
        }
        InfobipClient.TemplateInfo t = infobip.templates().get(template);
        return t != null && t.approved();
    }

    public boolean smsLive() {
        return smsEnabled && infobip.configured();
    }

    /**
     * Sends one text. {@code params} already have {@code @hotel} filled in. Never throws.
     */
    public Outcome send(String channel, String to, String text, String template, List<String> params) {
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
                            params, "AUTHENTICATION".equalsIgnoreCase(t.category()));
                    if (w.ok()) {
                        return new Outcome(true, "WHATSAPP", "whatsapp:" + w.messageId(), null);
                    }
                    whyNotWhatsApp = "WhatsApp refused it (" + w.error() + ")";
                    log.warn("WhatsApp {} to {} refused: {}; trying SMS", template, number, w.error());
                }
            }
        }
        if (!smsEnabled || !infobip.configured()) {
            return new Outcome(false, channel, null, (whyNotWhatsApp == null ? "" : whyNotWhatsApp + "; ") + "SMS is off");
        }
        // One page, always (docs/sms-whatsapp.md): GSM characters, 160 at most, links whole.
        InfobipClient.Sent s = infobip.sms(number, com.hospio.common.delivery.SmsText.fit(text));
        if (s.ok()) {
            return new Outcome(true, "SMS", "sms:" + s.messageId(), whyNotWhatsApp == null ? null : "Sent by SMS: " + whyNotWhatsApp);
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
