# Restaurant and bar · contract

Kitchen (restaurant and room service) and bar run on fnb-service, which already has outlets, tables, menus, happy hours, price-change approvals, orders, tabs and charge-to-room. This document lists only what changes so the flow works end to end: an order is taken, the kitchen or bar works it, the guest pays at the outlet or it goes on their room, stock is drawn down, and the money reaches the till, today's money, income by outlet and the night audit. Figma: Kitchen page 3373:1762 (section 3201:19882), Bar page 3373:2168 (section 3201:19911), Hotel setup "restaurants & tables" 3256:11392 and "kitchen & bar" 3260:12166.

Out of scope: inventory's own purchasing flow (requests, purchase orders, deliveries, counts screens) beyond what the outlets use; staff tablets / PIN.

## Rules

- Roles: KITCHEN works kitchen outlets (KITCHEN, RESTAURANT kinds), BAR works BAR outlets; FRONT_OFFICE may take room-service orders and charge to rooms; managers and owners can do everything; FULL level of KITCHEN/BAR manages that outlet's menu and happy hours. Every order and menu route carries a guard (`@PreAuthorize`) accordingly.
- Settings are read from hotel-config `OUTLETS` (`roomCharge`, `tabs`, `tabLimit`) and `TAXES_CURRENCY` (`pricesIncludeTax`, `vatPct`, `serviceChargePct`) through the common `HotelSettingsReader` (60 s cache).
- Money is counted once: an order is settled either by a payment at the outlet (revenue when the payment is approved, as today) or by a charge to the room (revenue when the folio is paid). Outlet *sales* (what was sold, by outlet) are reported separately from *income* (money received), so the bar's sales include room charges.
- A payment for an order carries the `orderId`; the order closes itself when that payment is approved.

## fnb-service (`/fnb`)

### Orders
| Method | Path | Who | Change |
|---|---|---|---|
| GET | `/orders?outletId=&status=&date=` | outlet staff, manager | `status=CLOSED` (or `ALL`) with `date` returns that day's closed orders (sale done, close shift); default stays the open board |
| POST | `/orders` | outlet staff, front office (room service), manager | TAB needs `OUTLETS.tabs`; ROOM_SERVICE / room orders need `OUTLETS.roomCharge` only when charged; numbers run per outlet per day (`B-0001`, `K-0001`) |
| POST | `/orders/{id}/lines` | same | adding to a TAB refuses when the tab total would exceed `OUTLETS.tabLimit` (400 "Tab limit ₦X reached · settle or ask the manager") unless the caller is a manager |
| PUT | `/orders/{id}/lines/{lineId}/status?status=READY|SERVED` | kitchen/bar | new: one dish ready / served; the order becomes READY when every live line is READY, SERVED when all served |
| POST | `/orders/{id}/lines/{lineId}/cancel` | outlet staff before SENT; manager or FULL after | new: `{reason}`; line CANCELLED, totals recalculated |
| POST | `/orders/{id}/cancel` | outlet staff while OPEN with no sent lines; manager after | new: `{reason}`; refused once settled |
| POST | `/orders/{id}/charge-to-room?roomId=` | outlet staff, front office, manager | refused when `OUTLETS.roomCharge` is off; checks synchronously that the room has an active stay **and an open folio** (folio internal `GET /internal/folios/open?bookingId=`); 400 "Nobody is checked in to room X" / "Room X has no open bill" instead of silently losing the charge; event carries actor |
| POST | `/orders/{id}/paid` | internal / payment listener | becomes internal only; fnb listens to `PAYMENT_APPROVED` with `orderId` and marks the order paid (tabs close when paid in full) |
| — | pricing | | happy hours apply automatically to lines added during an active happy hour of that outlet (`discount` per line, shown on the line); tax follows `pricesIncludeTax` (included: tax is extracted for reporting, total = sum of prices) |
| — | close tracking | | orders gain `closedBy`, `closedByName`, `settledBy` (PAYMENT / ROOM / COMPLIMENTARY / CANCELLED) |

### Sales
| Method | Path | Who | Response |
|---|---|---|---|
| GET | `/sales?date=&outletId=` | outlet staff (own outlet), manager, finance | `{date, outlets:[{outletId, name, kind, orders, sales, paidAtOutlet, chargedToRooms, cancelled, byMethod{CASH,CARD,TRANSFER}, topItems:[{name, qty, sales}]}], totals}` from the day's settled orders |
| GET | `/internal/sales?hotelId=&date=` | internal | same, for finance and the night audit |

