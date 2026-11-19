--liquibase formatted sql

--changeset hospio:platform-023-no-fee-debit
-- The daily fee debit is gone (19 Nov 2026): Hospio360 bills one monthly invoice and debits nothing (docs/plan-monthly-invoices.md).
-- Settings · Integrations no longer has the "Daily fee debit: OPay / Moniepoint" cards, so any values saved for them are deleted (the
-- catalogue would never show or read them again). fee_collections keeps only the POS pauses: the debit provider and switch go.
SELECT set_config('app.scope', 'platform', true);
DELETE FROM platform.integration_settings WHERE provider IN ('FEE_DEBIT_OPAY', 'FEE_DEBIT_MONIEPOINT');
ALTER TABLE platform.fee_collections DROP COLUMN IF EXISTS debit_enabled;
ALTER TABLE platform.fee_collections DROP COLUMN IF EXISTS provider;
