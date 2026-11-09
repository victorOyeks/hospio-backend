# Laundry and maintenance · contract

Maintenance work orders end to end on maintenance-service (raised by the desk, housekeeping, a manager or maintenance against a room or a public area; assigned, started, held, completed with notes, parts from the Maintenance store, labour and vendor cost; reopened or cancelled with a reason), rooms out of order from a work order, preventive schedules against assets that open work orders on their own, priority-based due times with overdue flagged. Laundry on housekeeping-service: guest laundry orders from a price list (express or normal) charged to the guest's bill or paid at the desk, hotel linen sent soiled by housekeeping and returned clean by laundry with shortfalls and condemned linen written off the stock, and laundry chemicals drawn from the Laundry store. Housekeeping, Laundry and Maintenance get an "ask the store" screen on the inventory-service requests that already exist (`docs/inventory-stores.md`). Figma: "Hotel-Internal-System" Housekeeping / Laundry / Maintenance pages (Maintenance 1.x–4.x, Laundry 1.x–3.x numbering in the code).

## Progress

| Step | Status |
|---|---|
| 1 · Contract | done (2026-11-10) |
| 2 · Backend (maintenance, housekeeping/laundry, inventory internal moves + seed, reservation check-in, messaging notices, common event) | in progress |
| 3 · Web screens wired (maintenance, laundry, housekeeping linen + store, front desk repairs + laundry, manager/owner/finance money) | to do |
| 4 · Staging verification (API + browser, every role) | to do |
| 5 · QA script `hotel-internal-system-admin-FE/docs/qa/laundry-maintenance.md` | to do |

## Rules

### Roles
- **Raise a work order**: FRONT_OFFICE, HOUSEKEEPING, MAINTENANCE (Edit or Full), manager, owner. **Read work orders**: those roles plus read-only sessions of them; FINANCE reads only the money summary.
- **Run a work order** (start, hold, resume, complete, add an update): the assignee (MAINTENANCE Edit), MAINTENANCE Full (the supervisor) or a manager/owner. A technician may take an unassigned work order for themselves; assigning someone else is MAINTENANCE Full or a manager.
- **Reopen** a done work order: whoever raised it or a manager/owner, with a reason. **Cancel**: whoever raised it (only before work starts), MAINTENANCE Full or a manager, with a reason.
- **Rooms out of order**: MAINTENANCE Edit or a manager, from a work order for that room.
- **Assets and preventive schedules**: MAINTENANCE Full or a manager write; maintenance and managers read.
- **Guest laundry**: LAUNDRY Edit, FRONT_OFFICE Edit or a manager take orders, move them along, charge them to the room, record a payment and cancel. **Price list**: everyone reads; a manager/owner writes.
- **Linen batches**: HOUSEKEEPING Edit or a manager sends; LAUNDRY Edit or a manager receives and returns; LAUNDRY Full or a manager closes a batch short.
- **Money summaries** (`/repairs/summary`, `/laundry/summary`): manager, owner, FINANCE (any level), plus MAINTENANCE Full / LAUNDRY Full for their own.
- Every route has a `@PreAuthorize`; the service re-checks per record (assignee, raiser). A route guard 403 reads "You don't have access to do that"; a service refusal is 403 with a sentence ("Only the technician on it, the maintenance supervisor or a manager can do that"); bad input 400 with a sentence. Read-only (support) sessions never write.

### Work orders (maintenance-service, `/maintenance`)
- Numbered `WO-YYYY-NNNNN` per hotel. Against a room (`roomId`, number looked up) or an area (free text, e.g. "Pool", "Kitchen extractor"); one of the two is required. Category: PLUMBING, ELECTRICAL, AC, CARPENTRY, APPLIANCE, PAINTING, CLEANING_EQUIPMENT, IT, OTHER. Priority: LOW, NORMAL, HIGH, URGENT. Title required (≤ 150), description ≤ 1000.
- Statuses: OPEN → ASSIGNED → IN_PROGRESS ⇄ ON_HOLD → DONE; CANCELLED from any open status; DONE → (reopen) ASSIGNED when someone was on it, else OPEN. Old WAITING_PARTS rows become ON_HOLD ("Waiting for parts").
  - start: ASSIGNED or OPEN (taking it) → IN_PROGRESS; hold: IN_PROGRESS → ON_HOLD with a reason (required); resume: ON_HOLD → IN_PROGRESS; complete: IN_PROGRESS or ON_HOLD → DONE; anything else is 400 "WO-… is done · reopen it first" style.
