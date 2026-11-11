--liquibase formatted sql

--changeset hospio:004-room-in-house-unique runAlways:true splitStatements:false
-- One guest checked in per room (docs/occupancy.md): the database's guarantee behind check-in, walk-in and room moves, so two desks
-- checking guests into one room at the same moment can't both succeed. A guest holds the room until checked out, whatever the dates.
-- Runs on every start and is a no-op once the index exists. While a room still has two guests checked in (bad data from before the
-- rule) the index can't be built: it is skipped with a warning and built on the first start after the extra guest is checked out
-- or moved; until then the service's per-room lock and in-house check keep new clashes out.
SELECT set_config('app.scope', 'platform', true);
DO $$
DECLARE
  clashes integer;
BEGIN
  IF to_regclass('reservation.ux_bookings_room_in_house') IS NOT NULL THEN
    RETURN;
  END IF;
  SELECT count(*) INTO clashes FROM (
    SELECT room_id FROM reservation.bookings
     WHERE status = 'CHECKED_IN' AND room_id IS NOT NULL
     GROUP BY room_id HAVING count(*) > 1) d;
  IF clashes > 0 THEN
    RAISE WARNING 'ux_bookings_room_in_house not built: % room(s) have more than one guest checked in', clashes;
  ELSE
    CREATE UNIQUE INDEX ux_bookings_room_in_house ON reservation.bookings (room_id)
     WHERE status = 'CHECKED_IN' AND room_id IS NOT NULL;
  END IF;
END $$;
