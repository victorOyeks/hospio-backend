--liquibase formatted sql

--changeset hospio:006-platform-settings
-- Platform-wide settings in three groups (general, fees, security); one row per key, value as JSON text.
CREATE TABLE platform.platform_settings
(
    id          VARCHAR(36) PRIMARY KEY,
    group_name  VARCHAR(40) NOT NULL,
    setting_key VARCHAR(60) NOT NULL,
    value       TEXT        NOT NULL,
    updated_by  VARCHAR(36),
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP   NOT NULL,
    UNIQUE (group_name, setting_key)
);

--changeset hospio:007-fee-statements-and-payments
-- A statement now carries when it is due, the business name, its per-hotel lines and when it was sent;
-- each payment against it is its own row.
ALTER TABLE platform.fee_statements ADD COLUMN IF NOT EXISTS due_on DATE;
ALTER TABLE platform.fee_statements ADD COLUMN IF NOT EXISTS business_name VARCHAR(150);
ALTER TABLE platform.fee_statements ADD COLUMN IF NOT EXISTS lines TEXT;
ALTER TABLE platform.fee_statements ADD COLUMN IF NOT EXISTS sent_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_fee_statements_status_due ON platform.fee_statements (status, due_on);
CREATE TABLE platform.fee_payments
(
    id               VARCHAR(36)    PRIMARY KEY,
    business_id      VARCHAR(36)    NOT NULL,
    hotel_id         VARCHAR(36),
    statement_id     VARCHAR(36)    NOT NULL REFERENCES platform.fee_statements (id),
    amount           NUMERIC(14, 2) NOT NULL,
    paid_on          DATE           NOT NULL,
    reference        VARCHAR(100),
    recorded_by      VARCHAR(36),
    recorded_by_name VARCHAR(150),
    created_at       TIMESTAMP      NOT NULL,
    updated_at       TIMESTAMP      NOT NULL
);
CREATE INDEX idx_fee_payments_statement ON platform.fee_payments (statement_id);

--changeset hospio:008-support-tickets-and-comments
ALTER TABLE platform.support_tickets ADD COLUMN IF NOT EXISTS business_name VARCHAR(150);
ALTER TABLE platform.support_tickets ADD COLUMN IF NOT EXISTS opened_by_name VARCHAR(150);
ALTER TABLE platform.support_tickets ADD COLUMN IF NOT EXISTS assigned_to_name VARCHAR(150);
ALTER TABLE platform.support_tickets ADD COLUMN IF NOT EXISTS last_activity_at TIMESTAMP;
CREATE INDEX IF NOT EXISTS idx_support_tickets_status_priority ON platform.support_tickets (status, priority, created_at DESC);
CREATE TABLE platform.support_ticket_comments
(
    id          VARCHAR(36)   PRIMARY KEY,
    business_id VARCHAR(36)   NOT NULL,
    hotel_id    VARCHAR(36),
    ticket_id   VARCHAR(36)   NOT NULL REFERENCES platform.support_tickets (id),
    author_id   VARCHAR(36),
    author_name VARCHAR(150),
    body        VARCHAR(4000) NOT NULL,
    internal    BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL
);
CREATE INDEX idx_ticket_comments_ticket ON platform.support_ticket_comments (ticket_id, created_at);

