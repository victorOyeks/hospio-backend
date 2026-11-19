# Lounge, bar and restaurant businesses (venue type)

**Status: all six phases done (19 Nov 2026).** What is left is under "Known limits".

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
| 3 | "Lounge / bar / restaurant" setup template (no stay rules or housekeeping, tabs on with a limit, service charge, happy hours) applied by kind in both wizards; Waiter and Cashier roles with default access levels; role pickers offer them for venues | Done (18 Nov 2026) |
| 4 | The bill: splitting (equally, by item, by amount) with each part paid its own way and a receipt per part; manual discounts and complimentary items with a limit and a reason; void and discount requests from the floor, approved from the dashboard or with the manager's PIN on the device; optional tips; outlets per person | Done (18 Nov 2026) |
| 5 | Wording: a lounge, bar or restaurant reads its own word everywhere (landing page, apps, admin portal, help, emails, server messages); a hotel reads what it read before | Done (19 Nov 2026) |
| 6 | Regression across hotel customers and docs: every role on a hotel, a lounge and a mixed business; contracts (onboarding.md, admin-portal.md, roles-permissions.md, restaurant-bar.md) updated | Done (19 Nov 2026) |

## Phase 5: wording

**Rule.** The word comes from the **location's type** ("hotel", "lounge", "bar", "restaurant", "club") for anything about one
location, and from the **business** for anything about all of it: "hotel(s)" while every location is a hotel, otherwise
"location(s)". The settings area is "Hotel setup" at a hotel and "Lounge setup" (Bar setup, Restaurant setup) at a venue; its
address stays `/dashboard/hotel-setup`. Copy about rooms, bookings, stays and folios is gated on the capability
(`hasAccommodation`), not reworded. A hotel reads exactly what it read before, with the few deliberate exceptions listed below.
No API field, route, enum or legal version changed. The landing page was done first (web QA script 68).

**How it is built.**

- Backend: `common/venue/VenueWording` + `VenuePlaces` (below).
- Web: `packages/api/src/venue.ts` `wording(location)`, `businessWording(locations, kind)` and `adaptText(text, words)`, the mirror
  of `VenueWording`. `adaptText` rewrites the hotel words of **our own copy** only ("hotel(s)", "Hotel setup"; never inside an
  address, an email, a `{{hotel}}` placeholder or "hotel guest") and returns hotel wording untouched, so the source strings stay
  the hotel's. The dashboard shell puts the words in a context (`packages/ui/src/shared/wording.tsx`): `usePlaceText()` for copy
  about the active location, `useBizText()` for the owner's business-level copy, `useWording().stays` to leave out copy about
  rooms. Server pages use `getWords()` (`packages/api/src/words.ts`). A name someone typed never goes through `adaptText`.
- Help: `helpFor(app, roles, role, words)` words every guide for the place, leaves out the guides about rooms and stays (a guide
  whose page is a rooms-and-stays page, or marked `stay`), and uses a guide's `venue` version where its steps differ (add a
  location, pause or close, guest messaging, night audit, new order, brand colours…).
- Roles: `rolePermissions(role, stays)` and `managerOnlyActions(stays)` leave out the lines about rooms, stays and folios;
  `roleLabel` / `roleDescription` ("Owner", "Run this lounge: sales, tabs, staff, money").

### Web: what changed and what was left

Method: every user-facing string with "hotel" in `apps/hotel`, `apps/admin`, `packages/ui` and `packages/api` (1,045 lines in 199
files, comments and identifiers excluded) classified; then every menu page of each role at QA Lounge qhye swept in the browser for
"hotel", "room", "check-in", "folio" and "booking" until none was left (exceptions in the phase 6 log).

