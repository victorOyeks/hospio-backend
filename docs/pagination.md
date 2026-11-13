# Server-side pagination

Every list that grows without limit is paged by the service that owns it. The browser never loads a whole
history to show ten rows of it. This page is the contract, the list of converted endpoints and the progress
checklist.

## The contract

### Request

| Parameter | Meaning |
|---|---|
| `page` | 0-based page. Absent means 0. Negative is a 400 ("page counts from 0"). |
| `size` | Rows per page, 1 to 100. Absent means 25. Anything else is a 400 ("size is 1 to 100"). |
| `sort` | `field` or `field,asc` / `field,desc` over the fields the list allows (unknown field is a 400). Absent means the list's default, newest first. The default order always follows as the tie-breaker, so pages never overlap. |
| filters | The list's own: usually `q` (case-insensitive contains search), `status`, `from` / `to` (Lagos days, both inclusive), and others named per endpoint below. |

Filtering, counting and sorting happen in the database (a JPA `Specification` or SQL), never in memory.
`common/.../paging/Paging.java` holds the checks (`Paging.of(page, size, sort)`, `Paging.sort(...)`,
`Paging.windowFrom(from)`, `Paging.like(q)`).

### Response

`ApiResponse<PagedResponse<T>>`: `content`, `number`, `size`, `totalElements`, `totalPages`, `first`, `last`,
`empty`.

A list whose screen shows tab counts or money over the whole filtered range answers
`ApiResponse<PageWithCounts<T>>`: the same fields plus

- `counts`: tab counts over the whole filtered range, computed with every filter except the one the tabs
  choose (usually `status`), so switching tabs never changes the numbers. `counts.all` is the sum.
- `totals`: money over the whole filtered range (naira, two decimals).

Counts and totals are the same on every page.

### Natural windows

Lists that are read as "recent" (payments, expenses, notifications, approvals) default `from` to 90 days
before today (Lagos) when it isn't given. Asking for an earlier `from` reaches older rows.

### Backwards compatibility

Where an endpoint used to answer a plain list and something other than a screen still reads it (another
service, an operations script), the endpoint keeps that answer when **neither** `page` nor `size` is sent,
and answers a page as soon as either is. Screens always send `page` and `size`. No caps (`Top300` and the
like) remain on the paged form. The unpaged form is for internal callers only and, since the scale audit
(docs/scale-audit-2026-11.md), is bounded by a date window so it can't read a whole history: payments, refunds and
disputes 90 days (plus anything still open), inventory requests, purchase orders, counts and deliveries 90 days (plus
anything still open), plain bookings at most 93 days between `from` and `to` (else 400), notifications `mine()` the
newest 50.

### Exports

An export covers the whole filtered range, not the page on screen: the web reads every page (100 at a time)
with the same filters and writes one CSV, so its row count equals `totalElements`.

## The web side

- `packages/api/src/data/paging.ts`: `Paged<T>`, `PageResult<T>`, `toPaged`, `pageQuery`, `allPages`,
  `listQueryFrom` (the URL's 1-based `?page=` and filters → the service's query).
- `packages/ui/src/shared/list-params.ts`: `useListParams` (page, `q`, `status`, `from`, `to`, `sort` and
  extra filters in the URL; debounced search; any filter change goes back to page 1; Back restores).
- `packages/ui/src/shared/data-table.tsx`: `DataTable server={{ page, onPage, sort, onSort, sortKeys }}`,
  `filtered` + `onClearFilters` (no-match state), `error` + `onRetry` (load error state).
- `packages/ui/src/shared/server-pager.tsx`: "Showing 26–48 of 48" and the page numbers.

## Converted endpoints

Every endpoint below takes `page`, `size` (1 to 100, else 400) and `sort`, filters in the database and keeps
the counts and totals the same on every page. "Legacy" means the plain list still answers when neither `page`
nor `size` is sent (internal callers and operations scripts; bounded as described above).

