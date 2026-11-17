--liquibase formatted sql

--changeset hospio:payment-010-hotel-pos-keys-per-provider
-- A hotel can hold keys for more than one POS provider (Moniepoint and OPay terminals side by side): one row per hotel and
-- provider. Each card payment uses the keys of its terminal's provider; the daily fee debit uses the provider chosen for it.
-- The keys already saved (one provider per hotel in hotel_pos_configs) move here unchanged, still encrypted; hotel_pos_configs
-- keeps only the POS pause.
SELECT set_config('app.scope', 'platform', true);
CREATE TABLE IF NOT EXISTS payment.hotel_pos_keys
(
    id              VARCHAR(36)  PRIMARY KEY,
    business_id     VARCHAR(36)  NOT NULL,
    hotel_id        VARCHAR(36)  NOT NULL,
    provider        VARCHAR(20)  NOT NULL,
    secrets         TEXT         NOT NULL,
    masked          TEXT         NOT NULL,
    updated_by_name VARCHAR(150),
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT uq_hotel_pos_keys UNIQUE (hotel_id, provider)
);
CALL app.protect('payment', 'hotel_pos_keys', 'hotel_id');
INSERT INTO payment.hotel_pos_keys (id, business_id, hotel_id, provider, secrets, masked, updated_by_name, created_at, updated_at)
SELECT gen_random_uuid()::text, business_id, hotel_id, provider, secrets, masked, updated_by_name, created_at, updated_at
FROM payment.hotel_pos_configs
WHERE provider IS NOT NULL AND secrets IS NOT NULL
ON CONFLICT (hotel_id, provider) DO NOTHING;
UPDATE payment.hotel_pos_configs SET provider = NULL, secrets = NULL, masked = NULL WHERE provider IS NOT NULL;
