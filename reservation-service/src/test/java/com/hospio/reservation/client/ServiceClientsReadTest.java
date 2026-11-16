package com.hospio.reservation.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.security.JwtUtil;
import com.hospio.reservation.StubHttp;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** property-service's rooms and prices and tenant-service's hotel, read into typed records as each endpoint gives them. */
class ServiceClientsReadTest {

    private static final String ENVELOPE = "{\"requestTime\":\"2026-11-10T09:12:01Z\",\"requestType\":\"API\",\"referenceId\":\"r-1\","
            + "\"status\":true,\"message\":\"OK\",\"data\":%s}";
    private static final String ROOM_101 = """
            {"id":"r1","hotelId":"h1","number":"101","floor":"1","areaId":null,"roomTypeId":"rt1","roomTypeName":"Deluxe","basePrice":25000.00,
             "capacityAdults":2,"capacityChildren":1,"status":"IN_SERVICE","outOfOrderReason":null,"outOfOrderUntil":null,"notes":""}""";

    private final JwtUtil jwt = mock(JwtUtil.class);
    private StubHttp server;
    private PropertyClient property;

    @BeforeEach
    void start() throws Exception {
        server = new StubHttp();
        property = new PropertyClient(server.url(), jwt, "key");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private static String ok(String data) {
        return ENVELOPE.formatted(data);
    }

    @Test
    void roomsAreReadWithWhetherTheyAreInService() {
        server.on("/internal/rooms", 200, ok("[" + ROOM_101 + ",{\"id\":\"r2\",\"number\":\"102\",\"roomTypeId\":\"rt1\",\"status\":\"OUT_OF_ORDER\","
                + "\"outOfOrderUntil\":\"2026-11-20\",\"capacityAdults\":2,\"capacityChildren\":0}]"));
        assertThat(property.rooms("h1", "rt1")).containsExactly(new PropertyClient.RoomSummary("r1", "101", "rt1", "Deluxe", true),
                new PropertyClient.RoomSummary("r2", "102", "rt1", null, false));
        assertThat(server.last().uri()).isEqualTo("/internal/rooms?hotelId=h1&roomTypeId=rt1");
        property.rooms("h1", null);
        assertThat(server.last().uri()).isEqualTo("/internal/rooms?hotelId=h1");
        server.on("/internal/rooms", 500, "{}");
        assertThatThrownBy(() -> property.rooms("h1", null)).isInstanceOf(ExternalServiceException.class).hasMessage("Rooms are not available right now");
    }

    @Test
    void oneRoomIsReadOrNotFound() {
        server.on("/internal/rooms/r1", 200, ok(ROOM_101));
        assertThat(property.room("r1")).isEqualTo(new PropertyClient.RoomSummary("r1", "101", "rt1", "Deluxe", true));
        server.on("/internal/rooms/r9", 200, ok("null"));
        assertThatThrownBy(() -> property.room("r9")).isInstanceOf(ExternalServiceException.class).hasMessage("Room not found");
        assertThatThrownBy(() -> property.room("r404")).isInstanceOf(ExternalServiceException.class).hasMessage("That room could not be read");
    }

    @Test
    void aRoomTypesNameAndPrice() {
        server.on("/internal/room-types/rt1", 200, ok("""
                {"id":"rt1","hotelId":"h1","name":"Deluxe","basePrice":25000.00,"capacityAdults":2,"capacityChildren":1,"active":true,
                 "roomCount":4,"specialPrices":[{"date":"2026-12-24","price":30000}]}"""));
        server.on("/internal/room-types/rt1/price", 200, ok("27500.50"));
        assertThat(property.roomTypeName("rt1")).isEqualTo("Deluxe");
        assertThat(property.roomTypeName("rt404")).isNull();
        assertThat(property.price("rt1", LocalDate.of(2026, 12, 24))).isEqualByComparingTo(new BigDecimal("27500.50"));
        assertThat(server.last().uri()).isEqualTo("/internal/room-types/rt1/price?date=2026-12-24");
        server.on("/internal/room-types/rt2/price", 200, ok("null"));
        assertThatThrownBy(() -> property.price("rt2", LocalDate.of(2026, 12, 24))).hasMessage("No price for that room type");
        assertThatThrownBy(() -> property.price("rt404", LocalDate.of(2026, 12, 24))).hasMessage("Prices are not available right now");
    }

    @Test
    void theHotelsCardForTheBookingPage() {
        server.on("/internal/hotels/h1", 200, ok("""
                {"id":"h1","businessId":"b1","name":"Simpas Ikeja","city":" Lagos ","address":"1 Allen Ave","phone":"+2348000000000","email":"",
                 "timezone":"Africa/Lagos","status":"ACTIVE","statusReason":null,"roomCount":20,"checkInTime":"14:00","checkOutTime":"12:00",
                 "starRating":4,"managerUserId":"u1","createdAt":"2026-01-01T00:00:00Z"}"""));
        server.on("/internal/hotels/h2", 200, ok("{\"id\":\"h2\",\"roomCount\":0}"));
        TenantHotelClient tenant = new TenantHotelClient(server.url(), jwt, "key");
        assertThat(tenant.hotel("h1")).contains(new TenantHotelClient.HotelCard("Simpas Ikeja", "1 Allen Ave", "Lagos", "+2348000000000", null,
                "14:00", "12:00"));
        assertThat(tenant.hotel("h2")).isEmpty();
        assertThat(tenant.hotel("h404")).isEmpty();
        assertThat(tenant.hotel(null)).isEmpty();
    }
}
