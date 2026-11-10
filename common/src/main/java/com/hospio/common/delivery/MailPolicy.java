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

    public MailPolicy(@Value("${hospio.mail.enabled:${MAIL_ENABLED:false}}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** What the echo says about a delivery: an email on its way, or not sent (SMS, WhatsApp, or email switched off). */
    public String statusFor(Delivery d) {
        if (d.channel() == Delivery.Channel.EMAIL && enabled) {
            return Delivery.EMAIL_SENT;
        }
        return Delivery.NOT_CONNECTED;
    }
}
