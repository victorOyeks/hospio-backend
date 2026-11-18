--liquibase formatted sql

--changeset hospio:platform-018-legal-debit-rate
--comment: The owner's debit box states the business's own rate (docs/legal-acceptance.md); a DEBIT acceptance records the rate it stated. Older rows stay null (their box said 1.5%). Adding a nullable column changes no row, so the append-only trigger is not involved.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE platform.legal_acceptances ADD COLUMN IF NOT EXISTS rate_pct NUMERIC(6, 3);
