# Email (Brevo SMTP) and deliveries

Every email, SMS and WhatsApp the platform sends goes through **one sender in messaging-service**. Services never talk
to SMTP themselves: they hand a `Delivery` to common's `DeliveryService`, which publishes it on
`hospio-delivery-requested` after the transaction commits. messaging-service records it, decides whether it may go out,
lays it out in the shared HTML template and sends it through Brevo SMTP. A delivery never fails the business action.

SMS and WhatsApp are **not connected yet** (the user's call): they are recorded and echoed, never sent.

## Settings (by name only)

Values live in `/opt/hospio-staging/.env.staging` (systemd `EnvironmentFile`), never in a repo file, log or doc.

| Env | Property | Meaning |
|---|---|---|
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD` | `spring.mail.*` (messaging-service) | Brevo SMTP relay, STARTTLS on 587 |
| `MAIL_FROM_ADDRESS` | `hospio.mail.from-address` | The From address of every email |
| `MAIL_FROM_NAME_DEFAULT` | `hospio.mail.from-name-default` | From name for platform mail (no hotel, no business) |
| `MAIL_ENABLED` | `hospio.mail.enabled` | `true` sends real email; anything else records and echoes only (default false) |
| `MAIL_BLOCKED_DOMAINS` | `hospio.mail.blocked-domains` | Comma list; replaces the defaults below when set |
| `DELIVERIES_ECHO` | `hospio.deliveries.echo` | Echo every delivery in the API response (`deliveries`), on for staging |

## Who it is from

- **From address**: `MAIL_FROM_ADDRESS`.
- **From name**: the hotel's name (the event's `hotelId`, else the `Delivery.from(businessId, hotelId)` the caller set);
  no hotel → the business's name; neither (H.M.S staff, marketers) → `MAIL_FROM_NAME_DEFAULT`. Names come from
  tenant-service and are cached for 10 minutes.
- **Reply-To**: the hotel's email (Hotel setup / tenant hotel `email`) when it has one, else the business's email, else none.
- A sender may override both with the event's `fromName` / `replyTo`.

## Blocked domains (staging safety)

Never sent, recorded as `SKIPPED_TEST_ADDRESS` and echoed as "Not sent (test address)":

`*.test`, `*.example`, `*.invalid`, `*.localhost`, `example.com`, `example.org`, `example.net`, `hospio-qa.test`, the seed
hotel's `royalsuites.ng` (it may be a real domain someone else owns), and the other made-up domains in the seed data and
QA scripts: `x.ng`, `y.ng`, `supplier.ng`, `savannahlodge.ng`, `lagosfreshfoods.ng`, `ikejadrinks.ng`, `bluelagoon.ng`,
`qasupplies.ng`, `adaqa.ng`, `simpashotels.com`. A domain also blocks its subdomains. Anything that is not an address is
treated the same way. Real addresses (a QA gmail sign-up) get a real email.

## The on-screen echo

With `DELIVERIES_ECHO=true` every delivery still comes back in the response under `deliveries`, now with `status`:

| `status` | Screen says |
|---|---|
| `EMAIL_SENT` | "Email sent to …" (handed to the sender; the outcome is in the delivery record) |
| `TEST_ADDRESS` | "Not sent (test address)" |
| `NOT_CONNECTED` | SMS / WhatsApp: "Not sent · SMS (WhatsApp) coming soon"; email with `MAIL_ENABLED` off: "Not sent · email is off" |

## The delivery record (`messaging.deliveries`)

One row per delivery: `channel`, `to_address`, `subject`, `kind`, `ref`, `from_name`, `reply_to`, `status`
(`QUEUED` → `SENT`, `FAILED`, `SKIPPED_TEST_ADDRESS`, `NOT_CONNECTED`, `DISABLED`), `attempts`, `provider_message_id`
(the SMTP Message-ID Brevo accepted), `error` (one line, never a credential), `sent_at`, `created_at`, `event_id` (unique:
a redelivered event is not sent twice), `business_id`, `hotel_id`. Forced row-level security like its neighbours; platform
mail (no business) is visible to H.M.S only.

`GET /messaging/deliveries?page&size&status&channel&q` (manager, owner, H.M.S): the hotel's delivery log, newest first,
`PageWithCounts` with counts per status.

## Sending

- Asynchronous: the event is published after the producer's commit; messaging-service's listener records the row, commits,
  then a worker thread sends.
- One retry on a transient failure (connection refused or timed out, SMTP 4xx) after 5 seconds; a permanent failure (5xx,
  bad address, authentication) is not retried. Either way the row says `FAILED` with the error and the action that asked
  for the email has already succeeded.
- HTML with a plain-text part, one shared layout: the hotel's name as the header, the body, a footer ("Sent by <hotel> with
  Hospio360 · reply to this email to reach <hotel>"). Kinds with their own heading and button: `INVITE` (Accept the
  invite), `TEMP_PASSWORD` / `PASSWORD_RESET` (Sign in), `BOOKING_CONFIRMATION`, `MESSAGE` (guest message), `PURCHASE_ORDER`,
  `REPORT` / `DAILY_REPORT` (Open the report), `RECEIPT`, `FOLIO`, `STATEMENT`, `PAYMENT_REMINDER`. Any other kind uses
  the plain layout. The first `https://` link in the text becomes the button.

## Contract for other services (Agent B: receipts, reports)

In-process, the same as every other delivery:

```java
// any service that depends on common
private final DeliveryService deliveries;

List<Delivery> echoed = deliveries.send(
        Delivery.email(guestEmail, "Your receipt R-00042", text, Delivery.Kind.RECEIPT, receiptNumber)
                .from(businessId, hotelId));   // optional: only when the request has no X-Hotel-Id
return response.withDeliveries(echoed);          // put it under "deliveries" so the screen shows it
```

- `text` is plain text: blank lines make paragraphs, the first `https://` link becomes the button. Keep it short.
- `ref` ties the row to your record (receipt number, report run id); `kind` picks the heading and button.
- Return `echoed` in the response as `deliveries`; the web shows it with `withDeliveries(...)` / `showDeliveries(...)`.
- Attachments are not supported yet; send a link (the report screen, a receipt page) instead.
- Services that cannot depend on common may publish `DeliveryRequestedEvent` on `hospio-delivery-requested` themselves
  with `channel`, `to`, `subject`, `text`, `kind`, `ref` (and optionally `hotelId`, `businessId`, `fromName`, `replyTo`, `html`).

Never put an address on a blocked domain into a test expecting `SENT`; use a blocked domain to check `SKIPPED_TEST_ADDRESS`.

## Per-user email preferences

None yet. If the Profile screen's notification settings (Agent C) add an email opt-out, it is a flag identity returns and
the producer checks before building the delivery; messaging-service does not filter by person. Password, invite and
reset emails are always sent.

## Limits

- No attachments, no open or bounce tracking, no inbound email (guest replies by email do not come back into the
  conversation; staff log a reply by hand).
- Brevo's daily quota applies (coryd's account for now). The From address is `no-reply@coryd.ng` until Hospio360 has its
  own sender domain.
- SMS and WhatsApp are recorded and echoed only. The phone code (OTP) stays on screen on staging.
- The From name lookup falls back to `MAIL_FROM_NAME_DEFAULT` when tenant-service cannot be reached.
