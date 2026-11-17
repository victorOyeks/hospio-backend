--liquibase formatted sql

--changeset hospio:tenant-003-one-fee-model
--comment: Hospio360 has one fee model, a share of each day's sales debited daily. Every business on another model (or none) moves to it at the usual 1.5 %; a business already on it keeps its rate.
-- Row security is forced on these tables: the platform scope, for this transaction only, lets the update see every row.
SELECT set_config('app.scope', 'platform', true);
UPDATE tenant.businesses
SET fee_model = 'PERCENT_OF_SALES', fee_value = 1.50
WHERE fee_model IS DISTINCT FROM 'PERCENT_OF_SALES';
