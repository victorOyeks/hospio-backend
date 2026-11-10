# Manager leftovers, housekeeping leftovers and receipts

The last screens of the hotel app that still said "Not connected yet" or showed sample data: manager Reports (Email me),
the activity log, room price approvals, Hotel setup's sample sections, Financials (categories, budgets, bills), lost &
found, inspection and printable receipts. This page is the contract, the rules and the progress.

## Routes

All behind the gateway (`https://api-staging.hospio360.com`). A refusal is one sentence the screen shows as it is.

| Method | Path | Who | What |
|---|---|---|---|
| GET | `/platform/activity` | manager, owner, H.M.S | Paged, newest first. `page`, `size` (1 to 200, else 400 "size is 1 to 200"), `q` (summary, person, event), `department` (Front office, Housekeeping, Laundry, Maintenance, Kitchen, Bar, Store, Finance, Management, H.M.S; unknown is a 400), `from`/`to` (Lagos days), `byPlatform`. Filtered in the database |
| GET | `/platform/activity/{id}` | manager, owner, H.M.S | one entry (a link off the page) |
| POST | `/platform/reports/email` | manager, owner | `{kind, from, to, hotelId?}`: an email to the caller's own address with a link that opens the report (live data, CSV download). A manager's report is for one of their hotels |
| PUT | `/property/room-types/{id}` | manager, owner | (existing) a manager's base price change above Approval limits → `rateChangeOwnerPct` (0 = every change) while `rateChangesNeedOwner` is on is saved as `requestedBasePrice` and becomes an approval (kind ROOM_PRICE, OWNER); everything else saves. "Saved · the new price is waiting for the owner" |
| POST | `/platform/approvals/{id}/decide` | owner | (existing) APPROVED applies the requested base price, DECLINED keeps the old one (property-service follows `APPROVAL_DECIDED`) |
| GET | `/finance/expense-categories[?all=true]` | anyone at the hotel | the hotel's categories `{id, code, label, sortOrder, active}`; the first read seeds the standard 12 |
| POST / PUT | `/finance/expense-categories[/{id}]` | manager, owner | `{label}` add (same code → "X is already a category"); `{label?, active?}` rename (code kept) or archive ("Other is always available") |
| GET | `/finance/budgets/overview?month=yyyy-MM` | manager, owner, Finance VIEW | `{month, rows:[{category, label, budget, spent, left, pct}], totals}`; spent = RECORDED + APPROVED + PAID expenses dated in the month |
| PUT | `/finance/budgets` | manager, owner | (existing) `{category, month, amount}`; category must be the hotel's, amount ≥ 0, month yyyy-mm |
| GET | `/finance/bills` | manager, owner, Finance | PageWithCounts: `status` (UNPAID, OVERDUE, PAID, CANCELLED), `q`, `from`/`to` on the due date; counts per status, totals unpaid, overdue, paidThisMonth |
| POST / PUT | `/finance/bills[/{id}]` | manager, owner, Finance EDIT | `{payee, category, amount, dueDate, repeats NONE/WEEKLY/MONTHLY, note?}`; a paid bill can't be changed |
| POST | `/finance/bills/{id}/paid` | manager, owner, Finance EDIT | `{paidFrom BANK/TILL/CASH}` → `{bill, expense, next}`: a PAID expense of the same amount and category ("Bill · payee"); the next bill when it repeats |
| POST | `/finance/bills/{id}/cancel` | manager, owner, Finance EDIT | unpaid only |
| GET | `/housekeeping/lost-found` | housekeeping, front office, manager | (existing, paged) rows now carry roomNumber, foundByName, details, hasPhoto, returnedByName, returnIdNote, returnHow, disposedAt/ByName/Reason, frontOfficeToldAt, disposableFrom, holdDays; counts FOUND, RETURNED, DISPOSED, all |
| GET | `/housekeeping/lost-found/{id}` | the same | one item |
| POST | `/housekeeping/lost-found` | housekeeping EDIT, front office EDIT, manager | `{description, details?, roomId?, roomNumber?, foundWhere?, foundById?, foundByName?, photoKey?, tellFrontOffice}`; tells the front office by in-app notice unless `tellFrontOffice` is false |
| POST | `/housekeeping/lost-found/{id}/photo` (multipart) · GET the same | as logging · as reading | JPEG/PNG/WebP up to 2 MB |
| POST | `/housekeeping/lost-found/{id}/tell-front-office` | as logging | the notice again |
| POST | `/housekeeping/lost-found/{id}/return` | as logging | `{returnedTo, idNote?, how?}`; records who handed it over |
| POST | `/housekeeping/lost-found/{id}/dispose` | housekeeping FULL, manager | `{reason}`; only after Hotel setup → Housekeeping `lostFoundHoldDays` (90) |
| POST | `/housekeeping/tasks/{id}/inspect` | housekeeping FULL, manager | (existing) a fail needs the note ("Say what needs doing again") and tells the attendant; a pass makes the room ready |
| GET | `/payment/receipts/{paymentId}` | front office, bar, kitchen, laundry, finance, manager, owner | one approved payment's receipt: number, issued, amount, method, reference, taken by, guest |
| GET | `/payment/receipts?orderId=` or `?folioId=` | the same | a sale's or a folio's receipts, oldest first |
| POST | `/payment/receipts/email` | those roles at EDIT, manager | `{paymentId | orderId | folioId, to, text}`: the receipt as printed to the address given (docs/email.md); the receipts remember where they were sent |

