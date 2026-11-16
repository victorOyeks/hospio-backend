--liquibase formatted sql

--changeset hospio:platform-009-integration-settings
-- Third-party keys and addresses managed from the admin portal (Settings · Integrations): one row per provider. sealed_values is the
-- provider's settings record as JSON, encrypted with SECRETS_KEY (else POS_SECRETS_KEY; AES-256-GCM, "v1:"); masked_values is what the
-- screen shows (secrets masked), so the list never needs the key. A provider with no row uses the server's environment. Platform-only.
CREATE TABLE IF NOT EXISTS platform.integration_settings
(
    id              VARCHAR(36)  PRIMARY KEY,
    provider        VARCHAR(40)  NOT NULL,
    sealed_values   TEXT         NOT NULL,
    masked_values   TEXT         NOT NULL,
    updated_by      VARCHAR(36),
    updated_by_name VARCHAR(150),
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uq_integration_settings_provider UNIQUE (provider)
);
ALTER TABLE platform.integration_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.integration_settings FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS platform_rw ON platform.integration_settings;
CREATE POLICY platform_rw ON platform.integration_settings USING (app.is_platform()) WITH CHECK (app.is_platform());
