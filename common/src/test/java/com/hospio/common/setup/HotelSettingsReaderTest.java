package com.hospio.common.setup;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The settings cache: answers and failures are both remembered (failures for less time), and it never grows past its cap. */
class HotelSettingsReaderTest {

    @BeforeEach
    void clear() {
        HotelSettingsReader.clearCache();
    }

    @Test
    void anAnswerIsReadOnceAndThenServedFromTheCache() {
        AtomicInteger calls = new AtomicInteger();
        JsonNode data = JsonNodeFactory.instance.objectNode().put("checkIn", "14:00");
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
