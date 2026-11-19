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
| Restriction | From **06:00 on the 12th** if unpaid (soft). Paused: card/transfer through the POS (payment-service), new F&B orders and adding lines to a tab (fnb), new bookings / walk-ins (reservation `POST /bookings`), **new guest-laundry orders** (housekeeping `POST /laundry/orders`, "New laundry orders are paused: …"; owner follow-up, 18 Nov) — each refuses with **423** and the reason. Still allowed: sign-in, viewing, check-in of existing bookings, check-out, **cash** on existing folios and tabs, charge to room, laundry orders already taken (moving them on, handing back, charge to room, cash, cancel), reports and exports, paying the invoice |
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
- **Daily statement email: stopped** (owner follow-up, 18 Nov). The 00:05 job only works the day out; nothing is emailed and no bell notice is made. Owners and managers see each day under Hospio fees ("this month so far"). `daily_fees.statement_sent_at` stays for the days emailed before; `RunResult` no longer has `statements`.
- Older trial rows (`SHADOW`, `SCHEDULED`) are billed like `ACCRUED`; rows the trial "debited" (`PAID`, `PART_PAID`, `UNPAID`, `CARRIED`, QA only) are not billed again.

## Free until charges start (19 Nov 2026)

Owner decision: a business pays **no platform fee until its "Charges start on" date**. Devices are not free.

