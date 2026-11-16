package com.hospio.housekeeping.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.exception.BadRequestException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HousekeepingClientsTest {

    private static LaundryClients laundry(String url) {
        return new LaundryClients(url, url, url, StubServer.jwt(), "k", new ObjectMapper());
    }

    @Test
    void stockMoveLinesAndTheStoresOwnRefusal() {
        try (StubServer s = new StubServer()) {
            LaundryClients c = laundry(s.url());
            s.reply("/internal/stock/moves", 200, "{\"status\":true,\"data\":{\"reference\":\"r\",\"value\":10,\"lines\":["
                    + "{\"itemId\":\"i1\",\"name\":\"Towel\",\"unit\":\"pcs\",\"quantity\":2,\"unitCost\":5,\"value\":10},{\"itemId\":\"i2\"}]}}");
            assertThat(c.move("r", "TRANSFER", "HOUSEKEEPING", "LAUNDRY", "HK", null, null, Map.of("i1", BigDecimal.TWO))).containsExactly(
                    new LaundryClients.Moved("i1", "Towel", "pcs", new BigDecimal("2"), new BigDecimal("5"), new BigDecimal("10")),
                    new LaundryClients.Moved("i2", null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
            s.reply("/internal/stock/moves", 400, "{\"status\":false,\"message\":\"Only 1 Towel in the Housekeeping store\",\"data\":null}");
            assertThatThrownBy(() -> c.move("r", "TRANSFER", "HOUSEKEEPING", "LAUNDRY", "HK", null, null, Map.of("i1", BigDecimal.TWO)))
                    .isInstanceOf(BadRequestException.class).hasMessage("Only 1 Towel in the Housekeeping store");
            s.reply("/internal/stock/moves", 400, "not json");
            assertThatThrownBy(() -> c.move("r", "USE", "LAUNDRY", null, "HK", null, null, Map.of("i1", BigDecimal.ONE)))
                    .isInstanceOf(BadRequestException.class).hasMessage("The store couldn't move that linen right now");
        }
    }

    @Test
    void paymentItemsAndArrivals() {
        try (StubServer s = new StubServer()) {
            LaundryClients c = laundry(s.url());
            s.reply("/internal/payments/p1", 200, "{\"status\":true,\"data\":{\"id\":\"p1\",\"status\":\"APPROVED\",\"amount\":2500,\"method\":\"CASH\"}}");
            assertThat(c.payment("p1")).isEqualTo(new LaundryClients.Payment("p1", "APPROVED", new BigDecimal("2500"), "CASH"));
            assertThatThrownBy(() -> c.payment("missing")).isInstanceOf(BadRequestException.class).hasMessage("That payment isn't in this hotel");
            s.reply("/internal/items", 200, "{\"status\":true,\"data\":[{\"id\":\"i1\",\"name\":\"Towel\",\"unit\":\"pcs\",\"category\":\"Linen\",\"active\":false},"
                    + "{\"id\":\"i2\",\"name\":\"Sheet\"}]}");
            Map<String, String[]> items = c.linenItems();
            assertThat(items.get("i1")).containsExactly("Towel", "pcs", "Linen", "false");
            assertThat(items.get("i2")).containsExactly("Sheet", null, "", "true");

            ReservationClient reservations = new ReservationClient(s.url(), StubServer.jwt(), "k");
            s.reply("/internal/arrivals", 200, "{\"status\":true,\"data\":[{\"id\":\"b1\",\"roomId\":\"r1\"},{\"id\":\"b2\",\"roomId\":null}]}");
            assertThat(reservations.roomsWithArrivalOn("h1", LocalDate.of(2026, 11, 16))).containsExactly("r1");
            assertThat(reservations.activeStay("r9")).isEmpty();
        }
        assertThat(new ReservationClient(StubServer.downUrl(), StubServer.jwt(), "k").roomsWithArrivalOn("h1", LocalDate.now())).isEmpty();
        assertThat(new PropertyClient(StubServer.downUrl(), StubServer.jwt(), "k").rooms("h1")).isEmpty();
    }

    @Test
    void repairIdOrMaintenancesSentenceOrNullWhenDown() {
        try (StubServer s = new StubServer()) {
            MaintenanceClient c = new MaintenanceClient(s.url(), StubServer.jwt(), "k");
            s.reply("/internal/repairs", 200, "{\"status\":true,\"message\":\"Work order opened\",\"data\":{\"id\":\"wo1\",\"title\":\"Leak\"}}");
            assertThat(c.openRepair("r1", "204", "Leak", null)).isEqualTo("wo1");
            s.reply("/internal/repairs", 400, "{\"status\":false,\"message\":\"Ada is checked in to 204\"}");
            assertThatThrownBy(() -> c.openRepair("r1", "204", "Leak", null, "HIGH", true)).isInstanceOf(BadRequestException.class)
                    .hasMessage("Ada is checked in to 204");
        }
        assertThat(new MaintenanceClient(StubServer.downUrl(), StubServer.jwt(), "k").openRepair("r1", "204", "Leak", null)).isNull();
    }
}
