--liquibase formatted sql

--changeset hospio:messaging-003-deliveries
-- Every email, SMS and WhatsApp the platform sends (docs/email.md): one row per delivery with what the provider answered.
-- The message body is not kept (temporary passwords travel in it). business_id is empty for platform mail (H.M.S staff,
-- marketers), which only H.M.S sees. event_id makes a redelivered event a no-op.
CREATE TABLE messaging.deliveries
(
    id                  VARCHAR(36)  PRIMARY KEY,
    business_id         VARCHAR(36),
    hotel_id            VARCHAR(36),
    event_id            VARCHAR(36),
    channel             VARCHAR(20)  NOT NULL,
    to_address          VARCHAR(255) NOT NULL,
    subject             VARCHAR(300),
    kind                VARCHAR(40),
    ref                 VARCHAR(80),
    from_name           VARCHAR(200),
    reply_to            VARCHAR(255),
    status              VARCHAR(30)  NOT NULL,
    attempts            INTEGER      NOT NULL DEFAULT 0,
    provider_message_id VARCHAR(255),
    error               VARCHAR(500),
    sent_at             TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX uq_deliveries_event ON messaging.deliveries (event_id);
CREATE INDEX idx_deliveries_hotel_created ON messaging.deliveries (hotel_id, created_at);
CREATE INDEX idx_deliveries_status ON messaging.deliveries (status, created_at);
CALL app.protect('messaging', 'deliveries');

--changeset hospio:messaging-003-guest-inbox
-- Guest conversations (Manager · Messaging, Front office · Messages): per booking (or guest contact), with the stay's
-- details for template placeholders, who it is assigned to, the direction of the last message (waiting = the guest wrote
-- last) and when it was marked done.
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS booking_id VARCHAR(36);
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS booking_ref VARCHAR(40);
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS room_number VARCHAR(20);
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS room_type VARCHAR(100);
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS check_in DATE;
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS check_out DATE;
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS guest_email VARCHAR(255);
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS guest_phone VARCHAR(40);
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS last_direction VARCHAR(10);
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS done_at TIMESTAMP;
ALTER TABLE messaging.conversations ADD COLUMN IF NOT EXISTS done_by_name VARCHAR(150);
CREATE INDEX IF NOT EXISTS idx_conversations_guest_inbox ON messaging.conversations (hotel_id, kind, status, last_message_at);
CREATE INDEX IF NOT EXISTS idx_conversations_booking ON messaging.conversations (hotel_id, booking_id);
-- A message out may be by email (sent for real), WhatsApp or SMS (not connected yet); its status follows the delivery.
ALTER TABLE messaging.messages ADD COLUMN IF NOT EXISTS channel VARCHAR(20);
ALTER TABLE messaging.messages ADD COLUMN IF NOT EXISTS error VARCHAR(500);
-- Templates staff pick from (purpose MESSAGE, with a name) next to the automatic ones (purpose AUTO: RECEIPT, STAY_SUMMARY).
ALTER TABLE messaging.templates ADD COLUMN IF NOT EXISTS name VARCHAR(100);
ALTER TABLE messaging.templates ADD COLUMN IF NOT EXISTS purpose VARCHAR(20) NOT NULL DEFAULT 'AUTO';
ALTER TABLE messaging.templates ALTER COLUMN body TYPE VARCHAR(4000);
