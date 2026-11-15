package com.hospio.common.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Hotel setup → Guest messaging as the senders read it. */
class GuestMessagingTest {

    @Test
    void theHotelsNumberBecomesWaMeDigits() throws Exception {
        var n = new ObjectMapper().readTree("{\"whatsappNumber\":\"0803 111 0001\",\"sendConfirmation\":false,\"arrivalMessage\":true,\"sendReceipts\":false}");
        GuestMessaging g = GuestMessaging.of(Optional.of(n));
        assertThat(g.whatsappNumber()).isEqualTo("2348031110001");
        assertThat(g.sendConfirmation()).isFalse();
        assertThat(g.sendReceipts()).isFalse();
        assertThat(GuestMessaging.shown("2348031110001")).isEqualTo("+234 803 111 0001");
    }

    @Test
    void unreadableOrEmptyMeansEverythingOnAndNoNumber() throws Exception {
        assertThat(GuestMessaging.of(Optional.empty())).isEqualTo(GuestMessaging.DEFAULTS);
        assertThat(GuestMessaging.of(Optional.of(new ObjectMapper().readTree("{\"whatsappNumber\":\"\"}"))).whatsappNumber()).isNull();
        assertThat(GuestMessaging.whatsApp("call me")).isNull();
        assertThat(GuestMessaging.whatsApp("+44 7700 900123")).isEqualTo("447700900123");
    }
}