- **Due time (SLA)** from the time it was raised (or reopened): URGENT 4 h, HIGH 24 h, NORMAL 3 days, LOW 7 days. `overdue` = still open and past `dueAt`. A job every 15 minutes (`Africa/Lagos`) tells the manager and the assignee once per work order when it goes overdue (`REPAIR_OVERDUE`); lists show "Overdue" and sort overdue first, then priority, then due time.
- **Complete** needs a note of what was done; `labourMinutes` ≥ 0; parts `[{itemId, quantity}]` are drawn at once from the hotel's MAINTENANCE store (inventory-service USED movements referenced `WO-…`, at the item's cost then) — 400 "Only 2 pcs AC filter in the Maintenance store" and nothing is saved; vendor (name + cost ≥ 0) optional. `partsCost` = sum of the movement values; `totalCost` = partsCost + vendorCost. A reopened work order completed again draws only the parts named the second time (reference `WO-…-2`).
- **Out of order**: only for a room work order that is still open; `until` (optional) is today or later; refused 400 "Room 104 has a guest checked in (Ada Obi) · move them to another room first" when a stay is checked in. The room is OUT_OF_ORDER in property-service at once and stays so until the work order completes or is cancelled (or "back in service" is used); bookings and check-in already refuse rooms that are not in service, and check-in now also refuses a booking whose pre-assigned room went out of order ("Room 104 is out of order · choose another room"). When the room goes back in service, housekeeping marks it dirty and puts a clean on today's list (it then goes through inspection as usual).
- **Notifications** (in-app, messaging-service `NOTICE` event): new work order → MAINTENANCE (as today); assigned → the assignee; on hold / done / cancelled → whoever raised it; reopened → the assignee (or MAINTENANCE when nobody); overdue → MANAGER and the assignee; URGENT raised → MANAGER too.
- **Photo** (optional): `POST /repairs/{id}/photo` multipart `file` (jpeg/png/webp, ≤ 2 MB) kept in the service's object storage; `GET /repairs/{id}/photo` returns the bytes to anyone who may read the work order.

### Assets and preventive maintenance
- Asset: name (unique per hotel), category, location (room or area), active. A schedule: title, category, priority, every N days (1–730), next due date, asset or room or area, default assignee, paused.
- A job at 06:00 `Africa/Lagos` (and on demand `POST /scheduled-jobs/run`, manager) opens one work order per due schedule (`source=SCHEDULED`) and moves the next due date on by the interval until it is after today. **Idempotent**: a work order is keyed by (schedule, due date) with a unique index, so a second run, a second instance or a retry never opens a duplicate. Paused or deleted schedules open nothing. Completing a scheduled work order records the run on the schedule (`lastDone`).

### Guest laundry (housekeeping-service, `/housekeeping/laundry`)
- Price list rows: name (unique per hotel), category (WASH_IRON, DRY_CLEAN, IRON_ONLY, OTHER), price > 0, express price (≥ price; defaults to 1.5 × price), active.
- An order is for an **in-house guest**: `roomId` must have a checked-in stay (400 "Nobody is checked in to room 104"). Lines `[{priceId, quantity}]` priced by the server from the active price list (express uses the express price); `total` = Σ quantity × price. Numbered `LDR-NNNNN`. Promised time optional (default: 24 h, express 6 h).
- Statuses RECEIVED → IN_PROGRESS → READY → DELIVERED; CANCELLED. Delivering needs the order settled (charged to the room or paid): 400 "Charge it to the room or take payment before it goes back to the guest".
- **Charge to room**: the stay must still be checked in and have an open bill (folio internal check, as restaurant room charges) — 400 "Room 104 has no open bill" / "Nobody is checked in to room 104" (the guest checked out); once only (400 "LDR-… is already charged to the room"). Posts `CHARGE_POSTED` (module LAUNDRY, category LAUNDRY, reference `LAUNDRY-{id}`, amount = total, tax 0) — the folio line equals the order total.
- **Paid at the desk**: the desk takes the payment through payment-service (role LAUNDRY or FRONT_OFFICE) and then `POST /orders/{id}/paid {paymentId}`; the payment must be this hotel's, APPROVED and at least the total, and not used for another order.
- **Cancel** with a reason, only before it is charged or paid (400 "LDR-… is already on the guest's bill · it can't be cancelled here").
- Laundry income = Σ totals of orders charged or paid (by the day they were settled).

