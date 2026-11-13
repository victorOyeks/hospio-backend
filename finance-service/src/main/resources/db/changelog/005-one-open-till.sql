--liquibase formatted sql

--changeset hospio:finance-005-one-open-till runAlways:true splitStatements:false
-- One open till per role at a hotel. Two people pressing Open at once could each open one (the check and the insert were not
-- atomic), splitting the desk's cash between two drawers. A partial unique index stops it. Two open tills already in the table
-- would make the index fail and stop the service starting, and closing a drawer with cash in it is a manager's call, so the
-- index is only created once no role has two open tills. The changeset runs on every start (a no-op once the index exists):
-- after the extra till is closed, the next start creates it. Until then a warning says how many roles are affected.
SELECT set_config('app.scope', 'platform', true);
DO $$
DECLARE
    dupes integer;
BEGIN
    IF to_regclass('finance.uq_tills_one_open_per_role') IS NOT NULL THEN
        RETURN;
    END IF;
    SELECT count(*) INTO dupes FROM (SELECT hotel_id, role FROM finance.tills WHERE status = 'OPEN' GROUP BY hotel_id, role HAVING count(*) > 1) d;
    IF dupes = 0 THEN
        CREATE UNIQUE INDEX IF NOT EXISTS uq_tills_one_open_per_role ON finance.tills (hotel_id, role) WHERE status = 'OPEN';
    ELSE
        RAISE WARNING 'finance.tills: % hotel roles have more than one open till; uq_tills_one_open_per_role not created yet', dupes;
    END IF;
END $$;
