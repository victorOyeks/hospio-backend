--liquibase formatted sql

--changeset hospio:inventory-005-supplier-contact-phone
-- The supplier's contact person may have their own number (optional), checked like the company phone (QA DEF-097).
ALTER TABLE inventory.suppliers ADD COLUMN IF NOT EXISTS contact_phone VARCHAR(30);
