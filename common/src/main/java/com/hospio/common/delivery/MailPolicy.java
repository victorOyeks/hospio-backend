package com.hospio.common.delivery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether email goes out at all: {@code hospio.mail.enabled} (env {@code MAIL_ENABLED}, default false). Every service reads
 * the same environment, so {@link DeliveryService} can say in the echo what messaging-service's sender will do. Every email
 * goes to the address it names, whatever the domain; what the provider answers is recorded (docs/email.md).
 */
@Component
public class MailPolicy {

    private final boolean enabled;
    private final boolean sms;
    private final boolean whatsApp;

    public MailPolicy(@Value("${hospio.mail.enabled:${MAIL_ENABLED:false}}") boolean enabled,
                      @Value("${hospio.sms.enabled:${SMS_ENABLED:false}}") boolean sms,
                      @Value("${hospio.whatsapp.enabled:${WHATSAPP_ENABLED:false}}") boolean whatsApp) {
        this.enabled = enabled;
        this.sms = sms;
        this.whatsApp = whatsApp;
    }


    public boolean enabled() {
        return enabled;
    }

    /**
     * What the echo says about a delivery: an email, SMS or WhatsApp on its way, or not sent (that channel switched off).
     * A WhatsApp with SMS on but WhatsApp off still goes, as an SMS. On staging only allow-listed numbers really receive
     * SMS and WhatsApp (docs/sms-whatsapp.md); the echo can't tell, so it says what the sender will try.
     */
    public String statusFor(Delivery d) {
        return switch (d.channel()) {
            case EMAIL -> enabled ? Delivery.EMAIL_SENT : Delivery.NOT_CONNECTED;
            case SMS -> sms ? Delivery.SMS_SENT : Delivery.NOT_CONNECTED;
            case WHATSAPP -> whatsApp ? Delivery.WHATSAPP_SENT : sms ? Delivery.SMS_SENT : Delivery.NOT_CONNECTED;
        };
    }
}
