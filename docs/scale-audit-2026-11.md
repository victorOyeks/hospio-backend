# Scale and race-condition audit (13 Nov 2026)

## How the review was done

Five read-only audits covered the whole system:

- race conditions;
- database queries and indexes;
- calls between services, Kafka and configuration;
- scheduled jobs and data growth;
- the frontend (`hotel-internal-system-admin-FE`) under large data.

Each finding was checked against the code. They were then fixed per area on `scale/*` branches, merged to `develop`
and deployed to staging.

Verification:

- The whole backend reactor passes: 601 tests, 0 failures, including Postgres Testcontainers tests that run real
  concurrent writers.
- On staging, a concurrency smoke test ran against the live services: two desks checking in one booking, 10 parallel
  folio charges, parallel cash payments, and check-out racing a cancel.

## The pattern behind most findings

No table had row-level concurrency control. There were no `@Version` columns and almost no `SELECT … FOR UPDATE`.
Every read-check-write (bookings, folios, stock, orders, payments, refunds, tills, invoices) relied on luck once two
writers touched the same row.

Several transactions also held a database connection, sometimes an advisory lock as well, while calling another
service over HTTP. Each service has a 5–7 connection pool, so one slow service could stall another service completely.

## Fixed

### Data correctness under concurrency

**Bookings**

- `@Version` column added.
- Every state change locks the row and re-checks status after the lock.
- Payment and refund events adjust `amount_paid` in one statement, once per payment or refund id (`booking_payments`).
- Check-in, change and create read property and housekeeping data before the transaction.
- `change()` locks the new room type.
- The night audit locks each no-show.

**Folios**

- Every posting, payment, discount, void, move and close locks the folio first. `@Version` is a backstop.
- Totals are a grouped SQL sum.
- Invoice numbers are per hotel, issued under an advisory lock. They used to collide platform-wide.

**FnB orders**

- Row locks on every mutation, plus `@Version`.
- Charge-to-room and card payment can no longer both settle one order.
- The tab limit holds under concurrent lines.

**Payments**

- Webhook, Verify and Cancel lock the payment and re-check, so there is one approval, one receipt and one event. A
  unique receipt index backs this up.
- Refunds lock the payment when summing refunds, and lock the refund on approve or decline. Decline only works from
  PENDING_APPROVAL. Owner approval of refunds now actually reaches the owner.
- Receipt numbers are issued under a per-hotel advisory lock.
- Payout batches claim settlements with a guarded update.
- Settlement sums in SQL, one transaction per business × provider.

**Finance**

- One open till per role (partial unique index).
- Till entries, movements, close and handover lock the till.
- The owner summary, series and income are SQL aggregates.

**Inventory**

- Stock levels are upserted and locked before the "not enough stock" check (tested with 15 concurrent draws).
- Request, PO and count headers are locked.
- PO numbers are unique per hotel, per year.

**Messaging billing**

- Invoice numbers are issued under an advisory lock.
- Late messages roll into the next invoice.
- Overdue and settle use guarded updates or locks, so a paid invoice can't be flipped back to OVERDUE.
- Notices are sent after commit.
- Platform fee statements lock on payment. `markOverdue` is one conditional update.

### Background work

**Scheduled jobs.** Every `@Scheduled` method runs on one instance at a time (`common/scheduling/SingleInstanceScheduling`,
`pg_try_advisory_lock` per job). Each service has a scheduling pool of 3.

**Email, SMS and WhatsApp.** These go through a database-backed queue (`messaging.deliveries`):

- Workers claim rows with `FOR UPDATE SKIP LOCKED` and a lease.
- Retries back off.
- Nothing is lost on restart, and there is no in-memory backlog.
- The 30-minute age cut-off only applies to codes, temporary passwords, resets and invites.

**Outbound queue.** Rows are claimed with `SKIP LOCKED`, and messages are sent outside the transaction.

**Kafka**

- Failed records go to `<topic>.DLT` after exponential back-off. Validation errors are not retried.
- The producer is idempotent with `max.block.ms=5s`, so a broker outage no longer blocks requests for 60s.
- Default partitions are raised to 6.

**Platform**

- The business list reads every page. It used to cap at 2,000, which meant later businesses were never billed.
- Payment and room-night lookups fail instead of answering 0. A 0 answer used to produce a ₦0 statement that was never
  redone.
- Fee statements and health scores run one business per transaction.
- The activity log is written with `insert … on conflict do nothing`. Its listener has concurrency 3.
- Activity older than `auditRetentionDays` is deleted nightly.

**Identity**

- Refresh tokens expired or revoked more than 30 days ago are deleted nightly.
- Grant-expiry and pause jobs only notify for rows they actually changed.

**Maintenance.** The overdue sweep processes 500 rows per transaction and flags each row with a guarded update.

### Queries

- 25 indexes added (`NNN-scale-indexes.sql` in 8 services) for lookups that used to scan whole tables, among them:
  - notifications by event;
  - sign-in by email;
  - booking overlaps by check-out;
  - receipts and refunds by payment;
  - activity by business and event type.
- Per-status COUNT loops are now one grouped query: delivery log, users, expenses, bills, notifications.
- Notifications `mine()` returns the newest 50, and mark-all-read is one UPDATE.
- Lists requested without paging are capped by a date window:
  - payments, refunds and disputes: 90 days;
  - inventory requests, POs, counts and deliveries: 90 days;
  - plain bookings: 93 days.
- The platform overview and usage screens use SQL aggregates, and the hotel overview calls services in parallel with a
  3s budget.

### Edges and configuration

**Hikari and Tomcat**