| Service | Endpoint | Filters | Answer | Legacy |
|---|---|---|---|---|
| payment | `GET /payment/payments` | `q`, `status` (list), `method`, `role`, `from`/`to` (90-day default), `mine`, `today`, `hotelId` (owner without `X-Hotel-Id` reads the business) | PageWithCounts: per status, all, toVerify, attention, today, mine; approved money today, this month and over the range, per method | yes |
| payment | `GET /payment/payments/refunds`, `/disputes` | `status` | PageWithCounts; rows carry guestName, amount, method, reference (no lookup of every payment) | yes |
| reservation | `GET /reservation/bookings` | `q` (guest name, phone, email, reference), `status`, `view` (arrivals, departures, in-house, upcoming, past, all), `from`/`to`, `arrivingFrom`/`arrivingTo`, `guestId`, `hotelId` (owner) | PageWithCounts: per view and status; booked, unpaid, paid this month, in-house owing; rows carry guestPhone, guestEmail | yes (7 days back to 30 ahead, as before) |
| reservation | `GET /reservation/guests` | `q`, `view` (all, in-house, repeat, blacklisted) | PageWithCounts per view; rows carry inHouse, lastStay | yes |
| messaging | `GET /messaging/notifications` | `unread`, `kind`, `q`, `from`/`to` (90-day default) | PageWithCounts: all, unread | yes |
| messaging | `GET /messaging/notifications/unread-count` (new) | — | `{count}` for the bell | — |
| messaging | `GET /messaging/threads` | `q` | PagedResponse, newest message first | yes |
| platform | `GET /platform/approvals/all` | `tab` (waiting, owner, decided, open), `status`, `kind`, `q`, `from`/`to` (90-day default; open ones always shown) | PageWithCounts: per tab, questions; money waiting and with the owner. Names filled for the page only | yes |
| platform | `GET /platform/approvals/{id}` (new) | — | one approval (a link to one off the page) | — |
| finance | `GET /finance/expenses` | `tab` (approval, questions, paid, requests), `status`, `category`, `q`, `from`/`to` (90-day default; waiting ones always shown) | PageWithCounts per tab; totals all, recorded, requests, matching. Newest date first, then newest recorded | yes |
| finance | `GET /finance/bills` | `status` (UNPAID, OVERDUE, PAID, CANCELLED; absent = all), `q` (payee, note), `from`/`to` (due dates) | PageWithCounts: UNPAID, OVERDUE, PAID, CANCELLED, all; totals unpaid, overdue, paidThisMonth. Default order due date ascending (soonest first), then newest recorded | no (new) |
| finance | `GET /finance/night-audit/past` | `status`, `from`/`to` | PagedResponse, newest business day first | yes |
| inventory | `GET /inventory/requests` | `team`, `status` (list, OPEN), `q` (incl. item names), `from`/`to` | PageWithCounts per status | yes |
| inventory | `GET /inventory/purchase-orders` | `status` (list, OPEN, DONE), `supplierId`, `q`, `from`/`to` | PageWithCounts per status | yes |
| inventory | `GET /inventory/deliveries` | `purchaseOrderId`, `storeId`, `q`, `from`/`to` | PageWithCounts; totals.value. Reads only the orders on the page | yes |
| inventory | `GET /inventory/counts` | `storeId`, `status` (ACTIVE alias), `q`, `from`/`to` | PageWithCounts per status | yes |
| inventory | `GET /inventory/movements` | `itemId`, `storeId`, `kind`, `q` (incl. item name), `from` (90-day default), `to` | PagedResponse (the cap of 300 is gone) | yes |
| maintenance | `GET /maintenance/repairs` | `scope` (open, mine, raised, overdue, held, unassigned, done, all), `q`, `roomId`, `from`/`to` | PageWithCounts per scope (Top300 gone) | yes |
| housekeeping | `GET /housekeeping/laundry/orders` | `status` (OPEN), `q`, `roomId`, `bookingId`, `from`/`to` | PageWithCounts per status; totals.value (Top300 gone) | yes |
| housekeeping | `GET /housekeeping/laundry/linen-batches` | `status` (OPEN, DONE), `q`, `from`/`to` | PageWithCounts per status; condemned, lost, writtenOff (Top200 gone) | yes |
| housekeeping | `GET /housekeeping/lost-found` | `status`, `q`, `from`/`to` | PageWithCounts per status | yes |
| folio | `GET /folio/organizations` | `q`, `status` | PageWithCounts; each account's figures in grouped queries (no calls per account) | yes |
| folio | `GET /folio/organizations/overview`, `/{id}/summary` (new) | — | the cards; one account's figures | — |
| folio | `GET /folio/organizations/invoices` (new), `/{id}/invoices` | `organizationId`, `q`, `status` (OVERDUE worked out in the query) | PageWithCounts per status; amount, paid, left | `/{id}/invoices` yes |
| folio | `GET /folio/organizations/{id}/stays` | `status` (OPEN, CLOSED), `from`/`to`, `q` | PageWithCounts; balance, charges | yes |
| identity | `GET /identity/users` | `hotelId`, `role` (with `hotelId`: held at that hotel or business-wide), `status` (ACTIVE, PAUSED, SUSPENDED, HELD, INVITED, WORKING), `q`, `staffOnly`, `roleCounts` | PageWithCounts per status (HELD, INVITED, WORKING too), managers, invites; `ROLE_<ROLE>` with `roleCounts` | yes (pickers, rotas) |
| tenant | `GET /tenant/businesses` | `stage`, `q` | PageWithCounts per stage, hotels, rooms (size over 100 is a 400; it used to be cut to 100 quietly) | was paged |
| tenant | `GET /tenant/onboarding` | `status` | PageWithCounts per status | yes |
| platform | `GET /platform/admin/referrals`, `/admin/commissions`, `/marketer/referrals`, `/marketer/commissions` | as before | PageWithCounts (stages; DUE, WAITING, PAID with gross, wht, net) | marketer ones yes |
| fnb | `GET /fnb/sales/history` | (reference, built before this work) | totals over the range | — |

