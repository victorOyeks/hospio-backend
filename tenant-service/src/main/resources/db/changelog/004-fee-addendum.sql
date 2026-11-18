--liquibase formatted sql

--changeset hospio:tenant-004-fee-addendum
SELECT set_config('app.scope', 'platform', true);
-- Fee lock (docs/plan-daily-fees.md): every business pays the platform default share of daily sales; another rate carries the reference of the signed addendum that allows it (null at the default).
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS fee_addendum_ref VARCHAR(120);