- Hikari: `connection-timeout` 3s, leak detection 20s, and pool sizes per service. The total is 78 connections against
  Postgres's limit of 100, which is shared with another product.
- Tomcat: 50 threads, with an accept queue of 50.

**Gateway**

- Connect timeout 2s and response timeout 20s.
- 60s for report and export routes.

**Identity sign-in rate limit.** Sign-in, PIN sign-in, sign-up and forgot-password allow 30 requests per minute per IP.

**Body logging**

- Secrets and PII are masked by key fragment, including numeric PINs and OTPs.
- Webhook bodies are never logged.
- Responses are no longer buffered.
- Request start lines moved to DEBUG.

**Caches.** TTL caches are bounded (10k entries, least recently used evicted). Failures are cached for 30s.

### Frontend

**HTTP client.** Only GET and HEAD requests are retried. POSTs used to be retried on 502–504, which could repeat a
payment, a charge or a check-in.

**`allPages`.** At most 4 pages are fetched at a time.

**Card payment**

- Each attempt is sent exactly once.
- "Try again on POS" first asks payment-service whether the last attempt went through or is still waiting at the
  terminal.

**Bookings and Payments pages.** These no longer reload rooms, folios, company accounts, terminals and POS-pending data
on every search keystroke or save. That data loads when a panel opens and is cached per hotel.

**Folios.** Folios are passed without their lines wherever only balances are shown.

**Dashboard shell.** Its calls run in parallel.

**`hotels.limits`.** It makes 1 call instead of 11.

**Repeated reads.** Loaders that run more than once per render are cached per request.

**Refreshes.** Double refreshes after actions are removed, and sidebar links no longer prefetch.

**Stale screens.** Screens that keep a copy of server data resync when fresh data arrives.

**Token renewal.** The token is renewed 60s before it expires.

## Server changes waiting for approval

These are not applied yet. They change the shared staging VPS.

1. **JVM flags** (`/opt/hospio-staging/.env.staging`, the `JAVA_OPTS` line):
   - drop `-XX:TieredStopAtLevel=1`, which costs about 30–50% CPU per request;
   - add `-XX:+ExitOnOutOfMemoryError -XX:ReservedCodeCacheSize=64m -XX:MaxDirectMemorySize=64m`.
   The exact line is in the common-infra notes and in `scripts/vps/bootstrap-staging.sh`.
2. **systemd units.** Copy `systemd/*.service` (they now have `MemoryMax=768M`), then run `daemon-reload` and restart.
3. **nginx** (`nginx/api-staging.hospio360.com`):
   - an upstream with keepalive;
   - gzip;
   - a 30s read timeout (60s for reports);
   - `limit_req` on the sign-in paths.
   Run `nginx -t` before reloading.

## Still open

These are bigger changes or need a product decision.

- **Transactional outbox.** Events are still published after commit, and a crash between commit and send loses the
  event. The design is in the common-infra notes: an outbox table per service plus a `SKIP LOCKED` relay.
- **Retention and partitioning.** Delivery and notification history is kept forever. Monthly partitions are needed for
  `messaging.deliveries` and `platform.activity_log` once they reach millions of rows.
- **Lagos time everywhere.** Business days, billing months and job times assume Africa/Lagos. A hotel in another
  timezone needs per-hotel scheduling based on `tenant.hotels.timezone`.
- **Bookings list totals.** The "Booked value" and "unpaid" cards still sum the whole history when no date window is
  given. This needs either a default window or a stored running total.
- **Folio screens.** The manager and front-desk Folios screens still load every open folio with its lines. They need a
  paged list with lines loaded per folio, plus a folio balances endpoint.
- **Endpoints the frontend would use:**
  - organization accounts by id or name;
  - POS-pending per booking;
  - clock-in and rota counts;
  - paged report runs;
  - an approvals count by level;
  - housekeeping `GET /internal/room-status?roomId=` returning one room.
- **Remaining HTTP calls inside a lock or transaction:**
  - fnb `create()` holds the outlet lock while calling settings and reservations;
  - finance till close with a variance calls identity while holding the till lock;
  - platform `ApprovalService.onRequested` makes HTTP calls inside its listener transaction.
- **Payout batch refs** are still count+1. A race gives a 409 or 500 instead of a duplicate.
- **`GuestService.countStay`** is read-modify-write. Two different bookings of one guest checked in at the same instant
  can lose one count.
- **Local file storage** (`STORAGE_TYPE=local`) ties uploads to one box. Move to S3-compatible storage before running
  a second instance.
- **Infrastructure.** Postgres, Kafka (1 replica) and Redis share one 12 GB VPS with 15 JVMs and another product. Memory
  runs out first. Postgres should move to its own host or a managed service before real growth, then Kafka. PgBouncer in
  transaction mode would need tenant settings bound with `set_config(..., true)` (see `TenantBinder`) instead of the
  session-level binding in `RowLevelSecurityDataSource`.

## Notes for operations

**Mail queue bodies at rest.** Email and SMS bodies, including temporary passwords, now sit in `messaging.deliveries`
while a row is QUEUED. They are cleared when the row is SENT or FAILED. A database backup taken mid-queue can contain
them.

**409 responses.** The new `@Version` columns turn a lost update into a 409, "Someone else changed this … just now;
reload and try again".

**Kafka partitions.** Topics grew from 3 to 6 partitions on the first start after this deploy. For a short window
around that start, some events may have been handled out of order.

**PO renumbering.** Duplicate purchase-order numbers on staging were renamed (`-2`, `-3`, …) before the unique index
was added.

**Redelivered payment events.** Payments applied before this deploy have no `booking_payments` row. If Kafka
redelivers an old payment event, it would be applied again once.
