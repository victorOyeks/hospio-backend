--liquibase formatted sql

--changeset hospio:messaging-010-monthly-invoice-link
-- Guest-message invoices are billed on Hospio360's monthly invoice (platform, docs/plan-monthly-invoices.md): the MSG- invoice keeps the
-- counts and messages, the HIN- invoice it went on is recorded here, and paying the HIN- invoice settles it.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE messaging.comm_invoices ADD COLUMN IF NOT EXISTS monthly_invoice_id VARCHAR(36);
ALTER TABLE messaging.comm_invoices ADD COLUMN IF NOT EXISTS monthly_invoice_number VARCHAR(30);
CREATE INDEX IF NOT EXISTS idx_comm_invoices_monthly ON messaging.comm_invoices (monthly_invoice_id);
