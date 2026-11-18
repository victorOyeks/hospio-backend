# SMS and WhatsApp

Every SMS and WhatsApp the platform sends goes the same way as email (docs/email.md): a service builds a `Delivery`, hands
it to `DeliveryService`, and messaging-service records it (`messaging.delivery_records`) and sends it. The provider is
Infobip, from the Oyeks Global account.

## SMS is only for one-time codes

Since November 2026 SMS carries **one thing: the phone verification code** (`Delivery.Kind.PHONE_CODE`, `Delivery.smsAllowed`).
Everything else (bookings, receipts, folios, statements, invites, temporary passwords, purchase orders, staff notices,
guest messages) goes by **WhatsApp or email only**:

- an SMS of any other kind is recorded `NOT_CONNECTED`, "Not sent: SMS is only used for sign-in codes", and never sent;
- a WhatsApp whose template isn't approved (or that WhatsApp refuses) is **not** swapped for an SMS any more; it is recorded
  as not sent with the reason. Only a phone code's WhatsApp falls back to SMS;
- the services that used to text when someone had no email (finance notices, folio statements, approvals, purchase orders,
  invites, password resets) now email only.

Codes go through **Termii**, as CoRyd's notification-service does: `POST {TERMII_BASE_URL}/api/sms/send` on the **`dnd`**
route (the `generic` route is promotional and never reaches a number on Nigeria's DND list, while Termii still says
"sent") from the approved sender ID `Hospio360`. Until `TERMII_ENABLED` is on, codes go by Infobip SMS as before.
Termii's delivery reports post to `POST /messaging/webhooks/termii/reports`, believed only with a valid
`X-Termii-Signature` (HMAC-SHA512 of the raw body under the secret key: Settings · Integrations · Termii → Secret key, else
`TERMII_SECRET_KEY`); records are `termii:<message_id>` (the report's `message_id`, `message_id_str` or `id` is tried in turn).
Until the secret key is set every report is 401 and codes stay `SENT`.

**Setting it up (owner, once):** Termii dashboard → Settings (API / Webhook) → copy the account's **Secret key** and paste it in
the admin portal, Settings · Integrations · Termii → Secret key (Save; in force within a minute). Then, in Termii's webhook
setting, put `https://api-staging.hospio360.com/messaging/webhooks/termii/reports` (production: the production API host) and
save. The next code's report moves its record to `DELIVERED` (or `FAILED` "DND Active on Phone Number").

## How a text is sent

| Asked for | What happens |
|---|---|
| **SMS (a phone code)** | Termii, DND route, sender ID Hospio360 (Infobip SMS while Termii is off). One page always (below). |
| **SMS (anything else)** | Not sent, recorded with the reason. |
| **WhatsApp with an approved template** | Sent as that template (`/whatsapp/1/message/template`) from the Oyeks Global sender. |
| **WhatsApp without an approved template, or that WhatsApp refuses** | Not sent, recorded with the reason; a phone code goes by SMS instead. |

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

Write code texts to fit anyway: the code first. A WhatsApp's `text` is kept on its delivery record (and is a phone code's
SMS fallback); the long version lives in the template.

## Which messages can go on WhatsApp (one list, 18 Nov 2026)

WhatsApp carries only templates Meta approved, so the kinds that can go on it are listed once, in common's
`WhatsAppTemplates`: `BOOKING_CONFIRMATION`, `BOOKING_CHANGED`, `BOOKING_CANCELLED`, `ARRIVAL_REMINDER`, `RECEIPT` and
`PHONE_CODE` (each with its template below). Every producer takes the template name from there.

- `GET /messaging/channels` adds **`whatsappKinds`**: those kinds whose template is approved right now, while WhatsApp is on.
  The hotel app (`useWhatsAppKinds`) offers WhatsApp only for a kind in it: the folio windows, Message guest / Messages,
  staff messages ("WhatsApp copy") and the take-payment receipt.
