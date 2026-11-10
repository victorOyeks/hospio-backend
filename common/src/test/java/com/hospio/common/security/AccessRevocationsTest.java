package com.hospio.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** A token issued before the person's access changed is stale; one issued in that second or later is not. */
class AccessRevocationsTest {

    private final AccessRevocations revocations = new AccessRevocations();

    @Test
    void tokensBeforeAChangeAreStale() {
        Instant change = Instant.parse("2026-11-10T10:00:05.400Z");
        revocations.record("{\"userId\":\"victor\",\"change\":\"REMOVE\"}", change.toEpochMilli());

        assertThat(revocations.isStale("victor", Instant.parse("2026-11-10T09:30:00Z"))).isTrue();
        assertThat(revocations.isStale("victor", Instant.parse("2026-11-10T10:00:04Z"))).isTrue();
        assertThat(revocations.isStale("victor", Instant.parse("2026-11-10T10:00:05Z"))).isFalse();
        assertThat(revocations.isStale("victor", Instant.parse("2026-11-10T10:01:00Z"))).isFalse();
        assertThat(revocations.isStale("grace", Instant.parse("2026-11-10T09:30:00Z"))).isFalse();
    }

    @Test
    void theLatestChangeWinsAndUnreadableRecordsAreIgnored() {
        revocations.record("{\"userId\":\"victor\"}", Instant.parse("2026-11-10T10:00:00Z").toEpochMilli());
        revocations.record("{\"userId\":\"victor\"}", Instant.parse("2026-11-10T09:00:00Z").toEpochMilli());
        revocations.record("not json", Instant.parse("2026-11-10T11:00:00Z").toEpochMilli());
        assertThat(revocations.isStale("victor", Instant.parse("2026-11-10T09:59:00Z"))).isTrue();
        assertThat(revocations.isStale("victor", Instant.parse("2026-11-10T10:30:00Z"))).isFalse();
    }
}
