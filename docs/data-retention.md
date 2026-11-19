# Data retention

Every store that is deleted automatically, and the ones that look like logs but are kept. Owner decision, 19 Nov 2026:
**audit logs (who changed what, when) are kept 7 days and then permanently deleted, to manage database space. Nothing else is
deleted by this rule.** The nightly jobs and their settings are in `docs/retention.md`.

## Deleted after 7 days (audit logs)

| Store | What it is | Where it shows | Job |
|---|---|---|---|
| `platform.activity_log` | One line per action, from every service's events: who did what, when ("Ada checked in Room 12") | Hotel app → Activity log (manager), Owner → Operations → Activity log, admin → business → Activity tab, admin → Changes by Hospio360 → Admin activity | platform `AuditRetentionJob`, 03:30 Lagos |
| `platform.platform_changes` | Changes Hospio360 made inside a business, with the reason, before/after and the undo state | admin → Changes by Hospio360 → Changes in businesses, business Activity tab, Owner → Operations → Changes by Hospio360 | the same job |

How long: admin portal → Settings → Security → audit retention days (7; it can be set longer, never shorter than 7). When the
setting is missing the property `hospio.retention.audit-days` (`AUDIT_RETENTION_DAYS`, 7) applies. The job deletes 5,000 rows per
statement (each its own short transaction), runs as the platform, and writes how many rows went to the platform-service log only.
Each page shows "Records are kept for N days, then deleted automatically to save database space." from
`GET /platform/activity/retention` → `{days}` (anyone signed in).

Safety rules:

- **Undo.** A change by Hospio360 is deleted only once it is older than the retention **and** its undo window (`undo_until`, 7 days
  after it was made) has ended. A change that can still be undone is never deleted; a 6-day-old change can be undone as before.
- **Not read by anything else.** "Last changed by", the current fee and addendum reference (`tenant.businesses.fee_addendum_ref`,
  `platform.fee_rules`), suspension state, referral/marketer attribution, integration settings and legal versions are all read from
  their own records, never from these logs.
- **Usage, health scores and alerts** used to count events in the activity log for up to a year (admin → business → Usage: 8 weeks,
  30 days, features ever used; health score and "night audit missed": ever closed a day). Before an entry is deleted it is added to
  `platform.activity_daily_counts` (business, Lagos day, event type, how many; no person and no text, kept 400 days) and its person to
  `platform.activity_people_seen` (business, person id, last seen; kept 31 days, read only for "active people in 30 days"). These are
  counts, not an audit trail. Windows of 7 days or less still read the log.

QA (staging only, `hospio.qa.tools` = `QA_TOOLS`, by default `QA_FEE_SIMULATION`): `POST /platform/admin/qa/retention/run` (SUPER_ADMIN)
runs the job now and answers `{days, activityDeleted, changesDeleted}`.

## Deleted on their own schedule (not audit logs, unchanged)

| Store | Kept | Job |
|---|---|---|
| `messaging.notifications` (the bell) | 30 days | messaging `MessagingRetentionJob` |
| `messaging.conversations` + `messaging.messages` (chats) | 30 days after the last message | messaging `MessagingRetentionJob` |
| `identity.refresh_tokens`, expired or revoked | 30 days | identity `RefreshTokenCleanupJob` |
| `platform.system_snapshots` (System health trends) | 7 days | platform `SnapshotStore` |

## Kept: records that look like logs

Never deleted by any retention job: they are business, financial or legal records, or the current state of something.

| Store | Why it stays |
|---|---|
| `platform.legal_acceptances` | Proof of consent (append-only; `docs/legal-acceptance.md`) |
| `platform.legal_documents` | The versions people accepted |
| `messaging.deliveries`, `messaging.comm_invoices`, `messaging.outbound_messages` | What was sent and billed (guest message invoices) and the send queue |
| `platform.monthly_invoices`, `_lines`, `_payments`, `platform.monthly_invoice_events` | Invoices and their notices: the events stop a reminder being sent twice and show what the owner was told before a restriction |
| `platform.suspension_notices` | The 7 days' notice before a suspension (Terms 7.3), proof it was sent |
| `platform.daily_fees`, `fee_statements`, `fee_payments`, `fee_collections`, `fee_rules` | Fees, what was charged and the rate in force on each day |
| `platform.commissions`, `marketer_statements`, `marketers`, `marketer_profiles`, `referrals` | Commission and attribution records |
| `platform.device_packages`, `device_invoices`, `device_payments` | Device packages and their payments |
| `platform.approvals`, `fnb.price_change_requests` | Requests and their decisions (part of the spend/discount/price record) |
| `platform.support_tickets`, `support_ticket_comments` | Support cases |
| `platform.alerts`, `health_scores`, `system_alert_states` | Current state, replaced or dismissed in place |
| `identity.support_access_grants` | Current "view as owner" grants with their reason and expiry |
| `tenant.businesses` suspension fields | The current suspension state (its history lines are in the activity log / Changes by Hospio360 and go after 7 days) |
| `finance.night_audits`, `tills`, `till_entries`, `shifts`, `clock_entries`, `revenue_entries`, `expenses`, `bank_deposits` | Financial records |
| `inventory.stock_movements`, `housekeeping.linen_movements`, `maintenance.repair_updates`, `maintenance.scheduled_job_runs` | Stock ledger and the repair / planned-maintenance history |
| bookings, guests, folios, folio lines, payments, receipts, refunds, orders, laundry and every other business table | Business records |

Sign-ins are lines in the activity log (`UserSignedIn`), so those lines go after 7 days with it.