| Area | A lounge, bar or restaurant now reads | A hotel reads |
|---|---|---|
| Menu | "Lounge setup"; owner: "Locations" | "Hotel setup"; "Hotels" (a mixed business: "Locations") |
| Owner · Locations page | "Locations", "Add a location" (asks the **Kind of location**, no check-in times, no rooms step, "Outlets are added in Lounge setup → Restaurants & tables"), "Edit / Close / Reopen location", "Pause lounge", the kind in each row, no Rooms or Occupancy column, tile or tab at a location without rooms | the same page as before, except "Revenue · month to date" → **"Money received · month to date"** (it counts money received) |
| Owner pages (Staff, Users, Financials, Payments, Reports, Operations, Messaging, Settings, Hospio fees, dashboard) | "All locations", "your locations", "Location" column and CSV header, "Change role & locations", "what each location sold", role "Owner" | unchanged ("All hotels", "Hotel owner"…) |
| Setup | "Lounge setup", "Lounge basics", "Lounge status", "Pause / Close / Reopen this lounge" (no "Pause new bookings" or "Guests in house finish their stay"), colours note without the booking page | unchanged |
| Staff, roles, tills, finance | "Remove from lounge", "the lounge's usual float", "set in Lounge setup → Money", "The lounge's list…", Roles & permissions without Front office, Housekeeping and the room / folio / booking lines | unchanged |
| Bar, kitchen, waiter, cashier | "…adds one in Lounge setup → Restaurants & tables", no "On rooms" tile on Sales | unchanged (Charge to room, "Hotel guest", "On rooms") |
| Laundry | no "Charged to rooms today" tile | unchanged |
| Night audit | links to Financials and Payments instead of Bookings, Folios, Organizations | unchanged |
| Billing | "…card/transfer POS payments and new sales pause…" (no new bookings, no check-out) | unchanged |
| Reports | "Location" columns; Revenue by source: "Restaurant, bar and other revenue" | unchanged |
| Help | guides in the place's words; rooms, bookings, folio, charge-to-room and minibar guides left out; venue versions of 20 guides; What's new without the room items | unchanged |
| Sign-in panel (before anyone is known) | "Run your business from one place." "For hotels, lounges, bars and restaurants: sales, guests, money and your team…" | the same new text |
| Sign-up, owner wizard | "Create your account", "For owners of hotels, lounges, bars and restaurants", "Lounge setup", "Locations → Add a location" | "Create your account" (was "Create your hotel account") |
| Admin portal | Customers → **Locations** (was Hotels) with a **Kind** column and filter and "—" for rooms where there are none; "Set up a business for them" asks **What kind of business?**; "location" / "business" through Fees & billing, Payments & POS, Support, Devices, Guest messages, Changes, onboarding People / Connections / Setup, Marketers ("Refer a business") and help | (Hospio360 staff only) |

Deliberately left:

- Addresses (`/dashboard/hotels`, `/dashboard/hotel-setup`, `/hotel-owner/...`), API fields (`hotelId`, `hotelName`), code names.
- Everything a location without rooms never sees: Front office, Rooms, Bookings, Folios, Organizations, Housekeeping, room panels,
  the back-office Rooms step, the guest's booking page (`/stay/...`).
- Charge-to-room labels ("Hotel guest", "Charge to room", "On rooms" when a past sale has some).
- "Not used at a lounge" (the page that explains a rooms page has no use there) says "rooms and stays" on purpose.
- Fallbacks shown only when a name is missing ("this hotel" in a few server pages; never seen in practice).
- Legal pages (they already say "venue"); the PWA names ("Hospio360") and icons.
- Notifications and activity already stored (history keeps its words).

**What a hotel reads differently in the web apps** (all deliberate): "Money received · month to date"; the sign-in panel and
"Create your account"; "Enter a valid email, like name@example.com"; "More locations can be added to your account later" on the
invite page; "Your location: …" in the copied invite text; a few last-resort errors ("That location is not one of yours",
"Choose a location first", "The location could not be saved"); the lock screen's fallback name.

### Backend

**Helper.** `common/venue/VenueWording` (pure words: `place()` hotel / lounge / bar / restaurant / club / location, `places()`,
`capital()`, `a()`, `possessive()`, `setup()` = "Hotel setup" / "Lounge setup" / "Setup"; `of(type)` for one location,
`ofBusiness(kind, types)` = "hotel" while every location is a hotel, else "location") and `common/venue/VenuePlaces` (the words for
a hotel id or business id, asked from tenant-service `/internal/hotels/{id}` and `/internal/businesses/{id}`, cached 5 minutes;
static `here()`, `at(hotelId)`, `business()`, `ofBusiness(id)`). Anything unknown, unreachable or unconfigured (unit tests) reads
as a hotel, so **a hotel's messages are byte-for-byte what they were**. tenant-service uses its own rows, not the lookup.
Method: every string literal with "hotel(s)" in `*/src/main` (196 hits in 80 files) classified; no API field, route, enum, event
or column renamed.

