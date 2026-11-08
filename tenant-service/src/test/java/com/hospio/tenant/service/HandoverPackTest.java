package com.hospio.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hospio.common.delivery.Delivery;
import com.hospio.tenant.dto.HotelResponse;
import com.hospio.tenant.entity.Hotel;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The pack names the business, each hotel with its rooms, each login under its role with its hotel, and the sign-in link; never a password. */
class HandoverPackTest {

    private final HandoverPack pack = new HandoverPack("https://web-staging.hospio360.com/");
    private final ObjectMapper mapper = new ObjectMapper();

    private static HotelResponse hotel(String id, String name, int rooms) {
        return new HotelResponse(id, "b1", name, "Lagos", null, null, null, "Africa/Lagos", Hotel.Status.ACTIVE, null, rooms, "14:00", "12:00",
                null, null, null);
    }

    private JsonNode login(String name, String email, String role, String hotelId, String password) {
        ObjectNode n = mapper.createObjectNode().put("fullName", name).put("email", email).put("role", role);
        if (hotelId == null) {
            n.putNull("hotelId");
        } else {
            n.put("hotelId", hotelId);
        }
        if (password != null) {
            n.put("temporaryPassword", password);
        }
        return n;
    }

    @Test
    void packListsBusinessHotelsLoginsByRoleAndSignInLink() {
        Delivery d = pack.email("b1", "Royal Suites", "Godwin Eze", "godwin@royalsuites.ng",
                List.of(hotel("h1", "Royal Suites Ikeja", 24), hotel("h2", "Royal Suites Lekki", 1)),
                List.of(login("Godwin Eze", "godwin@royalsuites.ng", "OWNER", null, null),
                        login("Tunde Bakare", "tunde@royalsuites.ng", "MANAGER", "h1", "Secret123"),
                        login("Ada Obi", "ada@royalsuites.ng", "MANAGER", "h2", null)));

        assertThat(d.kind()).isEqualTo(Delivery.Kind.HANDOVER_PACK);
        assertThat(d.channel()).isEqualTo(Delivery.Channel.EMAIL);
        assertThat(d.to()).isEqualTo("godwin@royalsuites.ng");
        assertThat(d.ref()).isEqualTo("b1");
        assertThat(d.subject()).contains("Royal Suites");
        String text = d.text();
        assertThat(text).contains("Hello Godwin Eze")
                .contains("Royal Suites Ikeja, Lagos \u00b7 24 rooms")
                .contains("Royal Suites Lekki, Lagos \u00b7 1 room")
                .contains("Owner\n- Godwin Eze \u00b7 godwin@royalsuites.ng\n")
                .contains("Managers\n- Tunde Bakare \u00b7 tunde@royalsuites.ng \u00b7 Royal Suites Ikeja\n- Ada Obi \u00b7 ada@royalsuites.ng \u00b7 Royal Suites Lekki\n")
                .contains("SIGN IN\nhttps://web-staging.hospio360.com\n")
                .doesNotContain("Secret123");
    }

    @Test
    void emptyPackStillReads() {
        String text = pack.text("New Place", null, List.of(), List.of());
        assertThat(text).contains("Hello there").contains("HOTELS\n- none yet").contains("LOGINS\n- none yet");
        assertThat(HandoverPack.label("FRONT_OFFICE")).isEqualTo("Front office");
        assertThat(HandoverPack.label("SOMETHING_ELSE")).isEqualTo("Something else");
    }
}
