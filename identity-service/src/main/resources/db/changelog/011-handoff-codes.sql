--liquibase formatted sql

--changeset hospio:identity-011-handoff-codes
-- One-time passes that carry a signed-in person from one web app to the other (docs/sign-in.md): web-staging signs everyone in
-- and hands platform staff and marketers to the admin console, and the other way round. Only the SHA-256 of the code is kept;
-- a code is bound to one person and one target app, lives at most 60 seconds and is used once (used_at). Platform-only rows,
-- like refresh tokens.
SELECT set_config('app.scope', 'platform', true);
CREATE TABLE IF NOT EXISTS identity.handoff_codes (
    id          VARCHAR(36)  PRIMARY KEY,
    code_hash   VARCHAR(128) NOT NULL UNIQUE,
    user_id     VARCHAR(36)  NOT NULL REFERENCES identity.user_accounts(id),
    target      VARCHAR(20)  NOT NULL,
    next_path   VARCHAR(512),
    session_id  VARCHAR(36),
    expires_at  TIMESTAMP    NOT NULL,
    used_at     TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_handoff_codes_expires ON identity.handoff_codes (expires_at);
ALTER TABLE identity.handoff_codes ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.handoff_codes FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS platform_only ON identity.handoff_codes;
CREATE POLICY platform_only ON identity.handoff_codes USING (app.is_platform()) WITH CHECK (app.is_platform());
