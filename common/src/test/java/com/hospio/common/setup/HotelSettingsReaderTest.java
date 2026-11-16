package com.hospio.common.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * The settings reader: hotel-config's envelope read into the typed section, answers and failures both remembered (failures for
 * less time), and the cache never grows past its cap.
 */
class HotelSettingsReaderTest {

    private HttpServer server;
    private final AtomicReference<String> answer = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        HotelSettingsReader.clearCache();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            lastPath.set(exchange.getRequestURI().toString());
            byte[] body = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HotelSettingsReader reader() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new HotelSettingsReader(url, RestClient.builder().baseUrl(url).build());
    }

    @Test
    void hotelConfigsEnvelopeIsReadIntoTheTypedSectionAndCached() {
        answer.set("{\"requestTime\":\"2026-11-16T10:00:00Z\",\"requestType\":\"API\",\"referenceId\":\"r\",\"status\":true,\"message\":\"OK\","
                + "\"data\":{\"allowCash\":false,\"allowCard\":true,\"receipts\":\"EMAIL\",\"depositPct\":25,\"tillFloat\":15000.5,\"futureKey\":1}}");
        HotelSettingsReader reader = reader();

        Optional<SetupSection.Payments> p = reader.section("h1", SetupSection.Payments.class);

        assertThat(p).isPresent();
        assertThat(p.get().allowCash()).isFalse();
        assertThat(p.get().receipts()).isEqualTo("EMAIL");
        assertThat(p.get().depositPct()).isEqualByComparingTo("25");
        assertThat(p.get().tillFloat()).isEqualByComparingTo("15000.5");
        assertThat(p.get().allowTransfer()).as("a key hotel-config left out is null, so the caller falls back").isNull();
        assertThat(lastPath.get()).isEqualTo("/internal/settings/PAYMENTS?hotelId=h1");

        reader.section("h1", SetupSection.Payments.class);
        assertThat(hits).hasValue(1);
    }

    @Test
    void anEmptySectionAnswersEmpty() {
        answer.set("{\"status\":true,\"data\":{}}");
        assertThat(reader().section("h1", SetupSection.Staff.class)).isEmpty();
        answer.set("{\"status\":true,\"data\":null}");
        assertThat(reader().section("h2", SetupSection.Staff.class)).isEmpty();
        assertThat(reader().section(null, SetupSection.Staff.class)).isEmpty();
    }

    @Test
    void aDownHotelConfigAnswersEmptyAndIsNotCalledAgainStraightAway() {
        answer.set("{\"status\":true,\"data\":{\"lateMinutes\":5}}");
        server.stop(0);
        HotelSettingsReader reader = reader();
        assertThat(reader.section("h1", SetupSection.Staff.class)).isEmpty();
        // Remembered as a failure: even with hotel-config back, the cached empty answer is served for now.
        assertThat(reader.section("h1", SetupSection.Staff.class)).isEmpty();
        assertThat(hits).hasValue(0);
    }

    @Test
    void anAnswerIsReadOnceAndThenServedFromTheCache() {
        AtomicInteger calls = new AtomicInteger();
        SetupSection.Basics data = new SetupSection.Basics("14:00", null, null, null);
        for (int i = 0; i < 3; i++) {
            assertThat(HotelSettingsReader.cached("u|h1|stay", () -> {
                calls.incrementAndGet();
                return Optional.of(data);
            })).contains(data);
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void aFailureIsRememberedSoADownHotelConfigIsNotCalledEveryTime() {
        AtomicInteger calls = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            assertThat(HotelSettingsReader.cached("u|h1|stay", () -> {
                calls.incrementAndGet();
                return Optional.empty();
            })).isEmpty();
        }
        assertThat(calls).hasValue(1);
    }

    @Test
    void theCacheIsBounded() {
        for (int i = 0; i < HotelSettingsReader.MAX_ENTRIES + 100; i++) {
            HotelSettingsReader.cached("u|h" + i + "|stay", Optional::empty);
        }
        assertThat(HotelSettingsReader.cacheSize()).isEqualTo(HotelSettingsReader.MAX_ENTRIES);
    }
}