### Hotel linen and chemicals
- Linen types are inventory items in category **Linen**; clean linen lives in the HOUSEKEEPING store, linen at the laundry in the LAUNDRY store.
- Housekeeping sends a soiled batch `LNB-NNNNN` (lines item + quantity, note). Laundry **receives** it with the counts that actually arrived: those move Housekeeping store → Laundry store (TRANSFER, reference `LNB-…-IN`); `missing` = sent − received is flagged as a shortfall. Laundry **returns** clean linen (per line returned and condemned with a reason): returned moves Laundry store → Housekeeping store, condemned is written off the Laundry store (WASTE) at the item's cost. Status SENT → RECEIVED → PART_RETURNED → RETURNED (everything received returned or condemned). **Close short** (LAUNDRY Full or manager) with a reason writes the rest off as lost (WASTE) and closes the batch.
- Chemicals and detergent: laundry uses stock from its own store with the existing `POST /inventory/levels/use` (USED, team LAUNDRY).

### Stock and money
- Inventory-service gets an internal move endpoint used by maintenance and laundry: `POST /internal/stock/moves {reference, kind: USE|WASTE|TRANSFER, fromKind, toKind?, team, note, lines}` — the stores are the hotel's first active store of that kind (400 "There's no Maintenance store · add one in Inventory first"); never below zero; **idempotent by reference** (a retry returns what was moved the first time); answers each line's unit cost and value.
- Maintenance cost = parts (movement values) + vendor cost of work orders completed in the period. Laundry money = income above; laundry stock used = USED + WASTE values of the Laundry store (inventory `/usage`). Both appear on Finance → Today's money, Manager → Financials and Owner → Financials as a "Laundry & maintenance" card (last 30 days).
- Money in naira, 2 decimals; quantities 2 decimals.

## Routes

### maintenance-service (`/maintenance`)
| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/repairs?scope=mine\|open\|done\|all&roomId=` | raisers, maintenance, manager | views: `number, roomNumber, area, category, priority, status, title, description, reportedByName, reportedByRole, assignedTo, assignedToName, dueAt, overdue, startedAt, holdReason, doneAt, completionNote, labourMinutes, parts:[{itemId,name,unit,quantity,unitCost,value}], partsCost, vendorName, vendorCost, totalCost, roomOutOfOrder, oooUntil, reopenCount, reopenReason, cancelReason, hasPhoto, assetName, scheduleId, createdAt`; default scope: open for everyone, `mine` = assigned to me |
| GET | `/repairs/{id}` | same | + `updates:[{at, byName, note}]` |
| POST | `/repairs` | raisers | `{roomId?, area?, category, priority, title, description?, assignedTo?, assignedToName?}`; 400 "Pick a room or say where it is", "Pick a category: …" |
| POST | `/repairs/{id}/assign` | MAINTENANCE Full, manager; a technician for themselves | `{assignedTo, assignedToName}` |
| POST | `/repairs/{id}/start` · `/hold` · `/resume` · `/complete` | assignee, MAINTENANCE Full, manager | hold `{reason}`; complete `{note, labourMinutes?, parts?:[{itemId, quantity}], vendorName?, vendorCost?}` |
| POST | `/repairs/{id}/reopen` · `/cancel` | see Rules | `{reason}` required |
| POST | `/repairs/{id}/updates` | raisers, maintenance | `{note}` |
| POST | `/repairs/{id}/out-of-order` · `/back-in-service` | MAINTENANCE Edit, manager | `{reason, until?}` |
| POST/GET | `/repairs/{id}/photo` | raisers / readers | multipart `file` |
| GET | `/repairs/summary?from=&to=` | manager, owner, FINANCE, MAINTENANCE Full | `{from, to, done, open, overdue, partsCost, vendorCost, labourMinutes, totalCost, byCategory:[{category, count, cost}]}`; Lagos days, default last 30 |
| GET/POST/PUT | `/assets`, `/assets/{id}` | read maintenance + manager; write MAINTENANCE Full, manager | `{name, category, roomId?, area?, active}` |
| GET/POST/PUT | `/scheduled-jobs`, `/scheduled-jobs/{id}` | same | `{title, category, priority, everyDays, nextDue, assetId?, roomId?, area?, assignedTo?, assignedToName?, paused}` |
| PUT/DELETE | `/scheduled-jobs/{id}/pause?paused=` · `/scheduled-jobs/{id}` | same | |
| POST | `/scheduled-jobs/run` | manager | runs the due-schedule job now for this hotel; answers how many were opened |

### housekeeping-service (`/housekeeping/laundry`)
| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/prices` | hotel staff | active and inactive rows |
| POST / PUT | `/prices`, `/prices/{id}` | manager, owner | `{name, category, price, expressPrice?, active}` |
| GET | `/orders?status=open\|all` | LAUNDRY, FRONT_OFFICE, manager | view rows with `number, lines:[{priceId,name,quantity,unitPrice,amount}], express, total, status, settlement (ROOM/PAID/null), chargedAt, paidAt, paymentId, promisedAt, cancelReason, receivedByName` |
| POST | `/orders` | LAUNDRY/FRONT_OFFICE Edit, manager | `{roomId, lines, express, promisedAt?, note?, chargeToRoom?}` |
| POST | `/orders/{id}/status` | same | `{status}` forward only |
| POST | `/orders/{id}/charge-to-room` · `/paid` · `/cancel` · `/pocket-note` | same | paid `{paymentId}`; cancel `{reason}` |
| GET | `/linen-batches?status=open\|all` | HOUSEKEEPING, LAUNDRY, manager | lines `[{itemId, name, unit, sent, received, returned, condemned, lost}]`, `missing`, `condemnedValue` |
| POST | `/linen-batches` | HOUSEKEEPING Edit, manager | `{lines:[{itemId, quantity}], note}`; items must be in category Linen |
| POST | `/linen-batches/{id}/receive` · `/return` · `/close` | LAUNDRY Edit (close: Full), manager | receive `{lines:[{itemId, quantity}]}`; return `{lines:[{itemId, returned, condemned}], reason?}`; close `{reason}` |
| GET | `/summary?from=&to=` | manager, owner, FINANCE, LAUNDRY Full | `{orders, income, charged, paid, open, cancelled, condemnedValue, lostValue}` |

