--liquibase formatted sql

--changeset hospio:housekeeping-006-laundry-service-charge
-- Hospio's fee passed to guests (docs/plan-device-packages.md): a laundry order paid directly carries "Service charge (x%)" in its total.
ALTER TABLE housekeeping.laundry_orders ADD COLUMN IF NOT EXISTS service_charge NUMERIC(14, 2) NOT NULL DEFAULT 0;
