--liquibase formatted sql

--changeset hospio:identity-013-bootstrap-admin
-- The bootstrap super admin (docs/admin-portal.md, "Bootstrap super admin"): on a fresh environment identity-service creates one
-- platform SUPER_ADMIN at start-up and emails a temporary password. One row at most (id is always 1), so two instances starting
-- together can never both record one. issue_count numbers the temporary passwords issued (a re-issue supersedes the last);
-- email_queued_at is when Kafka took the email for the current one; sending_until is how long an instance is still trying to send
-- it. Platform-only rows, like refresh tokens. No password is kept here.
SELECT set_config('app.scope', 'platform', true);
CREATE TABLE IF NOT EXISTS identity.bootstrap_admin (
    id               SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    user_id          VARCHAR(36)  NOT NULL REFERENCES identity.user_accounts(id) ON DELETE CASCADE,
    email            VARCHAR(255) NOT NULL,
    issue_count      INTEGER      NOT NULL DEFAULT 1,
    issued_at        TIMESTAMP    NOT NULL,
    email_queued_at  TIMESTAMP,
    sending_until    TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL
);
ALTER TABLE identity.bootstrap_admin ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.bootstrap_admin FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS platform_only ON identity.bootstrap_admin;
CREATE POLICY platform_only ON identity.bootstrap_admin USING (app.is_platform()) WITH CHECK (app.is_platform());
