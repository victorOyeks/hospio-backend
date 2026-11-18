# One monthly invoice per business · build tracker

Owner decision, 18 Nov 2026: Moniepoint and OPay have no debit or payout API, so Hospio360 **never debits** a business and does no
split payments. Every business is billed by **one monthly invoice**, paid through Hospio360's Paystack link. This replaces the 06:00
daily debit (`docs/plan-daily-fees.md`), the device invoices' own debit/Paystack/overdue steps (`docs/plan-device-packages.md`) and the
standalone guest-message invoice (`docs/guest-message-billing.md`). Frontend: hospio-frontend `docs/qa/63-monthly-invoices.md`.

## Decisions

| # | Decision |
|---|---|
| Fee | Unchanged: 1.5 % (or the addendum rate) of each location's daily sales as recorded in Hospio360, per sale day, all payment types, VAT excluded. Worked out at 00:05 (`DailyFeeService`); a day is now `ACCRUED`, then `INVOICED`, then `PAID` |
| Invoice | One per business per month, issued on the **1st at 07:00 Lagos** for the month before, number `HIN-YYYYMM-NNNN` (the billed month). Sections: **Platform fee** (one line per location: sales × rate = fee; the day-by-day breakdown is the daily fees billed on it), **Guest messages** (each location's `MSG-` invoice, made by messaging at 00:30), **Devices & internet** (device instalments and data-plan months scheduled in or before the month, plus the from-sales device share), **Carried in**, **Credits & adjustments** |
| Minimum | A total under **₦1,000** (`fees.invoiceMinimum`) is `CARRIED_FORWARD`: no notices to pay; the next invoice has a "Carried forward from …" line and paying it pays both |
| VAT | Not added. `fees.invoiceVatPct` (default 0) adds VAT on platform + messages + devices when set. **For the accountant to confirm** |
| Due / grace | Due 7 days after issue (the 8th); grace 3 days (to the 11th) |
| Notices | Email to the owner (Hospio360 as sender) + bell to owners and managers of every location + banner in the hotel app. Issue; reminders on the 6th and 7th; "due today" on the 8th; overdue on the 9th and 10th; **final warning** on the 11th ("from 12 December 2026 at 06:00, card/transfer POS payments, new sales and new bookings pause until it is paid"); a "paused" notice on the 12th. No SMS/WhatsApp. Logged once per invoice, kind and day (`monthly_invoice_events`); nothing once paid |
| Restriction | From **06:00 on the 12th** if unpaid (soft). Paused: card/transfer through the POS (payment-service), new F&B orders and adding lines to a tab (fnb), new bookings / walk-ins (reservation `POST /bookings`) — each refuses with **423** and the reason. Still allowed: sign-in, viewing, check-in of existing bookings, check-out, **cash** on existing folios and tabs, charge to room, reports and exports, paying the invoice. Laundry orders are not paused (not in the decided list) |
| Lifting | At once on a Paystack payment (return or webhook), an admin's offline payment that pays it in full, an extension, or a void. Services cache the answer 30 s (`BillingHold`), so within a minute |
| Paying | Owner only, full outstanding amount, Paystack (card, transfer, USSD). Admins (SUPER_ADMIN, FINANCE_ADMIN) record offline payments (part payments allowed; the balance stays due and the restriction stays), grant **one** extension (new last day + reason), add a credit note (negative) or adjustment, void (no payments on it) with optional reissue. All audit-logged |
| Managers | Read-only Billing & invoices: the invoice status and totals, lines and days of their own locations only; banner "ask the owner". Staff only see the red "paused" banner |
| Marketers | Commission accrues on the **platform-fee part of invoice payments received**: each day's `collected` = fee × share of the invoice received (after credit notes); `CommissionService.syncReceived` adds the difference (accrual, same agreement/window/20 %/5 % WHT rules) or claws it back (credit note on a paid invoice). Statements on the 5th unchanged |
| Staging | `BILLING_QA_ONLY=true`: only QA businesses (name starting "QA", owner at `@hospio-qa.test`, or `BILLING_QA_BUSINESS_IDS`) are invoiced, reminded or restricted, by the jobs or by hand |

Decisions taken while building (tell the owner):
- **Invoice number month** = the billed month (November's invoice issued 1 Dec is `HIN-202611-0001`).
- **Device items** keep each package's schedule (install day each month); an item is billed on the invoice of the month it falls in, i.e. a month in arrears. The items stay as rows (`device_invoices`, `DEV-…`) for history; their own debit, email, Paystack link and 3/7/14-day overdue steps are gone.
- **A credit on a paid invoice** becomes a "Credit from …" line on the next invoice.
- **Void** only for an invoice with no payment on it (otherwise use a credit note); a reissue is issued the same day with a new number.
- **Daily statement email** at 00:05 still goes, now saying the amount "is added to your Hospio360 invoice for <month>". The legal text no longer mentions it; the owner can ask to stop it.
- Older trial rows (`SHADOW`, `SCHEDULED`) are billed like `ACCRUED`; rows the trial "debited" (`PAID`, `PART_PAID`, `UNPAID`, `CARRIED`, QA only) are not billed again.

## How it works

1. **Issue** (`MonthlyInvoiceJob` 07:00 on the 1st → `MonthlyInvoiceService.issueAll(last month, today)`; per business LIVE/AT_RISK): messaging `GET /internal/billing/messages/to-bill?businessId&month` (open `MSG-` invoices for months ≤ M, not linked) — a failure stops that business's invoice (retried by an admin run); then in one transaction under an advisory lock: daily fees of the business with no invoice and `feeDate ≤ month end`, open device items due by month end, `CARRIED_FORWARD` invoices and paid invoices with a credit, VAT, total, status, number; rows are linked (`daily_fees.monthly_invoice_id`, `device_invoices.monthly_invoice_id/number`, `carried_into_id`); then messaging `POST /internal/billing/messages/link`; then the ISSUED notice. One live invoice per business and month (unique index where status ≠ VOID).
2. **06:00** `restrictDue(today)`: open invoices past the due date → `OVERDUE`; past `lastDay()` (extension or grace) → `restricted_at`, `fee_collections.pos_paused` on every hotel of the business (payment-service `/internal/pos/pause`).
3. **08:00** `remind(today)`: the day's notice (`noticeFor`).
4. **Pay**: `POST /billing/invoices/{id}/pay` → Paystack checkout (reference `HIN-…-XXXXXXXX`), back to `/dashboard/billing?invoice=…&reference=…` → `POST /billing/payments/confirm`. Paystack's webhook reaches messaging (`/messaging/webhooks/paystack`), which forwards `HIN-` references to platform `/internal/billing/paystack-confirm`. Each payment once (unique channel + reference) with a receipt `RCP-YYYYMM-NNNN`; full payment → `PAID`, then everything it billed is settled (days `PAID`, device items paid — instalments credit their package, the from-sales share credited to the package, `MSG-` invoices settled through `POST /internal/billing/messages/settle`, carried-in invoices `PAID` via `CARRIED`), the restriction lifts, commission syncs, the receipt is emailed.
5. **Admin**: record payment, extend, adjust, void (frees the days, items, message invoices and carried-in invoices; `release` on messaging), reissue.

## API (platform-service)

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/billing/status` | signed in | banner `{state: NONE\|DUE\|DUE_SOON\|DUE_TODAY\|OVERDUE\|FINAL_WARNING\|RESTRICTED, invoiceId, number, monthLabel, outstanding, dueOn, lastDay, restrictFrom, restricted, canPay, title, message}`; staff below manager only RESTRICTED |
| GET | `/billing/invoices`, `/billing/invoices/{id}`, `/{id}/days`, `/{id}/days.csv`, `/{id}/pdf`, `/{id}/payments/{pid}/receipt` | owner, manager (read-only, own locations) | list rows; InvoiceView with lines and payments; day-by-day DailyFeeView; PDFs on the Oyeks Global letterhead |
| POST | `/billing/invoices/{id}/pay` `{returnUrl}` | owner | Paystack checkout for the outstanding amount |
| POST | `/billing/payments/confirm?reference=` | owner, manager | checks with Paystack, records once |
| GET | `/admin/billing/invoices?status&month&businessId`, `/export` | platform | `{invoices, counts, totals}`; CSV |
| GET | `/admin/billing/invoices/{id}` (+ `/days`, `/pdf`, receipts) | platform | with `events` (reminder log and audit) |
| POST | `/admin/billing/invoices/{id}/payments` `{amount, via: BANK_TRANSFER\|CASH\|CHEQUE\|OTHER, reference, paidOn, note}` | money | part payments allowed |
| POST | `/admin/billing/invoices/{id}/extend` `{until, reason}` · `/adjust` `{amount, reason}` · `/void` `{reason, reissue}` | money | once · credit (negative) or charge · no payments on it |
| POST | `/admin/billing/invoices/run?month&businessId` | SUPER_ADMIN | an ended month |
| GET | `/admin/businesses/{id}/billing` | platform | `{state, openInvoices, outstanding, latestNumber, latestStatus, restricted}` |
| GET | `/internal/billing/holds/{businessId}` | internal | `{restricted, invoiceNumber, message}` (common `BillingHold`) |
| POST | `/internal/billing/paystack-confirm?reference=` | internal | from messaging's webhook |
| POST | `/admin/qa/billing/issue {businessId, month, issuedOn}` · `/admin/qa/billing/run-day {businessId, date}` · GET `/admin/qa/billing/invoices/{id}/notice?kind=` | SUPER_ADMIN, `QA_FEE_SIMULATION=true` only | any issue date / a chosen day's 06:00 + 08:00 steps / a notice's email text |

Removed: `FeeDebitRunner` and the 06:00/09:00/13:00/18:00 debit runs, `POST /admin/fees/daily/debit`, the QA simulated debit (`/admin/qa/hotels/{id}/simulated-debit`, `/admin/qa/fees/daily/{id}/debit`). `PUT …/fee-collection` refuses `debitEnabled: true`. Device invoice `pay` and guest-message invoice `pay` refuse (billed on the monthly invoice). Messaging `hospio.billing.monthly-invoice` (default true): `MSG-` invoices are made silently and never suspend guest messages on their own.

## Data (platform 019, messaging 010)

`monthly_invoices`, `monthly_invoice_lines`, `monthly_invoice_payments`, `monthly_invoice_events` (business-scoped RLS); `daily_fees.monthly_invoice_id`; `device_invoices.monthly_invoice_id/number`; `messaging.comm_invoices.monthly_invoice_id/number`. Migration 019 switches every `fee_collections.debit_enabled` off and moves `SHADOW`/`SCHEDULED` days to `ACCRUED`. Settings `fees.invoiceVatPct` (0), `fees.invoiceMinimum` (1000).

## Legal (`docs/legal-acceptance.md`)

New versions TOS-2026-11B, FEES-2026-11B ("Fees and monthly invoice"), DEV-2026-11B, SUP-2026-11B, RPA-2026-11B, effective 18 Nov 2026 on staging (published with `LEGAL_MATERIAL_NOTICE_DAYS=0` there; production keeps 30 days' notice). Box 2: "I agree to pay Hospio360's monthly invoice — 1.5% of my business's daily sales — within 7 days of issue." Record title "Monthly invoice agreement (1.5%)". Word templates rebuilt; 03 Direct Debit Mandate replaced by "03 - Billing & invoice terms (summary)". **Needs owner confirmation**: Terms 7.3 / Fees 5.5 "unpaid 30 days after its due date → full suspension after 7 days' notice".

## Phases

| Phase | What | Status |
|---|---|---|
| 1 | Backend: invoice, notices, restriction, payments, admin actions, PDFs, commission, messaging and device folding, staging guard, QA endpoints, tests | Done (18 Nov) |
| 2 | Frontend: Billing & invoices (owner, manager), banner, admin Invoices tab and business billing, wording, settings | Done (18 Nov) |
| 3 | Legal: web text, box text, new versions, Word templates | Done (18 Nov) |
| 4 | Staging end to end (QA businesses only), QA 63, screenshots | See the log below |

## Staging log
