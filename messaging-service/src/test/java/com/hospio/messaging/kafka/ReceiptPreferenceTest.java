package com.hospio.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** "Don't send" on the take-payment screen (NONE) sends no receipt; anything else does. */
class ReceiptPreferenceTest {

    @Test
    void noneAndNeverSendNothing() {
        assertThat(MessagingEventListener.sendsReceipt("NONE")).isFalse();
        assertThat(MessagingEventListener.sendsReceipt("never")).isFalse();
    }

    @Test
    void emailWhatsAppAndTheDefaultSend() {
        assertThat(MessagingEventListener.sendsReceipt("EMAIL")).isTrue();
        assertThat(MessagingEventListener.sendsReceipt("WHATSAPP")).isTrue();
        assertThat(MessagingEventListener.sendsReceipt(null)).isTrue();
    }
}
