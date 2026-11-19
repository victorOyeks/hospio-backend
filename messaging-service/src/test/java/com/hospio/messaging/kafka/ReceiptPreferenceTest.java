package com.hospio.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** "Don't send" on the take-payment screen (NONE, or PRINT from an older screen) sends no receipt; anything else sends the WhatsApp one. */
class ReceiptPreferenceTest {

    @Test
    void noneAndNeverSendNothing() {
        assertThat(MessagingEventListener.sendsReceipt("NONE")).isFalse();
        assertThat(MessagingEventListener.sendsReceipt("never")).isFalse();
        assertThat(MessagingEventListener.sendsReceipt("PRINT")).isFalse();
    }

    @Test
    void emailWhatsAppAndTheDefaultSend() {
        assertThat(MessagingEventListener.sendsReceipt("EMAIL")).isTrue();
        assertThat(MessagingEventListener.sendsReceipt("WHATSAPP")).isTrue();
        assertThat(MessagingEventListener.sendsReceipt(null)).isTrue();
    }
}
