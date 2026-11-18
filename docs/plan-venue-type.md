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
| 3 | Features key off a **capability**, never the label: `hasAccommodation` = type HOTEL **or** the location has rooms (`roomCount > 0`, kept current by property-service). A lounge that later adds rooms gets bookings, folios and charge-to-room on its own. Shared as `com.hospio.common.venue.VenueType.hasAccommodation` and `packages/api/src/venue.ts hasAccommodation`. |
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
| 2 | Venue dashboards and reports: sales by outlet, item and staff; open tabs; tills and cash-ups; best sellers; hour-of-day; owner comparison across locations by kind (only real comparisons) | To do |
| 3 | "Lounge / bar / restaurant" setup template (no stay rules or housekeeping, tabs on with a limit, service charge, happy hours) applied by kind in both wizards; Waiter and Cashier roles with default access levels; role pickers offer them for venues | To do |
| 4 | Bill splitting on tabs and sales (by item, by share, equal), each part paid its own way; receipts per part | To do |
| 5 | Wording: landing page, sign-up and sign-in ("hotel, lounge or restaurant"), handover pack and owner acceptance, admin portal ("Hotels" directory → Locations with kind), owner "Hotels" page → Locations, server messages that still say "hotel", Hotel setup → Location setup | To do |
| 6 | Regression across hotel customers and docs: every role on a hotel, a lounge and a mixed business; contracts (onboarding.md, roles-permissions.md, restaurant-bar.md) updated | To do |

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

- 18 Nov 2026: plan agreed; phase 1 built and tested on staging (see the QA script for the steps).
