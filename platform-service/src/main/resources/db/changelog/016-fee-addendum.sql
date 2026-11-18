--liquibase formatted sql

--changeset hospio:platform-016-fee-addendum
--comment: Fee lock (docs/plan-daily-fees.md): every business pays the platform default share of daily sales; a fee rule at another rate carries the reference of the signed addendum that allows it.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE platform.fee_rules ADD COLUMN IF NOT EXISTS addendum_ref VARCHAR(120);