| Service | Changed (hotel reads the same; a lounge reads its own word) | Left as is, and why |
|---|---|---|
| tenant | "Enter the lounge name", "the lounge phone / email", "Only the owner can add a lounge", "That lounge is not yours", "Only the owner, this lounge's manager or Hospio360 can edit it", "…change a lounge's status", "Lounge added"; a name already used: hotel "already has a hotel called X", venue "already has a **location** called X" (the other one may be of any kind) | "Hotel not found" (an id that matches nothing has no type); welcome and handover pack text (done in phase 1) |
| payment | "This lounge doesn't take cash / cards / transfers", "That POS isn't one of this lounge's", "…assign one in Lounge setup", "…Record tips in Lounge setup → Restaurant & bar", "…allow it in Lounge setup → Restaurant & bar", "This lounge has no keys for the terminal's provider", "Could not check the lounge right now", fee debit "The lounge has no opay keys", "Choose a location first (X-Hotel-Id header)" (hotel-only business: "a hotel") | "Hotel not found" (TerminalService: unknown id) |
| fnb | "Tabs are switched off for this lounge", "The outlet that makes it isn't one of this lounge's" | (charge-to-room refusal reworded in phase 1) |
| hotel-config | "Only a manager or the owner can change Lounge setup", "Lounge setup has no section X" | "Could not find which business hotel {id} belongs to" (internal failure with an id) |
| finance | "That category isn't in this lounge's list" (bills, pay-outs), "…can see the lounge's bills", "…ceiling this lounge has set for a single expense", "The locations of this business can't be read right now", day-closed email fallback name "your lounge" (was "your venue") | Night audit checklist (venue version since phase 1); "Rooms & front desk" report group (hotel desks only) |
| inventory | "That supplier / item isn't in this lounge's list / stock" | — |
| housekeeping | laundry "That item isn't in this lounge's stock", "That payment isn't in this lounge", lost & found "…this lounge's lost & found" | room and linen messages (accommodation only) |
| maintenance | "That asset isn't in this lounge" | "That room isn't in this hotel" (rooms only) |
| property | "X is already a floor or wing in this lounge" | room type and room number messages (rooms only) |
| identity | "That role is at a location you don't manage", "Choose the location this role is for", "You can only add people to your own locations", "That location does not belong to this business", "That person does not work at one of your locations", shared devices: "Only staff of the location…", "That location is not one of yours", "Pick a location", "Only the lounge's managers and the owner manage its shared devices", PIN sign-in "We don't know that person at this lounge", PIN reminder notice "…on the location's shared tablets" (business-level ones read "hotel(s)" for a hotel-only business) | — |
| messaging | every email's footer: "Hospio360 · lounge management by Oyeks Global" (mixed business mail: "hospitality management"; hotel: "hotel management" as before); handover pack heading "Your business is ready on Hospio360" for a venue or mixed business (the pack and the owner's welcome are business-level mail: the place comes from the business id in their reference, since they go out with no business in the context; a mail tied to one lounge would read "Your lounge is ready"); "Guest messages are off for this lounge (Lounge setup → Guest messages)", "This lounge has no guest-message plan", "This lounge has no manager to message yet", "None of your locations has a manager…", "Only the owner messages a location's managers as a group", default subject "A message from your lounge" | booking messages to guests ("That booking is not at this hotel", guest inbox roles): bookings exist only with rooms |
| platform | alert "…payments declined at this lounge in the last hour", scheduled report notice "· your lounge only", "That location is not one of yours", device invoice reminder "…make sure the lounge's Moniepoint/OPay account can be debited", "That day is already worked out for this lounge", "Enter the lounge's OPay keys first", "This lounge's package is…"; activity log "Lounge setup · SECTION · key changed"; **monthly invoice** emails, bell notices, the app's billing banner, the hold reason and the invoice PDF for a business without rooms: "card/transfer POS payments and new sales pause" (no "new bookings"), "Signing in, recording cash on existing bills and tabs…" (no "checking guests out" / "check-out"); a mixed business with rooms keeps the booking wording (the PDF drops it for any business that is not hotel-only) | suspension emails (they say "business"; no hotel wording); "Hotel not found" (unknown id) |
| reservation, folio | "Choose a location first…" only | everything else: bookings, stays, folios and guest notices are sent only by a location with rooms |
| common | JWT filter: "You don't have access to that location. Pick one of your locations or sign in again" (hotel-only business: unchanged) | "This action needs a hotel business in context", "Not a Hotel setup section" (never shown to a user); "Hotel could not honour the booking" (booking cancel reason) |

