--liquibase formatted sql

--changeset hospio:fnb-006-service-charge
-- Hospio's fee passed to guests (docs/plan-device-packages.md): an order's "Service charge (x%)" on its net, part of its total.
ALTER TABLE fnb.orders ADD COLUMN IF NOT EXISTS service_charge NUMERIC(14, 2) NOT NULL DEFAULT 0;