- Stay summary, folio, a typed guest message and staff messages have **no template**: messaging-service records any such
  WhatsApp `SKIPPED` ("Not sent: WhatsApp has no approved template for this message; it goes by email"), never queued and
  never `FAILED`; check-out no longer asks for a WhatsApp stay summary (the app sends `["EMAIL"]`, or nothing without an
  email, and messaging ignores `WHATSAPP` for it); `POST /folio/folios/{id}/send` with WHATSAPP and a guest message with
  `channel: WHATSAPP` are refused 400 in words; a staff message's `whatsapp: true` sends only the in-app notice.
- The echo (`MailPolicy`) says `NOT_CONNECTED` for such a WhatsApp.

## Templates in use

Templates are in `scripts/whatsapp/templates.json` and submitted with `scripts/whatsapp/sync_templates.py` (see the file).

| Message | Template | Values (in order) | Built in |
|---|---|---|---|
| Booking confirmed | `hospio_booking_confirmation_v2` | first name, `@hotel`, reference, check-in, check-out, room type, `@hotelPhone`, **link** | reservation `GuestNotices` |
| Dates changed | `hospio_booking_changed_v2` | first name, `@hotel`, reference, check-in, check-out, `@hotelPhone`, **link** | reservation `GuestNotices` |
| Cancelled | `hospio_booking_cancelled_v2` | first name, `@hotel`, reference, "was" dates, reason, `@hotelPhone`, **link** | reservation `GuestNotices` |
| Receipt (guest without email) | `hospio_payment_receipt_v2` | first name, `@hotel`, amount, paid by, reference | messaging `MessagingEventListener` |
| Phone code, "Send on WhatsApp instead" | `oyeks_otp` (authentication: the code also goes on the copy button) | code | identity `DeliveryComposer` |
| Day before arrival | `hospio_checkin_reminder_v2` | first name, `@hotel`, reference, check-in time, `@hotelPhone`, **link** | reservation `ArrivalReminders` |

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

## Hotel setup → Guest messaging

