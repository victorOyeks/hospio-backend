package com.hospio.property.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.security.JwtUtil;
import com.hospio.property.StubHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** reservation-service's booking count and tenant-service's hotel, read into typed answers as each endpoint gives them. */
class ServiceClientsReadTest {

    private static final String ENVELOPE = "{\"requestTime\":\"2026-11-10T09:12:01Z\",\"requestType\":\"API\",\"referenceId\":\"r-1\","
            + "\"status\":true,\"message\":\"OK\",\"data\":%s}";

    private final JwtUtil jwt = mock(JwtUtil.class);
    private StubHttp server;

    @BeforeEach
    void start() throws Exception {
        server = new StubHttp();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void aRoomsBookingCount() {
        ReservationClient client = new ReservationClient(server.url(), jwt, "key");
        server.on("/internal/rooms/r1/booking-count", 200, ENVELOPE.formatted("3"));
        assertThat(client.bookingCount("r1")).isEqualTo(3);
        server.on("/internal/rooms/r2/booking-count", 200, ENVELOPE.formatted("null"));
        assertThatThrownBy(() -> client.bookingCount("r2")).isInstanceOf(ExternalServiceException.class)
                .hasMessage("reservation-service gave no booking count");
        assertThatThrownBy(() -> client.bookingCount("r404")).isInstanceOf(ExternalServiceException.class)
                .hasMessage("Could not check the room's bookings right now. Try again in a minute");
    }

    @Test
    void theBusinessAHotelBelongsTo() {
        TenantClient client = new TenantClient(server.url(), jwt, "key");
        server.on("/internal/hotels/h1", 200, ENVELOPE.formatted("""
                {"id":"h1","businessId":"b1","name":"Simpas Ikeja","status":"ACTIVE","roomCount":20,"starRating":null,"createdAt":"2026-01-01T00:00:00Z"}"""));
        assertThat(client.businessOf("h1")).contains("b1");
        server.on("/internal/hotels/h2", 200, ENVELOPE.formatted("{\"id\":\"h2\"}"));
        assertThat(client.businessOf("h2")).isEmpty();
        assertThat(client.businessOf("h404")).isEmpty();
    }
}