Left unpaged on purpose (bounded): open folios, tills (today's and open), till entries (one shift), closed
tills (7 days), the FnB board, the rota, transfers (30 days), the open work-order board, the open laundry board
(plus 14 days), open linen batches, booking buckets (checked in plus stays touching today), and the short setup
lists (room types, rooms, areas, roles, outlets, stores, menu, price lists, suppliers, terminals, assets,
schedules). The staff pickers (housekeeping assignment, rota and cover, till handover, lost & found, departments,
messaging, the owner's hotel detail and Messaging) still read the hotel's whole users list: it is bounded by headcount,
and they filter it by role on the screen. The lists themselves are paged (below).

### People lists (small fixes 1)

- Manager · Staff, Hotel Owner · Staff and Roles & permissions · Staff access read `GET /identity/users?page=&size=`
  with the search (`q`: name, email or phone), filters and sort in the service. Only the Staff rota tab still reads
  everyone at the hotel (the grid, find cover, swap and auto-fill).
- `status` takes the screens' tabs: `HELD` (paused or suspended), `INVITED` (an invite and none accepted, not held),
  `WORKING` (active, not invited). Counts carry all three next to ACTIVE, PAUSED and SUSPENDED.
- `staffOnly=true` leaves out anyone with an OWNER grant (the owner's Staff screen).
- `roleCounts=true` adds `ROLE_<ROLE>`: people holding the role, not paused, at `hotelId` or business-wide, over
  the same search (the role cards on Roles & permissions).
- With `hotelId`, `role` matches a grant held at that hotel (or business-wide), not the role at another hotel.
- The owner's Staff is one row per person and hotel, so a page of 25 people can show a few more rows; `total`
  and the counts are people.
- "Give a role" finds people with the same endpoint (`q`, `size=10`) instead of listing the whole hotel.

## Progress

- [x] Contract, `Paging`, `PageWithCounts`, web kit (DataTable server mode, ServerPager, useListParams)
- [x] Payments, refunds, disputes (manager, front desk, bar, kitchen, laundry, owner)
- [x] Guests, bookings (front desk, manager, owner), buckets bounded
- [x] Notifications + unread count, staff threads (Inbox, owner Messaging)
- [x] Approvals (manager, dashboards, departments)
- [x] Expenses (finance, manager financials), night audits
- [x] Store requests, purchase orders, deliveries, stock counts, stock movements
- [x] Work orders, laundry orders, linen batches, lost & found
- [x] Organizations with stays and invoices
- [x] Admin businesses (the 100 cap bug), onboarding, marketer referrals and commissions; admin fees read every page
- [x] Owner users
- [ ] Manager and owner Staff, Roles & permissions as server tables (bounded today; endpoint ready)
- [ ] Single-item reads for a store request, lost & found item and expense opened by link off the current page