**What a hotel (or Hospio360's own team) now reads differently** — deliberate, all small:

- Email validation example: "…must be a valid email, like name@example.com" (was name@hotel.com), for everyone.
- Admin portal only (Hospio360 staff): POS connection check "2 locations have keys", "No location has Moniepoint or OPay keys saved", "Locations without keys", "Payments still need each location's own keys"; integration field hints ("Each location's own keys are set on the location", "…paid out of the business's OPay account"); "That location is not in this business", "That location isn't one of this business's", "Approvals are decided by the business's own managers", "refund to the business", "N locations got the default stores", "Say which business the location belongs to (businessId)"; the legal acceptances CSV column "Hotel" is now "Location".

**Checked.** `mvn -o -fae test` over every module: 1,119 tests, 0 failures (new: common `VenueWordingTest`, tenant `VenueTypeTest.messagesNameThePlaceByItsType`, messaging `EmailTemplatesTest` + `MailDispatcherTest` place tests, platform `MonthlyInvoiceServiceTest.aLoungeIsNotToldAboutBookingsOrCheckOut`). Staging (script `p5_be_check.py`, nothing created): QA Lounge qhye reads "Lounge setup has no section…", "already has a location called QA Lounge Wuse Bar", "Lounge name must contain letters…", "You don't have access to that location. Pick one of your locations…", "Choose a location first…" (that one also in payment and reservation); Simpas Ikeja (qa.pin.*) reads "Hotel setup has no section…", "…that hotel. Pick one of your hotels…" and its bookings, outlets, setup and till answer 200; QA Venue Mixed cvt5 reads Lounge setup / Hotel setup by the location chosen, "location(s)" at business level, and "already has a hotel called…" for a hotel-type name.
Emails rendered to HTML (texts from the real builders; `-Dvenue.email.texts=<dir>` on tenant, platform and messaging tests): handover pack (lounge, mixed, hotel for comparison), welcome, day closed, invoice issued / reminder / final warning / restricted. No "hotel", "room", "booking", "check-in" or "folio" in the lounge ones.

## Phase 4: the bill

How it works for staff (the contract is docs/restaurant-bar.md, "The bill").

- **Splitting.** From an open tab or sale, whoever takes its payment (bar or kitchen staff, the cashier, a manager; the waiter
  where "Waiters take payment" is on) opens the bill and splits what is still to pay: equally between 2 to 20 people, by item
  (each item on one guest's bill, or shared between several; an item of several units can be divided by unit), or by amount (each
  amount typed, the rest of the bill is the last part). Each part is then paid on its own, by any method: cash into the payer's
  till (a waiter's into the cashier's), card or transfer on the outlet's POS, or a room at a location with rooms. Each payment has
  its own receipt naming the part. What is still to pay is always on the bill. The split can be undone until a part is paid;
  while it stands nothing is added, taken off or discounted. The tab closes by itself with the last part.
- **To the kobo.** Parts are rounded down and the last takes the remainder, so they always add up to the bill: ₦10,000 between
  three is 3,333.33 + 3,333.33 + 3,333.34. VAT and the service charge inside the bill are shared in the same proportion.
- **One sale.** A split sale stays one order: sales by outlet, item, staff and hour count it once; payments by method count each
  part's money under its method.
- **Discounts and complimentary.** On a line or the whole bill, a percentage or an amount, or free, always with a reason from the
  location's list. "Who gives a discount without asking" (Hotel setup → Restaurant & bar; cashier and bar by default) up to the
  discount limit (Approval limits, 10 %); managers and owners any. More than the limit, a free item, or anyone else (waiters by
  default) sends a request. The Voids & discounts report shows what was given by hand, why, by whom and who approved it.
- **Requests from the floor.** A void of something already sent (or of a whole sale) and a discount over the limit are asked for
  from the bill with a reason. The manager and the owner get a notification; it is in the manager's "Needs your action" and both
  approvals lists. They approve or decline there, or on the requester's screen ("Manager is here": the manager picks their name
  and types their own PIN; the owner their password). The requester sees the outcome on the bill and in their notifications. A
  void counts against whoever asked, with "approved by …" in its reason. Everything is in the approvals history and activity log.
