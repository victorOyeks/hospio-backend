# A hotel's operating day · contract

The flow after onboarding: staff on shift, rooms cleaned, cash in tills, spend approved, the day closed, the owner seeing real numbers. Everything below is built on what exists in finance-service (workforce, tills, expenses, night audit, today), housekeeping-service and platform-service approvals; this document lists only what changes. Deliveries (`hospio.deliveries.echo`) work as in `docs/onboarding.md`.

Out of scope for this flow (later): payroll, bills to pay, branch P&L, organisation credit, staff tablets/PIN screens, kitchen, bar, inventory, laundry, maintenance.

## Rules

- Hotel settings are the source of truth. finance, folio and payment read `APPROVAL_LIMITS` (managerExpenseLimit, ownerExpenseLimit, discountPct, refundsNeedOwner) and `PAYMENTS.tillFloat` from hotel-config `GET /internal/settings/{section}?hotelId=` (copy payment-service `HotelConfigClient`), cached 60 s, falling back to the `hospio.approvals.*` properties only when hotel-config is unreachable.
- Expense routing: amount ≤ managerExpenseLimit → a manager's own spend is recorded, staff spend needs a manager; amount > managerExpenseLimit → needs the owner (unless the caller is the owner). `ownerExpenseLimit` 0 means no ceiling.
- Guards: reads of hotel-wide money, rota, leave and requests are `@access.managerView()` or the finance role (`@access.atLeast('FINANCE','VIEW')`); writes of tills, deposits and expense payment are `@access.managerView()`-plus: manager, owner, FINANCE EDIT, or the till's own role for open/close; staff keep their own routes (my shifts, my leave, clock, my till, my tasks).
- Every decision a person waits for publishes an event and a delivery to them (leave, shift request, approval, expense question).

## finance-service (`/finance`)

### Workforce

| Method | Path | Who | Change |
|---|---|---|---|
| GET | `/workforce/rota?from&to` | manager view | add `clockedIn` (open clock entry), `attendance` (PRESENT when clocked in during the shift, LATE when clock-in > start + STAFF.lateMinutes, ABSENT when the shift ended with no clock-in) |
| DELETE | `/workforce/shifts/{id}` | manager | new; status CANCELLED |
| GET | `/workforce/shift-requests` · `/workforce/leave/pending` | manager view | guard added |
| GET | `/workforce/clock/me` | any | new: `{clockedIn, since, todayMinutes}` |
| GET | `/workforce/clock?date` | manager view | new: today's clock entries (who is on, since when) |
| POST | `/workforce/leave/{id}/decide`, `/workforce/shift-requests/{id}/decide` | manager | publish `LeaveDecided` / `ShiftRequestDecided` and a delivery to the staff member; approved leave cancels that person's shifts in the range |
| — | LeaveRequest | | add `days` (inclusive) to the response |

### Tills

