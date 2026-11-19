# WhatsApp receipts, no printing (19 Nov 2026)

The owner's call: "when a user pays there's an option to print. Remove that UI. Receipts: just WhatsApp." and "We are not doing
receipt printers." Payment receipts go to the guest on WhatsApp, as the approved template `hospio_payment_receipt_v2` (first name,
`@hotel`, amount, paid by, reference), or not at all. Nothing is printed for a guest, and a payment receipt is never emailed or
sent by SMS. Emailing a receipt or folio (`POST /payment/receipts/email`), stay summaries and invoice emails stay for the callers
that use them; the apps just no longer offer email as the payment-receipt choice.

## What staff see

| Where | Before | Now |
|---|---|---|
| Take payment (front office, bar, kitchen, laundry, cashier, waiter, owner's bookings): receipt step | Email / Print / WhatsApp (greyed unless the guest had only a phone) | **WhatsApp** (picked when the guest's phone is known, number editable) / **Don't send**; "Skip receipt" stays |
| Take payment: done screen | Print receipt | nothing (the toast says where the receipt went) |
| Manager record payment (Payments → Record a payment, Folio → Record payment) | Email / Print / WhatsApp (greyed) | WhatsApp (to the folio's phone) / Don't send; rides on the payment (`receiptPreference`) |
| My payments, front office Payments, a sale's payments, a split bill's parts | Print receipt / Print or email receipt / Receipts | **Send receipt on WhatsApp** / **WhatsApp receipt** (number asked, the payment's filled in) |
| Check-out panel | Print receipt | removed |
| Manager Payments (panel) | Print receipt, Send receipt again (email or WhatsApp) | **Receipt PDF** (the receipt page, Save as PDF), **Send on WhatsApp** |
| Manager Payments (row menu) | Send receipt again (email or WhatsApp) | Send receipt again: WhatsApp to the payment's phone |
| Receipt page `/receipt` | Print, A4 / 80 mm roll, Email receipt | **Save as PDF** (A4) and, for one payment, Send on WhatsApp; an old `&paper=80mm` is ignored |
| Folio "Print or email the folio" | Print / copy (Print receipt), Email | "Email or copy the folio": Copy (manager: Copy / PDF with **Folio PDF**), Email (stay summary, unchanged) |
| Hotel setup → Payments, POS: default receipt channel | WHATSAPP / EMAIL / PRINT / NONE (POS default PRINT) | WHATSAPP / NONE (default WHATSAPP); a stored PRINT or EMAIL reads as WHATSAPP |
| Lounge / bar / restaurant setup template | receipts PRINT | receipts WHATSAPP |

Kept (documents, not the payment flow): owner and admin invoice / receipt **PDF downloads** (platform invoices), owner report
**Save as PDF**, night audit and finance month **Print** (reports), the onboarding handover pack **Print**, organisation
statements.

## API

| Call | Who | What |
|---|---|---|
| `POST /payment/receipts/send` `{paymentId, channel?, phone?}` | front office, bar, kitchen, cashier, laundry, finance at EDIT; managers | The approved payment's receipt on WhatsApp to `phone` (kept on the payment when it had none) or the payment's phone. `channel` WHATSAPP or left out. **PRINT / EMAIL** (an older screen) → 400 "Receipts go to the guest on WhatsApp only: pick WhatsApp or Don't send"; NONE → 400 "Nothing to send: …". No phone at all → 400 "Receipt not sent: no phone number · type the guest's WhatsApp number to send it". A bad number → 400 "The guest's WhatsApp number must be a real phone number, like +234 803 555 0192". Answer `{receiptNumber, sentTo, sentVia: "WHATSAPP", deliveries, outcome, reason, guestMessagesOn, message}` (below) and the same `message` |
| `GET /payment/receipts/outlook?phone=` | anyone who sees receipts | Before sending: `{outcome, reason, guestMessagesOn}` for a WhatsApp receipt at the caller's hotel (no `phone`: only whether guest messages are on). The receipt step's note "WhatsApp receipts won't go: guest messages are off…" |
| `POST /payment/payments/{id}/receipt/resend` | managers, finance EDIT | Send receipt again: the same, to the payment's phone (it used to prefer email) |
| `POST /payment/payments` `receiptPreference` | as before | **Mapped, never refused** (a payment must not be lost over its receipt choice, and the money may already be on the POS): PRINT, NEVER → NONE; EMAIL → WHATSAPP. Without one, the Hotel setup default (mapped the same way). The web apps send NONE from take payment (the receipt step sends it afterwards) and WHATSAPP/NONE from the manager's record payment. The answer carries `receipt`: the automatic receipt's outlook (null for NONE; SKIPPED "Automatic receipts are off (Hotel setup → Guest messaging)" when that switch is off; NO_PHONE without a phone) |
| `GET /payment/payments…` | as before | each payment now carries `guestPhone` (for Send receipt on WhatsApp) |

messaging-service, on `PaymentApproved`: NONE / NEVER / PRINT sends nothing; Hotel setup → Guest messaging "Send receipts
automatically" off sends nothing; otherwise the WhatsApp template to the guest's phone, and nothing when there is no phone (it no
longer emails the receipt). On staging the WhatsApp allow-list holds back every number not on it ("Held back: not on this
environment's allow-list"): that is expected. While Infobip answers "Account issue (7011)" the delivery is still queued and
recorded with that failure; nothing on our side changes.

## What the desk is told (19 Nov 2026)

The apps used to say "Receipt sent on WhatsApp to …" whatever happened. The delivery itself is asynchronous (Kafka, then
messaging-service's worker), so payment-service now asks messaging-service first (`GET /internal/deliveries/outlook?hotelId&businessId
&kind=RECEIPT&channel=WHATSAPP&to=`, `DeliveryOutlook`), with the same rules and order the dispatcher applies, then hands the receipt over
as before (a skipped one is still recorded, SKIPPED with the reason). messaging-service unreachable → UNKNOWN (treated as sent).

| `outcome` | When | Staff see |
|---|---|---|
| QUEUED | guest messages on, WhatsApp connected, the number allowed | "Receipt sent on WhatsApp to 0803…" |
| SKIPPED | the hotel's guest messages off (CommBilling plan not enabled) or suspended, or the business suspended | "Receipt not sent: guest messages are off for this hotel. Turn them on in Hotel setup → Guest messages." (manager, owner) / "… Ask your manager to turn them on." (everyone else); suspended: "Receipt not sent: guest messages are on hold (…)" |
| HELD_BACK | staging's allow-list (`TEXTS_ALLOW_ONLY`) doesn't have the number | "Receipt held back (test server): +234… is not on this server's allow-list" |
| NOT_CONNECTED | WhatsApp switched off or not configured on this server | "Receipt not sent: WhatsApp is not connected" |
| NO_PHONE | not a phone number (send refuses an empty one with 400 "Receipt not sent: no phone number · …") | "Receipt not sent: no phone number" |
| UNKNOWN | messaging-service couldn't be asked | "Receipt sent on WhatsApp to …" |

Anything but QUEUED/UNKNOWN is a warning toast, not a success, and the receipt keeps no "sent to" (held back keeps it: it is queued
like a real one). `guestMessagesOn` (plan enabled and not suspended) is in every answer: the receipt step, the manager's Record a payment,
a folio's Record payment and the Send receipt on WhatsApp window show a note under the WhatsApp choice while it is false, so staff know
before paying. The words are in the apps' `receiptOutcomeText` (packages/ui `receipt-channels.ts`), "Hotel setup" reworded per venue.

## Devices: no receipt printers

`platform-service` migration `022-no-receipt-printers.sql` switches the catalogue's "Receipt printer"
(`dc000000-0000-4000-8000-000000000003`) off. The row stays; packages that already list a printer keep their lines, totals,
payments and history. Saving a package refuses a new printer line (catalogue or typed, any name with "printer") or any other
switched-off item, unless the draft already lists it: "Hospio360 doesn't supply receipt printers: receipts go to guests on
WhatsApp". The catalogue refuses switching a printer back on or adding one. The admin package editor already offers only active
items (and those a package already has). Legal: the Device & Equipment Supply Terms (web page and Word template 06) no longer name
receipt printers; wording only, no new version.
