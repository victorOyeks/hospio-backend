package com.hospio.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** docs/sms-whatsapp.md: the booking page link can't be guessed, altered or carried to another environment. */
class StayLinksTest {

    private final StayLinks links = new StayLinks("secret-a", "https://hospio360.com/stay");
    private static final String ID = "4bd3010f-0111-4707-b189-1caeaf78a143";

    @Test
    void aLinkLeadsBackToItsBooking() {
        String t = links.token(ID).orElseThrow();
        assertThat(t).hasSize(38).matches("[A-Za-z0-9_-]+");
        assertThat(links.bookingId(t)).contains(ID);
    }

    @Test
    void anAlteredOrForeignLinkIsRefused() {
        String t = links.token(ID).orElseThrow();
        char sig = t.charAt(30);
        assertThat(links.bookingId(t.substring(0, 30) + (sig == 'A' ? 'B' : 'A') + t.substring(31))).isEmpty();
        char first = t.charAt(0);
        assertThat(links.bookingId((first == 'A' ? 'B' : 'A') + t.substring(1))).isEmpty();
        assertThat(new StayLinks("secret-b", null).bookingId(t)).isEmpty();
        assertThat(links.bookingId("short")).isEmpty();
        assertThat(links.bookingId(null)).isEmpty();
        assertThat(links.bookingId("../../../../etc/passwd/xxxxxxxxxxxxxxxx")).isEmpty();
    }

    @Test
    void theWholeLinkIsTheBookingPage() {
        assertThat(links.url(ID)).contains("https://hospio360.com/stay/" + links.token(ID).orElseThrow());
    }

    @Test
    void onlyUuidBookingsGetALink() {
        assertThat(links.token("b1")).isEmpty();
    }
}
