# Retention

What is deleted automatically, and when. Every job runs at night (Lagos time), deletes in small batches (each its own short
transaction, so the tables stay writable), runs on one instance at a time, and logs how much it removed.

| What | Kept | Job | Setting |
|---|---|---|---|
| Notifications (the bell), read or not | 30 days after they were created | messaging `MessagingRetentionJob`, 02:45 | `RETENTION_NOTIFICATIONS_DAYS` (at least 7) |
| Chats: staff Inbox threads and guest conversations, with their messages | 30 days after the last message | messaging `MessagingRetentionJob`, 02:45 | `RETENTION_CHATS_DAYS` (at least 7) |
| Activity log | 183 days (six months) | platform `ActivityRetentionJob`, 02:15 | Admin portal → Settings → Security → audit retention days (at least 30) |
| Refresh tokens, expired or revoked | 30 days | identity `RefreshTokenCleanupJob`, 03:20 | — |

A chat that is still going (a message within the last 30 days) is kept whole; once quiet for 30 days it goes with all its
messages in one statement. Not touched: delivery records (`messaging.deliveries`: what was sent and what a hotel is billed
for, kept for invoices), bookings, folios, payments, stock and every other business record.

The activity log's six months came in on 2026-11-15: migration `platform-007-activity-six-months` moved the seeded 365 to
183, leaving any value an admin had chosen.
