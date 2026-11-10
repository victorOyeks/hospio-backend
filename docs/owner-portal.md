# Owner portal (hotel app · Hotel Owner)

The owner's screens in the hotel app (`apps/hotel/src/app/(dashboard)/hotel-owner/*`,
`apps/hotel/src/components/roles/owner/*`). They read across every hotel of the business and act on one hotel at a
time. This contract lists what each screen reads and writes, the rules the services enforce, and what is not built.

## Rules

- **Who.** Owner routes are guarded with `@access.owner()` (the owner, or H.M.S where it makes sense) or
  `@access.manager()` plus a rule in the service. A refusal is one sentence the screen shows as it is.
- **Whose hotels.** The owner's token carries every hotel of the business (`hotels` claim). `JwtFilter` answers 403
  "That hotel is not granted to this user" to an `X-Hotel-Id` the token does not carry, and row-level security keeps
  every row to the caller's business. A request **without** `X-Hotel-Id` sees the whole business; with one, that hotel.
- **All hotels or one.** Every owner screen has the hotel filter (`?hotel=`, "All hotels" by default). Lists load every
  hotel and tag each row with its hotel; the filter narrows on screen, so the totals of "All hotels" are the sums of the
  single hotels. Actions on a row go to that row's hotel (`X-Hotel-Id` of the row), never to the sidebar's branch.
- **Deliveries.** No email, SMS or WhatsApp provider is connected on staging. Services return `deliveries` (what would
  have gone out) when `DELIVERIES_ECHO=true` and the app shows them in the "What we would have sent" notice. In-app
  notices go through the `NOTICE` topic (`hospio-notice`) to messaging-service.
- **Times.** Lists are newest first by time, not by day. Days are Lagos days.
- **Labels.** Codes (FRONT_OFFICE, CREDIT_LIMIT, WASH_IRON) are never shown; the app maps them to words.

## Routes

New or changed for the owner portal (all behind the gateway, `https://api-staging.hospio360.com`):

| Method | Path | Who | What |
|---|---|---|---|
| PUT | `/identity/users/{id}/details` | owner, manager | `{fullName?, phone?, email?, jobTitle?}`; only fields sent change. 409 on an email or phone in use |
| POST | `/identity/users/{id}/suspend` | owner, manager | `{reason}` (required). Status SUSPENDED until reinstated; signed out everywhere; the person's hotels' managers told |
| POST | `/identity/users/{id}/pause` | owner, manager | `{days 1-90, reason?}`. Status PAUSED until Lagos midnight after the last day (`pausedUntil`); ends by itself |
| POST | `/identity/users/{id}/reinstate` | owner, manager | ends a suspension or pause now |
| POST | `/identity/users/{id}/reset-password` | owner, manager | (existing) temporary password; deliveries now an email **and an SMS** when the person has a phone |
| GET | `/identity/users` | owner, manager | (existing) now also `pausedUntil, statusReason, statusChangedBy, statusChangedAt, jobTitle`; a pause that ran out reads ACTIVE |
| GET | `/messaging/threads` | anyone | my staff threads, newest first (business-wide without `X-Hotel-Id`) with `withName`, `mine`, `unread`, `lastPreview` |
| POST | `/messaging/threads` | owner, manager | at the `X-Hotel-Id` hotel: `{toUserId}` one person, or `{toHotelManagers:true}` (owner) the hotel's managers; `{subject?, body, whatsapp?}` |
| POST | `/messaging/threads/managers` | owner | `{subject?, body, whatsapp?}` one thread per manager of every hotel (`broadcastId` ties them) |
| GET | `/messaging/threads/{id}` | people in the thread | the thread and its messages; marks it read for the caller |
| POST | `/messaging/threads/{id}/reply` | people in the thread | `{body}`; the other side is told (notice links `/inbox?open={id}`) |
| POST | `/messaging/conversations/guest` | front office, manager, owner | (existing) now echoes the guest message in `deliveries` |
| PUT | `/reservation/bookings/{id}` | front office (edit), manager, owner | (existing) when the dates move the guest gets a WhatsApp message, echoed in `deliveries` |
| POST/PUT | `/folio/organizations[/{id}]` | owner, manager | (existing) a manager's credit limit over the owner threshold no longer fails: the rest is saved and the limit becomes `requestedCreditLimit`, an approval (kind CREDIT_LIMIT, OWNER) |
| POST | `/platform/approvals/{id}/decide` | owner | (existing) APPROVED applies the requested credit limit, DECLINED keeps the old one (folio follows `APPROVAL_DECIDED`) |
| GET | `/payment/payments/settlements?from&to` | owner | the business's settlements (day × provider: gross, fees, H.M.S share, net, status) |
| GET | `/platform/changes` | owner, manager | (existing) changes by H.M.S, now with `undoRequestedAt`, `undoRequestedByName`, `undoTicketId` |
| POST | `/platform/changes/{id}/request-undo` | owner | `{note?}` asks support to undo (HIGH ticket, category CHANGE_UNDO); once; within the 7 days |
| POST | `/platform/changes/{id}/undo` | H.M.S only (was owner too) | support marks it undone after putting it back; resolves the owner's ticket |
| POST | `/platform/support/ownership-transfer` | owner | `{newOwnerName, newOwnerEmail, newOwnerPhone?, note?}` HIGH ticket, category OWNERSHIP_TRANSFER; one open at a time |
| GET | `/platform/support/ownership-transfer` | owner | the latest transfer request (or null) |
| GET | `/platform/reports` | owner, manager | (existing) schedules (status SCHEDULED, STOPPED) and the runs they made (READY) |
| POST | `/platform/reports/schedules` | owner, manager | `{kind, every DAILY/WEEKLY/MONTHLY, sendAt HH:mm, hotelId?, managers?}`; same report and scope is updated, not doubled |
| POST | `/platform/reports/schedules/{id}/stop` | who scheduled it, owner | stops it; earlier runs stay |
| POST | `/platform/reports/schedules/{id}/run` | who scheduled it, owner | sends the latest full period now; the email comes back in `deliveries` |

