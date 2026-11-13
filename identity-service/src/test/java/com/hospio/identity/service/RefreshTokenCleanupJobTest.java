package com.hospio.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.identity.repository.RefreshTokenRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The nightly clean-up deletes in batches until a batch comes back short. */
class RefreshTokenCleanupJobTest {

    @Test
    void deletesBatchByBatchUntilNothingIsLeft() {
        RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);
        when(tokens.deleteStale(any(), eq(RefreshTokenCleanupJob.BATCH))).thenReturn(RefreshTokenCleanupJob.BATCH, RefreshTokenCleanupJob.BATCH, 42);

        int deleted = new RefreshTokenCleanupJob(tokens).purge(Instant.now());

        assertThat(deleted).isEqualTo(2 * RefreshTokenCleanupJob.BATCH + 42);
        verify(tokens, times(3)).deleteStale(any(), eq(RefreshTokenCleanupJob.BATCH));
    }
}
