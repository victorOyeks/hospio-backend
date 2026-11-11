# Who is in a room · occupancy rule

reservation-service owns availability. A room is occupied from check-in until the guest is **checked out**, not until their
departure date passes. Code: `reservation-service/.../service/Occupancy.java`; used by `BookingService` for availability, new
bookings, room changes and check-in.

## The rule

- A BOOKED stay holds the nights it covers, `[checkInDate, checkOutDate)`.
- A CHECKED_IN stay holds its room until it is checked out:
  - departure later than today: its nights, as booked;
  - **due out today**: it does not hold tonight against an arrival being *given* the room (same-day turnover), but nobody can
    be *checked in* to the room until this guest is checked out;
  - **past departure** (overstay): it holds tonight, so the room is neither free nor assignable. Nothing frees the room
    automatically; the night audit does not check guests out.
- CHECKED_OUT, CANCELLED and NO_SHOW stays hold nothing.
- "Today" is the Lagos business day (`Occupancy.today()`), whatever zone the server runs in.

## Where it is enforced

| Path | Check |
|---|---|
| `GET /bookings/availability` | `freeRoomIds`: nobody holds the room on those nights and nobody is in it. `turnoverRoomIds` (new): a guest due out today is still in; assignable to today's arrival. `booked` / `available` count overstays as occupying tonight; a guest due out today is not counted (the room can be sold). Out-of-order rooms are in neither list. |
| `POST /bookings` with `roomId` | the room must not be held on those nights (turnover allowed for an arrival today). With `walkIn: true` (the web's walk-in) the room must also have nobody checked in, so the refusal comes before the booking or any payment is saved; a walk-in must arrive today. |
| `PUT /bookings/{id}` with `roomId` (move / assign) | as above; moving an in-house guest also needs nobody else checked in to the target room. Extending a stay keeps the existing check against bookings on the room. |
| `POST /bookings/{id}/check-in` | nobody else checked in to the room (whatever their dates), and no other stay holding those nights. |
| Owner portal (`atHotel`) | the same endpoints with the hotel's `X-Hotel-Id`; nothing separate. |
| `/internal/**` | no internal endpoint puts a guest in a room. `/internal/rooms/{roomId}/active-booking` (fnb, housekeeping, maintenance) relies on there being one. |

Refusals are 409:

- "Room A113 still has a guest checked in (Ada Obi). Check them out first, or choose another room."
- "Room A113 is already booked for those nights" (another booking holds those nights).

Out of order is checked first and unchanged (`docs/laundry-maintenance.md`): 400 "Room 104 is out of order · choose another
room".

## Dirty rooms (unchanged)

After check-out the room goes to housekeeping as DIRTY. Check-in into a DIRTY room: with `HOUSEKEEPING.inspectDepartures` on
(the default) it is refused (400 "Room … hasn't been cleaned yet. A manager can check the guest in anyway") unless a manager or
owner sends `overrideDirty: true`; with it off the check-in goes through with `warning: "Room not cleaned yet"`. Housekeeping
being unreachable counts as clean.

## Concurrency

- Every path that puts a guest in a room takes a transaction-scoped advisory lock on the room (`room:<roomId>`) before checking,
  after the per-type lock where one is taken, so two desks can't both pass the check.
- The database guarantee is a partial unique index: `ux_bookings_room_in_house ON reservation.bookings (room_id) WHERE status =
  'CHECKED_IN' AND room_id IS NOT NULL` (changeset `004-room-in-house-unique`). A clash at commit becomes the 409 above.
- The changeset runs on every start and is a no-op once the index exists. If old data still has two guests checked in to one
  room it logs a warning and skips (rather than failing the start); the index is built on the first start after the extra
  guest is checked out or moved.

## Web (hospio-frontend)

- Rooms board: an occupied room's tile says "Due out" (leaves today) or "Past departure".
- Check-in panels (front office and manager): an occupied room shows "Due out today · <guest> still checked in" in the picker and
  the refusal as a note; check-in stays disabled and the manager's dirty-room override does not apply to it.
- Walk-in sends `roomId` and `walkIn: true`.
- Today: "Leaving today" also lists guests past departure ("Past departure"), with a to-do; arrivals waiting on a turnover show
  "Turnover · <guest> not out yet". Bookings rows: "In house · past departure".
