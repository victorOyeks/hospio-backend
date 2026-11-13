# Guest messages as a paid service

Hotels pay for the emails, SMS and WhatsApp messages they send outside. Hospio360 charges per message and invoices each
hotel monthly. Everything lives in messaging-service (`billing/`), next to the deliveries it counts.

## What is billable

| Billable (the hotel pays) | Free (always goes) |
|---|---|
| Booking confirmed / dates changed / cancelled, receipts, folios, stay summaries, messages to guests (guest inbox), company statements, payment reminders, purchase orders | Phone codes, temporary passwords, invites, password resets, handover packs, staff messages, approvals, reports, support, platform fee statements, these invoices |

The list is `CommBilling.BILLABLE_KINDS`, by `Delivery.Kind`.

## Switching it on

A hotel's service is a `messaging.comm_plans` row: `enabled`, its own prices (null = the default), and a suspension.

- **Off or no plan:** billable messages are **not sent**. Their delivery record is `SKIPPED` with the reason ("Guest
  messages are off for this hotel").
- **Suspended:** the same, with "Guest messages are suspended: invoice … was due on …".
- The owner or manager switches it on in **Hotel setup → Guest messages**. Hospio360 can switch it on and set the hotel's
  prices from the admin portal, during onboarding or later.

## Prices

The default prices are `messaging.comm_prices` (id `DEFAULT`): email ₦5, SMS ₦10, WhatsApp ₦25, editable by Hospio360. A
hotel's own prices override them.

A message is priced when it goes out (`deliveries.unit_price`) by the channel it actually went on (`sent_via`): a WhatsApp
that went as an SMS is charged as an SMS. Only messages that went out (`SENT`, `DELIVERED`) are charged; one the network
rejected (e.g. DND) is `FAILED` and costs nothing.

## Invoices

- At **00:30 on the 1st (Lagos)** each hotel's billable messages of the month before become **one invoice**
  (`comm_invoices`, number `MSG-YYYYMM-0001`).
  - The invoice lists counts and amounts per channel, and has lines per kind of message.
  - Every message carries `deliveries.invoice_id`, so the invoice shows each one.
- Each invoice is **due 10 days after issue**. The owner and the hotel get an email, and owners and managers get an in-app
  notification.
- **09:00 daily:** an invoice past its due date becomes `OVERDUE` and the hotel's guest messages are **suspended**.
- **Paying it** (`PAID`) lifts the suspension, unless another invoice is still overdue.

## Paying

1. **Pay:** `POST /billing/messages/invoices/{id}/pay` starts a Paystack checkout (reference `MSG-…-XXXX`) and returns the
   page to send the payer to.
2. **Return:** Paystack sends the payer back to `/dashboard/hotel-setup/guest-messages?invoice=…&reference=…`, and the web
   app calls `POST /billing/messages/payments/{reference}/confirm`. That checks the payment with Paystack (status
   `success`, amount at least the invoice total) before marking the invoice paid.
3. **Webhook:** `POST /messaging/webhooks/paystack` (signature `x-paystack-signature`, HMAC-SHA512 with the secret key)
   does the same for `charge.success`. This covers a payer who closes the tab before coming back.

Hospio360 can also **mark an invoice paid** by hand, for example for a bank transfer.

OPay can sit beside Paystack the same way once there are OPay keys.

## Endpoints

| Hotel (owner, manager; `X-Hotel-Id`) | Hospio360 (platform) |
|---|---|
| `GET /billing/messages` (plan, prices, this month's usage, outstanding) | `GET/PUT /billing/messages/admin/prices` |
| `PUT /billing/messages {enabled}` | `GET /billing/messages/admin/plans?hotelIds=` |
| `GET /billing/messages/this-month` (messages so far) | `PUT /billing/messages/admin/plans/{hotelId} {businessId, enabled, email, sms, whatsapp, useDefaults}` |
| `GET /billing/messages/invoices`, `/invoices/{id}`, `/invoices/{id}/messages` | `POST /billing/messages/admin/plans/{hotelId}/suspension {suspended, reason}` |
| `POST /billing/messages/invoices/{id}/pay {returnUrl}` | `GET /billing/messages/admin/invoices?status&month&hotelId` |
| `POST /billing/messages/payments/{reference}/confirm` | `POST /billing/messages/admin/invoices/issue {month, hotelId?}`, `/{id}/mark-paid`, `/admin/overdue-check` |

## Settings

| Variable | Meaning |
|---|---|
| `PAYSTACK_SECRET_KEY` | Staging: the test key. Production: the live key, only in the server's environment. |
| `HOTEL_APP_URL` | Where Paystack sends the payer back. |
