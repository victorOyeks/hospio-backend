# SMS and WhatsApp

Every SMS and WhatsApp the platform sends goes the same way as email (docs/email.md): a service builds a `Delivery`, hands
it to `DeliveryService`, and messaging-service records it (`messaging.delivery_records`) and sends it. The provider is
Infobip, from the Oyeks Global account.

## How a text is sent

| Asked for | What happens |
|---|---|
| **SMS** | Sent by Infobip SMS (`/sms/2/text/advanced`), one page always (below). |
| **WhatsApp with an approved template** | Sent as that template (`/whatsapp/1/message/template`) from the Oyeks Global sender. |
| **WhatsApp whose template isn't approved yet, has none, or that WhatsApp refuses** | The same message goes as an SMS of its `text`. The record keeps why (`error`: "Sent by SMS: template … is pending"). |

Which templates are approved is read from Infobip every ten minutes, so a template starts being used **on its own** the
moment Meta approves it: nothing to deploy or switch.

Each record is `SENT` once Infobip takes it (`provider_message_id` = `sms:<id>` or `whatsapp:<id>`), `FAILED` (with the
reason) or `NOT_CONNECTED` (that channel is off). Infobip then posts the **delivery report** to
`POST /messaging/webhooks/infobip/reports?key=…` (the address and key are given on every message): the record becomes
`DELIVERED` (with `delivered_at`) or `FAILED` with the network's words, e.g. "Destination on DND list - DND blacklisted
(REJECTED_DND, …)". In Infobip's portal the same messages are under **Analyze → Logs**.

**DND.** Nigerian networks block promotional SMS to numbers on the Do-Not-Disturb list. Until a *transactional* sender ID
is approved (and set in `INFOBIP_SMS_SENDER`), SMS goes from Infobip's shared sender and DND numbers reject it.

## One page per SMS

`SmsText.fit` (common) runs on every SMS, in the sender and in the staging echo:

- the text is brought into the GSM 7-bit alphabet (₦ → N, · → -, curly quotes → straight, accents dropped) — one character
  outside it would make the whole SMS Unicode with 70 characters a page;
- over 160 characters it is cut at a word with "...", and a link is never cut in half (the words before it go first).

Write SMS texts to fit anyway: the important part first, a link last. A WhatsApp's `text` is its SMS fallback, so it is a
short SMS too; the long version lives in the template.

## Templates in use

Templates are in `scripts/whatsapp/templates.json` and submitted with `scripts/whatsapp/sync_templates.py` (see the file).

| Message | Template | Values (in order) | Built in |
|---|---|---|---|
| Booking confirmed | `hospio_booking_confirmation_v2` | first name, `@hotel`, reference, check-in, check-out, room type, `@hotelPhone`, **link** | reservation `GuestNotices` |
| Dates changed | `hospio_booking_changed_v2` | first name, `@hotel`, reference, check-in, check-out, `@hotelPhone`, **link** | reservation `GuestNotices` |
| Cancelled | `hospio_booking_cancelled_v2` | first name, `@hotel`, reference, "was" dates, reason, `@hotelPhone`, **link** | reservation `GuestNotices` |
| Receipt (guest without email) | `hospio_payment_receipt_v2` | first name, `@hotel`, amount, paid by, reference | messaging `MessagingEventListener` |
| Phone code, "Send on WhatsApp instead" | `oyeks_otp` (authentication: the code also goes on the copy button) | code | identity `DeliveryComposer` |
| Check-in reminder | `hospio_checkin_reminder_v2` | — not sent yet (no reminder job) | — |

The `_v2` templates (November 2026) have the **Hospio360 banner** as an image header, one detail per line, and (bookings) a
**View booking** button. Their shape is read from Infobip with the approval, so the sender needs no list of them:

- an **image header** gets the banner, `hospio.whatsapp.header-image-url` (env `WHATSAPP_HEADER_IMAGE_URL`, default
  `https://hospio360.com/whatsapp/hospio360-banner.png`, served by the landing site);
- a **link button** (`https://hospio360.com/stay/{{1}}`) takes the template's **last value** as the link's ending, and the body
  the values before it.

The first versions (`hospio_booking_confirmation` …) stay approved but are no longer sent. They said "reply to this
message": guest replies to the WhatsApp number are not connected yet, so the v2 texts point to the hotel's phone instead.

`@hotelPhone` is the hotel's phone from tenant-service, or "its front desk number" when it has none, so the text still reads
("Call the hotel on its front desk number").

## The guest's booking page

**View booking** opens `https://hospio360.com/stay/<token>` (landing app, `apps/landing/src/app/stay/[token]`), which reads
`GET /reservation/public/stays/{token}` — public, no sign-in. The token (`StayLinks`) is the booking id plus an HMAC of it
(38 characters, base64url) under `hospio.stay-links.secret` (env `STAY_LINK_SECRET`, default the internal API key), so it
can't be guessed or changed and nothing is stored. An altered or unknown token is 404 "This link is not valid"; 60 days after
check-out it is 410 "expired". The page shows the status, reference, dates with the hotel's check-in/out times, nights, room
type, guests, a cancellation's reason, and the hotel's address (Get directions), phone (Call the hotel) and email — never the
room number, prices or notes.

**Every channel carries the link** (`StayLinks.url`, base `hospio.stay-links.base-url`, default `https://hospio360.com/stay/`):
WhatsApp's View booking button, the end of the SMS it falls back to ("Details and directions: <link>", kept whole by
`SmsText.fit`), and the confirmation / change / cancellation email, whose button says **View booking**. **Get directions**
opens Google Maps at the hotel's name and address, or its name and city when it has no street address; a hotel with
neither says "Contact the hotel for directions".

`@hotel` is replaced by messaging-service with the sending hotel's name (the same name an email is from), in the values
and in the SMS fallback text. Everything else on WhatsApp (folios, stay summaries, staff messages to guests) has no
template and goes as SMS.

## Settings (environment, `/opt/hospio-staging/.env.staging` on staging)

| Variable | Meaning |
|---|---|
| `SMS_ENABLED`, `WHATSAPP_ENABLED` | Switch each channel on (default off). Every service reads them for the echo. |
| `INFOBIP_BASE_URL`, `INFOBIP_API_KEY` | The Infobip account. The key is only ever in the environment. |
| `INFOBIP_WHATSAPP_SENDER` | The WhatsApp number, digits only (2348148849797). |
| `INFOBIP_SMS_SENDER` | The approved alphanumeric sender (e.g. `Hospio360`). Empty uses Infobip's default; set it once approved. |
| `INFOBIP_NOTIFY_URL`, `INFOBIP_WEBHOOK_KEY` | Where Infobip posts delivery reports, and the key it must quote. Both empty: no reports. |
| `WHATSAPP_HEADER_IMAGE_URL` | The banner sent with image-header templates (default the landing site's). |
| `STAY_LINK_SECRET` | Signs the booking page links (default the internal API key). Changing it breaks every link already sent. |
| `TEXTS_ALLOW_ONLY` | Comma-separated numbers. When set only these receive SMS/WhatsApp; others are recorded as held back. **Staging sets it** (test data holds real-looking numbers); production leaves it empty. |

While the Infobip account is on its free trial it only delivers to the numbers verified in Infobip.

## The code screen

`GET /messaging/channels` says `{ sms, whatsapp, whatsappCodes }`. The web app's "Check your phone" shows **Send on
WhatsApp instead** only when `whatsappCodes` is true (`oyeks_otp` approved); the code then goes with
`POST /identity/auth/phone/send-code {"channel":"WHATSAPP"}`.
