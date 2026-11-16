package com.hospio.folio.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PaymentClientTest {

    @Test
    void onlyApprovedPaymentsWithDefaultsForMissingFields() {
        try (StubServer s = new StubServer()) {
            PaymentClient client = new PaymentClient(s.url(), StubServer.jwt(), "k");
            s.reply("/internal/payments/by-booking/b1", 200, "{\"status\":true,\"data\":["
                    + "{\"id\":\"p1\",\"status\":\"APPROVED\",\"amount\":5000.00,\"method\":\"TRANSFER\",\"requestedByName\":\"Ada\",\"currency\":\"NGN\"},"
                    + "{\"id\":\"p2\",\"status\":\"PENDING\",\"amount\":100},"
                    + "{\"id\":\"p3\",\"status\":\"APPROVED\",\"amount\":null,\"method\":null}]}");
            assertThat(client.approvedForBooking("b1")).containsExactly(
                    new PaymentClient.ApprovedPayment("p1", new BigDecimal("5000.00"), "TRANSFER", "Ada"),
                    new PaymentClient.ApprovedPayment("p3", BigDecimal.ZERO, "CASH", null));
            assertThat(client.approvedForBooking("missing")).isEmpty();
        }
        assertThat(new PaymentClient(StubServer.downUrl(), StubServer.jwt(), "k").approvedForBooking("b1")).isEmpty();
    }
}
