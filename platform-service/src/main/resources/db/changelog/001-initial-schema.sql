--liquibase formatted sql

--changeset hospio:001-activity-log
-- Every write in Hospio, from every service. Manager, owner and H.M.S logs all read this.
CREATE TABLE platform.activity_log
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36),
    hotel_id    VARCHAR(36),
    event_id    VARCHAR(36)  UNIQUE,
    event_type  VARCHAR(60)  NOT NULL,
    topic       VARCHAR(80),
    actor_id    VARCHAR(36),
    actor_name  VARCHAR(150),
    summary     VARCHAR(500) NOT NULL,
    payload     TEXT,
    occurred_at TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE INDEX idx_activity_hotel_time ON platform.activity_log (hotel_id, occurred_at DESC);
CREATE INDEX idx_activity_business_time ON platform.activity_log (business_id, occurred_at DESC);

--changeset hospio:002-approvals
CREATE TABLE platform.approvals
(
    id              VARCHAR(36)    PRIMARY KEY,
    business_id     VARCHAR(36)    NOT NULL,
    hotel_id        VARCHAR(36),
    source_module   VARCHAR(40)    NOT NULL,
    source_ref      VARCHAR(80),
    kind            VARCHAR(40)    NOT NULL,
    amount          NUMERIC(14, 2),
    requester_id    VARCHAR(36),
    requester_name  VARCHAR(150),
    approver_level  VARCHAR(20)    NOT NULL,
    description     VARCHAR(500),
    status          VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    decided_by      VARCHAR(36),
    decided_by_name VARCHAR(150),
    note            VARCHAR(500),
    decided_at      TIMESTAMP,
    created_at      TIMESTAMP      NOT NULL,
    updated_at      TIMESTAMP      NOT NULL
);
CREATE INDEX idx_approvals_business_status ON platform.approvals (business_id, status, created_at);

--changeset hospio:003-reports
CREATE TABLE platform.reports
(
    id           VARCHAR(36)  PRIMARY KEY,
    business_id  VARCHAR(36)  NOT NULL,
    hotel_id     VARCHAR(36),
    kind         VARCHAR(40)  NOT NULL,
    period_from  DATE,
    period_to    DATE,
    status       VARCHAR(20)  NOT NULL DEFAULT 'READY',
    storage_key  VARCHAR(255),
    schedule     VARCHAR(20),
    recipients   VARCHAR(500),
    requested_by VARCHAR(36),
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);

--changeset hospio:004-platform-business-data
-- Platform-only tables (fees, marketers, support, health, edits made by H.M.S). business_id scopes them to the client business.
CREATE TABLE platform.fee_rules
(
    id          VARCHAR(36)    PRIMARY KEY,
    business_id VARCHAR(36)    NOT NULL,
    hotel_id    VARCHAR(36),
    model       VARCHAR(30)    NOT NULL,
    value       NUMERIC(14, 2) NOT NULL,
    starts_on   DATE           NOT NULL,
    ends_on     DATE,
    created_at  TIMESTAMP      NOT NULL,
    updated_at  TIMESTAMP      NOT NULL
);
CREATE TABLE platform.fee_statements
(
    id          VARCHAR(36)    PRIMARY KEY,
    business_id VARCHAR(36)    NOT NULL,
    hotel_id    VARCHAR(36),
    month       VARCHAR(7)     NOT NULL,
    base_amount NUMERIC(14, 2) NOT NULL,
    fee_amount  NUMERIC(14, 2) NOT NULL,
    paid_amount NUMERIC(14, 2) NOT NULL DEFAULT 0,
    status      VARCHAR(20)    NOT NULL DEFAULT 'DUE',
    created_at  TIMESTAMP      NOT NULL,
    updated_at  TIMESTAMP      NOT NULL,
    UNIQUE (business_id, month)
);
CREATE TABLE platform.marketers
(
    id          VARCHAR(36)   PRIMARY KEY,
    business_id VARCHAR(36)   NOT NULL,
    hotel_id    VARCHAR(36),
    user_id     VARCHAR(36)   NOT NULL,
    full_name   VARCHAR(150)  NOT NULL,
    share_pct   NUMERIC(5, 2) NOT NULL DEFAULT 20,
    months      INTEGER       NOT NULL DEFAULT 24,
    wht_pct     NUMERIC(5, 2) NOT NULL DEFAULT 5,
    status      VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    started_on  DATE,
    ended_on    DATE,
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL
);
CREATE TABLE platform.commissions
(
    id           VARCHAR(36)    PRIMARY KEY,
    business_id  VARCHAR(36)    NOT NULL,
    hotel_id     VARCHAR(36),
    marketer_id  VARCHAR(36)    NOT NULL REFERENCES platform.marketers (id),
    month        VARCHAR(7)     NOT NULL,
    base_amount  NUMERIC(14, 2) NOT NULL,
    gross        NUMERIC(14, 2) NOT NULL,
    wht          NUMERIC(14, 2) NOT NULL,
    net          NUMERIC(14, 2) NOT NULL,
    status       VARCHAR(20)    NOT NULL DEFAULT 'DUE',
    paid_at      TIMESTAMP,
    created_at   TIMESTAMP      NOT NULL,
    updated_at   TIMESTAMP      NOT NULL
);
CREATE TABLE platform.support_tickets
(
    id          VARCHAR(36)   PRIMARY KEY,
    business_id VARCHAR(36)   NOT NULL,
    hotel_id    VARCHAR(36),
    subject     VARCHAR(200)  NOT NULL,
    body        VARCHAR(2000),
    priority    VARCHAR(10)   NOT NULL DEFAULT 'NORMAL',
    status      VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    opened_by   VARCHAR(36),
    assigned_to VARCHAR(36),
    resolution  VARCHAR(2000),
    resolved_at TIMESTAMP,
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL
);
CREATE TABLE platform.health_scores
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL UNIQUE,
    hotel_id    VARCHAR(36),
    score       INTEGER      NOT NULL,
    factors     TEXT,
    computed_at TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE TABLE platform.platform_changes
(
    id          VARCHAR(36)   PRIMARY KEY,
    business_id VARCHAR(36)   NOT NULL,
    hotel_id    VARCHAR(36),
    area        VARCHAR(60)   NOT NULL,
    description VARCHAR(1000) NOT NULL,
    reason      VARCHAR(500)  NOT NULL,
    before_json TEXT,
    after_json  TEXT,
    made_by     VARCHAR(36),
    made_by_name VARCHAR(150),
    undo_until  TIMESTAMP     NOT NULL,
    undone_at   TIMESTAMP,
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL
);

--changeset hospio:005-row-level-security
-- The activity log and approvals are business-scoped (owner sees all hotels, manager their hotel).
-- Platform-only tables are visible to the business they concern (owner's Changes by H.M.S, fee statements) and the platform.
CALL app.protect('platform', 'activity_log');
CALL app.protect('platform', 'approvals');
CALL app.protect('platform', 'reports');
CALL app.protect('platform', 'fee_rules', NULL);
CALL app.protect('platform', 'fee_statements', NULL);
CALL app.protect('platform', 'marketers', NULL);
CALL app.protect('platform', 'commissions', NULL);
CALL app.protect('platform', 'support_tickets', NULL);
CALL app.protect('platform', 'health_scores', NULL);
CALL app.protect('platform', 'platform_changes', NULL);