- **Tips** (optional, off by default: "Record tips"). When on, the payment step takes a tip on top of the bill, in cash or on the
  card. It is kept against whoever opened the sale, shown in Reports → Tips (by staff, by day) and on the till as cash tips held.
  It is never a sale: not in sales, income, payments by method or the base of Hospio360's daily fee. When off, nothing shows.
- **Outlets per person.** Optional list of outlets for a waiter, cashier, bar or kitchen person (Add / Edit staff, onboarding
  People). Empty = every outlet. "Serving at" offers only theirs and the server refuses the rest.

Data: fnb `009-splits-discounts-tips` (`order_splits`, `order_split_parts`, `order_tips`, `sale_requests`, `staff_outlets`,
`orders.room_amount`, `split_parts`, bill discount columns, `order_lines.manual_discount`, `bill_discount`, `complimentary`…),
payment `011-payment-part-tip`, finance `007-till-cash-tips`; settings `OUTLETS.discountRoles`, `OUTLETS.recordTips`.
QA script: `hotel-internal-system-admin-FE/docs/qa/67-bill-splitting-discounts-approvals-tips.md`.

## Phase 3: venue setup, waiters and cashiers

**Setup template.** The "Lounge / bar / restaurant" setup is built in (`common SetupCatalogue.VENUE_DEFAULTS`, `VENUE_LISTS`), not a row
in tenant-service: one source for a new location and for re-applying.

