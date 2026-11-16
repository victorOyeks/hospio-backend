package com.hospio.messaging.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.security.JwtUtil;
import com.hospio.messaging.StubHttp;
import com.hospio.messaging.mail.EmailTemplates;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The other services' answers, read into typed records exactly as each endpoint gives them (ApiResponse envelope). */
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

    private static String ok(String data) {
        return ENVELOPE.formatted(data);
    }

    @Test
    void todaysRotaSkipsCancelledShiftsAndKeepsEachPersonOnce() {
        server.on("/workforce/rota", 200, ok("""
                [{"id":"s1","hotelId":"h1","userId":"u1","userName":"Ada","role":"FRONT_DESK","shiftDate":"2026-11-10","startsAt":"08:00:00",
                  "endsAt":"16:30:00","status":"SCHEDULED","clockedIn":true,"attendance":"PRESENT","createdAt":"2026-11-01T08:00:00Z"},
                 {"id":"s2","userId":"u1","userName":"Ada","role":"FRONT_DESK","startsAt":"18:00:00","endsAt":"22:00:00","status":"SCHEDULED"},
                 {"id":"s3","userId":"u2","userName":"Bola","role":"HOUSEKEEPING","startsAt":"07:00:00","endsAt":"15:00:00","status":"CANCELLED"},
                 {"id":"s4","userId":"u3","role":"KITCHEN","startsAt":"09:15:00","endsAt":"17:00:00","status":"SCHEDULED","clockedIn":false}]"""));
        List<FinanceClient.OnShift> rota = new FinanceClient(server.url(), jwt, "key").onShiftToday();
        assertThat(rota).containsExactly(new FinanceClient.OnShift("u1", "Ada", "FRONT_DESK", "08:00", "16:30", true),
                new FinanceClient.OnShift("u3", "", "KITCHEN", "09:15", "17:00", false));
        assertThat(server.last().uri()).startsWith("/workforce/rota?from=").contains("&to=");
    }

    @Test
    void anUnreachableRotaIsAnExternalError() {
        server.on("/workforce/rota", 500, "{}");
        assertThatThrownBy(() -> new FinanceClient(server.url(), jwt, "key").onShiftToday()).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void aBookingIsReadWithItsDatesAndBlankFieldsAsNull() {
        server.on("/bookings/b1", 200, ok("""
                {"id":"b1","reference":"HBT7UFWH","hotelId":"h1","guestId":"g1","guestName":"Ada Obi","roomTypeId":"rt1",
                 "roomTypeName":"Deluxe","roomId":"r1","roomNumber":"101","checkInDate":"2026-11-10","checkOutDate":"2026-11-12","nights":2,
                 "status":"CONFIRMED","nightlyRate":25000.00,"deliveries":[],"guestPhone":"","guestEmail":"ada@example.com","billTotal":50000}"""));
        ReservationClient client = new ReservationClient(server.url(), jwt, "key");
        assertThat(client.booking("b1")).contains(new ReservationClient.Booking("b1", "h1", "HBT7UFWH", "g1", "Ada Obi", "ada@example.com", null,
                "101", "Deluxe", LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 12), "CONFIRMED"));
        assertThat(client.booking("missing")).isEmpty(); // 404
        server.on("/bookings/b2", 200, ok("null"));
        assertThat(client.booking("b2")).isEmpty();
        server.on("/bookings/b3", 503, "{}");
        assertThatThrownBy(() -> client.booking("b3")).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void aHotelsBrandComesFromItsSetupAndFallsBackToHospio360() {
        server.on("/internal/settings/BRAND", 200, ok("""
                {"primaryColour":"#0b4f50","shortName":"Simpas","darkSidebar":true,"logoUrl":"https://cdn.hospio360.com/l.png"}"""));
        HotelBrandClient client = new HotelBrandClient(server.url(), jwt, "key");
        assertThat(client.brand("h1")).isEqualTo(EmailTemplates.Brand.of("#0b4f50", "https://cdn.hospio360.com/l.png"));
        assertThat(server.last().uri()).isEqualTo("/internal/settings/BRAND?hotelId=h1");
        server.on("/internal/settings/BRAND", 200, ok("null"));
        assertThat(new HotelBrandClient(server.url(), jwt, "key").brand("h2")).isEqualTo(EmailTemplates.Brand.HOSPIO);
        server.on("/internal/settings/BRAND", 500, "{}");
        assertThat(new HotelBrandClient(server.url(), jwt, "key").brand("h3")).isEqualTo(EmailTemplates.Brand.HOSPIO);
    }

    @Test
    void aHotelAndABusinessAreReadAsSenders() {
        server.on("/internal/hotels/h1", 200, ok("""
                {"id":"h1","businessId":"b1","name":"Simpas Ikeja","city":"Lagos","address":"1 Allen Ave","phone":" +2348000000000 ",
                 "email":"","timezone":"Africa/Lagos","status":"ACTIVE","roomCount":20,"checkInTime":"14:00","checkOutTime":"12:00"}"""));
        server.on("/internal/businesses/b1", 200, ok("""
                {"id":"b1","name":"Simpas Group","email":"owner@simpas.ng","stage":"LIVE","hotelCount":2,"hotels":[{"id":"h1","name":"x","roomCount":1}],
                 "onboarding":{"status":"DONE","step":5,"completedSteps":[1,2,3]}}"""));
        server.on("/internal/hotels/h2", 200, ok("{\"id\":\"h2\",\"roomCount\":0}"));
        TenantClient client = new TenantClient(server.url(), jwt, "key");
        assertThat(client.hotel("h1")).contains(new TenantClient.Sender("Simpas Ikeja", null, "+2348000000000"));
        assertThat(client.business("b1")).contains(new TenantClient.Sender("Simpas Group", "owner@simpas.ng", null));
        assertThat(client.hotel("h2")).isEmpty();
        assertThat(client.hotel("h404")).isEmpty();
    }

    @Test
    void peopleAreReadWithTheirRoles() {
        server.on("/internal/users/u1/contact", 200, ok("""
                {"id":"u1","fullName":"Ada Obi","email":"ada@example.com","phone":"+2348000000000","businessId":"b1","status":"ACTIVE",
                 "roles":["MANAGER","FRONT_DESK"],"emailPreferences":{"receipts":true}}"""));
        server.on("/internal/users/with-role", 200, ok("""
                [{"id":"u1","fullName":"Ada Obi","status":"ACTIVE","roles":["MANAGER"]},{"id":"u2","fullName":"Bola","roles":null}]"""));
        IdentityClient client = new IdentityClient(server.url(), jwt, "key");
        assertThat(client.contact("u1")).contains(new IdentityClient.Contact("u1", "Ada Obi", "ada@example.com", "+2348000000000", "b1", "ACTIVE",
                List.of("MANAGER", "FRONT_DESK")));
        assertThat(client.contact("nobody")).isEmpty();
        assertThat(client.withRole("h1", "MANAGER")).containsExactly(
                new IdentityClient.Contact("u1", "Ada Obi", null, null, null, "ACTIVE", List.of("MANAGER")),
                new IdentityClient.Contact("u2", "Bola", null, null, null, null, List.of()));
        assertThat(server.last().uri()).isEqualTo("/internal/users/with-role?hotelId=h1&role=MANAGER");
    }
}
