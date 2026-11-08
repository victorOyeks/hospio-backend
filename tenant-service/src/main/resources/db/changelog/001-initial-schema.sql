--liquibase formatted sql

--changeset hospio:001-businesses
-- A hotel business (one owner, many hotels). Stage drives the platform admin pipeline.
CREATE TABLE tenant.businesses
(
    id                  VARCHAR(36)  PRIMARY KEY,
    name                VARCHAR(200) NOT NULL,
    legal_name          VARCHAR(200),
    registration_number VARCHAR(50),
    business_type       VARCHAR(50),
    owner_user_id       VARCHAR(36),
    owner_name          VARCHAR(150),
    owner_email         VARCHAR(255),
    owner_phone         VARCHAR(20),
    stage               VARCHAR(20)  NOT NULL DEFAULT 'LEAD',
    account_manager_id  VARCHAR(36),
    marketer_id         VARCHAR(36),
    fee_model           VARCHAR(30),
    fee_value           NUMERIC(14, 2),
    currency            VARCHAR(3)   NOT NULL DEFAULT 'NGN',
    country             VARCHAR(100),
    timezone            VARCHAR(50)  NOT NULL DEFAULT 'Africa/Lagos',
    suspended_at        TIMESTAMP,
    suspension_reason   VARCHAR(500),
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL
);
CREATE INDEX idx_businesses_stage ON tenant.businesses (stage);

--changeset hospio:002-hotels
CREATE TABLE tenant.hotels
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL REFERENCES tenant.businesses (id),
    name        VARCHAR(200) NOT NULL,
    city        VARCHAR(100),
    address     VARCHAR(255),
    phone       VARCHAR(20),
    email       VARCHAR(255),
    timezone    VARCHAR(50),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    room_count  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE INDEX idx_hotels_business ON tenant.hotels (business_id);

--changeset hospio:003-onboarding
-- The platform's 10-step onboarding wizard for a business, and the handover to its owner.
CREATE TABLE tenant.onboarding_drafts
(
    id          VARCHAR(36) PRIMARY KEY,
    business_id VARCHAR(36) NOT NULL REFERENCES tenant.businesses (id),
    step        INTEGER     NOT NULL DEFAULT 1,
    data        TEXT,
    assigned_to VARCHAR(36),
    status      VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP   NOT NULL
);
CREATE TABLE tenant.handovers
(
    id          VARCHAR(36) PRIMARY KEY,
    business_id VARCHAR(36) NOT NULL REFERENCES tenant.businesses (id),
    pack_key    VARCHAR(255),
    sent_at     TIMESTAMP,
    sent_by     VARCHAR(36),
    accepted_at TIMESTAMP,
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP   NOT NULL
);

--changeset hospio:004-setup-templates
-- Platform-owned Hotel setup templates applied to a new hotel (I3/I4, J8).
CREATE TABLE tenant.setup_templates
(
    id          VARCHAR(36)  PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    settings    TEXT         NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);

--changeset hospio:005-row-level-security
-- A business sees itself; hotels are business-scoped; onboarding, handovers and templates are the platform's.
ALTER TABLE tenant.businesses ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.businesses FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_rw ON tenant.businesses USING (app.can_see(id, NULL)) WITH CHECK (app.can_see(id, NULL));
CALL app.protect('tenant', 'hotels', NULL);
CALL app.protect('tenant', 'onboarding_drafts', NULL);
CALL app.protect('tenant', 'handovers', NULL);
ALTER TABLE tenant.setup_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant.setup_templates FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_write ON tenant.setup_templates USING (true) WITH CHECK (app.is_platform());
