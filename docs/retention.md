# Retention

What is deleted automatically, and when. Every job runs at night (Lagos time), deletes in small batches (each its own short
transaction, so the tables stay writable), runs on one instance at a time, and logs how much it removed.

| What | Kept | Job | Setting |
|---|---|---|---|
| Notifications (the bell), read or not | 30 days after they were created | messaging `MessagingRetentionJob`, 02:45 | `RETENTION_NOTIFICATIONS_DAYS` (at least 7) |
| Chats: staff Inbox threads and guest conversations, with their messages | 30 days after the last message | messaging `MessagingRetentionJob`, 02:45 | `RETENTION_CHATS_DAYS` (at least 7) |
| Audit logs: the activity log and Changes by Hospio360 | 7 days (a change is never deleted while it can still be undone) | platform `AuditRetentionJob`, 03:30 | Admin portal → Settings → Security → audit retention days (at least 7); `AUDIT_RETENTION_DAYS` when unset |
| Refresh tokens, expired or revoked | 30 days | identity `RefreshTokenCleanupJob`, 03:20 | — |

A chat that is still going (a message within the last 30 days) is kept whole; once quiet for 30 days it goes with all its
messages in one statement. Not touched: delivery records (`messaging.deliveries`: what was sent and what a hotel is billed
for, kept for invoices), bookings, folios, payments, stock and every other business record.

Audit logs are kept 7 days since 19 Nov 2026 (owner decision; migration `platform-026-audit-seven-days` set the value to 7). Every
store, what is deleted and what is deliberately kept: `docs/data-retention.md`. (Before: 365 days, then six months from 2026-11-15.)
