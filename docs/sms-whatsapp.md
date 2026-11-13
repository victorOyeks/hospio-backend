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

Each record ends `SENT` (with `provider_message_id` = `sms:<id>` or `whatsapp:<id>`), `FAILED` (with the reason), or
`NOT_CONNECTED` (that channel is off). "Sent" means Infobip accepted it; the handset delivery report is Infobip's.

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
| Booking confirmed | `hospio_booking_confirmation` | first name, reference, `@hotel`, check-in, check-out, room type | reservation `GuestNotices` |
| Dates changed | `hospio_booking_changed` | first name, reference, `@hotel`, check-in, check-out | reservation `GuestNotices` |
| Cancelled | `hospio_booking_cancelled` | first name, reference, `@hotel`, reason sentence | reservation `GuestNotices` |
| Receipt (guest without email) | `hospio_payment_receipt` | first name, `@hotel`, amount, reference | messaging `MessagingEventListener` |
| Phone code, "Send on WhatsApp instead" | `oyeks_otp` (authentication: the code also goes on the copy button) | code | identity `DeliveryComposer` |
| Check-in reminder | `hospio_checkin_reminder` | — not sent yet (no reminder job) | — |

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
| `TEXTS_ALLOW_ONLY` | Comma-separated numbers. When set only these receive SMS/WhatsApp; others are recorded as held back. **Staging sets it** (test data holds real-looking numbers); production leaves it empty. |

While the Infobip account is on its free trial it only delivers to the numbers verified in Infobip.

## The code screen

`GET /messaging/channels` says `{ sms, whatsapp, whatsappCodes }`. The web app's "Check your phone" shows **Send on
WhatsApp instead** only when `whatsappCodes` is true (`oyeks_otp` approved); the code then goes with
`POST /identity/auth/phone/send-code {"channel":"WHATSAPP"}`.
