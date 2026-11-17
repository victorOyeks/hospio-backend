--liquibase formatted sql

--changeset hospio:platform-010-one-fee-model
--comment: One fee model (a share of daily sales, debited daily): the platform default is it at 1.5 %, and any fee rule on another model moves to it at 1.5 %.
-- Row security is forced on these tables: the platform scope, for this transaction only, lets the update see every row.
SELECT set_config('app.scope', 'platform', true);
UPDATE platform.platform_settings SET value = '"PERCENT_OF_SALES"' WHERE group_name = 'fees' AND setting_key = 'model';
UPDATE platform.platform_settings SET value = '1.5' WHERE group_name = 'fees' AND setting_key = 'revenuePct';
UPDATE platform.fee_rules SET model = 'PERCENT_OF_SALES', value = 1.50 WHERE model <> 'PERCENT_OF_SALES';
