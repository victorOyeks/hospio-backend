--liquibase formatted sql

--changeset hospio:payment-009-hotel-pos-configs
-- Each hotel's POS provider (MONIEPOINT or OPAY) and its keys (docs/pos-providers.md). Hospio admins enter them; the hotel sees
-- them masked. secrets: the keys as JSON, encrypted with AES-256-GCM (POS_SECRETS_KEY); masked: the same fields as shown to people.
-- pos_paused: Hospio's daily fee unpaid for 3 days in a row (platform-service), POS push refused until it is paid.
SELECT set_config('app.scope', 'platform', true);
CREATE TABLE IF NOT EXISTS payment.hotel_pos_configs
(
    id              VARCHAR(36)  PRIMARY KEY,
    business_id     VARCHAR(36)  NOT NULL,
    hotel_id        VARCHAR(36)  NOT NULL UNIQUE,
    provider        VARCHAR(20),
    secrets         TEXT,
    masked          TEXT,
    pos_paused      BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_by_name VARCHAR(150),
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);
CALL app.protect('payment', 'hotel_pos_configs', 'hotel_id');

-- The fee debits payment-service ran for platform-service: one row per reference, so a repeat never debits twice.
CREATE TABLE IF NOT EXISTS payment.fee_debits
(
    id           VARCHAR(36)    PRIMARY KEY,
    business_id  VARCHAR(36)    NOT NULL,
    hotel_id     VARCHAR(36)    NOT NULL,
    reference    VARCHAR(60)    NOT NULL UNIQUE,
    provider     VARCHAR(20)    NOT NULL,
    amount       NUMERIC(14, 2) NOT NULL,
    status       VARCHAR(20)    NOT NULL,
    provider_ref VARCHAR(100),
    message      VARCHAR(500),
    created_at   TIMESTAMP      NOT NULL,
    updated_at   TIMESTAMP      NOT NULL
);
CALL app.protect('payment', 'fee_debits', 'hotel_id');
