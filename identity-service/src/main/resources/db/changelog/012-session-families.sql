--liquibase formatted sql

--changeset hospio:identity-012-session-families
-- A browser's session family (docs/sign-in.md): a sign-in starts one, its refreshes keep it, and a hand-over to the other web
-- app carries it to the session opened there. "Sign out" ends that family only (both apps in this browser); "Sign out
-- everywhere" ends every session. Older rows have none: their family is their session id.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE identity.refresh_tokens ADD COLUMN IF NOT EXISTS family_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family ON identity.refresh_tokens (family_id) WHERE family_id IS NOT NULL;
ALTER TABLE identity.handoff_codes ADD COLUMN IF NOT EXISTS family_id VARCHAR(36);
