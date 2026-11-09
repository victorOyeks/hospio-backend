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

| Method | Path | Who | Change |
|---|---|---|---|
| GET | `/tills` | manager view or FINANCE | unchanged list; add `entries` count and `openedByName` |
| GET | `/tills/mine` | any | new: the open till of the caller's paying role, or null |
| POST | `/tills` | manager, FINANCE EDIT, or a holder of that role | body `{role, openingFloat?}`; float defaults to `PAYMENTS.tillFloat` |
| POST | `/tills/{id}/movements` | till role holder, manager | new: `{kind: PAY_IN|PAY_OUT, amount, reason}` → TillEntry with negative amount for PAY_OUT; expectedCash recomputed |
| POST | `/tills/{id}/close` | till role holder, manager | `note` required when variance ≠ 0; publishes `TillClosed{tillId, role, variance}`; a variance ≠ 0 raises a delivery to the hotel's managers |
| POST | `/tills/{id}/handover` | till role holder, manager | new: closes the till with the count and opens a new one for the same role with `openingFloat = countedCash`, `openedBy` = the named next person `{countedCash, note, toUserId, toUserName}` |
| — | refunds | | consume `REFUND_APPROVED`: a CASH refund adds a negative TillEntry to that role's open till |
| — | cash expenses | | an APPROVED/RECORDED expense with `paidFrom = TILL` adds a PAY_OUT to the caller's role till when marked paid |
| — | cash with no open till | | `recordPayment` keeps a RevenueEntry flagged `unattachedCash = true`; `GET /today` returns `unattachedCash` so the manager sees it |

### Expenses

| Method | Path | Who | Change |
|---|---|---|---|
| GET | `/expenses?status=&from=&to=` | manager view or FINANCE | filters, guard |
| POST | `/expenses` | any | routing from hotel settings (Rules); response includes `approverLevel` |
| POST | `/expenses/{id}/answer` | the requester | new: `{note}` answers a QUESTION; the approval goes back to PENDING via `ApprovalAnswered` |
| POST | `/expenses/{id}/paid` | manager, FINANCE EDIT | `{paidFrom?}` |

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
