package com.hospio.common.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** docs/sms-whatsapp.md: SMS is only for one-time codes, and the echo says so. */
class MailPolicyTest {

    private final MailPolicy policy = new MailPolicy(true, true, false);

    @Test
    void onlyAPhoneCodeGoesBySms() {
        assertThat(policy.statusFor(Delivery.sms("0803", "Your code is 1", Delivery.Kind.PHONE_CODE, "u1"))).isEqualTo(Delivery.SMS_SENT);
        assertThat(policy.statusFor(Delivery.sms("0803", "Receipt", Delivery.Kind.RECEIPT, "p1"))).isEqualTo(Delivery.NOT_CONNECTED);
    }

    @Test
    void withWhatsAppOffOnlyACodeFallsBackToSms() {
        assertThat(policy.statusFor(Delivery.whatsApp("0803", "s", "Your code is 1", Delivery.Kind.PHONE_CODE, "u1"))).isEqualTo(Delivery.SMS_SENT);
        assertThat(policy.statusFor(Delivery.whatsApp("0803", "s", "Booked", Delivery.Kind.BOOKING_CONFIRMATION, "b1"))).isEqualTo(Delivery.NOT_CONNECTED);
    }
}
