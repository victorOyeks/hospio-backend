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
| `POST /payment/receipts/send` `{paymentId, channel?, phone?}` | front office, bar, kitchen, cashier, laundry, finance at EDIT; managers | The approved payment's receipt on WhatsApp to `phone` (kept on the payment when it had none) or the payment's phone. `channel` WHATSAPP or left out. **PRINT / EMAIL** (an older screen) → 400 "Receipts go to the guest on WhatsApp only: pick WhatsApp or Don't send"; NONE → 400 "Nothing to send: …". No phone at all → 400 "This payment has no guest phone · type the guest's WhatsApp number to send the receipt". A bad number → 400 "The guest's WhatsApp number must be a real phone number, like +234 803 555 0192". Answer `{receiptNumber, sentTo, sentVia: "WHATSAPP", deliveries}` |
| `POST /payment/payments/{id}/receipt/resend` | managers, finance EDIT | Send receipt again: the same, to the payment's phone (it used to prefer email) |
| `POST /payment/payments` `receiptPreference` | as before | **Mapped, never refused** (a payment must not be lost over its receipt choice, and the money may already be on the POS): PRINT, NEVER → NONE; EMAIL → WHATSAPP. Without one, the Hotel setup default (mapped the same way). The web apps send NONE from take payment (the receipt step sends it afterwards) and WHATSAPP/NONE from the manager's record payment |
| `GET /payment/payments…` | as before | each payment now carries `guestPhone` (for Send receipt on WhatsApp) |

messaging-service, on `PaymentApproved`: NONE / NEVER / PRINT sends nothing; Hotel setup → Guest messaging "Send receipts
automatically" off sends nothing; otherwise the WhatsApp template to the guest's phone, and nothing when there is no phone (it no
longer emails the receipt). On staging the WhatsApp allow-list holds back every number not on it ("Held back: not on this
environment's allow-list"): that is expected. While Infobip answers "Account issue (7011)" the delivery is still queued and
recorded with that failure; nothing on our side changes.

## Devices: no receipt printers

`platform-service` migration `022-no-receipt-printers.sql` switches the catalogue's "Receipt printer"
(`dc000000-0000-4000-8000-000000000003`) off. The row stays; packages that already list a printer keep their lines, totals,
payments and history. Saving a package refuses a new printer line (catalogue or typed, any name with "printer") or any other
switched-off item, unless the draft already lists it: "Hospio360 doesn't supply receipt printers: receipts go to guests on
WhatsApp". The catalogue refuses switching a printer back on or adding one. The admin package editor already offers only active
items (and those a package already has). Legal: the Device & Equipment Supply Terms (web page and Word template 06) no longer name
receipt printers; wording only, no new version.
