package com.hospio.maintenance.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaintenanceClientsTest {

    @Test
    void partsUsedAndTheStoresOwnRefusal() {
        try (StubServer s = new StubServer()) {
            InventoryClient c = new InventoryClient(s.url(), StubServer.jwt(), "k", new ObjectMapper());
            List<Map<String, Object>> lines = List.of(Map.of("itemId", "i1", "quantity", 1));
            s.reply("/internal/stock/moves", 200, "{\"status\":true,\"data\":{\"lines\":[{\"itemId\":\"i1\",\"name\":\"Bulb\",\"unit\":\"pcs\","
                    + "\"quantity\":1,\"unitCost\":800,\"value\":800}],\"repeated\":false}}");
            assertThat(c.useParts("wo1", "note", lines)).containsExactly(
                    new InventoryClient.Part("i1", "Bulb", "pcs", BigDecimal.ONE, new BigDecimal("800"), new BigDecimal("800")));
            s.reply("/internal/stock/moves", 400, "{\"status\":false,\"message\":\"No Bulb left in the Maintenance store\"}");
            assertThatThrownBy(() -> c.useParts("wo1", "note", lines)).isInstanceOf(BadRequestException.class)
                    .hasMessage("No Bulb left in the Maintenance store");
            s.reply("/internal/stock/moves", 400, "");
            assertThatThrownBy(() -> c.useParts("wo1", "note", lines)).isInstanceOf(BadRequestException.class)
                    .hasMessage("The Maintenance store couldn't give those parts right now");
        }
    }

    @Test
    void roomAndGuest() {
        try (StubServer s = new StubServer()) {
            PropertyClient rooms = new PropertyClient(s.url(), StubServer.jwt(), "k");
            ReservationClient reservations = new ReservationClient(s.url(), StubServer.jwt(), "k");
            s.reply("/internal/rooms/r1", 200, "{\"status\":true,\"data\":{\"id\":\"r1\",\"number\":\"204\",\"status\":\"OUT_OF_ORDER\"}}");
            assertThat(rooms.room("r1")).isEqualTo(new PropertyClient.Room("r1", "204", false));
            assertThatThrownBy(() -> rooms.room("missing")).isInstanceOf(BadRequestException.class).hasMessage("That room isn't in this hotel");
            s.reply("/internal/rooms/r1/active-booking", 200, "{\"status\":true,\"data\":{\"id\":\"b1\",\"guestName\":\"Ada\"}}");
            s.reply("/internal/rooms/r2/active-booking", 200, "{\"status\":true,\"data\":{\"id\":\"b2\",\"guestName\":\" \"}}");
            assertThat(reservations.guestIn("r1")).contains("Ada");
            assertThat(reservations.guestIn("r2")).contains("a guest");
            assertThat(reservations.guestIn("r3")).isEmpty();
        }
        assertThatThrownBy(() -> new ReservationClient(StubServer.downUrl(), StubServer.jwt(), "k").guestIn("r1"))
                .isInstanceOf(BadRequestException.class).hasMessage("Can't check who is in the room right now · try again");
    }
}