Cash at a till desk needs the till open. The desks with a till are the front office, bar and kitchen (each has a Till page). payment-service `POST /payments` with `method: CASH` for one of these desks (the `role` sent, else the caller's first paying desk) asks finance whether that desk's till is open and refuses with 400 "Open your till first: cash taken at the bar goes into the bar till, and it isn't open. Open it on the Till page (count the float), then take the cash. Card and transfer still work". `GET /payment/payments/cash-till?role=` gives `{role, needed, open, message}`, so the payment step can say so before the cash is counted and open the till in place. Card, transfer and charge to room are unchanged. Not asked: laundry, which hands its cash in and has no till page (its cash is recorded and Today's money and the night audit list it as cash taken with no till). Managers and owners have no till of their own: since 20 Nov their cash goes into the till of the desk where it is taken (see "Manager and owner cash" below). A manager who also holds a desk role and sends `role` for that desk is asked like the desk. When finance-service can't be reached the cash is refused too (since 22 Nov, owner's decision; before, it went through with no till): 400 "We couldn't check the till just now, so cash can't be taken yet. Try again in a moment. Card and transfer still work", and `cash-till` answers `needed: true, open: false` with that message. A cash refund is refused the same way. Cash outside every till can't be counted at the close, so a short outage pauses cash rather than letting it slip past the drawer.

| Method | Path | Who | Change |
|---|---|---|---|
| GET | `/tills` | manager view or FINANCE | unchanged list; add `entries` count and `openedByName` |
| GET | `/tills/mine?role=` | any | the open till of the desk named (`BAR`, `KITCHEN`, `CASHIER`, `FRONT_OFFICE`, `FINANCE`; `WAITER` reads the cashier's), or null; 403 for a desk the caller doesn't work (managers, owners and finance may read any). Without `role`, the first of the caller's desks with a till open (older callers only: every Till page sends its desk). Each till carries `stale` |
| GET | `/tills/mine/closed?role=` | any | that desk's tills closed in the last 7 days (all the caller's desks without `role`) |
| POST | `/tills` | manager, FINANCE EDIT, or a holder of that role | body `{role, openingFloat?}`; float defaults to `PAYMENTS.tillFloat` |
| POST | `/tills/{id}/movements` | till role holder, manager | new: `{kind: PAY_IN|PAY_OUT, amount, reason}` → TillEntry with negative amount for PAY_OUT; expectedCash recomputed |
| POST | `/tills/{id}/close` | till role holder, manager | `note` required when variance ≠ 0; publishes `TillClosed{tillId, role, variance}`; a variance ≠ 0 raises a delivery to the hotel's managers |
| POST | `/tills/{id}/handover` | till role holder, manager | new: closes the till with the count and opens a new one for the same role with `openingFloat = countedCash`, `openedBy` = the named next person `{countedCash, note, toUserId, toUserName}` |
| — | refunds | | consume `REFUND_APPROVED`: a CASH refund adds a negative TillEntry to that role's open till |
| — | cash expenses | | an APPROVED/RECORDED expense with `paidFrom = TILL` adds a PAY_OUT to the caller's role till when marked paid |
| — | cash with no open till | | `recordPayment` keeps a RevenueEntry flagged `unattachedCash = true`; `GET /today` returns `unattachedCash` so the manager sees it |
| GET | `/internal/tills/open?hotelId&role` | internal key | `{open, tillId, stale, openedAt, openedBy, openedByName}`: payment-service asks before it takes cash |

#### One till per desk, one till per person, tills left open (20 Nov 2026, "Bar till and cash")

- **Shared desk till.** A till belongs to a desk (role) at a location, not to a person: one open till per desk (`uq_tills_one_open_per_role`), and everyone working that desk takes cash into it (a waiter's cash goes into the cashier's). Whoever opened it is shown on the Till page ("Bar till · opened by …"). Each Till page reads its own desk's till (`/tills/mine?role=`): before this fix the bar page of someone who also works the front office showed the front office till (the first desk with a till open), its entries, and Count & close closed that one, then the bar till on the next press.
- **One person, one till.** `POST /tills` refuses with 400 "You already have the front office till open (since Thu 19 Nov, 8:35am). One person holds one till: count and close it or hand it over first" when the caller works the desk and opened another desk's till that is still open. A handover to someone who holds another open till is refused the same way. A manager opening a desk's till they don't work is not asked.
- **Already open.** Opening a desk's till that is open says who has it and since when ("The bar till is already open (opened by …, since …). Cash at the bar goes into it"), adding "it was left open from an earlier day, so count and close it first" when stale.
- **Left open from an earlier day (`stale`).** A till opened on an earlier Lagos day and 12 hours or more ago (`Till.staleAt`): a night shift that runs past midnight is not stale, yesterday's till at noon today is. Cash can't go into it: `POST /payments` with CASH refuses with "Open your till first: the bar till was opened by … on Wed 18 Nov, 4:06pm and never closed. Count and close it on the Till page, then open today's till before taking cash. Card and transfer still work"; `GET /payments/cash-till` answers `open: false, stale: true` with the same message; a PAY_IN is refused. Count & close works as for any open till.
- **Cash refunds.** A manager approving a CASH refund of a payment taken at a till desk needs that desk's till open and not stale ("This cash refund comes out of the bar till, and it isn't open. Open it first, then approve the refund"): the cash leaves a drawer someone is counting. An owner's answer to a refund sent for approval is not refused (it arrives as an event); the cash then comes out of the desk's till if it is open, as before.
- **Count & close** closes the till whose id is sent and only while it is OPEN (the row is locked and its status re-read): a second close of the same till is 400 "This till is already closed".
- `GET /payments/cash-till?role=` also returns `tillRole`, `tillId`, `openedByName`, `openedAt` and `stale`, so the payment step names the drawer the cash goes into.

#### Manager and owner cash (20 Nov 2026, "Manager cash into till")

Before this, a manager's or owner's cash (Sales, Payments → Record a payment, a folio's Record payment, check-in, check-out, a booking's payment) was recorded under MANAGER or OWNER with no till and only listed on Today's money as cash taken with no till. Now it goes into the till of the desk where the sale or payment happens, the same drawer the desk's staff use:

| What is paid | Till |
|---|---|
| An outlet sale (`orderId`) at a bar | the bar till |
| An outlet sale at a kitchen or restaurant | the kitchen till |
| A folio, a booking (deposit, check-in, check-out) or a walk-in with no sale | the front office till |
| A sale taken on the cashier's or a waiter's screen | the cashier's till (the desk sent is CASHIER or WAITER, as for staff: a manager reaches these screens only when they also hold that role) |

- payment-service `POST /payments` with `method: CASH`, when the paying role resolves to MANAGER or OWNER (no `role` sent, or `role: MANAGER`/`OWNER`), records the payment under that desk's role (`role: BAR`, `KITCHEN` or `FRONT_OFFICE`; `requestedBy`/`requestedByName` still say who took it), so `PaymentApproved` carries the desk and finance puts it in that desk's open till: it counts in the till's expected cash, its entries (the Till page's cash list) and its count and close, and a cash refund of it comes out of the same drawer (`RefundService.requireTillForCash` asks for that till as for the desk's own cash). Card and transfer stay under MANAGER or OWNER (their POS routing is unchanged).
- The till must be open and opened today, as for staff, or the cash is refused with 400: "Open the bar till first: your cash goes into the till of the desk where it is taken (the bar for this sale), and that till isn't open. Count the float and open it, then take the cash. Card and transfer still work" (front office: "...(the front office for a folio, booking or walk-in)..."). A till left open from an earlier day: "Open the bar till first: it was opened by … on Wed 18 Nov, 4:06pm and never closed. Count and close it (Financials → Tills, or the bar's Till page), then open today's till and take the cash".
- `GET /payments/cash-till?role=&orderId=` answers for the payment being taken: with `orderId` the sale's outlet is read from fnb-service, so a manager's answer names the desk (`role` and `tillRole`: `BAR`) with `manager: true` and `takenAs: MANAGER` (or `OWNER`). Without `orderId`, a manager's or owner's answer is the front office till.
- Opening the till: a manager or owner may open any desk's till (`POST /tills {role}`; `TillAccess.canOpenOrClose` lets managers and owners in, view-only support tokens excepted). The payment step opens it in place (float, "Open the bar till"). The one-person-one-till rule is kept for desk staff and not applied to a manager or owner opening a desk they don't work: a manager who takes cash at the bar and then at the front desk opens both drawers for the desks, which is a normal manager flow, and each drawer is still counted and closed by its desk (or by the manager in Financials → Tills). A manager who also holds the desk role (Edit) is asked like the desk.
- **No accommodation, no walk-in cash (20 Nov, owner's follow-up).** At a location without accommodation (`hasAccommodation` false: a lounge, bar or restaurant with no rooms) there is no front office, so a manager's or owner's walk-in cash (no sale, folio or booking: Payments → Record a payment) is refused with 400 "Cash at a lounge is taken on the bar or kitchen sale screen, where it goes into that outlet's till. Open the sale there and take the cash. Card and transfer still work here" instead of needing a front office till. `GET /payments/cash-till` answers `open: false, noWalkInCash: true` with the same message (the form shows it with no open button). Sales at that location are unchanged (their outlet's till). Card and transfer walk-ins are still taken: they go to the POS and no drawer is involved, so the till question doesn't arise. Hotels, and any location with rooms, keep the front office till. tenant-service is asked (`/internal/hotels/{id}` `hasAccommodation`, cached 5 minutes); when it can't be reached the location is treated as having accommodation.
- When the outlet of a sale can't be told (fnb-service unreachable) or finance-service can't be reached, the cash is refused with the same "couldn't check the till" message as for staff (since 22 Nov; it used to go through with no till).
- Laundry: unchanged. Laundry has no till page and hands its cash in; a manager or owner does not take laundry payments through the laundry desk (the laundry screens are the laundry and front office roles'). A laundry payment taken at the front desk is the front office's and goes into the front office till.
- Owner-approved refunds: a manager approving a cash refund needs the desk's till open before it is approved or sent to the owner (the check runs before the owner hand-off). The owner's answer arrives as an event (`ApprovalDecided`) after the decision is made in platform-service and can't be refused, so it is settled as before: the cash comes out of whichever till of that desk is open when the event arrives; when none is open, finance records no till entry and the refund shows on the payment only. Refusing there would leave an approved refund stuck as pending, so the owner's decision stands; the desk sees the refund on the payment. An owner approving in payment-service directly (`POST /refunds/{id}/approve`) is asked for the till like a manager.
- Existing data is unchanged: cash a manager or owner took before this stays under MANAGER or OWNER with no till, and Today's money and the night audit keep listing it as cash taken with no till.

### Expenses

| Method | Path | Who | Change |
|---|---|---|---|
| GET | `/expenses?status=&from=&to=` | manager view or FINANCE | filters, guard |
| POST | `/expenses` | any | routing from hotel settings (Rules); response includes `approverLevel` |
| POST | `/expenses/{id}/answer` | the requester | new: `{note}` answers a QUESTION; the approval goes back to PENDING via `ApprovalAnswered` |
| POST | `/expenses/{id}/paid` | manager, FINANCE EDIT | `{paidFrom?}` |

Supplier bills (21 Nov 2026): a delivery received in Inventory becomes an unpaid bill under Stock purchases (docs/inventory-stores.md,
Supplier bills). Finance pays it from Bills (Mark paid → bank, cash or a till), which records the PAID expense that shows in Expenses and
in Today's money "Paid out"; paying a bill is for a manager, the owner or Finance EDIT and is not routed for approval, like every bill. The
Record expense form hints at an unpaid supplier bill of about the same amount ("pay that instead?") so a delivery isn't paid twice; it is
only a hint.

### Night audit

| Method | Path | Who | Change |
|---|---|---|---|
| GET | `/night-audit?date` | manager view or FINANCE | no longer creates a row; returns the audit if any plus a computed `checklist[]` `{key, label, done, detail}`: `arrivals` (no BOOKED arrivals for the date: reservation internal), `departures` (no CHECKED_IN departing that day), `tills` (none open), `balances` (open folios with balance > 0 among departures: folio internal), and counts `arrivals, departures, inHouse, noShows, roomsOccupied, rooms` |
| POST | `/night-audit/close?date` | manager, owner, FINANCE FULL | refuses while `tills` is not done; when `NIGHT_AUDIT.arrivalsResolvedFirst` is on, refuses while `arrivals` is not done; marks remaining BOOKED arrivals NO_SHOW only when `{markNoShows: true}` is sent; computes revenue, payments by method, expenses, cash counted vs expected, occupancy from reservation; stores a `report` JSON; publishes `DayClosed` and a delivery of the summary (`DAILY_REPORT`) to the business owner |
| GET | `/night-audit/past` | manager view or FINANCE | unchanged, adds `report` |

### Money

| Method | Path | Who | Change |
|---|---|---|---|
| GET | `/today?date` | manager view or FINANCE | add `occupancyPct`, `arrivals`, `departures`, `inHouse` (reservation internal), `unattachedCash`, `pendingApprovals` count |
| GET | `/series?from&to` | manager view or FINANCE | new: per day `{date, revenue, expenses}` |
| GET | `/transactions?date` | manager view or FINANCE | new: payments (RevenueEntry), expenses paid, deposits and till movements merged, newest first `{at, kind, description, amount, method, by}` |
| GET | `/owner/summary?from&to` | owner | new: per hotel of the business `{hotelId, name, revenue, expenses, profit, occupancyPct}` plus totals (finance runs it per hotel as the system) |

## housekeeping-service (`/housekeeping`)

| Method | Path | Who | Change |
|---|---|---|---|
| — | room status seeding | | `GET /room-status` returns every room of the hotel (property internal rooms), defaulting to CLEAN where no row exists |
| — | check-in | | the stayover task no longer marks the room DIRTY (only departures and failed inspections do) |
| POST | `/room-status/{roomId}` | manager, front office, HOUSEKEEPING EDIT | new: `{status: CLEAN|DIRTY|INSPECTED, note}` (front-desk "mark ready" / "send to housekeeping") |
| POST | `/tasks/{id}/rush` | manager, front office | new: sets `priority = RUSH` |
| GET | `/tasks?date` | | adds `priority` and `guestArrives` (true when the room has a BOOKED arrival today: reservation internal) |
| — | guards | | reads by any hotel user; writes per the service checks |

reservation-service: check-in into a room whose housekeeping status is DIRTY is allowed but the response carries `warning: "Room not cleaned yet"`, and when `HOUSEKEEPING.inspectDepartures` is on, a DIRTY room needs `{overrideDirty: true}` from a manager.

## platform-service approvals (`/platform/approvals`)

| Method | Path | Who | Change |
|---|---|---|---|
| GET | `/approvals` · `/approvals/all` | manager view | add `hotelName`, `requesterRole` |
| POST | `/approvals/{id}/escalate` | manager | new: MANAGER-level → OWNER-level with `{note}` |
| — | decided | | `ApprovalDecided` also produces a delivery to the requester |
| — | answered | | consume `ApprovalAnswered`: status back to PENDING with the answer appended to `note` |

## Web (hospio-frontend)

Live domains add `finance`, `housekeeping`. New action files `packages/api/src/actions/{workforce,finance,housekeeping,approvals}.ts`; mappers `data/mappers/{finance,workforce,housekeeping,approvals}.ts`; seam branches for `finance.{today,tills,expenses,transactions,nightAudits,series}`, `staff.{shifts,leave,shiftRequests}`, `approvals.{list,get}`, `housekeeping.{tasks,lostFound}`. Screens switched from `useDemoAction` to `useAction`: manager staff (rota, leave, requests), manager approvals, manager financials, manager night audit, manager dashboard money, owner dashboard (seam, not fixture imports) and approval panel, owner financials (KPIs, series, expenses, approvals; payroll/bills/branch sections show "Not connected yet"), finance role (today, tills, expenses, night audit), front-office till (open, movements, close, handover) and room board (mark ready, send to housekeeping, rush), housekeeping my rooms and inspect, shared My shifts (clock in/out, requests, leave). Hard-coded limits and the owner's name come from settings and the business.

## Tips on a till (venue type, phase 4)

Where a location records tips, a cash tip handed over with a payment is in the drawer: it is its own till entry (`TIP`, "Cash tip
(held for staff)"), part of `expectedCash`, and summed on the till as `cashTips` so the count at close says how much of the cash is
tips. Income (`revenue_entries`) is the payment less the tip. Card and transfer tips are not in any till.