- A new location without accommodation (HotelCreated with its `venueType`, so admin onboarding and the owner's sign-up alike) gets it
  from hotel-config `createDefaults`; a hotel keeps the catalogue ("Standard hotel") defaults.
- What it sets: tabs on, tab limit ₦100,000, a tab flagged when open over 4 hours (`OUTLETS.tabMaxHours`, read by `GET /fnb/sales/today`
  when `staleHours` is left out), a tab needs the guest's name or a table (`tabNameRequired`), waiters don't take payment
  (`waitersTakePayment`, `waiterCash` off); cash, card and transfer, till float ₦10,000, receipts on WhatsApp (printed until 19 Nov 2026, docs/whatsapp-receipts.md); VAT as the catalogue
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

## Known limits (after phase 6)

- **Words follow the type, features follow the rooms.** A lounge that adds rooms gets the room pages and stay settings by
  itself but is still called a lounge ("Lounge setup"); change its type to Hotel (admin onboarding → Locations, or
  `PUT /tenant/hotels/{id}`) to change the words.
- **Club** is a type with its own word ("Club setup"); the owner's sign-up offers Hotel, Lounge, Bar, Restaurant and Mixed, so a
  club is set per location (or by Hospio360).
- **A mixed business with its lounge chosen** still sees the hotel's rooms and occupancy on the business-level pages
  (Locations, dashboard branch cards, Occupancy report): they are about every location.
- **Stored text keeps its words**: notifications, activity entries and audit lines written before 19 Nov 2026 ("Setting changed ·
  Hotel setup · …" is now "Lounge setup · …" for new entries only).
- **Laundry and maintenance at a venue** are worded for the place and lose the room tiles and guides, but those desks were built
  around rooms (linen from housekeeping, rooms out of order); nobody has run them at a lounge yet.
- **Fallback names** when a location has no name at hand still say "this hotel" in a few server pages and "the hotel" on a device
  invoice with no location; never seen with real data.
- **Emails to a real inbox were not checked on staging** (`.test` addresses are skipped by design): the venue texts are rendered
  from the real builders by the tests (`-Dvenue.email.texts=<folder>`), the day-closed one composed in the service's words.
- **The owner's welcome email of a mixed business** says "check each hotel's rooms, prices and staff" (any location with rooms).
- **Simpas Ikeja** was regressed with the `qa.pin.*` accounts only (front office, bar, kitchen, waiter, cashier); the manager's
  and owner's hotel pages (Hotel setup, Hotels, reports, roles) were checked on QA hotels (QA Debit Rate rrydw, the Mixed cvt5
  hotel), not on Simpas.
- Not built for venues: table reservations or a floor plan, a guest-facing menu or QR ordering, loyalty, scheduled or emailed
  outlet reports, tip pooling.

## QA approach

- Unit tests: tenant `VenueTypeTest` (capability, default type from the kind, handover pack and welcome text), finance `NightAuditServiceTest` (lounge checklist tills + orders only; hotel unchanged), hotel-config `RoomChargeVenueTest`, FE `packages/api/src/venue.test.ts` (capability, stay-only paths).
- Staging end to end (API + browser): (a) admin onboards "QA Venue <id>" as a Lounge with one location: no rooms, go-live green, handover, owner accepts; owner/manager/bar/kitchen see no hotel modules; (b) owner self-serve sign-up as a Restaurant through Outlets; (c) mixed business: hotel + lounge, room features only on the hotel; (d) regression on Simpas Ikeja (hotel): front office, bookings, check-in, folios, charge to room from the bar, night audit.
- QA scripts: `hotel-internal-system-admin-FE/docs/qa/61-venue-type.md` (phase 1), 62, 65, 67, 68 (landing) and `70-venue-wording-and-regression.md` (phases 5 and 6).

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
- 18 to 19 Nov 2026: phase 4 built and shipped (backend `1da9dea` the bill, `38a827c` splitParts, the complimentary-tab and approver
  refusal fixes and these docs; web "Venue type phase 4: the bill…" and follow-ups, QA 67). The first staging run failed on a
  platform-service build in CI and deployed part of the services; a `[deploy-all]` push redeployed everything. Staging e2e, QA
  venues only, cash and the Bar test terminal (the logging POS confirmed through its webhook), API and browser:
  **Equal split**: a ₦10,000 tab (4 Chapman) between three is 3,333.33 + 3,333.33 + 3,333.34 (VAT 232.55 + 232.55 + 232.57 =
  697.67), paid cash (Bar till), card and transfer on the Bar test terminal; a payment naming no part is refused ("B-0021 is split
  into 3 parts…"), more than the part too ("Only ₦3,333.33 is due on part 1 of 3 of B-0021"), undo and adding an item are refused
  once a part is paid; the tab closed on the third payment with ₦10,000 paid in 3 payments.
  **By item, service charge on** (guest pays the 1.5 % fee; VAT 7.5 % included): Star Lager + Chapman + Coke = ₦4,866.98 (service
  charge 66.98, VAT 334.88); Guest 1 (Star Lager, half the Coke) ₦1,926.51, Guest 2 (Chapman, half the Coke) ₦2,940.47, adding up
  exactly, service charge 26.51 + 40.47 and VAT 132.55 + 202.33; paid transfer + cash into the cashier till by the cashier; an item
  on nobody's bill is refused; a waiter can't split while waiters don't take payment.
  **By amount / undo / void**: ₦3,800 as 1,000 + 1,500.50 + rest 1,299.50; undone; more than the bill refused; split again in two,
  then the bar's void request, the manager's and the owner's bell ("Approval needed: void"), approved from the manager's
  approvals: sale cancelled, split undone, the requester's bell "Approved: Void the whole sale (₦3,800)", the void under Bisi with
  "… · approved by Tunde Lounge Manager". With a part paid the void is refused; an approval arriving late is kept "not applied".
  **Discounts**: cashier 10 % of ₦8,500 at once (₦7,650); 25 % sent to a manager, a wrong PIN on her screen refused, the manager's
  PIN approved it there (₦6,375, "by Chika Cashier", approved by Tunde, note "Decided on Chika Cashier's screen"); the cashier
  can't approve it herself (403); a free Star Lager from the bar approved by the owner (complimentary, reason, giver and approver
  on the line; ₦5,250 paid); the waiter's 5 % declined with a note (nothing changed, bell "Declined…: Not today"); the owner
  declined a waiter's void on his screen with her password; the manager made a whole bill free at once (it closes as
  complimentary; a tab too after a fix).
  **Tips**: refused while "Record tips" is off; on: cash ₦3,000 + ₦500 tip by the cashier for Wale's tab (payment 3,500, sale and
  income 3,000, `order_tips` 500 for Wale, till expected +3,500 with cash tips held 500 and a TIP entry) and card ₦1,600 + ₦200 on
  the test terminal; Tips report ₦700 (cash 500, card 200; Wale 500, Bisi 200); a waiter sees only their own; switched off again.
  **Outlets per person**: Kemi kept to VIP Lounge: refused at Main Bar (403 "You aren't assigned to Main Bar…"), works at VIP; a
  cashier kept to VIP can neither open a Main Bar bill nor take its payment; both set back to all outlets.
  **By SQL**: each split sale's `order_payments` add up to its total; tips are in `order_tips` only (not in `order_payments` or
  `revenue_entries`); the day's fee base at the lounge (the `SalesReport.daily` sum) went from ₦72,558.21 to ₦100,728.76 =
  +₦28,170.55, exactly the new payments less VAT and service charge: the ₦10,000 split sale counts 9,302.33 (as one unsplit sale
  would), the tipped sales 2,790.70 and 1,488.37 (3,000 and 1,600 less VAT, no tip), the discounted sale 4,883.72 (₦5,250 paid of
  ₦8,500 listed), the complimentary bill nothing. Sales by staff and the sales count take a split sale once; Voids & discounts
  lists reasons, givers and approvers.
  **Hotel** (Simpas Ikeja, `qa.pin.*` only, cash): a ₦2,400 bar tab split in two, part 1 on room A102 (folio line "Bar · B-0017
  (part 1 of a split bill)" 1,116.28 + VAT 83.72), part 2 cash: closed, ₦1,200 paid at the bar and ₦1,200 on the room; charging
  the whole tab is refused while split; the hotel cashier gave 10 % on the waiter's ₦3,600 tab, took ₦1,000 cash and put the
  rest (₦2,240) on the room; a whole tab on a room as before; a tip refused (tips off); the folio rose by exactly ₦4,640.
  **Browser** (1024 and 820): tab → Split or discount → the bill; split equally in three, by item and by amount windows; undo;
  pay a part in cash (change ₦0.67) and back on the bill with ₦6,666.67 to pay; discount over the limit → Send to a manager →
  Manager is here with a wrong then the right PIN; the tip step; cash tips on the till; the manager's Needs your action,
  Approvals, Hotel setup (Who gives a discount, Record tips), Reports → Tips and Voids & discounts, Staff outlets; no sideways
  scroll. Found and fixed on the way: a refused PIN reached the screen as "Could not check that PIN" (the 401 body was lost
  between services); a tab made wholly complimentary stayed open at ₦0; the bill showed the items net of VAT; the payment steps
  rounded a part to the naira; the reason picker fell back to free text.
  Left for a decision: tips stay off everywhere until the owner decides (no tip pooling or pay-out is built: the report says who
  earned what and the till how much cash is held); a refund of a paid part still goes through Payments; a split by item is
  offered only before any money is taken.
- 19 Nov 2026: **phases 5 and 6 done; the programme is complete.** Backend `346ffad` + `1b54106` (VenueWording, ~80 messages,
  emails, invoice texts; 1,119 tests) and the invoice line / notification tag / docs commit after them; web `983d845d`,
  `cc7a7195`, `17e5747b`, `23636a1c`, `3342675a` with QA script 70 and the one-page summary
  (`hotel-internal-system-admin-FE/docs/hospio360-for-lounges-bars-restaurants.md`). Staging, QA businesses only, cash and the
  Bar test terminal, no SMS or WhatsApp:
  **Lounge sweep** (QA Lounge qhye; owner 32 pages, manager 36, bar 12, kitchen 11, waiter 7, cashier 9: every menu page, every
  setup section, Help and My profile; visible text searched for hotel, room, check-in, folio, booking): first pass 22 pages with
  hits, last pass **0** for owner, manager, kitchen, waiter and cashier, and one for the bar (the tag "room service" under a
  stored "New bar order … from Wale Waiter" notice: the tag is now "new order" unless the order is for a room). Search boxes and example placeholders were swept too on the last pass (the header, Help, Tasks, Activity and Staff ones no longer name bookings, check-in or rooms there). No sideways
  scroll at 820 or 1024 on any of them. Allowed places: "Not used at a lounge" ("rooms and stays"), the kind picker's hints
  ("A hotel has rooms…", "…no rooms"), "Rooms later?" on the owner's Outlets step, names people typed, text stored before today,
  "On rooms" only when a past sale has some.
  **Lounge flow** (API, orders B-0019 to B-0022): bar sale ₦3,000 cash; the bill offers no charge to a room and the call answers
  400 "…it needs rooms: a lounge, bar or restaurant has none"; waiter tab with Peppered chicken → kitchen ticket → ready; the
  waiter refused ("…A manager can allow it in Lounge setup → Restaurant & bar"); cashier ₦6,000 by card on the Bar test terminal,
  the tab closing once both items were served; ₦3,800 split in two (cash + transfer); night audit `accommodation: false` with
  tills and orders only; sales report ₦45,975 sold and paid, nothing on rooms; billing status and tills fine; no hotel word in
  anything the server said.
  **Hotel** (Simpas Ikeja, `qa.pin.*` only, booking HBE2TFAY): walk-in → "Room B130 hasn't been cleaned yet. A manager can check
  the guest in anyway" → checked in to A116 (13 occupied rooms refused by name) → folio ₦35,000 → bar tab on the room
  (₦35,139.53) → laundry on the room, taken to delivered (₦36,639.53) → ₦36,650 cash on a new front desk till → balance nil →
  checked out → A116 Dirty with a housekeeping task; till closed balanced; "Hotel setup has no section…", "…that hotel. Pick one
  of your hotels…"; Hotel setup still has check-in time, room charge on and stay rules. Browser: front office (17 pages) and bar
  (12) read no "lounge" or "location" as the place's word. Hotels-only owner (QA Debit Rate rrydw, 35 pages): menu Hotels and
  Hotel setup, "Hotel owner", "Add new hotel", "Money received · month to date".
  **Mixed** (QA Venue Mixed cvt5): hotel chosen → Locations, Bookings, **Hotel setup**, Rooms & stay rules and Housekeeping in
  setup; lounge chosen → **Lounge setup**, no Bookings, no stay sections; "locations" on the owner's pages both ways (14 checks).
  **Sign-up and onboarding**: sign-in panel and sign-up for hotels, lounges, bars and restaurants; the restaurant owner still in
  the wizard (QA Venue Resto yrio): "Your first restaurant", Outlets, no hotel wording; admin: Locations directory with Kind and
  the Lounge filter, Businesses "Locations · rooms", "What kind of business?" in Set up a business, the lounge in onboarding
  (QA Venue wy4f) reads "each location". PWA names unchanged ("Hospio360", "Hospio360 Admin").
  **Emails** rendered to HTML (handover pack lounge / mixed / hotel, welcome, day closed, invoice issued, reminder, final
  warning, restricted): no hotel, room, booking, check-in or folio in the lounge ones; the mixed pack's only one is its hotel's
  "12 rooms".
  Found and fixed on the way: at a mixed business's hotel the menu read "Location setup" (the setup's name is now put in after
  the other words); two sentences had lost the space before them; the monthly invoice line printed "0 SMS"; the sign-in still
  showed "Folio 1042"; the admin wizard's People step said "each hotel" for a lounge.
  Legal, wording only (no new versions; `docs/legal-acceptance.md`): guest message charges are emails and WhatsApp (SMS only for
  sign-in codes), and Terms 7.3 names the grounds for another suspension; the 11 Word templates were rebuilt with both, the
  suspension wording, the new wordmark and no receipt printers.
  Housekeeping: phase 4 and suspension worktrees and branches removed (all clean and merged); the web repo's leftover autostash
  (phase 3 work, all upstream) dropped.
