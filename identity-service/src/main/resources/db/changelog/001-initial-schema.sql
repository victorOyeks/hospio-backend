--liquibase formatted sql

--changeset hospio:001-user-accounts
-- A person who can sign in. Platform admins have no business; everyone else belongs to one.
CREATE TABLE identity.user_accounts
(
    id                            VARCHAR(36)  PRIMARY KEY,
    business_id                   VARCHAR(36),
    full_name                     VARCHAR(150) NOT NULL,
    email                         VARCHAR(255) NOT NULL UNIQUE,
    phone                         VARCHAR(20),
    password_hash                 VARCHAR(100),
    pin_hash                      VARCHAR(100),
    failed_pin_attempts           INTEGER      NOT NULL DEFAULT 0,
    locked_until                  TIMESTAMP,
    status                        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    must_change_password          BOOLEAN      NOT NULL DEFAULT FALSE,
    temporary_password_expires_at TIMESTAMP,
    two_step_enabled              BOOLEAN      NOT NULL DEFAULT FALSE,
    two_step_secret               VARCHAR(100),
    language                      VARCHAR(10),
    last_sign_in_at               TIMESTAMP,
    created_at                    TIMESTAMP    NOT NULL,
    updated_at                    TIMESTAMP    NOT NULL
);
CREATE INDEX idx_user_accounts_business ON identity.user_accounts (business_id);
CREATE INDEX idx_user_accounts_phone ON identity.user_accounts (phone);

--changeset hospio:002-role-grants
-- One role at one hotel (business-wide when hotel_id is null, as for the owner) at one level.
CREATE TABLE identity.role_grants
(
    id          VARCHAR(36) PRIMARY KEY,
    user_id     VARCHAR(36) NOT NULL REFERENCES identity.user_accounts (id),
    business_id VARCHAR(36),
    hotel_id    VARCHAR(36),
    role        VARCHAR(30) NOT NULL,
    level       VARCHAR(10) NOT NULL DEFAULT 'FULL',
    paused      BOOLEAN     NOT NULL DEFAULT FALSE,
    expires_at  TIMESTAMP,
    granted_by  VARCHAR(36),
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP   NOT NULL,
    UNIQUE (user_id, hotel_id, role)
);
CREATE INDEX idx_role_grants_user ON identity.role_grants (user_id);
CREATE INDEX idx_role_grants_hotel_role ON identity.role_grants (hotel_id, role);

--changeset hospio:003-refresh-tokens
CREATE TABLE identity.refresh_tokens
(
    id         VARCHAR(36)  PRIMARY KEY,
    user_id    VARCHAR(36)  NOT NULL REFERENCES identity.user_accounts (id),
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    expires_at TIMESTAMP    NOT NULL,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP    NOT NULL,
    updated_at TIMESTAMP    NOT NULL
);
CREATE INDEX idx_refresh_tokens_user ON identity.refresh_tokens (user_id);

--changeset hospio:004-support-access
-- "View as owner": a platform admin looking into a business, with the reason, so every view is logged.
CREATE TABLE identity.support_access_grants
(
    id          VARCHAR(36)  PRIMARY KEY,
    admin_id    VARCHAR(36)  NOT NULL,
    business_id VARCHAR(36)  NOT NULL,
    reason      VARCHAR(500) NOT NULL,
    expires_at  TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);

--changeset hospio:005-row-level-security
-- Sign-in looks users up before any tenant is known, so identity-service runs those reads as the
-- platform. Everything else is business-scoped. Platform admins (business_id null) are only visible
-- to the platform.
CALL app.protect('identity', 'user_accounts', NULL);
CALL app.protect('identity', 'role_grants', NULL);
ALTER TABLE identity.refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.refresh_tokens FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_only ON identity.refresh_tokens USING (app.is_platform()) WITH CHECK (app.is_platform());
ALTER TABLE identity.support_access_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity.support_access_grants FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_only ON identity.support_access_grants USING (app.is_platform()) WITH CHECK (app.is_platform());
