# Real suspension of a business

Owner decision, 18 Nov 2026: the Suspended stage must lock a business out, not only record it. Terms of Service 7.3 and Fees 5.5: an
invoice unpaid 30 days after its due date allows full suspension after 7 days' notice. Related: `docs/plan-monthly-invoices.md` (the
soft pause at the end of the grace period, which is separate and stays), `docs/admin-portal.md`. Frontend: hospio-frontend
`docs/qa/69-real-suspension.md`.

## What it does

| | |
|---|---|
| Who is locked out | Everyone who belongs to the business: owner, managers, staff, on any device, a shared tablet's PIN unlock and the installed app included |
| What they see | One full page, "This account is suspended": since when, the reason if Hospio360 chose to show it, hello@oyeksglobal.com, Sign out. **Owner**: every unpaid invoice with its amount, the total, **Pay now** (Paystack) and invoice / receipt PDFs. **Managers and staff**: "Please ask the owner to contact Hospio360", no amounts |
| What still works for them | Sign-in, refresh, sign-out, PIN unlock, their own profile read (`me`), the suspension status, the owner's billing (list, invoice, days, PDF, receipt, pay, confirm), legal acceptance. Nothing else |
| Hospio360 | Admins keep full access to the business in the admin portal. **View as owner** (support access) still opens the hotel app for a suspended business, read-only as always, so support can look while helping |
| Other businesses | Unaffected |
| Data | Kept. Nothing is deleted or changed by a suspension |

## How it is enforced

`common` `SuspensionFilter` runs in **every service** after the caller is known and before any controller: a request from someone
whose token carries a suspended business is answered **403**, header `X-Business-Suspended: true`, body
`{status: false, message: "This account is suspended. Contact Hospio360 at hello@oyeksglobal.com", data: {code: "BUSINESS_SUSPENDED", since, reason, contact}}`.
`reason` is the admin's note only when they chose to show it.

