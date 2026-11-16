package com.hospio.fnb.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hospio.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

class FnbClientsTest {

    @Test
    void activeStayIsReadAndEmptyWhenNobodyIsIn() {
        try (StubServer s = new StubServer()) {
            ReservationClient client = new ReservationClient(s.url(), StubServer.jwt(), "k");
            s.reply("/internal/rooms/r1/active-booking", 200, "{\"status\":true,\"data\":{\"id\":\"b1\",\"guestName\":\"Ada\",\"roomNumber\":\"204\","
                    + "\"paymentPlan\":\"COMPANY\",\"organizationId\":\"org1\",\"status\":\"CHECKED_IN\"}}");
            ReservationClient.ActiveStay stay = new ReservationClient.ActiveStay("b1", "Ada", "204", "COMPANY", "org1");
            assertThat(client.findActiveStay("r1")).contains(stay);
            assertThat(client.activeStay("r1")).isEqualTo(stay);
            assertThat(client.findActiveStay("r2")).isEmpty();
            assertThatThrownBy(() -> client.activeStay("r2")).isInstanceOf(BadRequestException.class).hasMessage("Nobody is checked in to that room");
            s.reply("/internal/rooms/r3/active-booking", 500, "{\"status\":false}");
            assertThatThrownBy(() -> client.findActiveStay("r3")).isInstanceOf(BadRequestException.class).hasMessage("Can't check that room right now");
        }
    }

    @Test
    void roomNumberOrNull() {
        try (StubServer s = new StubServer()) {
            PropertyClient client = new PropertyClient(s.url(), StubServer.jwt(), "k");
            s.reply("/internal/rooms/r1", 200, "{\"status\":true,\"data\":{\"id\":\"r1\",\"number\":\"204\"}}");
            assertThat(client.roomNumber("r1")).isEqualTo("204");
            assertThat(client.roomNumber("missing")).isNull();
        }
        assertThat(new PropertyClient(StubServer.downUrl(), StubServer.jwt(), "k").roomNumber("r1")).isNull();
    }
}