### inventory-service
| Method | Path | Who | Notes |
|---|---|---|---|
| POST | `/internal/stock/moves` | services (internal key) | see Stock and money |

### reservation-service
- Check-in refuses a booking whose assigned room is out of order (unless another room is chosen).

### common / messaging-service
- `NoticeEvent` (topic `hospio-notice`): `kind, title, body, link, userIds, roles` → one in-app notification per user and per role.

## Seed (`hospio.seed.enabled`, Royal Suites · Simpas Ikeja, missing rows only)
- Inventory: "Maintenance store" and "Laundry store" (created only when the hotel has no store of that kind), a Housekeeping store when missing; maintenance parts (LED bulb 9W, AC filter, Refrigerant gas R410A, Fuse 13A, Tap washer set, Door hinge) in the Maintenance store; Laundry detergent, Bleach, Fabric softener in the Laundry store; linen (Bed sheet, Pillowcase, Bath towel, Duvet cover) in the Housekeeping store.
- Laundry price list: Shirt, Trousers, Dress, Suit (2-piece), Native attire, Bedsheet (guest), Underwear (pair).
- Maintenance assets: "Room 101 AC unit", "Generator 1 (60 kVA)", "Pool pump"; schedules "AC service" every 90 days and "Generator service" every 30 days.

## Web (hotel app)
- Data seam `data.maintenance.*` and `data.laundry.*` live (`routes-ops-lm.ts`), actions `actions/maintenance.ts`, `actions/laundry.ts` returning `ActionResult`; no button in these screens fakes success when the domain is live.
- MAINTENANCE: Work orders (KPIs, tabs mine/open/overdue/on hold/done, detail with every action, out of order, complete with parts from the Maintenance store), Preventive (assets + schedules), Rooms (rooms out of order with their work order), Maintenance store (ask the store).
- LAUNDRY: Jobs (guest orders board: new order for an in-house guest from the price list, express, move along, charge to room, take payment, cancel), Linen (batches to receive and return, condemned, close short, chemicals used), Laundry store (ask the store).
- HOUSEKEEPING: Linen (clean linen on hand, send soiled, batches with what came back and what is missing), Housekeeping store (ask the store), Repairs (raise and follow work orders).
- FRONT_OFFICE: Repairs (raise and follow), Laundry (take a guest order, charge to the room, take payment).
- MANAGER: Tasks becomes work orders + preventive + laundry price list; Financials shows the Laundry & maintenance card. OWNER and FINANCE: the same card on their money screens.

## Known limits
- Out of order starts now and lasts until the work order is completed or cancelled; a future-dated out-of-order period is not supported, and bookings already assigned to the room are not moved (check-in refuses the room and the desk picks another).
- A completed work order's parts are not returned to the store when it is reopened; parts are drawn again only for what the second completion names.
- A laundry charge on a guest's bill is not reversed by cancelling the order (cancel is refused once charged); corrections are made on the folio.
- Linen in rooms and on floor trolleys is not tracked separately from the Housekeeping store.
- Photos are kept on the service's own disk on staging (no CDN); one photo per work order.