### Menu, outlets, tables
| Method | Path | Who | Change |
|---|---|---|---|
| GET | `/outlets/{outletId}/menu?all=true` | manager / FULL | include inactive items for the manage-menu screen |
| PUT | `/menu/{id}/price` | | instead of throwing 400 for "sent for approval", returns 202 `{status:"PENDING_APPROVAL", requestId}` |
| PUT | `/outlets/{outletId}/menu/{id}` | manager / FULL | `options` documented as JSON `[{group, choices:[{name, priceDelta}], required, multiple}]`; `recipe` `[{stockItemId, quantity}]` (see stock) |
| GET/POST/PUT | `/outlets/{outletId}/categories` | read: staff; write: manager / FULL | new: categories with sort order (menu items keep `category` by name) |
| — | outlets | | an outlet gains `storeId` (the inventory store it draws from) |
| — | seed | | `hospio.seed.enabled`: for the seed hotel create "Restaurant" (KITCHEN) and "Bar" (BAR) with 8 and 4 tables, about 12 dishes and 12 drinks with categories, one happy hour (Bar, Mon–Fri 17:00–19:00, 20 %), and link their stores |

### Stock
- A menu item's `recipe` lists stock items and quantities per portion. When an order is settled (paid or charged to room), fnb publishes `ORDER_SETTLED {orderId, outletId, storeId, lines:[{menuItemId, name, quantity, recipe}]}`; inventory-service consumes it and records `USED` movements per recipe line against the outlet's store (idempotent on orderId), raising `STOCK_LOW` as it does today. Items without a recipe draw nothing.
- A cancelled line or order after settlement is not reversed automatically (manager adjusts stock); documented as a known limit.

