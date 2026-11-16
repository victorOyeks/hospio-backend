--liquibase formatted sql

--changeset hospio:platform-008-daily-fees
-- Hospio's daily fee (docs/plan-daily-fees.md). daily_fees: one row per hotel per Lagos day: the sales (lines from each service,
-- VAT excluded), the rate, the fee, what was carried in from earlier unpaid days, what was due and collected, and how the debit
-- went. fee_collections: per hotel, the provider the fee is debited from and whether the debit is switched on (shadow mode while
-- off: statements only), plus the POS pause after 3 unpaid days.
SELECT set_config('app.scope', 'platform', true);
CREATE TABLE IF NOT EXISTS platform.daily_fees
(
    id                VARCHAR(36)    PRIMARY KEY,
    business_id       VARCHAR(36)    NOT NULL,
    hotel_id          VARCHAR(36)    NOT NULL,
    hotel_name        VARCHAR(150),
    fee_date          DATE           NOT NULL,
    sales_total       NUMERIC(14, 2) NOT NULL,
    sales_lines       TEXT,
    rate              NUMERIC(7, 4)  NOT NULL,
    fee               NUMERIC(14, 2) NOT NULL,
    carried_in        NUMERIC(14, 2) NOT NULL DEFAULT 0,
    due               NUMERIC(14, 2) NOT NULL,
    collected         NUMERIC(14, 2) NOT NULL DEFAULT 0,
    status            VARCHAR(20)    NOT NULL,
    provider          VARCHAR(20),
    debit_ref         VARCHAR(60),
    attempts          INT            NOT NULL DEFAULT 0,
    last_error        VARCHAR(500),
    statement_sent_at TIMESTAMP,
    debited_at        TIMESTAMP,
    created_at        TIMESTAMP      NOT NULL,
    updated_at        TIMESTAMP      NOT NULL,
    CONSTRAINT uq_daily_fees_hotel_date UNIQUE (hotel_id, fee_date)
);
CREATE INDEX IF NOT EXISTS idx_daily_fees_business_date ON platform.daily_fees (business_id, fee_date DESC);
CREATE INDEX IF NOT EXISTS idx_daily_fees_status ON platform.daily_fees (status, fee_date);
CALL app.protect('platform', 'daily_fees', 'hotel_id');

CREATE TABLE IF NOT EXISTS platform.fee_collections
(
    id              VARCHAR(36)  PRIMARY KEY,
    business_id     VARCHAR(36)  NOT NULL,
    hotel_id        VARCHAR(36)  NOT NULL UNIQUE,
    provider        VARCHAR(20),
    debit_enabled   BOOLEAN      NOT NULL DEFAULT FALSE,
    changed_by      VARCHAR(36),
    changed_by_name VARCHAR(150),
    changed_at      TIMESTAMP,
    pos_paused      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);
CALL app.protect('platform', 'fee_collections', 'hotel_id');
