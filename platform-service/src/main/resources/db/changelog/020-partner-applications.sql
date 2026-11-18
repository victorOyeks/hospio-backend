--liquibase formatted sql

--changeset hospio:platform-020-partner-applications
--comment: "Apply to be a partner" on the landing site (docs/plan-marketers.md): one row per application, reviewed in Marketers · Applications. Platform-only table (no business), like marketer_profiles.
SELECT set_config('app.scope', 'platform', true);
CREATE TABLE IF NOT EXISTS platform.partner_applications
(
    id                   VARCHAR(36)  PRIMARY KEY,
    full_name            VARCHAR(150) NOT NULL,
    email                VARCHAR(255) NOT NULL,
    phone                VARCHAR(20)  NOT NULL,
    city                 VARCHAR(100) NOT NULL,
    state                VARCHAR(60)  NOT NULL,
    business_kinds       VARCHAR(100) NOT NULL,
    business_count       VARCHAR(10)  NOT NULL,
    network_note         VARCHAR(500),
    company_name         VARCHAR(150),
    status               VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    decided_at           TIMESTAMP,
    decided_by           VARCHAR(36),
    decided_by_name      VARCHAR(150),
    decline_reason       VARCHAR(500),
    decline_reason_shared BOOLEAN     NOT NULL DEFAULT FALSE,
    marketer_profile_id  VARCHAR(36),
    ip                   VARCHAR(64),
    user_agent           VARCHAR(300),
    rpa_version          VARCHAR(30),
    privacy_version      VARCHAR(30),
    created_at           TIMESTAMP    NOT NULL,
    updated_at           TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_partner_applications_status ON platform.partner_applications (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_partner_applications_email ON platform.partner_applications (LOWER(email));
-- One open application per email, whatever two requests arriving together do.
CREATE UNIQUE INDEX IF NOT EXISTS uq_partner_applications_pending_email ON platform.partner_applications (LOWER(email)) WHERE status = 'PENDING';
ALTER TABLE platform.partner_applications ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.partner_applications FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS platform_rw ON platform.partner_applications;
CREATE POLICY platform_rw ON platform.partner_applications USING (app.is_platform()) WITH CHECK (app.is_platform());
