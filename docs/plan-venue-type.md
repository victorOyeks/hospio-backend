# Lounge, bar and restaurant businesses (venue type)

Tracker for letting a lounge, bar, restaurant or club run on Hospio360 without pretending to be a hotel, and for businesses that own
both (a hotel and a lounge). Agreed with the owner on 18 Nov 2026, after the QA Lounge test (business "QA Lounge qhye",
venue "QA Lounge Wuse Bar"): a lounge could already trade with 0 rooms, but every screen still spoke hotel, the owner's sign-up could
not get past "Rooms & prices", and room features (bookings, folios, charge-to-room, night audit arrivals) showed where they mean nothing.

## Decisions

| # | Decision |
|---|---|
| 1 | The type lives on the **location** (tenant-service `hotels` row = a location/branch): `venueType` = HOTEL, LOUNGE, BAR, RESTAURANT or CLUB. One business can own a hotel and a lounge. |
| 2 | The **business** has a kind (`venueKind`: the same values plus MIXED), asked in both wizards ("What kind of business?"). It only sets the type a new location starts with (MIXED starts each as a hotel; the type is picked per location). |
| 3 | "Lounge / bar / restaurant" setup (built in, no stay rules or housekeeping, tab rules, receipts only, opening hours) for every location without rooms, in both wizards; Hospio360 adds a venue's outlets during onboarding; Waiter and Cashier roles (hotels too) with tills, permissions, PIN tiles, reports | Done (18 Nov 2026) |
| 4 | Backwards compatible: both columns are `NOT NULL DEFAULT 'HOTEL'`, every existing location and business is a hotel, older events and responses without the field read as HOTEL. Real customers stay HOTEL; only QA lounge test businesses were switched. |
| 5 | The apps gate on the **active location** (branch switcher). An owner of a hotel and a lounge sees room features while the hotel is active, not while the lounge is. |
| 6 | Room pages opened directly at a location without accommodation answer **"Not used at a lounge"** (with the way back), never a 404. |
| 7 | Charge-to-room is **off by capability**: hotel-config reads OUTLETS.roomCharge as false for a location without accommodation (whatever is stored or a template set), fnb then refuses room charges; a new non-accommodation location also stores it off. |
| 8 | Night audit at a location without accommodation checks only **tills** and **open tabs / unsettled orders**; no arrivals, departures, departing balances or occupancy (also left out of the owner's day-closed email). |
| 9 | Dashboards show only real data. No made-up comparisons (the "vs 72% last month" hint was removed everywhere). Full venue dashboards are phase 2. |
| 10 | Step numbers of both wizards do not change (drafts are stored by number): the back-office Rooms step reads "Not needed"; the self-serve step 3 becomes **Outlets** for a location without accommodation. |

## Data model and APIs (phase 1)

- tenant-service migration `005-venue-type.sql`: `hotels.venue_type`, `businesses.venue_kind` (both VARCHAR(20) NOT NULL DEFAULT 'HOTEL').
- `HotelResponse` adds `venueType`, `hasAccommodation` (public and `/internal/hotels/**`, so every service can ask). `POST/PUT /tenant/hotels` take `venueType` (left out on create: the business kind's default).
- `BusinessResponse` adds `venueKind`, `hasAccommodation` (any location); `BusinessSummary` (platform list) adds `venueKind`, `venueTypes`. `POST/PUT /tenant/businesses`, `PUT /tenant/businesses/mine` and the internal sign-up create take `venueKind` (left out: kept).
- `HotelCreated` event carries `venueType`; hotel-config stores `roomCharge=false` for a non-accommodation location.
- hotel-config: `GET` sections/all read OUTLETS.roomCharge false without accommodation (`TenantClient.hasAccommodation`, cached 60 s, true when tenant can't be reached).
- finance-service: `NightAuditView.accommodation`; the checklist drops `arrivals`, `departures`, `balances` without accommodation.
- fnb-service: refusal reads "Charging to a room is switched off here (it needs rooms: a lounge, bar or restaurant has none)"; the sale's "switched off" note was dropped (the button is simply not offered).
- Handover pack email: "LOCATIONS" with "QA Lounge Wuse Bar, Abuja · Lounge" instead of "0 rooms"; the owner's welcome email asks a lounge owner to check outlets and menus, not rooms and bookings.
- Onboarding draft step data: `outlets: [{outletId, name, kind}]` (self-serve step 3 at a location without accommodation).

## Phases

| Phase | What | Status |
|---|---|---|
| 1 | Foundation: venue type per location + business kind; capability helper; admin wizard (kind, Locations step with type, times only for hotels, Rooms "Not needed", go-live without rooms, handover text); owner self-serve (kind, location step without times, Outlets step); hide hotel-only modules for the active location (nav, routes, quick actions, front office role); charge-to-room off; dashboards without room cards; night audit tabs-only; QA lounges set to LOUNGE | Done (18 Nov 2026) |
| 2 | Venue dashboards and reports: sales by outlet, item and staff; open tabs; tills and cash-ups; best sellers; hour-of-day; owner comparison across locations by kind (only real comparisons) | Done (18 Nov 2026) |
| 3 | "Lounge / bar / restaurant" setup template (no stay rules or housekeeping, tabs on with a limit, service charge, happy hours) applied by kind in both wizards; Waiter and Cashier roles with default access levels; role pickers offer them for venues | To do |
| 4 | Bill splitting on tabs and sales (by item, by share, equal), each part paid its own way; receipts per part | To do |
| 5 | Wording: landing page, sign-up and sign-in ("hotel, lounge or restaurant"), handover pack and owner acceptance, admin portal ("Hotels" directory → Locations with kind), owner "Hotels" page → Locations, server messages that still say "hotel", Hotel setup → Location setup | To do |
| 6 | Regression across hotel customers and docs: every role on a hotel, a lounge and a mixed business; contracts (onboarding.md, roles-permissions.md, restaurant-bar.md) updated | To do |

## Phase 3: venue setup, waiters and cashiers

**Setup template.** The "Lounge / bar / restaurant" setup is built in (`common SetupCatalogue.VENUE_DEFAULTS`, `VENUE_LISTS`), not a row
in tenant-service: one source for a new location and for re-applying.

- A new location without accommodation (HotelCreated with its `venueType`, so admin onboarding and the owner's sign-up alike) gets it
  from hotel-config `createDefaults`; a hotel keeps the catalogue ("Standard hotel") defaults.
- What it sets: tabs on, tab limit ₦100,000, a tab flagged when open over 4 hours (`OUTLETS.tabMaxHours`, read by `GET /fnb/sales/today`
  when `staleHours` is left out), a tab needs the guest's name or a table (`tabNameRequired`), waiters don't take payment
  (`waitersTakePayment`, `waiterCash` off); cash, card and transfer, till float ₦10,000, receipts printed; VAT as the catalogue
  (7.5 % included), service charge 0; guest messages receipts only; opening hours 10:00 to 02:00 (`BASICS.openingTime`,
  `closingTime`), receipt footer, day closes 04:00; reasons for sales (void, cancel, discount/complimentary), Day and Evening shifts,
  Service, Cashier, Kitchen, Bar, Inventory, Finance departments.
- What it leaves out (nothing stored): STAY_RULES, HOUSEKEEPING, check-in/out times, `OUTLETS.roomCharge`, the night audit's arrivals
  check, the housekeeping checklist and public areas. Reads lay the catalogue under what is stored, so a location that later gains
  rooms keeps its settings and reads the hotel defaults for the stay keys (charge to a room on).
- `POST /hotel-config/setup/apply-template {"builtIn": "VENUE"}` (manager, owner, Hospio360) re-applies it to a location already set up
  as a hotel: the venue values, the venue list entries, and the hotel catalogue's own entries a venue has no use for ("No-show",
  "Booked elsewhere", "Night" shift, Front office…) are hidden; entries the business added are kept. 400 at a location with rooms.
- Admin onboarding step 6: locations with rooms pick a template as before; each location without rooms shows what the venue setup
  sets and an **Outlets** card (name + Bar / lounge, Restaurant, Kitchen). Continue applies the setup and creates the outlets
  (`POST /fnb/outlets` as Hospio360: fnb binds the location's business), each linked to the store of its kind (read
  back from fnb each time, not kept in the draft). The owner's review then lists them.
- Discounts and complimentary sales: fnb has happy hours and nothing else (no manual discount on a sale), so the template only seeds
  the reasons and keeps the approval limit (`APPROVAL_LIMITS.discountPct` 10 %). A manual discount with "who can give, up to x %" is
  not built.

**Roles.** `WAITER` and `CASHIER` (common `Role`), given like any department role (new staff start at Can edit), at hotels too.
They work every outlet of the location (the waiter picks one in "Serving at"); there is no per-person outlet list.

| | Waiter | Cashier |
|---|---|---|
| Orders | opens a tab or order, adds items, sends, marks served, moves the table, takes off an item not yet sent, on the orders **they opened** (Full access, a head waiter: anyone's) | sees every order; rings nothing up ("A cashier takes payments…") |
| Kitchen/bar steps | never marks ready | none |
| Voids | never cancels what was sent (the outlet's Full access or a manager) | none |
| Payment | only when `OUTLETS.waitersTakePayment` is on, for their own sales; cash only when `waiterCash` is on, into the cashier's open till | any open tab or sale (role `CASHIER`); cash into the CASHIER till; card/transfer on the outlet's POS (the outlet desk's when the cashier has none) |
| Charge to a room (hotels) | their own orders | any |
| Till | none | CASHIER till: open, pay in/out, close, hand over |
| Sales history | their own sales only (never widened) | every outlet; settles anyone's |
| Menu, prices, happy hours | read only | read only |

- fnb `OutletAccess` (`takes`, `ownOnly`, `waiter`, `cashier`), `OrderService` (own-order checks, tab name rule), `SalesHistoryService`
  (scope and `can`), guards on the order and sales routes; `GET /internal/orders/{id}/due` adds `openedBy`.
- payment `PaymentService`: `CASHIER` and `WAITER` are paying roles for outlet sales only; `requireWaiterMayPay`; the cashier till
  (`TILL_DESKS`, a waiter's cash checks the CASHIER till); receipts routes. finance `MoneyService`: CASHIER till, a waiter's cash
  lands in it (`tillRole`). Income stays the outlet's (`desk` BAR/KITCHEN), so Sales by staff shows the waiter who opened the sale
  and the till shows the cashier's cash.
- identity: default level, shared-device roles (PIN tiles), labels; platform: staff terms; tenant: handover pack labels.
- Web: `/waiter` (My tabs, My sales) and `/cashier` (To pay, Sales, My payments, Till); Roles & Permissions matrix, Add staff,
  onboarding People, role picker, shared-device roles, help, rota departments (Service, Cashier).
- QA script: `hotel-internal-system-admin-FE/docs/qa/65-venue-setup-waiters-cashiers.md`.

## Phase 2: venue dashboards and reports

APIs (managers, owners and a support look, finance; every outlet of the active location; one grouped SQL statement per figure,
`fnb-service VenueReportQuery`, indexes `fnb-008`):

- `GET /fnb/sales/today?staleHours=4` (`VenueToday`): today's sales (opened today, not cancelled; tabs included), `lastWeek` = the
  same weekday a week ago **by the same time** and for the whole day, **null when that day had no sales** (no made-up comparison);
  money taken today by method (`fnb.order_payments`, by when it was taken); sales by outlet; top 5 items (the same name counted once
  across outlets); open tabs (count, still to pay, oldest, those over `staleHours`); sales still to pay (any day); today's voids with
  who and why; price changes waiting.
- `GET /fnb/sales/report?from&to[&outletId]` (`VenueReport`, up to 93 days): totals (sold, paid, to pay, on rooms, cancelled,
  discounts, service charge, tax, items, voids, complimentary), by outlet, item (category from the menu, revenue = price × qty less
  discount), category, staff (sales they opened + voids they made), Lagos hour, method, voids (a cancelled sale = what was on it;
  a voided item = its price × qty), discounts and complimentary sales (latest 500 each), and the stock the recipes of the items
  sold call for (`StockSource`: a dish made at another outlet uses its twin's recipe).
- `GET /payment/payments/by-terminal?from&to`: approved outlet payments (with an order) per method and POS terminal.
- `GET /inventory/usage/items?from&to`: per stock item drawn by sales (USED, reference ORDER-…), used by hand, wasted, count difference.

Screens: owner dashboard "… today" per venue location and **All venues** (sums; the comparison only when every venue has one),
the top "Sales today" card at a venue reads the same figure; manager dashboard at a venue: the same section, "Needs your action" adds
tabs over 4 h, unpaid sales, price changes, counts to approve, low stock; Reports (owner and manager) area "Outlets": Sales by outlet,
item, category, staff, hour, Payments by method & POS, Voids & discounts, Stock used vs sold, CSV and Save as PDF (print), not
scheduled or emailed (platform-service's catalogue doesn't have them); hotels get them too for their restaurant and bar. **Tips are not
recorded** anywhere in Hospio360, so there is no tips report (a later phase if wanted). Hotel dashboards are unchanged.
QA script: `hotel-internal-system-admin-FE/docs/qa/62-venue-dashboards-reports.md`.

## Phase 1: what a lounge, bar or restaurant sees

- **Menus**: no Rooms, Bookings, Folios, Organizations (manager, finance), no Bookings (owner), no Rooms out of order (maintenance), no Inspect (housekeeping); the front office's pages answer "Not used at a lounge".
- **Owner dashboard**: locations count without rooms, "Sales today" instead of occupancy, branch cards with the kind. Hotels page hides total rooms and occupancy when no branch has rooms. Reports: Occupancy and Guest list hidden.
- **Manager dashboard**: no New booking, Check in/out, Room status or Today's guests; KPIs Sales today, Money taken today, Open tills, Cancelled today; outlet sales card.
- **Bar / kitchen**: no Charge to a room, no Charged to rooms tile or close-shift row, no Room service order type.
- **Night audit**: only "Every till counted and closed" and "No open tabs or unsettled orders at the outlets".
- **Hotel setup**: no Rooms & stay rules, Housekeeping, check-in/out times or "Guests can charge to their room".
- **Staff**: Front office (and housekeeping in the back-office People step) not offered at a location without rooms.

## QA approach

- Unit tests: tenant `VenueTypeTest` (capability, default type from the kind, handover pack and welcome text), finance `NightAuditServiceTest` (lounge checklist tills + orders only; hotel unchanged), hotel-config `RoomChargeVenueTest`, FE `packages/api/src/venue.test.ts` (capability, stay-only paths).
- Staging end to end (API + browser): (a) admin onboards "QA Venue <id>" as a Lounge with one location: no rooms, go-live green, handover, owner accepts; owner/manager/bar/kitchen see no hotel modules; (b) owner self-serve sign-up as a Restaurant through Outlets; (c) mixed business: hotel + lounge, room features only on the hotel; (d) regression on Simpas Ikeja (hotel): front office, bookings, check-in, folios, charge to room from the bar, night audit.
- QA script: `hotel-internal-system-admin-FE/docs/qa/61-venue-type.md`.

## Log

- 18 Nov 2026: plan agreed; phase 1 built and shipped (backend `f4aec5f`, web `b1c76fd` + `Owner wizard: Continue runs once`). QA Lounge qhye
  and its location set to LOUNGE (the only QA lounge; every other location stays HOTEL: 105). Staging e2e: admin onboarded "QA Venue 5jf4"
  as a Lounge (no rooms step, go-live 7/7 green, handover pack "Lounge", owner ticked the boxes and accepted); owner/manager/bar/kitchen
  of it and of QA Lounge qhye see no room modules, Bookings/Rooms by address say "Not used at a lounge", night audit tills + tabs only,
  charge to room refused (400); self-serve "QA Venue Resto 66bs" as a Restaurant through Outlets (no SMS: made-up number held back);
  mixed "QA Venue Mixed cvt5" (hotel + lounge) follows the branch; Simpas Ikeja unchanged (front office, bookings, folios, bar charge to
  room, night audit stay checks). Found and fixed on the way: the owner wizard ran each step's save twice (duplicate outlets, a false
  "already has a hotel called" after a good save).
- 18 Nov 2026 (evening): phase 2 built and shipped (backend `ebe234e` + `StockSource / top 5 by name` fix, web `4eaf456` + owner
  top card fix and QA 62). Staging e2e at QA Lounge qhye (cash and the Bar test terminal only): bar, kitchen and manager clocked in,
  a till opened, 8 sales over Main Bar, VIP Lounge and Kitchen by three people (cash, card on the test terminal, transfer), one tab
  left open, one item voided, one sale cancelled, the till closed (balanced) and reopened. Every card and report checked against SQL:
  sold ₦68,800 / 16 sales / 2 cancelled; taken cash ₦37,400 (10), card ₦13,400 (3, Bar test terminal), transfer ₦7,500; outlets Main
  Bar ₦52,400 (13), VIP ₦8,900 (2), Kitchen ₦7,500 (1); top Star Lager 16, Coke 11; open tabs 1 / ₦3,000; unpaid 2 / ₦7,500; voids 3
  / ₦4,800 with who and why; tills 1 open (expected ₦5,000) and 4 closed; 3 clocked in; staff Bisi 14 / ₦55,300 (3 voids), Tunde 1 /
  ₦6,000, Chidi 1 / ₦7,500; hours 10:00 6 / ₦31,900, 12:00 3 / ₦6,900, 18:00 7 / ₦30,000; chicken 4 called for, 3 drawn (one sale
  unsettled). No comparison at the lounge (nothing sold last Wednesday); the comparison checked on Simpas Ikeja via the API
  (last Wednesday 1 sale ₦2,400 = SQL). Mixed cvt5: hotel KPIs with the hotel chosen plus the lounge's section ("No sales yet
  today"); lounge chosen → its section. Simpas front office and bar unchanged (charge to a room still offered). 820/1024 no
  sideways scroll. Bar/kitchen get 403 on the venue APIs; 94 days → 400.
- 18 Nov 2026 (night): phase 3 built and shipped (backend `bdd049b` + `4179855` old-tab flag from Hotel setup + `d4c543f` wording, web
  `cd32242e`, `b0222043`, `8853cc08`, `d09a1237`, QA 65). Staging e2e, QA venues only, cash and the Bar test terminal:
  **Setup**: admin-onboarded "QA Venue wy4f" (Lounge) and self-serve "QA Venue Resto yrio" (Restaurant) got the venue setup by
  themselves (44 keys written by "venue defaults", none for stay rules, housekeeping, check-in times, room charge or the arrivals
  check; 7 sale lists; outlets on Bar store / Kitchen store). Re-applied (`builtIn: VENUE`) to QA Lounge qhye, QA Venue 5jf4 (it had
  the hotel template: "No-show", "Booked elsewhere", Morning/Afternoon/Night shifts, Front office… now hidden), Resto feke, Resto
  66bs and the Mixed cvt5 lounge; refused (400) at the Mixed cvt5 hotel and Simpas Ikeja, whose settings did not change.
  **Outlets by Hospio360**: Main Bar and Kitchen through the API (500 before the fix: a platform caller had no business), Terrace
  through step 6; the step lists all three with "setup applied".
  **Flow at QA Lounge Main Bar** (Wale and Kemi waiters, Chika cashier, all created at Can edit): Wale opened a tab at Table 4
  (2 Star, peppered chicken, Coke; a tab with no name or table refused), the kitchen saw a ticket with only the chicken, marked it
  ready, Wale served, Chika took ₦8,300 cash after opening the cashier till (float ₦10,000 from Hotel setup; refused while the till
  was closed), tab closed; a second tab by card on the Bar test terminal (the cashier has no POS: the outlet desk's was used).
  Refusals seen: waiter paying with the switch off (403), voiding a sent item or tab (403), marking the kitchen's dish ready (403),
  serving it before it was ready (400), changing a price or the sold-out switch (403), opening the cashier till (403); Kemi opening,
  listing or adding to Wale's tab (403, not on her board or in her sales); cashier ringing up, adding, voiding, editing the menu or a
  price (403); either on a folio payment (400) or the venue reports (403). With "Waiters take payment" on: Wale paid his tab by
  card, cash refused (400), Kemi's tab refused to Wale (403); with "Waiters can take cash" on too: Kemi's ₦1,500 and a ₦800 tab from
  the screen landed in the cashier's till. Till closed balanced at ₦26,600 (10,000 + 8,300 + 6,000 + 1,500 + 800). Both switches off
  again. **By SQL**: sales under Wale 5 / ₦18,200 and Kemi 1 / ₦1,500 (Sales by staff shows the same; nothing under the cashier);
  payments role CASHIER 3 (cash ₦14,300, card ₦1,600) and WAITER 3 (card ₦1,500, cash ₦2,300), income desk BAR, every cash entry in the
  CASHIER till opened by Chika. **PIN**: a shared device for Waiter, Cashier, Bar showed the three tiles; Wale and Chika unlocked
  onto My tabs and To pay; device revoked. **Hotel** (Simpas Ikeja, new `qa.pin.waiter` / `qa.pin.cashier` only): waiter tab at the
  Bar, bar staff still see it, waiter can't pay (403), cashier may pay or charge to a room, cash into a cashier till closed
  balanced; front office and Hotel setup unchanged. 820 and 1024 wide: no sideways scroll on My tabs, My sales, To pay, Till, Sales
  and the manager dashboard. Found and fixed on the way: saving step 6 with outlets in the draft ("Request body could not be read"),
  the waiter's default outlet was the kitchen, a stale error line after a tab opened.
  Not built (decisions for the owner): a per-person outlet list (waiters and cashiers work every outlet of their location); manual
  discounts or complimentary items on an outlet sale with a limit (only happy hours exist), so the setup seeds reasons only;
  manager approval of a void from the waiter's or cashier's screen (they are refused and a manager does it).
