package com.hospio.platform.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hospio.common.delivery.Delivery;
import com.hospio.common.exception.ConflictException;
import com.hospio.common.exception.ExternalServiceException;
import com.hospio.common.security.JwtUtil;
import com.hospio.platform.dto.HotelFigures;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The platform's typed reads of tenant, identity, payment, reservation and the hotel-overview routes, and their fallbacks. */
class PlatformClientsTest {

    private HttpServer server;
    /** "path?query" or "path" → [status, body]; anything else answers 404. */
    private final Map<String, Object[]> answers = new ConcurrentHashMap<>();
    private String url;
    private final JwtUtil jwt = mock(JwtUtil.class);

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery();
            Object[] a = answers.getOrDefault(query == null ? path : path + "?" + query, answers.getOrDefault(path, new Object[] {404, "{}"}));
            byte[] out = ((String) a[1]).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders((int) a[0], out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(String key, String data) {
        answers.put(key, new Object[] {200, "{\"status\":true,\"message\":\"OK\",\"data\":" + data + "}"});
    }

    @Test
    void aBusinessReadsWithItsHotels() {
        answer("/internal/businesses/b1", "{\"id\":\"b1\",\"name\":\"Royal Suites\",\"stage\":\"LIVE\",\"source\":\"SALES\",\"feeModel\":\"PERCENT_OF_SALES\","
                + "\"feeValue\":1.5,\"hotelCount\":1,\"createdAt\":\"2026-10-01T09:00:00Z\",\"onboarding\":{\"status\":\"DONE\",\"step\":10,\"completedSteps\":[1]},"
                + "\"hotels\":[{\"id\":\"h1\",\"businessId\":\"b1\",\"name\":\"Simpas Ikeja\",\"city\":\"Lagos\",\"status\":\"ACTIVE\",\"roomCount\":24,"
                + "\"managerUserId\":\"u9\",\"createdAt\":\"2026-10-01T09:00:00Z\"}]}");
        TenantClient tenant = new TenantClient(url, jwt, "k");
        TenantClient.Business b = tenant.business("b1").orElseThrow();
        assertThat(b.stage()).isEqualTo("LIVE");
        assertThat(b.feeValue()).isEqualByComparingTo("1.5");
        assertThat(b.hotelCount()).isEqualTo(1);
        assertThat(b.hotels()).containsExactly(new TenantClient.Hotel("h1", "Simpas Ikeja", "Lagos", 24, "ACTIVE", "u9"));
        assertThat(tenant.business("missing")).isEmpty();
    }

    @Test
    void listRowsCarryTheirHotelCountAndEveryPageIsRead() {
        answer("/internal/businesses?marketerId=m1", "[{\"id\":\"b1\",\"name\":\"A\",\"stage\":\"LIVE\",\"hotels\":3,\"rooms\":40,\"feeValue\":null}]");
        answer("/businesses?stage=LIVE&page=0&size=100", "{\"content\":[{\"id\":\"b1\",\"hotels\":1}],\"last\":false,\"counts\":{\"LIVE\":2}}");
        answer("/businesses?stage=LIVE&page=1&size=100", "{\"content\":[{\"id\":\"b2\",\"hotels\":2}]}");
        TenantClient tenant = new TenantClient(url, jwt, "k");
        List<TenantClient.Business> mine = tenant.businesses(null, "m1");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).hotelCount()).isEqualTo(3);
        assertThat(mine.get(0).hotels()).isEmpty();
        assertThat(mine.get(0).feeValue()).isNull();
        assertThat(tenant.businesses("LIVE", null)).extracting(TenantClient.Business::id).containsExactly("b1", "b2");
        assertThatThrownBy(() -> tenant.businesses("CHURNED", null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theSummaryKeepsEveryStageAndTheCountsAreNullWhenMissing() {
        TenantClient tenant = new TenantClient(url, jwt, "k");
        assertThat(tenant.summary()).isEqualTo(TenantClient.Summary.empty());
        assertThat(tenant.onboardingCounts()).isNull();
        assertThat(tenant.onboarding(null)).isEmpty();
        answer("/internal/businesses/summary", "{\"total\":5,\"byStage\":{\"LIVE\":4,\"LEAD\":1},\"hotels\":7,\"rooms\":120}");
        answer("/onboarding", "{\"content\":[],\"counts\":{\"IN_PROGRESS\":3,\"READY_FOR_HANDOVER\":1}}");
        answer("/internal/onboarding", "[{\"id\":\"d1\",\"businessId\":\"b1\",\"businessName\":\"A\",\"source\":\"SALES\",\"step\":4,\"status\":\"IN_PROGRESS\","
                + "\"startedAt\":\"2026-11-01T08:00:00Z\",\"updatedAt\":null,\"data\":{\"1\":{\"x\":1}}}]");
        TenantClient.Summary s = tenant.summary();
        assertThat(s.total()).isEqualTo(5);
        assertThat(s.byStage()).containsEntry("LIVE", 4L).containsEntry("LEAD", 1L).containsEntry("CHURNED", 0L).hasSize(7);
        assertThat(tenant.onboardingCounts()).containsEntry("IN_PROGRESS", 3L).hasSize(2);
        assertThat(tenant.onboarding(null)).singleElement().satisfies(d -> {
            assertThat(d.step()).isEqualTo(4);
            assertThat(d.updatedAt()).isNull();
        });
    }

    @Test
    void aMarketerLoginSkipsDeliveriesThisBuildDoesNotKnow() {
        answer("/internal/users", "{\"userId\":\"u1\",\"temporaryPassword\":\"T3mp\",\"temporaryPasswordExpiresAt\":\"2026-11-20T10:00:00Z\","
                + "\"inviteToken\":\"inv\",\"inviteExpiresAt\":null,\"deliveries\":[{\"channel\":\"EMAIL\",\"to\":\"m@x.ng\",\"subject\":\"Hi\",\"text\":\"t\","
                + "\"kind\":\"INVITE\",\"status\":\"EMAIL_SENT\"},{\"channel\":\"PIGEON\",\"to\":\"x\",\"kind\":\"INVITE\"}]}");
        IdentityClient identity = new IdentityClient(url, jwt, "k");
        IdentityClient.CreatedLogin login = identity.createMarketer("Mo", "m@x.ng", null);
        assertThat(login.userId()).isEqualTo("u1");
        assertThat(login.temporaryPasswordExpiresAt()).isEqualTo(java.time.Instant.parse("2026-11-20T10:00:00Z"));
        assertThat(login.deliveries()).singleElement().satisfies(d -> {
            assertThat(d.channel()).isEqualTo(Delivery.Channel.EMAIL);
            assertThat(d.status()).isEqualTo("EMAIL_SENT");
        });

        answer("/internal/users", "{\"userId\":\"u2\"}");
        assertThat(identity.createMarketer("Mo", "m@x.ng", null).deliveries()).isNull();

        answers.put("/internal/users", new Object[] {409, "{\"status\":false}"});
        assertThatThrownBy(() -> identity.createMarketer("Mo", "m@x.ng", null)).isInstanceOf(ConflictException.class);
        answers.put("/internal/users", new Object[] {500, "{}"});
        assertThatThrownBy(() -> identity.createMarketer("Mo", "m@x.ng", null)).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void contactsReadAndFailSoft() {
        IdentityClient identity = new IdentityClient(url, jwt, "k");
        assertThat(identity.contacts(List.of("u1"))).isEmpty();
        assertThat(identity.contact("u1")).isEmpty();
        answer("/internal/users/contacts", "[{\"id\":\"u1\",\"fullName\":\"Grace\",\"email\":\"g@x.ng\",\"roles\":null,\"emailPreferences\":{}}]");
        answer("/internal/users/u1/contact", "{\"id\":\"u1\",\"fullName\":\"Grace\",\"roles\":[\"OWNER\"]}");
        assertThat(identity.contacts(List.of("u1"))).singleElement().satisfies(c -> assertThat(c.roles()).isEmpty());
        assertThat(identity.contact("u1").orElseThrow().roles()).containsExactly("OWNER");
    }

    @Test
    void paymentAndReservationFiguresReadAndAFailureIsNeverZero() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 31);
        PaymentClient payment = new PaymentClient(url, jwt, "k");
        ReservationClient reservation = new ReservationClient(url, jwt, "k");
        assertThatThrownBy(() -> payment.summary("b1", from, to)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reservation.nights("b1", from, to)).isInstanceOf(IllegalStateException.class);
        answer("/internal/admin/summary", "{\"businessId\":\"b1\",\"gross\":250000.00,\"count\":12,\"byProvider\":[],"
                + "\"byHotel\":[{\"hotelId\":\"h1\",\"gross\":250000.00,\"count\":12}]}");
        answer("/internal/admin/nights", "{\"nights\":40,\"value\":null}");
        PaymentClient.Summary s = payment.summary("b1", from, to);
        assertThat(s.gross()).isEqualByComparingTo("250000");
        assertThat(s.byHotel()).containsExactly(new PaymentClient.HotelTotal("h1", new BigDecimal("250000.00"), 12));
        assertThat(reservation.nights("b1", from, to)).isEqualTo(new ReservationClient.Nights(40, BigDecimal.ZERO));
        answer("/internal/admin/summary", "null");
        assertThat(payment.summary("b1", from, to)).isEqualTo(PaymentClient.Summary.empty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void hotelOverviewFiguresPassThroughUnderTheirServiceNames() throws Exception {
        answer("/internal/admin/hotel-overview", "{\"rooms\":24,\"outOfOrder\":1,\"roomTypes\":3}");
        HotelOverviewClient client = new HotelOverviewClient(url, url + "/down", url, url, url, jwt, "k");
        HotelFigures.Property property = client.property("h1");
        assertThat(property).isEqualTo(new HotelFigures.Property(24L, 1L, 3L));
        assertThat(client.reservation("h1")).isNull();

        HotelFigures.Raw raw = new HotelFigures.Raw(property, null, new HotelFigures.Folio(2L, new BigDecimal("15000")),
                new HotelFigures.Payment("h1", new HotelFigures.PaymentsToday(3L, new BigDecimal("45000")), 0L),
                new HotelFigures.HotelConfig("+234", "MONIEPOINT", 80));
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> json = mapper.readValue(mapper.writeValueAsString(raw), LinkedHashMap.class);
        assertThat(json.keySet()).containsExactly("property", "reservation", "folio", "payment", "hotel-config");
        assertThat((Map<String, Object>) json.get("property")).containsOnlyKeys("rooms", "outOfOrder", "roomTypes");
        assertThat((Map<String, Object>) json.get("payment")).containsOnlyKeys("hotelId", "paymentsToday", "pendingPayments");
        assertThat((Map<String, Object>) json.get("hotel-config")).containsOnlyKeys("whatsappNumber", "posProvider", "setupCompletePct");
        assertThat(json.get("reservation")).isNull();
    }
}