--changeset hospio:009-marketer-profiles-and-referrals
-- The marketer as a person (profile) and the hotels they send in (referrals). Agreements and commissions point at the profile.
CREATE TABLE platform.marketer_profiles
(
    id                VARCHAR(36)   PRIMARY KEY,
    user_id           VARCHAR(36),
    full_name         VARCHAR(150)  NOT NULL,
    email             VARCHAR(255)  NOT NULL,
    phone             VARCHAR(20),
    status            VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    share_pct         NUMERIC(5, 2) NOT NULL DEFAULT 20,
    months            INTEGER       NOT NULL DEFAULT 24,
    bank_name         VARCHAR(100),
    bank_account      VARCHAR(30),
    bank_account_name VARCHAR(150),
    joined_at         TIMESTAMP,
    ended_at          TIMESTAMP,
    referral_code     VARCHAR(20)   UNIQUE,
    created_at        TIMESTAMP     NOT NULL,
    updated_at        TIMESTAMP     NOT NULL
);
CREATE UNIQUE INDEX idx_marketer_profiles_email ON platform.marketer_profiles (lower(email));
CREATE INDEX idx_marketer_profiles_user ON platform.marketer_profiles (user_id);
CREATE TABLE platform.referrals
(
    id          VARCHAR(36)   PRIMARY KEY,
    profile_id  VARCHAR(36)   NOT NULL REFERENCES platform.marketer_profiles (id),
    hotel_name  VARCHAR(150)  NOT NULL,
    owner_name  VARCHAR(150),
    phone       VARCHAR(20),
    email       VARCHAR(255),
    city        VARCHAR(100),
    stage       VARCHAR(20)   NOT NULL DEFAULT 'LEAD',
    note        VARCHAR(1000),
    business_id VARCHAR(36),
    sent_at     TIMESTAMP     NOT NULL,
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL
);
CREATE INDEX idx_referrals_profile ON platform.referrals (profile_id, sent_at DESC);
CREATE INDEX idx_referrals_business ON platform.referrals (business_id);
ALTER TABLE platform.marketers ADD COLUMN IF NOT EXISTS profile_id VARCHAR(36);
ALTER TABLE platform.commissions ADD COLUMN IF NOT EXISTS profile_id VARCHAR(36);
ALTER TABLE platform.commissions ADD COLUMN IF NOT EXISTS paid_reference VARCHAR(100);
ALTER TABLE platform.commissions ADD COLUMN IF NOT EXISTS paid_on DATE;
CREATE INDEX IF NOT EXISTS idx_commissions_profile_month ON platform.commissions (profile_id, month DESC);

--changeset hospio:010-platform-changes-names
ALTER TABLE platform.platform_changes ADD COLUMN IF NOT EXISTS undone_by VARCHAR(36);
ALTER TABLE platform.platform_changes ADD COLUMN IF NOT EXISTS business_name VARCHAR(150);
CREATE INDEX IF NOT EXISTS idx_platform_changes_made_by ON platform.platform_changes (made_by, created_at DESC);

--changeset hospio:011-alerts
-- business_id is nullable: a platform-wide alert belongs to nobody. app.protect still fits, because
-- app.can_see(NULL, NULL) is true only for the platform.
CREATE TABLE platform.alerts
(
    id                VARCHAR(36)   PRIMARY KEY,
    business_id       VARCHAR(36),
    business_name     VARCHAR(150),
    hotel_id          VARCHAR(36),
    kind              VARCHAR(40)   NOT NULL,
    title             VARCHAR(200)  NOT NULL,
    detail            VARCHAR(1000),
    tone              VARCHAR(10)   NOT NULL DEFAULT 'warning',
    opened_at         TIMESTAMP     NOT NULL,
    dismissed_at      TIMESTAMP,
    dismissed_by      VARCHAR(36),
    dismissed_by_name VARCHAR(150),
    created_at        TIMESTAMP     NOT NULL,
    updated_at        TIMESTAMP     NOT NULL
);
CREATE INDEX idx_alerts_open ON platform.alerts (dismissed_at, opened_at DESC);
CREATE INDEX idx_alerts_business ON platform.alerts (business_id, kind);

--changeset hospio:012-admin-portal-row-level-security
-- Business-scoped tables take the standard policy (the owner may see their own statements' payments, ticket
-- comments and alerts; the platform sees everything). Settings, profiles and referrals have no business:
-- platform only, and every reader (admin routes, the marketer portal running as the system, payment-service's
-- settings lookup) is in the platform scope.
CALL app.protect('platform', 'fee_payments', NULL);
CALL app.protect('platform', 'support_ticket_comments', NULL);
CALL app.protect('platform', 'alerts', NULL);
ALTER TABLE platform.platform_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.platform_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_rw ON platform.platform_settings USING (app.is_platform()) WITH CHECK (app.is_platform());
ALTER TABLE platform.marketer_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.marketer_profiles FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_rw ON platform.marketer_profiles USING (app.is_platform()) WITH CHECK (app.is_platform());
ALTER TABLE platform.referrals ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.referrals FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_rw ON platform.referrals USING (app.is_platform()) WITH CHECK (app.is_platform());
