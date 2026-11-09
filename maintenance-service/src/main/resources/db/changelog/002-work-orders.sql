--liquibase formatted sql

--changeset hospio:005-work-orders
-- Work orders (docs/laundry-maintenance.md): number, category, due time, hold/reopen/cancel reasons, completion with parts,
-- labour and vendor cost, out-of-order end date, preventive schedule key. WAITING_PARTS becomes ON_HOLD.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE maintenance.repairs ADD COLUMN number VARCHAR(20);
ALTER TABLE maintenance.repairs ADD COLUMN category VARCHAR(30) NOT NULL DEFAULT 'OTHER';
ALTER TABLE maintenance.repairs ADD COLUMN reported_by_name VARCHAR(150);
ALTER TABLE maintenance.repairs ADD COLUMN reported_by_role VARCHAR(30);
ALTER TABLE maintenance.repairs ADD COLUMN due_at TIMESTAMP;
ALTER TABLE maintenance.repairs ADD COLUMN started_at TIMESTAMP;
ALTER TABLE maintenance.repairs ADD COLUMN hold_reason VARCHAR(300);
ALTER TABLE maintenance.repairs ADD COLUMN completion_note VARCHAR(1000);
ALTER TABLE maintenance.repairs ADD COLUMN labour_minutes INTEGER;
ALTER TABLE maintenance.repairs ADD COLUMN parts TEXT;
ALTER TABLE maintenance.repairs ADD COLUMN parts_cost NUMERIC(14, 2) NOT NULL DEFAULT 0;
ALTER TABLE maintenance.repairs ADD COLUMN vendor_name VARCHAR(150);
ALTER TABLE maintenance.repairs ADD COLUMN vendor_cost NUMERIC(14, 2) NOT NULL DEFAULT 0;
ALTER TABLE maintenance.repairs ADD COLUMN reopen_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE maintenance.repairs ADD COLUMN reopen_reason VARCHAR(300);
ALTER TABLE maintenance.repairs ADD COLUMN cancel_reason VARCHAR(300);
ALTER TABLE maintenance.repairs ADD COLUMN cancelled_at TIMESTAMP;
ALTER TABLE maintenance.repairs ADD COLUMN ooo_until DATE;
ALTER TABLE maintenance.repairs ADD COLUMN overdue_notified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE maintenance.repairs ADD COLUMN asset_id VARCHAR(36);
ALTER TABLE maintenance.repairs ADD COLUMN schedule_id VARCHAR(36);
ALTER TABLE maintenance.repairs ADD COLUMN schedule_due DATE;
UPDATE maintenance.repairs SET status = 'ON_HOLD', hold_reason = 'Waiting for parts' WHERE status = 'WAITING_PARTS';
UPDATE maintenance.repairs SET due_at = created_at + (CASE priority WHEN 'URGENT' THEN INTERVAL '4 hours' WHEN 'HIGH' THEN INTERVAL '24 hours'
    WHEN 'LOW' THEN INTERVAL '7 days' ELSE INTERVAL '3 days' END) WHERE due_at IS NULL;
UPDATE maintenance.repairs r SET number = 'WO-' || to_char(r.created_at, 'YYYY') || '-' || lpad(x.n::text, 5, '0')
FROM (SELECT id, row_number() OVER (PARTITION BY hotel_id ORDER BY created_at) AS n FROM maintenance.repairs) x
WHERE x.id = r.id AND r.number IS NULL;
CREATE UNIQUE INDEX ux_repairs_hotel_number ON maintenance.repairs (hotel_id, number);
CREATE UNIQUE INDEX ux_repairs_schedule_due ON maintenance.repairs (schedule_id, schedule_due) WHERE schedule_id IS NOT NULL;

--changeset hospio:006-assets-and-schedules
SELECT set_config('app.scope', 'platform', true);
CREATE TABLE maintenance.assets
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    name        VARCHAR(150) NOT NULL,
    category    VARCHAR(30)  NOT NULL DEFAULT 'OTHER',
    room_id     VARCHAR(36),
    room_number VARCHAR(20),
    area        VARCHAR(100),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX ux_assets_hotel_name ON maintenance.assets (hotel_id, lower(name));
CALL app.protect('maintenance', 'assets');
ALTER TABLE maintenance.scheduled_jobs ADD COLUMN category VARCHAR(30) NOT NULL DEFAULT 'OTHER';
ALTER TABLE maintenance.scheduled_jobs ADD COLUMN priority VARCHAR(10) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE maintenance.scheduled_jobs ADD COLUMN asset_id VARCHAR(36);
ALTER TABLE maintenance.scheduled_jobs ADD COLUMN room_id VARCHAR(36);
ALTER TABLE maintenance.scheduled_jobs ADD COLUMN room_number VARCHAR(20);
ALTER TABLE maintenance.scheduled_jobs ADD COLUMN last_opened DATE;