## Rules

- **Activity log.** One page at a time from platform-service; the screen's search, department and dates are the
  service's filters, so counts and the pager cover the whole range. The export reads every page of the same filters,
  200 at a time, into one CSV (row count = `totalElements`). Departments map topics the same way on both sides
  (`ActivityLogService.DEPARTMENT_TOPICS`, web `activity-labels.ts`).
- **Room prices.** Mirrors the credit-limit approval: one waiting request per room type ("A price of ₦X for Deluxe is
  already waiting for the owner. Wait for their answer first"); the owner setting a price settles it; hotel-config
  unreachable means the change waits for the owner.
- **Expense categories.** Stored on expenses as a code (`FUEL_AND_POWER`); older spellings ("Fuel & power", REPAIRS)
  normalise to the same code, so filters, budgets and reports find them all. Labels are the hotel's (rename-safe).
- **Budgets.** Monthly per category per hotel; actual is the sum of recorded, approved and paid expenses of the month;
  totals are the sums of the rows.
- **Bills.** Simple payables. Paying one records a PAID expense straight away (no approval routing; only a manager, the
  owner or Finance EDIT can pay a bill); paying from the till takes the cash out of the payer's open till.
- **Lost & found.** Rooms are the hotel's rooms; public areas are Hotel setup's PUBLIC_AREAS list plus the hotel's floors
  and blocks (property areas), or "somewhere else" typed in. The front office has the same screen.
- **Receipts.** One number per approved payment, issued by payment-service (`RCP-<year>-<6 digits>`, unique per hotel by
  index). A sale's receipt lists its payments' numbers; a folio's receipt lists the stay's charges, discounts, tax and
  every payment line. Print is the browser's (`window.print()`), A4 or an 80 mm roll; Email receipt uses the email
  contract.

## Web

`data.activity`, `data.receipts`, `data.finance.{categories,budget,bills}`, `data.housekeeping.{lostFoundItem,places}`
(`packages/api/src/data/ml.ts`), actions in `packages/api/src/actions/ml.ts`, routes in `packages/api/src/api/routes-ml.ts`.
The receipt page is `/receipt?payment=|sale=|folio=[&paper=80mm]` (outside the dashboard shell so only the receipt prints).

## Limits

- Payroll stays "Not connected yet" (no payroll service); the bank balance is not connected either.
- Report emails carry a link, not an attachment (docs/email.md has no attachments).
- Linen write-offs were a sample tab on Inspect; they were removed there (linen losses and write-offs live with the
  laundry's linen batches).
- Receipt numbers are allocated as "one past the highest"; two approvals of the same hotel in the same instant can
  collide on the unique index, and that approval is refused rather than numbered twice.
- A sale charged to the room has no payment receipt; the folio receipt at check-out covers it.

## Progress

- [x] Contract (this file)
- [x] platform: activity filters and guard, Email me, absolute links in report emails, tests
- [x] property: room price approvals, decision listener, tests
- [x] finance: categories, budget overview, bills, tests
- [x] housekeeping: lost & found (photo, notice, return, dispose), inspection notice and guards, tests
- [x] payment: receipts read and email, tests
- [x] web: activity log, reports, room types, hotel setup, financials (manager, owner, finance), lost & found, inspect, receipts
- [x] staging deploy (backend 92d01bf, finance e5245ed; web c9ba0e4)
- [x] API sweep, browser sweep, print previews, regressions
- [x] QA script `docs/qa/10-manager-housekeeping-receipts.md` (hotel-internal-system-admin-FE)

## Verification (2026-11-10, staging)

- Unit tests: platform ActivityLogFilterTest 4; payment ReceiptViewsTest 5; property PropertyServiceTest 10 (7 new);
  housekeeping InspectAndLostFoundTest 9; finance ExpenseCategoriesTest 5, BudgetMathTest 4, BillServiceTest 7, ExpensePagingTest
  (Testcontainers) +1. Module suites green (finance 47, platform 25, payment 30, property 10, housekeeping 25).
- API sweep (`mlapi.ts`): 130 checks, all pass: activity (paging, 200 cap, filters, guards, isolation), Email me, room price
  approve/decline/second request/owner direct, categories, budget = sum of expenses per category and in total, bill paid =
  expense of the same amount and category plus the next bill, lost & found (photo, notice, return, hold days, guards,
  isolation), inspection (reason, notice, ready), receipts (numbers unique and stable, sale receipts total = sale, email).
- Browser sweep (`mlui.ts`) at 1512 and 1280 (housekeeping also 390), print previews A4 and 80 mm as PDF and print-media
  screenshots; regressions shapi, rbmoney, lmapi, ownapi, pgapi.