## payment-service (`/payment`)
- `PaymentRequestDto` gains `orderId` and `role` (optional: the desk taking the payment; must be one of the caller's roles, else 400). When `role` is absent the order of preference is deterministic: FRONT_OFFICE, BAR, KITCHEN, LAUNDRY, MANAGER, OWNER.
- `PaymentApprovedEvent` carries `orderId`.

## finance-service (`/finance`)
- `GET /today` adds `outletSales` (from fnb internal sales) next to income by role.
- `GET /income` keeps money received by desk; new `GET /sales?date` passes through outlet sales for the manager and owner screens.
- Night audit checklist adds `orders`: open tabs and unsettled orders of the day at every outlet ("3 open tabs at the Bar · settle or move them") — must be done to close, unless the manager passes `{carryOpenTabs: true}`, which records them in the report. The daily report gains outlet sales.
- Tills for BAR and KITCHEN already exist; nothing changes server-side.

## inventory-service (`/inventory`)
- Consumes `ORDER_SETTLED` (above).
- Guards: `/levels/use` requires the store's team role, INVENTORY, or manager; `/requests/{id}/issue` and `/decline` require INVENTORY or manager.
- Seed: stores "Kitchen store" and "Bar store" for the seed hotel with the stock items the seeded recipes use.

## folio-service
- `GET /internal/folios/open?bookingId=` (if not present) for fnb's check.

## messaging-service
- `ORDER_CREATED` notifies the outlet's role for every order created by someone outside the outlet (front office room service), with a link the role can open (`/dashboard?order={id}`); `ORDER_READY` (new, when an order becomes READY) notifies whoever opened it; tab-limit refusals notify the manager.

## Web (hotel app)
- Live domains add `fnb` and `inventory`. Routes, mappers (`MenuItemRow`, `OrderRow` reconciled with the backend shapes), seam branches for `data.outlets.*` (outlets, tables, menu, categories, orders, sales) and the inventory reads the outlets use; actions file `actions/fnb.ts` (+ inventory use/waste, ask the store, breakage, count).
- Kitchen: orders board (by status, per-line ready/served, cancel line with reason), new order (dine-in table from the API, room service with room picker, takeaway; items with choices), ticket, charge to room (with "can't charge" message), take payment through the shared payment steps with `orderId` and `role: KITCHEN`, sale done; menu & prices (sold out, change price with approval state), manage menu (add/edit/deactivate items, categories, recipe), kitchen store (levels, ask the store, use or waste).
- Bar: today (open tabs, quick sale, hotel-guest sale), tabs (details, add drinks, move table, settle by payment or room, tab limit message), menu & prices with happy hours, bar stock (breakage, end-of-night count), close shift (summary from `/fnb/sales` and the bar till; closes the till).
- Till for BAR and KITCHEN: the front-desk till screen reused (open with float, pay in/out, close, handover), added to their navigation.
- Manager: Hotel setup "Restaurants & tables" (outlets: name, kind, store, open/closed; tables per outlet), menus for every outlet, price-change approvals through the existing approvals screen; manager and owner financials and dashboard show outlet sales.
- The shared payment flow used by kitchen and bar is the real one (no timers or simulated declines).

## Sales history

Every bar and kitchen sale over a range of days, with filters, the money and one sale in detail, so an unpaid quick sale from any shift can be found and collected. fnb-service (`/fnb`).

| Method | Path | Who | Response |
|---|---|---|---|
| GET | `/sales/history?from=&to=&outletId=&desk=&status=&method=&attendant=&mine=&q=&page=&size=` | KITCHEN, BAR (any level), manager, owner, finance | `{from, to, page, size, matching, rows[], totals, outlets[], attendants[], earlierUnpaid, viewer, outletChoices[]}` |
| GET | `/sales/history/export?` (same filters, no paging) | Full access on the outlet's role, manager, owner, finance | `{filename, csv, rows, truncated}` |
| GET | `/sales/history/unpaid?desk=` | as the list | `{count, amount}`: sales still owing money, any day, in the caller's default view (the sidebar count) |
| GET | `/sales/history/{id}` | as the list, for an outlet the caller sees | `{sale, roomId, bookingId, tableId, notes, subtotal, discount, tax, total, paidAmount, balance, lines[], payments[], timeline, can}` |

- **Days**: `from` and `to` are Lagos days, both included (today when omitted); a sale belongs to the day it was opened (`createdAt`). Up to 93 days at a time; `page` from 0, `size` 1–100 (50 by default). Rows are newest first by the moment the sale was opened.
- **Status** (`status=`, one or several, comma separated, or `UNPAID` for To pay + Part paid). First match wins: `CANCELLED`; `ON_ROOM` (charged to a room); `PAID` (paid in full, or closed as complimentary); `PART_PAID` (some money taken); `IN_PROGRESS` (an open tab, a kitchen or restaurant ticket not yet served, or a sale worth nothing yet); `TO_PAY` (anything else: a bar sale that is not a tab, or a served ticket, waiting for its money). The rule is `SaleBucket` (Java and SQL side by side).
- **Method** (`method=`): `CASH`, `CARD`, `TRANSFER` (a payment of that kind was taken) or `ROOM` (charged to a room). **Attendant** (`attendant=`): the user who opened the sale (`none` for sales with nobody recorded). **Search** (`q=`): sale number, guest name or an item's name, case-insensitive, up to 80 characters.
- **Money**: `sold` is the total of every sale not cancelled and splits exactly into `paid` (taken at the outlet, part payments included), `onRooms` (what went on rooms: the balance after any part payment) and `unpaid` (still due, open tabs and tickets in progress included); `sales` counts sales not cancelled, `cancelled` (and `cancelledAmount`) the cancelled ones. `outlets[]` and `attendants[]` break the same money down; each ignores its own filter, so with no outlet or attendant filter they add up to `totals`. `earlierUnpaid {count, amount, oldest}` is what still owes money from before `from` at the same outlets and attendant.
- **Who sees what**: bar staff see bar outlets, kitchen staff the kitchen and restaurant outlets (`desk=BAR|KITCHEN` narrows a person holding both); managers, owners and finance see every outlet. Another outlet's id is 403. Edit-level staff start on their own sales (`mine` defaults to true for them) and may pass `mine=false` to see the whole outlet; Full access and management start on the whole outlet. `viewer {userId, management, mine, mineByDefault, canExport, actsOnOthers}` tells the screen.
- **Acting on a sale** (`can {pay, chargeToRoom, cancel, note}` on the detail): only while it is open and owes money, by someone who works the outlet. Edit-level staff act on sales they opened; Full access and managers on any (`note` says why not, e.g. "Opened by Ngozi Eze…"). Finance only looks. Cancelling follows the order rules (whoever took it while nothing was sent; a manager after) and is refused once anything was paid. The actions are the existing routes: payment-service `POST /payment/payments` with `orderId` (a part payment is any amount up to the balance), `POST /orders/{id}/charge-to-room`, `POST /orders/{id}/cancel`. Screens hide what `can` refuses; the order routes keep their own rules (a tab is still settled by whoever is on shift).
- **Detail**: lines carry `cancelReason` and `cancelledByName`, and `choices [{name, extra}]` priced from the menu item's current choices; `payments [{paymentId, method, amount, at, reference, takenByName}]` (the screen asks payment-service `GET /payment/payments/{id}` for anything missing); `timeline {openedAt, openedByName, sentAt, settledAt, settledBy, settledByName, closedAt, closedByName}`.
- **Changes to the order flow**: an order records `settledByUser`/`settledByName` when it is paid, charged or closed as complimentary; a cancelled line records `cancelledByName`; a payment keeps its `reference` (the terminal's) and `takenByName` (the event's actor). A part-paid order charged to a room posts only the balance (tax in proportion, "(balance)" on the folio line), and close shift counts the part paid at the outlet. Cancelling an order with money on it is refused ("₦X is already paid…"), as is cancelling a line that would leave less than was paid.
- **Limits**: export stops at 5,000 rows (`truncated: true`). Option prices on the detail come from today's menu. No receipt printing (there is no print pattern for receipts yet; payment-service resends receipts).

### Progress
- [x] fnb-service: `SaleBucket`, `SalesHistoryQuery` (filters and money in SQL), `SalesHistoryService`, routes above, migration `008-sales-history`; tests: buckets, service guards, and the query against Postgres (Testcontainers, skipped without Docker).
- [x] Order flow: settled-by name, line canceller, payment reference and taker, balance-only room charge, no cancel with money on it.
- [x] Web: Sales screen for bar and kitchen with the unpaid count in the sidebar, sale panel from Sales and Recent sales, all-outlets view for manager, owner and finance (QA script `docs/qa/sales-history.md` in the web repo).
