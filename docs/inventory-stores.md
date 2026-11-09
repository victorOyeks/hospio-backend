# Inventory and stores · contract

The hotel's stores end to end on inventory-service: a main store and outlet stores (kitchen, bar, housekeeping …), the items they hold, teams asking the store and the store issuing, moving stock between stores, suppliers, purchase orders with approval, deliveries against them, stock counts with manager approval, low stock with reorder, and what the stock is worth and what each outlet used. The restaurant & bar flow (`docs/restaurant-bar.md`) already draws kitchen and bar stock when an order settles; that keeps working unchanged. Figma: "Hotel-Internal-System" Inventory pages (screens numbered Inventory 1.x–6.x in the code).

## Progress

| Step | Status |
|---|---|
| 1 · Contract | done (2026-11-09) |
| 2 · Backend (inventory-service, common event/delivery kind, messaging) | done (2026-11-09): `4cb03f7`, fixes `25b94ff` (approval-limit constructor), `e065eec` (count-approval message); deployed `6895387` [deploy-all]; seeded Main store, 25 items' main levels, 3 suppliers |
| 3 · Web screens wired (inventory role, manager and owner hub, finance figure) | done: hotel-internal-system-admin-FE `6a85771`, fix `97b0de5` (empty Select values) |
| 4 · Staging verification (API + browser, every role) | done: API 113/114 then 35/35 after the fix; browser 77/77 (INVENTORY, MANAGER, OWNER, FINANCE, KITCHEN, BAR); ORDER_SETTLED draw-down re-checked |
| 5 · QA script `hotel-internal-system-admin-FE/docs/qa/inventory-stores.md` | done (`f8fc7be`) |

## Rules

