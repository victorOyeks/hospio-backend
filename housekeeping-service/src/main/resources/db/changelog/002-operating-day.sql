--liquibase formatted sql

--changeset hospio:007-task-priority-and-room-note
-- Front desk can rush a room (priority) and leave a note when it marks a room ready or sends it to housekeeping.
ALTER TABLE housekeeping.cleaning_tasks ADD COLUMN IF NOT EXISTS priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE housekeeping.room_status ADD COLUMN IF NOT EXISTS note VARCHAR(300);
