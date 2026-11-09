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