One platform WhatsApp number (Oyeks Global, `INFOBIP_WHATSAPP_SENDER`) sends every hotel's automatic messages. Each hotel's
section (`SetupCatalogue` MESSAGING, read with `GuestMessaging` through the shared `HotelSettingsReader`, cached 60 s; all on
when hotel-config can't be read):

| Setting | Effect |
|---|---|
| `whatsappNumber` | The hotel's own WhatsApp, where guests chat with it: **Chat with the hotel on WhatsApp** on the booking page and a `wa.me` line in the booking emails (with "Hello, about my booking <ref>" typed in), and `@hotelPhone` when the hotel has no phone. Replies to the platform number are not connected; this is how guests reach the hotel. |
| `sendConfirmation` | Off: no booking confirmation (email or WhatsApp). Date changes and cancellations always go. |
| `arrivalMessage` | On: `ArrivalReminders` (reservation, every hour 09:00–20:00 Lagos) tells each guest arriving tomorrow, once (`bookings.arrival_reminded_at`): "we look forward to welcoming you tomorrow", the hotel's check-in time and the booking page, by `hospio_checkin_reminder_v2` and email (`ARRIVAL_REMINDER`, billed as "Arrival reminders"). |
| `sendReceipts` | Off: no automatic receipt after a payment (messaging `MessagingEventListener`); the desk can still resend one. |

## The hotel's brand everywhere

Hotel setup → **Brand & colours** (`BRAND`: `primaryColour`, `darkSidebar`, `shortName`, `logoUrl`) is the one source of a
hotel's look, read within a minute of saving by every place it shows:

| Where | How |
|---|---|
| Hotel app (every role) | The dashboard shell renders `brandStyle` (FE `packages/api/src/brand.ts`): brand tokens, the sidebar in the colour or white (Dark sidebar off, or a colour too pale for white text), the short name and logo at the top of the sidebar. The Brand section previews it live on the page before saving. |
| Booking page | `GET /reservation/public/stays/{token}` returns `brand`; the page takes the colour and shows the hotel's name or logo, "with Hospio360". |
| Emails | messaging `HotelBrandClient` (cached 1 minute): header band, buttons, links, logo (docs/email.md). |
| WhatsApp banner | For a hotel with its own colour, each image-header message's banner is `hospio.whatsapp.banner-url` (default `https://hospio360.com/whatsapp/banner`)`?c=rrggbb&n=<hotel name>`, drawn by the landing site; Hospio360's colour keeps `hospio360-banner.png`. Meta approved the template with the example image; the image itself may differ per message. |

## The guest's booking page

**View booking** opens `https://hospio360.com/stay/<token>` (landing app, `apps/landing/src/app/stay/[token]`), which reads
`GET /reservation/public/stays/{token}` — public, no sign-in. The token (`StayLinks`) is the booking id plus an HMAC of it
(38 characters, base64url) under `hospio.stay-links.secret` (env `STAY_LINK_SECRET`, default the internal API key), so it
can't be guessed or changed and nothing is stored. An altered or unknown token is 404 "This link is not valid"; 60 days after
check-out it is 410 "expired". The page shows the status, reference, dates with the hotel's check-in/out times, nights, room
type, guests, a cancellation's reason, and the hotel's address (Get directions), phone (Call the hotel) and email — never the
room number, prices or notes.

**Every channel carries the link** (`StayLinks.url`, base `hospio.stay-links.base-url`, default `https://hospio360.com/stay/`):
WhatsApp's View booking button and the confirmation / change / cancellation email, whose button says **View booking**.
(The WhatsApp's record text also ends with the link, but it is never sent as an SMS: SMS carries only sign-in codes.) **Get directions**
opens Google Maps at the hotel's name and address, or its name and city when it has no street address; a hotel with
neither says "Contact the hotel for directions".

`@hotel` is replaced by messaging-service with the sending hotel's name (the same name an email is from), in the values
and in the text kept on the record. Everything else on WhatsApp (folios, stay summaries, typed messages to guests, staff
messages) has no template, so it is never offered or queued on WhatsApp (and never by SMS); it goes by email when there is
one (above, "Which messages can go on WhatsApp").

## Settings (environment, `/opt/hospio-staging/.env.staging` on staging)

Every Infobip and Termii value below, and `SMS_ENABLED` / `WHATSAPP_ENABLED`, can instead be saved in the admin portal
(Settings · Integrations, [integrations.md](integrations.md)); a saved value wins, an empty one falls back to the variable here.

| Variable | Meaning |
|---|---|
| `SMS_ENABLED`, `WHATSAPP_ENABLED` | Switch each channel on (default off). Every service reads them for the echo. SMS only ever carries codes. |
| `TERMII_ENABLED`, `TERMII_API_KEY`, `TERMII_SENDER_ID` | Codes by Termii (sender ID default `Hospio360`, approved in Termii). The key is in the environment or the admin portal. |
| `TERMII_BASE_URL` | Default `https://api.ng.termii.com` (the account's base URL from the Termii dashboard). |
| `TERMII_SECRET_KEY` | Checks Termii's delivery-report signature; set the webhook in Termii to `https://<api>/messaging/webhooks/termii/reports`. |
| `INFOBIP_BASE_URL`, `INFOBIP_API_KEY` | The Infobip account. The key is in the environment or the admin portal, never in a repo file. |
| `INFOBIP_WHATSAPP_SENDER` | The WhatsApp number, digits only (2348148849797). |
| `INFOBIP_SMS_SENDER` | Infobip's SMS sender, used for codes only while Termii is off. Empty uses Infobip's shared sender. |
| `INFOBIP_NOTIFY_URL`, `INFOBIP_WEBHOOK_KEY` | Where Infobip posts delivery reports, and the key it must quote. Both empty: no reports. |
| `WHATSAPP_HEADER_IMAGE_URL` | The banner sent with image-header templates (default the landing site's). |
| `STAY_LINK_SECRET` | Signs the booking page links (default the internal API key). Changing it breaks every link already sent. |
| `TEXTS_ALLOW_ONLY` | Comma-separated numbers. When set only these receive SMS/WhatsApp; others are recorded as held back. **Staging sets it** (test data holds real-looking numbers); production leaves it empty. |

While the Infobip account is on its free trial it only delivers to the numbers verified in Infobip.

## The code screen

`GET /messaging/channels` says `{ sms, whatsapp, whatsappCodes }`. The web app's "Check your phone" shows **Send on
WhatsApp instead** only when `whatsappCodes` is true (`oyeks_otp` approved); the code then goes with
`POST /identity/auth/phone/send-code {"channel":"WHATSAPP"}`.