Unchanged routes the screens reuse: `/tenant/hotels`, `/tenant/businesses/mine`, `/finance/owner/summary`,
`/finance/workforce/clock`, `/finance/workforce/rota`, `/reservation/bookings` and its check-in, check-out and cancel,
`/folio/folios/*`, `/payment/payments` (+ record, verify), `/platform/approvals`, `/platform/activity`,
`/platform/departments/live`, `/maintenance/work-orders`, `/identity/users/{id}/roles/{grantId}` (REMOVE for "remove
from hotel"; owned by Roles & permissions).

## Service rules

**identity · staff accounts (`StaffAccountService`).** Nobody acts on themselves ("You can't suspend your own account
here…"), on the owner ("Only H.M.S can change the owner's account") or outside the business ("That person is not in
your business"). A manager acts only on people of their hotels ("That person does not work at one of your hotels") and
never on a manager ("Only the owner can pause a manager"). Suspending needs a reason ("Say why X is suspended") and
happens once; a suspended person is reinstated before being paused; a pause is not stacked ("X is already paused until
Tue 17 Nov"). Suspend and pause revoke refresh tokens and publish `ACCESS_CHANGED` (SUSPENDED, PAUSED_UNTIL), so live
tokens are refused at once. `AccountPauseJob` (hourly at :10 Lagos) ends pauses whose last day has passed (RESUMED, the
person told); sign-in also lets the person in as soon as the pause has run out and says "This account is paused until
Tue 17 Nov" before that. Details: name must have letters; email and phone unique across Hospio; "Nothing to change"
when the same.

**messaging · staff threads (`StaffThreadService`).** Only the owner or a manager starts one, never to themselves or
outside the business. "To the hotel" means the hotel's active managers (owner only; "This hotel has no manager to
message yet"). "Message all managers" is owner only, one thread per manager across the business (a manager of two
hotels gets one). Only the starter, the person, or (to managers) a manager of that hotel opens or replies ("This
conversation is between other people"). Unread counts are per side. `whatsapp:true` also sends the message to the
phones (echoed).

**folio · credit limits.** The owner threshold is `hospio.approvals.org-credit-owner-limit` (₦1,000,000 on staging).
A manager raising a limit above it: everything else is saved, the limit waits (`requestedCreditLimit`), the save says
"Saved · the new credit limit is waiting for the owner". A different second request waits for the first answer. The
owner setting a limit directly settles any waiting request. Approved → the limit applies; declined → the old one stays;
a question leaves it waiting.

**platform · requests to H.M.S.** Owner only. Undo: within 7 days, once, not already undone. Transfer: not to the
owner's own email, one open at a time. Both are HIGH tickets the admin portal's Support queue shows.

**platform · report schedules.** Kinds: DAILY_SUMMARY, OCCUPANCY, REVENUE, PAYMENTS, EXPENSES, GUESTS. A manager
schedules for their own hotel only and cannot send to the managers. `ReportScheduleJob` (every 5 minutes) runs due
schedules: a READY row for the last full period (yesterday; last Monday-Sunday; last month), an in-app notice to the
person who scheduled it (and, with `managers`, to each hotel's managers for their own hotel) linking to
`/reports?report=KIND&from&to[&hotel]`, and an email with the same link (echoed on staging, nobody to show it to from
the job; "Send now" shows it).

## Screens (web)

Data seam: `data.owner.*` in `packages/api/src/data/owner.ts` loads every hotel (one call per hotel with that hotel's
`X-Hotel-Id`, or one business-wide call without it) and tags rows with `hotelId` and `hotelName`. Server actions in
`packages/api/src/actions/owner.ts` and the hotel-scoped wrapper `atHotel(hotelId, …)`; the manager's booking and
payment components take an optional `hotelId` through `HotelScope` so the owner reuses them unchanged.

- **Dashboard** — hotels with money (finance owner summary), approvals, activity (business-wide), 30-day series.
- **Hotels** — cards and list from tenant-service with this month's revenue, expenses, occupancy (finance), staff on
  shift (workforce clock), open repairs (maintenance) and the last action (activity). Message the manager starts a
  staff thread with the hotel's manager at that hotel.
- **Bookings** — every hotel's bookings with the hotel filter. The booking panel, new booking, check-in, check-out,
  change dates (guest told), cancel and take payment are the manager's components acting at the booking's hotel.
  Message guest uses messaging's guest conversation (echoed).
- **Staff** — everyone with a role at each hotel (one row per person and hotel) from identity. Add (invite with the
  temporary password and link), edit details, pause for 7 days, suspend with a reason / reinstate, remove from the
  hotel (REMOVE of their grants there), new temporary password (email + SMS, echoed), message (staff thread).
- **Financials** — Organizations: credit limit requests waiting for the owner, approve or decline (approvals).
- **Payments & payouts** — every hotel's payments; settlements by day and provider with what is still to be paid out.
- **Messaging** — my staff threads across hotels; new message to one manager or a hotel's managers; message all
  managers; reply. Everyone else reads and replies in **Inbox** (`/dashboard/inbox`, any role).
- **Operations** — hotels now (on shift, open repairs, occupancy, last action), departments (live board per hotel),
  activity log (business-wide or one hotel), changes by H.M.S with "Ask support to undo".
- **Reports** — six reports built from live data for a period and a hotel (or all), downloaded as CSV; schedule
  (daily, weekly, monthly), stop, send now.
- **Settings** — company details, taxes, approval limits (existing); transfer ownership sends the request to H.M.S
  support and shows its state.

## Limits

- Reports are CSV, built in the browser from the live data; no PDF or Excel. Scheduled reports arrive as an in-app
  notice and an email link (echoed on staging), not as attachments.
- A change by H.M.S is put back by support, not by the owner's click: the owner asks, support undoes and marks it.
- Ownership transfer is done by H.M.S support from the ticket; there is no self-serve accept flow.
- Staff (non-managers) read and reply to owner messages in Inbox; WhatsApp replies do not come back into the thread.
- Payroll, budgets and branch P&L on Financials stay "Not connected yet" (no payroll service).

## Progress

- [x] Contract (this file)
- [x] identity: details, suspend, pause N days, reinstate, SMS temporary password, tests
- [x] messaging: staff threads, broadcast to managers, guest message echo, tests
- [x] folio: credit limit requests to the owner, tests
- [x] platform: undo request, ownership transfer, report schedules + job, tests
- [x] reservation: guest told when dates change, tests
- [x] payment: owner settlements
- [ ] web: data seam + hotel scope
- [ ] web: bookings, staff, financials, hotels, messaging + inbox, operations, reports, settings, payments
- [ ] staging deploy (backend, web)
- [ ] API sweep (positive, negative, cross-tenant, money)
- [ ] browser sweep (1512 / 1280, all hotels vs one)
- [ ] QA script `docs/qa/owner-portal.md` (hotel-internal-system-admin-FE)
