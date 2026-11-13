--liquibase formatted sql

--changeset hospio:folio-003-scale-indexes
-- Organization screens list folios, invoices, payments and members of one organization; a folio's lines in posting order.
CREATE INDEX IF NOT EXISTS idx_folios_organization ON folio.folios (organization_id) WHERE organization_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_invoices_organization ON folio.invoices (organization_id);
CREATE INDEX IF NOT EXISTS idx_org_payments_organization ON folio.organization_payments (organization_id);
CREATE INDEX IF NOT EXISTS idx_org_members_organization ON folio.organization_members (organization_id);
CREATE INDEX IF NOT EXISTS idx_folio_lines_folio_posted ON folio.folio_lines (folio_id, posted_at);
