# Device packages and the guest service charge

> **18 Nov 2026:** no auto-debit. TRANSACTION device shares and every MONTHLY instalment / data-plan month are billed on the business's **monthly invoice** (`docs/plan-monthly-invoices.md`); the device items keep their schedule and stay viewable, but their own debit, Paystack link, emails and 3/7/14-day overdue steps are gone. The restriction for an unpaid monthly invoice replaces the device POS pause.

Tracker for the devices-and-internet package Hospio360 supplies to hotels that lack tablets or internet, and for the owner's choice
of who pays the platform fee. Agreed on 17 Nov 2026.

## Decisions

| # | Decision |
|---|---|
| 1 | Internet is split: the **router/modem** is a one-off item in the package; a **data plan** is a separate recurring monthly charge for as long as the hotel uses it (never "paid off"). |
| 2 | Monthly invoices are **auto-debited** from the hotel's Moniepoint/OPay account on the due date; if the debit fails the owner pays by **Paystack** link; an admin can mark one paid by hand (cash/transfer). |
| 3 | Overdue instalment: reminder at 3 days, business flagged at risk at 7, **POS payments paused at 14** (lifted when paid). |
| 4 | Transaction option: **2.5 %** of daily sales by default, editable per hotel, locked once collection starts. |
| 5 | A package is **per hotel** (devices are in one building; the debit comes from that hotel's POS account). |
| 6 | **Device catalogue** in Settings (standard items and prices, one-off or monthly); the package copies prices, which can be changed per hotel. |
| 7 | Guest-facing line: **"Service charge (1.5%)"**, chosen **per hotel** by the owner; a change applies to new charges only. |
| 8 | The service charge applies to everything counted in daily sales (rooms, restaurant, bar, kitchen, laundry); not to no-show deposits. |
| 9 | Hospio360 owns the devices **until paid off**; leaving early means paying the balance or returning them (stated at handover). |
| 10 | A part debit pays the **platform fee first**, then devices. The device fee is never passed to guests. |
| 11 | No daily cap on device collection: 2.5 % of sales, stopping when the balance reaches ₦0; the tracker estimates the finish date from the last 30 days. |

## Payment options

- **OWN_DEVICES**: nothing supplied, nothing charged.
- **TRANSACTION**: 2.5 % (per hotel) of each day's sales is added to the daily fee as a device line, capped at what is left, debited at 06:00 with the platform fee until the balance is ₦0.
- **MONTHLY**: the one-off total over 1–6 monthly instalments, invoiced and auto-debited.
- **ONE_TIME**: paid in full at installation; an admin records the payment.

The data plan, when chosen, is invoiced monthly on top of whichever option (or alone).

## Where it lives

- platform-service: catalogue, packages, items, ledger (`device_payments`), invoices; the device line in `daily_fees`.
- The admin onboarding wizard: a **Devices & internet** section in the Connections step (no new step: drafts are stored by step number).
- Owner/manager: a Devices section on Hospio fees; the handover page shows the package and ownership terms.

## Phases

| Phase | What | Status |
|---|---|---|
| 1 | Catalogue in Settings; package per hotel (items, option, rate, months, data plan) in the Connections step and the hotel panel; install; record one-time payment | Done |
| 2 | TRANSACTION collection inside the daily fee and 06:00 debit; ledger; stops at ₦0 | Done |
| 3 | Tracking: admin (Fees & billing → Devices, hotel panel, Money tab), owner/manager (Hospio fees → Devices), handover | Done |
| 4 | Monthly invoices (instalments and data plan): auto-debit, Paystack fallback, mark paid, overdue reminders / at risk / POS pause | Done |
| 5 | Service charge: owner's per-hotel choice; "Service charge (1.5%)" on folios and counter orders, receipts; excluded from sales | Backend done; screens in progress |
| 6 | End-to-end on staging, QA scripts | To do |

## How the service charge works (phase 5)

- Hotel setup → Payments: `guestPaysPlatformFee` (owner only). hotel-config fills `platformFeePct` with the business's fee rate on every
  read, so the charge always matches the fee (`GuestServiceCharge` in common).
- folio-service: each charge posts a `SERVICE_CHARGE` line ("Service charge (1.5%) · …") keyed `SC:<line id>`; a void or a move takes it
  along; a negative adjustment takes its share back.
- fnb-service: an order's `serviceCharge` (on the net, no tax) is part of its total; charged to a room it posts without it (the folio adds
  its own line).
- housekeeping: a laundry order paid directly carries it; charged to a room it comes off (the folio adds it).
- Daily sales never count it (folio skips the category; fnb and laundry subtract it).
