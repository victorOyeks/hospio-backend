--liquibase formatted sql

--changeset hospio:folio-002-credit-limit-requests
-- Owner portal · Financials (docs/owner-portal.md): a credit limit above the owner's threshold asked for by a manager
-- waits here until the owner approves it (it then becomes credit_limit) or declines it (the old limit stays).
ALTER TABLE folio.organizations ADD COLUMN IF NOT EXISTS requested_credit_limit NUMERIC(14, 2);
ALTER TABLE folio.organizations ADD COLUMN IF NOT EXISTS credit_approval_id VARCHAR(36);
ALTER TABLE folio.organizations ADD COLUMN IF NOT EXISTS credit_requested_by_name VARCHAR(150);
ALTER TABLE folio.organizations ADD COLUMN IF NOT EXISTS credit_requested_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_organizations_credit_approval ON folio.organizations (credit_approval_id) WHERE credit_approval_id IS NOT NULL;
