# Guest messages as a paid service

> **18 Nov 2026:** the `MSG-` invoices below are still made at 00:30 on the 1st, but silently: they are billed on the business's **monthly invoice** (`docs/plan-monthly-invoices.md`, `hospio.billing.monthly-invoice=true`), are not paid on their own, and never suspend guest messages by themselves. Paying the monthly invoice settles them.

> **19 Nov 2026:** there is no SMS price any more. Guest messages are emails and WhatsApp only; SMS is only for sign-in and
> verification codes (Termii), which are free. The SMS price is gone from the platform defaults, the hotel plans, the API
> and both apps (`messaging-012-no-guest-sms-price` drops `comm_prices.sms_price` and `comm_plans.sms_price`). A request
> that still sends `sms` is accepted and the field is ignored. An older invoice that charged SMS keeps its `smsCount` and
> `smsAmount` (read-only history) and still shows its SMS line; staging had one (`MSG-202611-0090`, 6 SMS, ₦60).

Hotels pay for the emails and WhatsApp messages they send outside. Hospio360 charges per message and invoices each
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

The default prices are `messaging.comm_prices` (id `DEFAULT`): email ₦5, WhatsApp ₦25, editable by Hospio360. A
hotel's own prices override them. There is no SMS price: a billable message is never sent by SMS (the dispatcher refuses
SMS for anything but codes), and if one ever were it would cost nothing.

A message is priced when it goes out (`deliveries.unit_price`) by the channel it actually went on (`sent_via`). Only messages that went out (`SENT`, `DELIVERED`) are charged; one the network
rejected (e.g. DND) is `FAILED` and costs nothing.

## Invoices

- At **00:30 on the 1st (Lagos)** each hotel's billable messages of the month before become **one invoice**
  (`comm_invoices`, number `MSG-YYYYMM-0001`).
  - The invoice takes every billable message that went out **before the month's end** and is on no invoice yet, whatever
    its month. A message from the last day that only turns `SENT`/`DELIVERED` after its month was invoiced (a late
    delivery report) is not lost: it rolls into the next month's invoice.
  - The invoice lists counts and amounts per channel (email, WhatsApp; `smsCount`/`smsAmount` are 0 on every new invoice
    and only an older invoice has SMS), and has lines per kind of message. Counts and amounts are summed
    from the messages actually put on it, so they always match its lines.
  - Every message carries `deliveries.invoice_id`, so the invoice shows each one.
  - Numbers are taken per month under a database lock, so two instances (or Hospio360's "issue now" racing the 1st) never
    pick the same number. A second run for a month that already has the hotel's invoice issues nothing; leftovers wait.
  - The emails and notifications go out after the invoice is saved; one that fails never undoes the invoice.
- Each invoice is **due 10 days after issue**. The owner and the hotel get an email, and owners and managers get an in-app
  notification.
- **09:00 daily:** an invoice past its due date becomes `OVERDUE` and the hotel's guest messages are **suspended**. Only
  an invoice still `DUE` at that moment moves (one paid meanwhile stays `PAID`); each invoice is handled on its own, so
  one failure does not stop the rest.
- **Paying it** (`PAID`) lifts the suspension, unless another invoice is still overdue. The invoice is locked while it is
  settled, so the payer's return and Paystack's webhook arriving together settle it, and notify, once.

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
| `GET /billing/messages` (plan, prices `{email, whatsapp}`, this month's usage `{emailCount, whatsappCount, emailAmount, whatsappAmount, total}`, outstanding) | `GET/PUT /billing/messages/admin/prices {email, whatsapp}` (an `sms` field is ignored) |
| `PUT /billing/messages {enabled}` | `GET /billing/messages/admin/plans?hotelIds=` |
| `GET /billing/messages/this-month` (messages so far) | `PUT /billing/messages/admin/plans/{hotelId} {businessId, enabled, email, whatsapp, useDefaults}` (an `sms` field is ignored) |
| `GET /billing/messages/invoices`, `/invoices/{id}`, `/invoices/{id}/messages` | `POST /billing/messages/admin/plans/{hotelId}/suspension {suspended, reason}` |
| `POST /billing/messages/invoices/{id}/pay {returnUrl}` | `GET /billing/messages/admin/invoices?status&month&hotelId` |
| `POST /billing/messages/payments/{reference}/confirm` | `POST /billing/messages/admin/invoices/issue {month, hotelId?}`, `/{id}/mark-paid`, `/admin/overdue-check` |

## Settings

| Variable | Meaning |
|---|---|
| `PAYSTACK_SECRET_KEY` | Staging: the test key. Production: the live key, only in the server's environment. |
| `HOTEL_APP_URL` | Where Paystack sends the payer back. |
