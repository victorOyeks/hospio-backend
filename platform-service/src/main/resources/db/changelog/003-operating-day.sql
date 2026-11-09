--liquibase formatted sql

--changeset hospio:013-approval-hotel-and-requester-role
-- The approvals list shows which hotel a request came from and what role the requester holds; both are snapshotted when it arrives.
ALTER TABLE platform.approvals ADD COLUMN IF NOT EXISTS hotel_name VARCHAR(150);
ALTER TABLE platform.approvals ADD COLUMN IF NOT EXISTS requester_role VARCHAR(30);
