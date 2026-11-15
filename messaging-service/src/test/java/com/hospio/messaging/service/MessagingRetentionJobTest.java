package com.hospio.messaging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hospio.messaging.repository.ConversationRepository;
import com.hospio.messaging.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** docs/retention.md: notifications and quiet chats go after 30 days, batch by batch. */
class MessagingRetentionJobTest {

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private static final Instant NOW = Instant.parse("2027-01-31T01:45:00Z");

    private MessagingRetentionJob job(int notificationDays, int chatDays) {
        MessagingRetentionJob j = new MessagingRetentionJob(notifications, conversations, notificationDays, chatDays);
        j.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return j;
    }

    @Test
    void thirtyDaysByDefaultAndBatchByBatchUntilFewerThanABatchAreLeft() {
        Instant cutoff = Instant.parse("2027-01-01T01:45:00Z");
        when(notifications.deleteCreatedBefore(eq(cutoff), anyInt())).thenReturn(5000, 5000, 120);
        when(conversations.deleteQuietSince(eq(cutoff), anyInt())).thenReturn(7);
        int[] gone = job(30, 30).run();
        assertThat(gone).containsExactly(10_120, 7);
        verify(notifications, times(3)).deleteCreatedBefore(eq(cutoff), eq(MessagingRetentionJob.BATCH));
        verify(conversations, times(1)).deleteQuietSince(eq(cutoff), eq(MessagingRetentionJob.BATCH));
    }

    @Test
    void neverLessThanAWeekWhateverTheSetting() {
        when(notifications.deleteCreatedBefore(eq(Instant.parse("2027-01-24T01:45:00Z")), anyInt())).thenReturn(0);
        when(conversations.deleteQuietSince(eq(Instant.parse("2027-01-24T01:45:00Z")), anyInt())).thenReturn(0);
        assertThat(job(1, 0).run()).containsExactly(0, 0);
        verify(notifications).deleteCreatedBefore(eq(Instant.parse("2027-01-24T01:45:00Z")), anyInt());
    }
}
