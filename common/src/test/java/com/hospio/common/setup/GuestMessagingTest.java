package com.hospio.common.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Hotel setup → Guest messaging as the senders read it. */
class GuestMessagingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void theHotelsNumberBecomesWaMeDigits() throws Exception {
        var m = mapper.readValue("{\"whatsappNumber\":\"0803 111 0001\",\"sendConfirmation\":false,\"arrivalMessage\":true,\"sendReceipts\":false}",
                SetupSection.Messaging.class);
        GuestMessaging g = GuestMessaging.of(Optional.of(m));
        assertThat(g.whatsappNumber()).isEqualTo("2348031110001");
        assertThat(g.sendConfirmation()).isFalse();
        assertThat(g.sendReceipts()).isFalse();
        assertThat(GuestMessaging.shown("2348031110001")).isEqualTo("+234 803 111 0001");
    }

    @Test
    void unreadableOrEmptyMeansEverythingOnAndNoNumber() throws Exception {
        assertThat(GuestMessaging.of(Optional.empty())).isEqualTo(GuestMessaging.DEFAULTS);
        assertThat(GuestMessaging.of(Optional.of(mapper.readValue("{\"whatsappNumber\":\"\"}", SetupSection.Messaging.class))).whatsappNumber()).isNull();
        assertThat(GuestMessaging.whatsApp("call me")).isNull();
        assertThat(GuestMessaging.whatsApp("+44 7700 900123")).isEqualTo("447700900123");
    }

    @Test
    void aSwitchTheSectionLeavesOutStaysOn() throws Exception {
        GuestMessaging g = GuestMessaging.of(Optional.of(mapper.readValue("{\"arrivalMessage\":false}", SetupSection.Messaging.class)));
        assertThat(g.arrivalMessage()).isFalse();
        assertThat(g.sendConfirmation()).isTrue();
        assertThat(g.sendReceipts()).isTrue();
    }
}
