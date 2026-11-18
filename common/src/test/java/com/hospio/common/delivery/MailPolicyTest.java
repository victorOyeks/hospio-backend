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

    @Test
    void withWhatsAppOnOnlyAKindWithATemplateGoesOnWhatsApp() {
        MailPolicy on = new MailPolicy(true, true, true);
        assertThat(on.statusFor(Delivery.whatsApp("0803", "s", "Receipt", Delivery.Kind.RECEIPT, "p1"))).isEqualTo(Delivery.WHATSAPP_SENT);
        assertThat(on.statusFor(Delivery.whatsApp("0803", "s", "Booked", Delivery.Kind.BOOKING_CONFIRMATION, "b1"))).isEqualTo(Delivery.WHATSAPP_SENT);
        // No template: the folio, a typed guest message and a staff notice never go on WhatsApp.
        assertThat(on.statusFor(Delivery.whatsApp("0803", "s", "Folio", Delivery.Kind.FOLIO, "f1"))).isEqualTo(Delivery.NOT_CONNECTED);
        assertThat(on.statusFor(Delivery.whatsApp("0803", "s", "Hi", Delivery.Kind.MESSAGE, "m1"))).isEqualTo(Delivery.NOT_CONNECTED);
        assertThat(on.statusFor(Delivery.whatsApp("0803", "s", "Hi", Delivery.Kind.NOTICE, "n1"))).isEqualTo(Delivery.NOT_CONNECTED);
    }

    @Test
    void theTemplateListIsTheOneSource() {
        assertThat(WhatsAppTemplates.forKind("RECEIPT")).isEqualTo("hospio_payment_receipt_v2");
        assertThat(WhatsAppTemplates.forKind("receipt")).isEqualTo("hospio_payment_receipt_v2");
        assertThat(WhatsAppTemplates.capable("STAY_SUMMARY")).isFalse();
        assertThat(WhatsAppTemplates.capable("FOLIO")).isFalse();
        assertThat(WhatsAppTemplates.capable((String) null)).isFalse();
        assertThat(WhatsAppTemplates.byKind()).containsKeys("BOOKING_CONFIRMATION", "BOOKING_CHANGED", "BOOKING_CANCELLED",
                "ARRIVAL_REMINDER", "RECEIPT", "PHONE_CODE").hasSize(6);
    }
}
