--liquibase formatted sql

--changeset hospio:housekeeping-005-notes-linen-requests
-- My rooms: the attendant's note for the supervisor and the time a skipped room is tried again; linen requests carry who asked
-- and who sent them, so laundry can list and fulfil them; a reported problem keeps its urgency and whether the room went out of
-- order. The tables are already protected (forced row-level security); new columns inherit it.
ALTER TABLE housekeeping.cleaning_tasks ADD COLUMN IF NOT EXISTS attendant_note VARCHAR(500);
ALTER TABLE housekeeping.cleaning_tasks ADD COLUMN IF NOT EXISTS retry_at VARCHAR(5);
ALTER TABLE housekeeping.linen_requests ADD COLUMN IF NOT EXISTS requested_by_name VARCHAR(150);
ALTER TABLE housekeeping.linen_requests ADD COLUMN IF NOT EXISTS sent_at TIMESTAMP;
ALTER TABLE housekeeping.linen_requests ADD COLUMN IF NOT EXISTS sent_by_name VARCHAR(150);
ALTER TABLE housekeeping.problem_reports ADD COLUMN IF NOT EXISTS priority VARCHAR(20);
ALTER TABLE housekeeping.problem_reports ADD COLUMN IF NOT EXISTS out_of_order BOOLEAN NOT NULL DEFAULT FALSE;