| # | Decision |
|---|---|
| Where the date lives | platform-service `platform.billing_starts` (one row per business: `charges_start_on`, who set it, reason, `reminded_for`), not tenant's `contract_starts_on`: billing reads it in its own database (a tenant-service hiccup can never turn billed days free), the change is audit-logged next to it, and the onboarding "Fee & contract" step mirrors the same date into `contract_starts_on` (shown as "Charges start on") so the two never disagree |
| No date = FREE | Each day is still worked out at 00:05 for visibility: status **`FREE`**, `fee` 0, the would-be fee in `daily_fees.waived_fee`, `due` = the from-sales device share only. Never invoiced, so no platform-fee invoice, reminder, `BillingHold` restriction or suspension candidate; the marketer earns nothing (commission follows what is paid) |
| Date set | Days on or after it are `ACCRUED` as before (1.5 % or the addendum rate); days before it `FREE`. The first invoice is the 1st after the first billed month, with a partial month (the platform line covers the billed days only: "2 sale days, 15 November to 16 November · NGN …") |
| Changing it | Money roles (SUPER_ADMIN, FINANCE_ADMIN) only, with a reason (8+ characters), in the admin onboarding step 3 and the business page Money tab; Support reads it (403 on change). Only days **not on an invoice yet** are worked out again (`fee + waived_fee` = rate × sales, so nothing is lost); an issued invoice is never touched. A change that makes FREE days billable (an earlier date, or a first date in the past) needs `confirmEarlier: true` (the preview says how many days and how much). Clearing the date makes every day not on an invoice FREE again. Under the same advisory lock as the invoice run, so a change never races an issue |
| Audit | Changes by Hospio360, area **"Billing start"**: "Charges start on 1 March 2027 (was free)", before/after `{"chargesStartOn": …}`, the reason. Audit entries are deleted after 7 days, so the Money tab's history is kept for good in `platform.billing_start_changes` (before, after, reason, who, days worked out again) |
| Devices | Billed as today, free period or not: instalments and data plan (`device_invoices`) and the from-sales share. A FREE day with a device share goes on the invoice for that share only and stays `FREE`. A month with only devices gives an invoice with only the Devices section (same minimum, due date, reminders and restriction) |
| Guest messages | Before the date, messages that are switched on are sent and recorded at NGN 0 (`deliveries.free_period`, "Free period" on the screens) and never put on a `MSG-` invoice; from the date they are priced as today (messaging asks platform `GET /internal/billing/charges-start/{businessId}`, cached 60 s; when platform can't be reached the message is priced as usual) |
| Reminder | 08:15 Lagos daily (`ChargesStartJob`): bell + email to every Super admin and Finance admin 7 days before a business's date ("QA X: charges start on 1 March 2027"), once per business and date. On staging only QA businesses |
| Tags | Admin Businesses list and Fees & billing show **Free** / **Billing from 1 Mar 2027** |
| Owner | Billing & invoices banner: "Free: no charges until Hospio360 tells you" / "Charges start on 1 March 2027 — 1.5% of daily sales, invoiced monthly" / "Charges started on …"; Hospio fees shows FREE days at NGN 0 with "free" |
| Existing businesses (migration platform 027) | Date = `contract_starts_on` (never later than the first day with a fee), else the first day worked out, else (live, at risk or suspended with no day yet) the day the business was created. Leads and drafts with none of these start free, like new businesses. Existing days were not changed by the migration |

API (platform-service): `GET /admin/billing/charges-start` (`{rows: [{businessId, chargesStartOn, state: FREE|STARTS|BILLING, label}]}`; not listed = free) · `GET /admin/businesses/{id}/charges-start` (`{chargesStartOn, state, label, ratePct, canEdit, setByName, setAt, reason, history}`) · `GET …/charges-start/preview?date=` (money; `{earlier, toBill: {days, fee}, toFree: {days, fee}, message}`) · `PUT …/charges-start {chargesStartOn | null, reason, confirmEarlier}` (money) · `GET /billing/charges` (owner, manager: `{state, chargesStartOn, ratePct, title, message}`) · `GET /internal/billing/charges-start/{businessId}` · QA `POST /admin/qa/billing/charges-start-reminders?today=`. `DailyFeeView` gains `waivedFee`. Frontend: hospio-frontend `docs/qa/77-free-period-billing-start.md`.

### Staging log: free until charges start (19 Nov 2026)

QA businesses only. New business **QA Free Period ie2rw** (owner `qa.free.owner.ie2rw@hospio-qa.test`, restaurant "QA Free ie2rw Grill"),
created after the deploy, so it started with no date. 19 Nov was a real cash sale (₦100,000 takeaway); the other days (5, 20, 25 Oct;
3, 8, 12, 16 Nov) were seeded by SQL as FREE rows (ids `fp-…`, test only). Screenshots: `~/Desktop/Hospio test screenshots/Free period and billing start (19 Nov 2026)/`.

| Scenario | Result |
|---|---|
| No date | Money tab "Free", no tag row (= Free); owner banner "Free: no charges until Hospio360 tells you"; 19 Nov worked out **FREE**: sales 100,000, fee 0, waived 1,500, due 0; October issue run → "Nothing to invoice" (no platform-fee invoice) |
| Support | reads the date; `PUT` 403; no Change/Clear on the Money tab; onboarding step 3 date disabled |
| Set 15 Oct (past, earlier) | preview: 7 free days become billable (NGN 26,700); without `confirmEarlier` 400 "… Confirm to go ahead."; with it (Finance admin) 20, 25 Oct and the 5 Nov days ACCRUED, 5 Oct stays FREE |
| Partial month | October issued → **HIN-202610-0004**, platform 10,500 = "2 sale days, 20 October to 25 October · NGN 700,000.00 × 1.5%" (SQL: the two linked days, sales 700,000, fee 10,500) |
| Clear | free again: the 5 Nov days back to FREE (fee 0, waived kept), the October invoice and its two days untouched (INVOICED) |
| Future date 1 Mar 2027 | no confirm needed (nothing becomes billable); STARTS; owner banner "Charges start on 1 March 2027 — 1.5% of daily sales, invoiced monthly"; tags "Billing from 1 Mar 2027" in Businesses and Fees & billing; history of the 3 changes (also in Changes by Hospio360, area Billing start) |
| 7-day reminder | QA run as on 22 Feb 2027: 1 business reminded, bell to the 3 money admins, email to each (victor@ delivered; amoria@ and tobi@hospio360.com bounced: those mailboxes don't exist); a second run reminded nobody |
| Devices only | MONTHLY package (tablet 180,000 over 3 + data plan 30,000, signed schedule QA-FREE-DEV-1) → November issued **HIN-202611-0005**: Devices only (instalment 1 of 3 60,000 + data plan 30,000 = 90,000), platform 0; the 5 free November days not linked. Steps as on 12 Dec: overdue, restricted (both invoices), business RESTRICTED → so restriction works with devices alone. Both recorded paid by transfer (QA-FREE-OCTINV / QA-FREE-NOVINV) to lift it |
| Guest messages | both off on the new restaurant; owner Email on / WhatsApp off: receipt outlook and the resend say "Receipt not sent: WhatsApp is switched off for this restaurant (Restaurant setup → Guest messages)", delivery SKIPPED with that reason; a guest email went to a `.test` address (SKIPPED, test address); Email off: guest email refused 400 "Email is switched off for this restaurant (…)"; WhatsApp on: a fake number HELD_BACK. One receipt to the allow-listed number in the free period: SENT, `free_period` true, NGN 0 (the provider then reported it undeliverable, so it ended FAILED and is not charged anyway) |
| Existing businesses | migration 027 set 32 dates (e.g. QA Owner B Hotels fcekd 10 Jul 2026, QA Debit Rate rrydw 28 Oct 2026, Royal Suites 14 Nov 2026, THEWISEMAN 15 / 15 / 10 Nov 2026; all first day worked out or contract start). Their daily-fee totals by status and their invoices were identical before and after (fcekd: 11 PAID 41,430.01, 1 ACCRUED 2,790.00; rrydw: 2 PAID 3,000; HIN-… unchanged); owner fcekd sees "Charges started on 10 July 2026" |
| Tablet widths | Money tab, owner Billing & invoices and Guest messages at 1024, 820 and 768 px: no sideways scroll |

Fixed during the run: saving the date returned 500 (the Changes by Hospio360 record re-bound the connection to the business inside the
transaction, so row security refused the platform-only row); the record is now written after the commit.

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

Removed: `FeeDebitRunner` and the 06:00/09:00/13:00/18:00 debit runs, `POST /admin/fees/daily/debit`, the QA simulated debit (`/admin/qa/hotels/{id}/simulated-debit`, `/admin/qa/fees/daily/{id}/debit`). `PUT …/fee-collection` is gone too (19 Nov 2026, with the rest of the debit code: docs/plan-daily-fees.md). Device invoice `pay` and guest-message invoice `pay` refuse (billed on the monthly invoice). Messaging `hospio.billing.monthly-invoice` (default true): `MSG-` invoices are made silently and never suspend guest messages on their own.

## Data (platform 019, messaging 010)

`monthly_invoices`, `monthly_invoice_lines`, `monthly_invoice_payments`, `monthly_invoice_events` (business-scoped RLS); `daily_fees.monthly_invoice_id`; `device_invoices.monthly_invoice_id/number`; `messaging.comm_invoices.monthly_invoice_id/number`. Migration 019 switches every `fee_collections.debit_enabled` off and moves `SHADOW`/`SCHEDULED` days to `ACCRUED`. Settings `fees.invoiceVatPct` (0), `fees.invoiceMinimum` (1000).

## Legal (`docs/legal-acceptance.md`)

New versions TOS-2026-11B, FEES-2026-11B ("Fees and monthly invoice"), DEV-2026-11B, SUP-2026-11B, RPA-2026-11B, effective 18 Nov 2026 on staging (published with `LEGAL_MATERIAL_NOTICE_DAYS=0` there; production keeps 30 days' notice). Box 2: "I agree to pay Hospio360's monthly invoice — 1.5% of my business's daily sales — within 7 days of issue." Record title "Monthly invoice agreement (1.5%)". Word templates rebuilt; 03 Direct Debit Mandate replaced by "03 - Billing & invoice terms (summary)". Terms 7.3 / Fees 5.5 "unpaid 30 days after its due date → full suspension after 7 days' notice": **confirmed by the owner (18 Nov), kept**; built 19 Nov (`docs/suspension.md`), and both clauses now say what a full suspension is (web text only, no new version). Terms 5.7 and Fees 5.2/5.3 name what pauses one by one, so "new guest laundry orders" (paused) and "laundry already taken" (still allowed) were added to the web text of TOS-2026-11B / FEES-2026-11B the day they were published, **without a new version** (nobody is asked again). The Word templates (01 Terms, 03 Billing & invoice terms summary) need the same two lines at their next rebuild; **owner to say** if a new version (re-acceptance) is wanted instead.

## Full suspension (Terms 7.3 / Fees 5.5): what admins do at 30 days overdue

Since 19 Nov 2026 the Suspended stage **locks the business out** (`docs/suspension.md`). Nothing suspends automatically: the 06:00
step only starts the soft restriction above. An invoice due on the 8th reaches 30 days overdue around the 8th of the next month; by
then the business has been paused since the 12th.

1. **Find them** (Finance admin, weekly): Fees & billing → Invoices → **Suspension candidates** (an invoice 30 or more days past its
   due date). The business page shows the same as an amber card.
2. **Check** the invoice panel: no part payment or extension pending, the reminder log shows the notices went, no open ticket disputing it.
3. **Send suspension notice** (on the row or the card): pick the suspension date, at least 7 days ahead. The owner is emailed and
   owners and managers get a bell notice; the notice date is recorded.
4. **After that date, still unpaid**: **Suspend business** → Unpaid invoices, tick "7 days' notice was sent on …" (filled in), a
   note, and whether the business sees the note. A finance admin can only do this by the Terms; a super admin is warned and can go ahead.
5. **When it is paid** the business comes back by itself (the owner's Paystack payment, or a payment you record). A suspension for
   another reason is lifted with **Reactivate** by a super admin.

## Phases

| Phase | What | Status |
|---|---|---|
| 1 | Backend: invoice, notices, restriction, payments, admin actions, PDFs, commission, messaging and device folding, staging guard, QA endpoints, tests | Done (18 Nov) |
| 2 | Frontend: Billing & invoices (owner, manager), banner, admin Invoices tab and business billing, wording, settings | Done (18 Nov) |
| 3 | Legal: web text, box text, new versions, Word templates | Done (18 Nov) |
| 4 | Staging end to end (QA businesses only), QA 63, screenshots | See the log below |
| 5 | Owner follow-ups (18 Nov): no daily statement email; new laundry orders pause too; full suspension kept as a manual admin step; a QA invoice left unpaid for the owner's own Paystack test | See "Follow-ups" in the log |
| 6 | Real suspension (19 Nov): the Suspended stage locks the business out; candidates, notice, suspend, reactivate, automatic lift on payment | `docs/suspension.md` |

## Staging log

18 Nov 2026, QA businesses only (`BILLING_QA_ONLY=true`, `LEGAL_MATERIAL_NOTICE_DAYS=0` on staging). Daily fee rows and one guest-message
invoice were seeded by SQL (test only, ids `mi-…`); the device package was made, accepted and installed through the API. Screenshots:
`~/Desktop/Hospio test screenshots/Monthly invoices (18 Nov 2026)/`.

| Scenario | Business | Result |
|---|---|---|
| Issue with all three sections | QA Owner B Hotels fcekd (Ikoyi + Abuja), issued 8 Nov | HIN-202611-0004: platform 31,830.01 (Ikoyi 1,638,500.50 × 1.5 % = 24,577.51; Abuja 483,500 × 1.5 % = 7,252.50; matches `sum(fee)` of its daily fees), guest messages 350.00 (MSG-202611-0090 linked), devices 111,666.67 (instalment 1 of 3 = 245,000 / 3 = 81,666.67 + data plan 30,000; DEV-202611-0009/0010 linked), VAT 0, total **143,846.68**, due 15 Nov, grace to 18 Nov |
| Reminders | same, steps run for 13–18 Nov | REMINDER 13, 14 · DUE_TODAY 15 · OVERDUE 16, 17 · FINAL_WARNING 18 (one each, EMAILED → messaging SKIPPED "test address"); bell notices to OWNER and MANAGER of both hotels; banner FINAL_WARNING on the real 18 Nov |
| Restriction | same, step for 19 Nov | restricted; POS pause on both hotels (platform + payment-service); 423 on card at POS, card on a folio, new order (TAKEAWAY), new booking and walk-in; cash on the in-house guest's folio (₦60,000) and her check-out went through; red banner for owner (Pay now), manager ("ask the owner"), front desk (no amounts) |
| Carried forward | QA Venue 5jf4 | October 630.00 → CARRIED_FORWARD (HIN-202610-0001); November 1,500 + 630 carried = 2,130.00 |
| Void and reissue | QA Venue 5jf4 | HIN-202611-0002 voided with reissue → HIN-202611-0003 (2,130.00, due 25 Nov) |
| Extension, part payments, commission | QA Referred Hotel uuxcq (marketer Ada), 14 Nov fee 12,000 | restricted on 12 Nov → finance admin extended to 25 Nov (lifted; a second extension refused) → transfer 6,000 (RCP-202611-0001, 6,000 still due) → Ada +1,200 gross / 60 WHT / **1,140 net** → transfer 6,000 (RCP-202611-0002) → PAID → +1,140 net → credit note −3,000 on the paid invoice → **clawback −600 / −30 / −570** (total 9,000; day collected 9,000) |
| Staging guard | Royal Suites, THEWISEMAN | no invoices; QA issue refused "Staging only invoices QA businesses"; their SHADOW days became ACCRUED, nothing billed |
| Pay (Paystack test mode) | QA Owner B Hotels fcekd, restricted | Pay now → Paystack checkout opened (its Cloudflare check blocks automated browsers, so the test card was charged through Paystack's test charge API on the server with reference HIN-202611-0004-QACARD01); signed `charge.success` webhook to messaging → forwarded → verified with Paystack → **PAID**, receipt RCP-202611-0003 (143,846.68), restriction lifted at once (POS pause off on both hotels, new orders accepted after the 30 s cache, banner gone); MSG-202611-0090 PAID (HOSPIO_INVOICE), DEV-202611-0009/0010 PAID (instalment credited to the package: 81,666.67), 7 days PAID with collected 31,830.01; an unsigned webhook is refused 401; the Paystack return page shows Paid and the receipt |
| Terms | QA Debit Rate rrydw (addendum 1.2 %) | re-accept modal "I agree to pay Hospio360's monthly invoice — 1.2% of my business's daily sales — within 7 days of issue"; recorded RE_ACCEPTANCE TOS-2026-11B + FEES-2026-11B rate 1.200 |

Clean-up: the last open QA invoice (HIN-202611-0003, QA Venue 5jf4) was recorded paid (reference QA-CLEANUP-MI) so no reminder or
pause reaches the venue QA; QA logins made for this run: `qa.mi.mgr.fcekd@hospio-qa.test`, `qa.mi.staff.fcekd@hospio-qa.test`.

Fixed during the run: device schedule numbers collided across hotels (row security on the count), a credit-note line had no invoice id,
the owner's day-by-day showed one location only (row security on the hotel header), Paystack refuses `.test` payer emails (QA payers use
`payments@hospio360.com`), POS check order (the pause is said before "no POS assigned").

### Follow-ups (18 Nov 2026, evening)

Owner follow-ups, QA businesses only. Screenshots: `~/Desktop/Hospio test screenshots/Monthly invoices follow-ups (18 Nov 2026)/`.

| Scenario | Business | Result |
|---|---|---|
| No daily statement email | QA Ikeja yyzn4b (17 Nov row deleted by SQL, then `POST /admin/fees/daily/run?date=2026-11-17`) | `{worked: 7, skipped: 19}` (no `statements`); the day was made again with `statement_sent_at` empty; `messaging.deliveries` `FEE_STATEMENT` stayed at 78 (last one 17 Nov 23:05 UTC, the night before); no delivery and no bell notice of any kind followed the run. The other six days it worked out were QA hotels missing 17 Nov, all `NO_FEE` |
| New laundry orders pause | QA Owner B Hotels fcekd, Ikoyi: guest checked in to room 101 and LDR-00001/00002 taken first; October invoice HIN-202610-0002 (3,000.00, seeded day `mif-…`) issued as on 1 Nov, step run for 12 Nov → RESTRICTED | `POST /laundry/orders` **423** "New laundry orders are paused: the Hospio360 invoice HIN-202610-0002 (October 2026) is unpaid. They resume within a minute of the owner paying it under Billing & invoices. Check-out and cash on existing bills and tabs still work." for the owner, the manager and a laundry login (with and without charge to room); in the app the laundry desk shows the red banner and "New guest order" answers with that sentence. Still worked while paused: LDR-00001 In progress → Ready → paid in **cash** (card refused 423 by payment-service) → Delivered; LDR-00002 charged to the room, pocket note, In progress |
| Lifting | same | Admin recorded 3,000 by transfer (QA-MIF-LAUNDRY) → PAID, banner gone; after the 30 s cache a new laundry order was taken (LDR-00003, then cancelled). Clean-up: LDR-00002 delivered, folio settled in cash (33,000) and the guest checked out |
| Owner's Paystack test | QA Debit Rate rrydw (1.2 %) | HIN-202610-0003, 3,000.00 (two seeded October days `mif-…`), issued 18 Nov, due 25 Nov, last day 28 Nov, **left unpaid for the owner to pay** (hospio-frontend QA 63, 5a). Owner sign-in shows no terms modal, the "invoice is ready" notice and Pay now · ₦3,000; `POST …/pay` returned a Paystack checkout address. If it is not paid the usual dates apply to this QA business (paused from 29 Nov 06:00); void it to cancel |

QA login made for this run: `qa.mif.laundry.fcekd@hospio-qa.test` (Laundry, QA B Ikoyi fcekd). Laundry prices (Shirt 1,500, Trousers 2,000) were added to that hotel.