The answer comes from tenant-service (`GET /tenant/internal/businesses/{id}/suspension`, the business's stage) through `common`
`BusinessSuspension`, cached **30 seconds** per business in each service, like `BillingHold`. So a suspension, and a reactivation,
reaches everything within a minute.

**When tenant-service can't be reached the business is treated as not suspended** (fail-open). A suspension is rare and deliberate;
an outage of one service must never lock every hotel out of its front desk. The cost: a suspended business could get in for as long
as tenant-service is down.

Never refused: requests with no signed-in person (public pages, webhooks), Hospio360 staff and marketers (their tokens carry no
business), support "view as" tokens (`act` claim), and service-to-service calls under `/internal/**`.

Allow-list (path inside the service, any method unless said):

| Service | Paths |
|---|---|
| identity | `/auth/**` (sign-in, two-step, refresh, logout, PIN sign-in, device, change password), `GET /users/me` |
| tenant | `GET /businesses/suspension`, `GET /businesses/mine`, `GET /hotels` |
| platform | `/billing/**` (owner-only and manager rules stay with the controller), `/legal/**` |
| messaging, payment | `/webhooks/**` carry no user token (Paystack's webhook confirms the payment that lifts a suspension) |

Sign-in is deliberately **not** refused: people have to be able to see why they can't work, and the owner has to be able to pay.

## State (tenant-service, migration 006)

`businesses.stage = SUSPENDED` plus `suspended_at`, `suspension_reason` (the note), `suspension_type` (`UNPAID_INVOICE` | `OTHER`),
`suspension_show_reason`, `suspension_notice_sent_on`, `stage_before_suspension` (LIVE or AT_RISK), `suspended_by`, `suspended_by_name`.
All cleared on reactivation. `PUT /tenant/businesses/{id}/stage` refuses to move a business into or out of SUSPENDED ("Use Suspend
business" / "Use Reactivate"): those go through platform-service so the notice, emails and audit trail are never skipped. Businesses
that were marked Suspended before this were made `OTHER` with previous stage LIVE.

## What admins do (platform-service)

| Step | Who | Endpoint | What happens |
|---|---|---|---|
| Find | any platform role | `GET /platform/admin/suspension/candidates` | Businesses not suspended with an open invoice **30+ days past its due date**: invoices, total, days, the last notice and whether its 7 days are over. Admin portal: Fees & billing → Invoices → **Suspension candidates**; the business page shows an amber card |
| Notice | super, finance | `POST /platform/admin/businesses/{id}/suspension/notice {suspendOn?}` | Needs an invoice 30+ days overdue; `suspendOn` defaults to today + 7 and can't be sooner. **Email to the owner** ("Notice: your Hospio360 account will be suspended on <date> unless <invoices> are paid": invoices, amount, clause 7.3, Pay now link) and a **bell notice** to owners and managers. Email only, no SMS or WhatsApp. Recorded in `platform.suspension_notices` (sent on, suspend on, invoices, total, recipient, who) |
| Suspend | super, finance | `POST /platform/admin/businesses/{id}/suspend {reasonType, note, showNote, noticeSent, noticeSentOn}` | `UNPAID_INVOICE`: needs an overdue invoice. Outside the Terms (oldest under 30 days, no notice recorded, or the notice under 7 days old) a **finance admin is refused**; a **super admin goes through with `warnings`** in the answer and in the audit trail. `OTHER` (fraud, a breach of the Terms, the owner asked): **super admin only**. Note 8 to 500 characters. Email to the owner + bell |
| Reactivate | super, finance (`OTHER`: super only) | `POST /platform/admin/businesses/{id}/reactivate {note}` | Back to the stage it had (`stage_before_suspension`). Email to the owner + bell |
| Read | any platform role | `GET /platform/admin/businesses/{id}/suspension` | State, overdue invoices, last notice |

Every step writes a line in **Changes by Hospio360** (area "Suspension") with who, the note and before/after.

On staging (`BILLING_QA_ONLY=true`) notices and suspensions are refused for any business that isn't a QA one.

## What lifts it

- **By itself**: a business suspended for `UNPAID_INVOICE` is reactivated the moment a payment leaves it with **no overdue invoice**
  (every open invoice past its due date paid in full). The payment can be the owner's Paystack payment (return page or webhook) or
  an offline payment an admin records. Audit line "Reactivated automatically", actor "Hospio360 · automatic". An invoice that is due
  but not yet overdue does not keep it suspended.
- **Never by itself**: a suspension for `OTHER`. Paying invoices changes nothing; a super admin reactivates.
- Voiding or extending an invoice does not reactivate by itself; the admin uses Reactivate.
- The soft pause is separate: it is worked out from the invoices as before. Reactivating a business that still owes an invoice past
  its grace period leaves card/transfer POS payments, new sales and new bookings paused until that is paid.

## What stops and what keeps running while suspended

| Thing | While suspended | Why |
|---|---|---|
| New fee days, monthly invoices | Stop (billing covers LIVE and AT_RISK only) | No service, no fee |
| Invoice reminders (08:00) | Stop | The owner was told at suspension; the unpaid invoices stay on the suspended screen |
| Invoice status (06:00: overdue, restriction) | Keeps running | It is bookkeeping on invoices already issued |
| Device instalments and data-plan months | No new items; the ones that fell due meanwhile are made on the first run after reactivation | Hardware is still owed, but nothing new is raised while locked out |
| Scheduled reports | Stop; a due schedule runs once after reactivation | |
| Guest messages (confirmations, changes, arrival reminders, receipts, folios, statements, typed messages) | Not sent; recorded `SKIPPED` "the business's Hospio360 account is suspended" | |
| Preventive-maintenance work orders | Not opened; still due after reactivation | |
| Marketer commission | Unchanged for what was already paid; a payment received while suspended still earns commission | It follows money received |
| Card settlements, pending POS payment checks | Keep running | Guests' money already taken must still be reconciled and paid out |
| Guest stay page (`/stay/<token>`) | Still readable | It is the guest's own booking; there is no online booking to stop |
| Receipts already sent to guests | Unaffected (they were emails / WhatsApp). The in-app receipt page is for staff and is locked like the rest | |
| Attendance watch, retention jobs, health scores (LIVE only) | Unchanged | Housekeeping of data |

## Hotel app

`/suspended` is the screen. The app shell asks `GET /tenant/businesses/suspension` on every page and sends a suspended business
there (so deep links and the installed app's `/dashboard` land on it; pages are fetched from the network every time). People already
in the app are moved within a minute (a check every 60 s, and at once on any refused action). The screen checks every 30 s and lets
everyone back in when it is lifted. Back from Paystack it confirms the payment and re-checks. On a shared device "Sign out" is "Lock".

## QA (staging, `QA_FEE_SIMULATION=true`)

`GET /platform/admin/qa/suspension/{businessId}/email?kind=NOTICE|SUSPENDED|REACTIVATED` → the email's subject and text for the
business as it is now. Overdue invoices are made with `POST /platform/admin/qa/billing/issue` using an old `issuedOn`.
