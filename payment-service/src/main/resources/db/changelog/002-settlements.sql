--liquibase formatted sql

--changeset hospio:005-settlements
-- A day's approved card and transfer payments per business and provider, net of the provider's fee and H.M.S's share.
CREATE TABLE payment.settlements
(
    id              VARCHAR(36)    PRIMARY KEY,
    business_id     VARCHAR(36)    NOT NULL,
    hotel_id        VARCHAR(36),
    business_name   VARCHAR(150),
    provider        VARCHAR(40)    NOT NULL,
    period_date     DATE           NOT NULL,
    gross           NUMERIC(14, 2) NOT NULL,
    provider_fees   NUMERIC(14, 2) NOT NULL,
    hms_share       NUMERIC(14, 2) NOT NULL,
    net             NUMERIC(14, 2) NOT NULL,
    payment_count   INTEGER        NOT NULL DEFAULT 0,
    status          VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    settled_at      TIMESTAMP,
    payout_batch_id VARCHAR(36),
    failure_reason  VARCHAR(500),
    created_at      TIMESTAMP      NOT NULL,
    updated_at      TIMESTAMP      NOT NULL,
    UNIQUE (business_id, provider, period_date)
);
CREATE INDEX idx_settlements_status_date ON payment.settlements (status, period_date DESC);
CREATE INDEX idx_settlements_batch ON payment.settlements (payout_batch_id);

--changeset hospio:006-payout-batches
-- Platform-only: a batch spans businesses, so it carries no business_id.
CREATE TABLE payment.payout_batches
(
    id               VARCHAR(36)    PRIMARY KEY,
    ref              VARCHAR(20)    NOT NULL UNIQUE,
    created_by       VARCHAR(36),
    created_by_name  VARCHAR(150),
    status           VARCHAR(20)    NOT NULL DEFAULT 'PROCESSING',
    total            NUMERIC(14, 2) NOT NULL,
    business_count   INTEGER        NOT NULL DEFAULT 0,
    settlement_count INTEGER        NOT NULL DEFAULT 0,
    paid_at          TIMESTAMP,
    paid_on          DATE,
    paid_reference   VARCHAR(100),
    created_at       TIMESTAMP      NOT NULL,
    updated_at       TIMESTAMP      NOT NULL
);

--changeset hospio:007-failed-payouts
CREATE TABLE payment.failed_payouts
(
    id              VARCHAR(36)    PRIMARY KEY,
    business_id     VARCHAR(36)    NOT NULL,
    hotel_id        VARCHAR(36),
    settlement_id   VARCHAR(36)    NOT NULL REFERENCES payment.settlements (id),
    payout_batch_id VARCHAR(36),
    business_name   VARCHAR(150),
    amount          NUMERIC(14, 2) NOT NULL,
    bank_label      VARCHAR(150),
    reason          VARCHAR(500)   NOT NULL,
    attempts        INTEGER        NOT NULL DEFAULT 0,
    last_tried_at   TIMESTAMP,
    resolved_at     TIMESTAMP,
    resolved_by     VARCHAR(36),
    created_at      TIMESTAMP      NOT NULL,
    updated_at      TIMESTAMP      NOT NULL
);
CREATE INDEX idx_failed_payouts_open ON payment.failed_payouts (resolved_at, created_at DESC);

--changeset hospio:008-settlements-row-level-security
-- Settlements and failed payouts belong to a business (its owner may see them; the platform sees all); payout
-- batches have no business and are read only through SUPER_ADMIN/FINANCE_ADMIN routes, so platform scope only.
CALL app.protect('payment', 'settlements', NULL);
CALL app.protect('payment', 'failed_payouts', NULL);
ALTER TABLE payment.payout_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment.payout_batches FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_rw ON payment.payout_batches USING (app.is_platform()) WITH CHECK (app.is_platform());