- **Roles.** `STORE` = INVENTORY (Edit or Full) or a manager/owner: everything in the store. INVENTORY Full or a manager: correct a level by hand. Manager/owner only: approve a stock count. FINANCE (any level): read stock value, usage, movements, orders, deliveries and suppliers; no writes. Outlet teams (KITCHEN, BAR, HOUSEKEEPING, LAUNDRY, MAINTENANCE at Edit): read stores, items and levels, raise requests for their own team, see their own team's requests, use or waste stock in their own store, count their own store. Read-only (support) sessions never write. Every route has a `@PreAuthorize`; the service re-checks per store/team. Refusals are 403 with a sentence ("Approving a count is for a manager"), bad input 400 with a sentence.
- **Stores.** A hotel has one or more stores; `kind` is MAIN, KITCHEN, BAR, HOUSEKEEPING, LAUNDRY, MAINTENANCE or OTHER. A store whose kind is a team's role belongs to that team. Names are unique per hotel. A store holding stock can't be deactivated.
- **Items.** Name (unique per hotel), category, unit, `minLevel` (reorder level: at or below it the item is low and `STOCK_LOW` is raised once when it crosses), `parLevel` (reorder up to; 0 = twice the minimum), `unitCost` and usual supplier. An item is held in any number of stores (one level row per store).
- **Cost** is the **weighted average** across the hotel: a delivery of `q` at cost `c` makes `unitCost = (onHand × unitCost + q × c) / (onHand + q)` where `onHand` is the item's quantity across all stores before the delivery. Every movement records the unit cost at that moment, so values in reports don't change when the cost moves later.
- **Movements** (never edited): RECEIVED (delivery, opening), ISSUED (to a team without its own store), TRANSFER_OUT / TRANSFER_IN (between stores, from a request or a direct transfer), USED (sold or used in service), WASTE, ADJUSTED (hand correction), COUNTED (approved count). Stock never goes below zero.
- **Approval limit for purchase orders** = Hotel setup → Approval limits → `managerExpenseLimit` (default ₦200,000; `hospio.approvals.manager-spend-limit` when hotel-config can't be reached). At or under it an order goes straight to the supplier. Over it: raised by the storekeeper → the manager approves; raised by a manager → the owner approves; raised by the owner → sent. Approval uses the existing approvals (platform-service `APPROVAL_REQUESTED`/`APPROVAL_DECIDED`, kind `PURCHASE_ORDER`), so it shows on the manager's Approvals screen and the owner's approvals.
- **Deliveries** (anything that would be emailed or texted) are echoed in the response on staging (`deliveries`), as elsewhere: the order sent to the supplier (email when the supplier has one, else SMS to their phone).
- Money is in naira with 2 decimals; quantities 2 decimals.
- Errors: a 403 from a route guard reads "You don't have access to do that"; a bean-validation 400 carries the first field's message (common `GlobalExceptionHandler`).

## inventory-service (`/inventory`)

### Stores and items
| Method | Path | Who | Change |
|---|---|---|---|
| GET | `/stores` | any hotel staff | adds `value`, `items`, `lowItems` per store (value only for STORE/FINANCE/manager, else null) |
| POST / PUT | `/stores`, `/stores/{id}` | STORE | `{name, kind, active}`; 400 "There's already a store called X", "Pick a kind: …"; deactivating with stock: 400 "X still holds stock (N items) · move or count it out first" |
| GET | `/items` | any hotel staff | adds `parLevel`, `supplierName`, `onHand` (all stores) |
| GET | `/items/{id}` | any hotel staff | new: item, `levels:[{storeId, storeName, quantity}]`, last 30 movements (names, value) |
| POST / PUT | `/items`, `/items/{id}` | STORE | `{name, category, unit, minLevel, parLevel, unitCost, supplierId, active, storeId?, openingQuantity?}`; opening stock on create is a RECEIVED movement "Opening stock". 400: name missing/duplicate, negative numbers, par below minimum, unknown supplier/store; deactivating with stock: 400 "X still has 12 kg in Main store · count it out or move it first" |
| GET | `/levels?storeId=` | any hotel staff | unchanged |
| GET | `/movements?itemId=&storeId=&kind=&from=&to=` | STORE, FINANCE, manager | new filters; rows carry `itemName, unit, storeName, unitCost, value, byName` (latest 300) |
| POST | `/levels/adjust` | INVENTORY Full, manager | `{itemId, storeId, quantity, reason}`; reason required |
| POST | `/levels/use` | the store's team, STORE | unchanged |

### Transfers (new)
| Method | Path | Who | Change |
|---|---|---|---|
| POST | `/transfers` | STORE | `{fromStoreId, toStoreId, lines:[{itemId, quantity}], note}` → TRANSFER_OUT / TRANSFER_IN pairs, reference `TRF-xxxxxx`. 400: same store, no lines, quantity ≤ 0, "Only 4 kg Rice in the Main store" |
| GET | `/transfers?from=&to=` | STORE, FINANCE, manager | transfers grouped by reference: `{reference, at, fromStore, toStore, lines, value, byName, note}` (requests' issues included) |

### Requests
| Method | Path | Who | Change |
|---|---|---|---|
| GET | `/requests?team=&status=` | STORE: any team; outlet teams: their own teams only | default for STORE is everything newest first (open first on the screen); lines come as an array `[{itemId, name, unit, quantity, issued}]`; adds `requestedByName`, `issuedByName`, `storeName`, `reason` |
| POST | `/requests` | outlet teams for their own team; STORE for any team | `{fromTeam, storeId?, lines, note}`; 403 "You can only ask the store for your own team (KITCHEN)"; 400 no lines / quantity ≤ 0 / unknown item / unknown store |
| POST | `/requests/{id}/issue` | STORE | `{storeId (issuing store), lines?:[{itemId, quantity}]}`; lines omitted = everything still outstanding. Per line up to what is outstanding (400 "Only 5 kg Rice is still outstanding"); stock check (400 "Only 2 kg Rice in the Main store"). Moves stock: TRANSFER_OUT from the issuing store and TRANSFER_IN to the request's store when it has one, else ISSUED to the team. Status PART_ISSUED while anything is outstanding (it stays on the waiting list), ISSUED when complete. 400 when issuing from the request's own store, or when it is already issued/declined/closed |
| POST | `/requests/{id}/decline` | STORE | `{reason}` required (400 "Give a reason so the team knows why"); REQUESTED → DECLINED, PART_ISSUED → CLOSED (the rest won't come) |
| — | notification | | `STOCK_ISSUED` carries `status` and `note`; the team's role is told "Supplies issued" / "Part of your request issued" / "Request declined · reason" |

### Suppliers
| Method | Path | Who | Change |
|---|---|---|---|
| GET | `/suppliers` | STORE, FINANCE, manager | adds `items` (count), `itemIds`, `openOrders`, `lastOrderAt`, `spent` (received value, last 30 days) |
| POST / PUT | `/suppliers`, `/suppliers/{id}` | STORE | `{name, contactName, phone, email, paymentTerms, address, notes, active, itemIds?}`; `itemIds` (when sent) become the items this supplier supplies. 400: name missing/duplicate, bad phone ("Enter a phone number like 0803 123 4567"), bad email; deactivating with open orders: 400 "X has 2 open orders · cancel or close them first" |

### Purchase orders
| Method | Path | Who | Change |
|---|---|---|---|
| GET | `/purchase-orders?status=` | STORE, FINANCE, manager | view rows: `supplierName, storeName, createdByName, lines:[{itemId, name, unit, quantity, unitCost, received, damaged}], total, receivedValue, approverLevel, decisionNote, sentTo, cancelReason` |
| GET | `/purchase-orders/{id}` | same | + its deliveries |
| POST | `/purchase-orders` | STORE | `{supplierId, storeId?, lines:[{itemId, quantity, unitCost?}], expectedOn?, note?, submit}`; storeId defaults to the MAIN store; unitCost defaults to the item's cost. `submit:false` → DRAFT; `submit:true` → as submit. 400: no lines, quantity ≤ 0, cost < 0, duplicate item, unknown supplier/store/item, inactive supplier, expected date in the past |
| PUT | `/purchase-orders/{id}` | STORE | edit while DRAFT only (400 "Only a draft can be changed") |
| POST | `/purchase-orders/{id}/submit` | STORE | DRAFT → SENT (with `deliveries` echo of the order sent to the supplier) or PENDING_APPROVAL (message "Sent to the manager for approval" / "… owner …") |
| POST | `/purchase-orders/{id}/cancel` | STORE | `{reason}` required; DRAFT, PENDING_APPROVAL or SENT with nothing received; 400 "Something has already been received on PO-… · close it instead" |
| POST | `/purchase-orders/{id}/close` | STORE | `{reason}`; PARTIAL → CLOSED (the rest won't come) |
| — | approval decided | | APPROVED → SENT (order published to the supplier); DECLINED → CANCELLED with the approver's note |
| — | statuses | | DRAFT, PENDING_APPROVAL, SENT, PARTIAL, RECEIVED, CLOSED, CANCELLED; numbers `PO-YYYY-NNNNN` per hotel |

### Deliveries
| Method | Path | Who | Change |
|---|---|---|---|
| GET | `/deliveries` | STORE, FINANCE, manager | adds `poNumber, storeName, receivedByName, lines:[{itemId, name, unit, quantity, unitCost, damaged, ordered, note}], short, over, damaged` |
| POST | `/deliveries` | STORE | `{purchaseOrderId?, supplierName?, storeId?, invoiceNumber?, lines:[{itemId, quantity, unitCost?, damaged?, note?}], note}`. Against an order: PO must be SENT or PARTIAL (400 "PO-… is waiting for approval" / "… is cancelled" / "… is already received"), items must be on it (400 "Paper towels isn't on PO-…"); store defaults to the PO's. Without an order: `supplierName` and `storeId` required. Good quantity goes into stock (RECEIVED at the delivered cost), damaged is noted not stocked, short/over computed per line; item cost re-averaged; PO becomes PARTIAL or RECEIVED. 400: nothing received, negative numbers |

### Stock counts
| Method | Path | Who | Change |
|---|---|---|---|
| GET | `/counts` | STORE, FINANCE, manager; outlet teams see their own stores' | rows: `storeName, startedByName, countedByName, approvedByName, lines:[{itemId, name, unit, expected, quantity, variance, unitCost, value}], varianceValue, note` |
| POST | `/counts` | STORE, the store's team | `{storeId}`; 400 "The Bar store already has a count open (started by X) · finish or cancel it first" while one is OPEN or COUNTED |
| PUT | `/counts/{id}` | STORE, the store's team | `{lines:[{itemId, quantity}], note}`; OPEN or COUNTED (recount); expected = system level now; variance and value worked out; 400 negative / no lines / approved already |
| POST | `/counts/{id}/approve` | manager, owner | COUNTED → APPROVED; each line's variance (counted − expected at submission) is posted as a COUNTED movement against the current level (sales since then are kept), never below zero. 403 "Approving a count is for a manager"; 400 "Nothing counted yet" |
| POST | `/counts/{id}/cancel` | STORE, manager | `{reason}`; OPEN or COUNTED → CANCELLED |

### Money
| Method | Path | Who | Response |
|---|---|---|---|
| GET | `/value` | STORE, FINANCE, manager, owner | `{total, stores:[{storeId, name, kind, items, lowItems, value}], low:[{itemId, name, unit, storeId, storeName, quantity, minLevel, parLevel, suggest, unitCost, supplierId, supplierName}], onOrder}`; `suggest` = par (or 2 × min) − quantity; `onOrder` = value still to arrive on SENT/PARTIAL orders |
| GET | `/usage?from=&to=` | same | `{from, to, stores:[{storeId, name, kind, received, transferredIn, issuedOut, used, wasted, counted}], teams:[{team, issued}], totals:{received, used, wasted, counted, issued}}`; values at each movement's cost; dates are Lagos days, default the last 30 days |

### Seed (`hospio.seed.enabled`)
For Royal Suites · Simpas Ikeja (missing rows only): "Main store" (MAIN, id `80000000-0000-4000-8000-000000000003`) holding back stock of the 20 kitchen and bar items plus housekeeping items (bath soap, toilet roll, hand towel, detergent, bin liners) with levels; a "Housekeeping store" is not created (housekeeping is issued to directly). Suppliers "Lagos Fresh Foods" (produce, meat, dry goods), "Ikeja Drinks Depot" (beer, soft drinks), "CleanPro Supplies" (housekeeping), linked as the items' usual supplier.

## common / messaging-service
- `StockIssuedEvent` gains `status` and `note`; messaging tells the team accordingly.
- `Delivery.Kind.PURCHASE_ORDER`.

## Web (hotel app)
- Data seam `data.inventory.*` is live for every read (stores, items with levels, item detail, requests, purchase orders, deliveries, counts, suppliers, transfers, value, usage); `routes-inventory.ts`; actions `actions/inventory.ts` returning `ActionResult` with `withDeliveries()` where the server echoes. No inventory button fakes success or uses demo rows when inventory is live.
- INVENTORY role: Stores & stock (KPIs: items, low, on order, stock value; store cards with value; low stock with Reorder → prefilled order; item panel with levels per store and movements; add/edit item; correct level; move stock), Requests (issue partly or fully from a chosen store, decline with reason), Purchase orders (new / draft / submit / cancel / close; approval state; what was sent to the supplier), Deliveries (receive against an order with received, damaged and cost per line; without an order), Stock counts (start, count sheet, submit, cancel; approval state), Suppliers (add/edit, items they supply, deactivate), Usage (stock used and issued per outlet for a date range).
- Manager and owner: Inventory is the same set of screens behind tabs, plus approving counts; purchase-order approvals arrive on Approvals.
- Finance: the financials overview shows stock value and last-30-days stock used.
- Kitchen store and Bar stock keep working (ask the store, use/waste, end-of-night count; their requests list shows issued/declined with the reason).

## Known limits
- Cancelling an order that is waiting for approval leaves the approval on the approver's list; deciding it later does nothing.
- Units are free text; there is no unit conversion (a carton is not 24 bottles).
- A settled order's stock draw (restaurant & bar) is not reversed when the order is cancelled afterwards.
- Housekeeping, laundry and maintenance have no ask-the-store screen yet; the API takes their requests.
- Counts and orders stored before this release (old line format) carry no expected quantities.
