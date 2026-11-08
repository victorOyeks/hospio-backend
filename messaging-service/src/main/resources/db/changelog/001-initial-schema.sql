--liquibase formatted sql

--changeset hospio:001-templates
CREATE TABLE messaging.templates
(
    id          VARCHAR(36)   PRIMARY KEY,
    business_id VARCHAR(36)   NOT NULL,
    hotel_id    VARCHAR(36)   NOT NULL,
    key         VARCHAR(60)   NOT NULL,
    channel     VARCHAR(20)   NOT NULL,
    subject     VARCHAR(200),
    body        VARCHAR(2000) NOT NULL,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL,
    UNIQUE (hotel_id, key, channel)
);

--changeset hospio:002-conversations
CREATE TABLE messaging.conversations
(
    id               VARCHAR(36)  PRIMARY KEY,
    business_id      VARCHAR(36)  NOT NULL,
    hotel_id         VARCHAR(36)  NOT NULL,
    kind             VARCHAR(20)  NOT NULL,
    guest_id         VARCHAR(36),
    guest_name       VARCHAR(150),
    guest_contact    VARCHAR(255),
    channel          VARCHAR(20)  NOT NULL,
    subject          VARCHAR(200),
    assigned_to      VARCHAR(36),
    assigned_to_name VARCHAR(150),
    status           VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    last_message_at  TIMESTAMP,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL
);
CREATE INDEX idx_conversations_hotel_status ON messaging.conversations (hotel_id, status, last_message_at);
CREATE TABLE messaging.messages
(
    id              VARCHAR(36)   PRIMARY KEY,
    business_id     VARCHAR(36)   NOT NULL,
    hotel_id        VARCHAR(36)   NOT NULL,
    conversation_id VARCHAR(36)   NOT NULL REFERENCES messaging.conversations (id),
    direction       VARCHAR(10)   NOT NULL,
    sender_id       VARCHAR(36),
    sender_name     VARCHAR(150),
    body            VARCHAR(4000) NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'SENT',
    provider_ref    VARCHAR(100),
    created_at      TIMESTAMP     NOT NULL,
    updated_at      TIMESTAMP     NOT NULL
);
CREATE INDEX idx_messages_conversation ON messaging.messages (conversation_id, created_at);

--changeset hospio:003-notifications
-- In-app notifications: for one user, or for everyone holding a role at the hotel.
CREATE TABLE messaging.notifications
(
    id          VARCHAR(36)   PRIMARY KEY,
    business_id VARCHAR(36)   NOT NULL,
    hotel_id    VARCHAR(36),
    user_id     VARCHAR(36),
    role        VARCHAR(30),
    kind        VARCHAR(40)   NOT NULL,
    title       VARCHAR(200)  NOT NULL,
    body        VARCHAR(1000),
    link        VARCHAR(255),
    event_id    VARCHAR(36),
    read_at     TIMESTAMP,
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL
);
CREATE INDEX idx_notifications_user ON messaging.notifications (user_id, read_at, created_at);
CREATE INDEX idx_notifications_role ON messaging.notifications (hotel_id, role, created_at);

--changeset hospio:004-outbound-queue
-- WhatsApp, SMS and email to send, with retries by the worker.
CREATE TABLE messaging.outbound_messages
(
    id              VARCHAR(36)   PRIMARY KEY,
    business_id     VARCHAR(36)   NOT NULL,
    hotel_id        VARCHAR(36),
    channel         VARCHAR(20)   NOT NULL,
    recipient       VARCHAR(255)  NOT NULL,
    subject         VARCHAR(200),
    body            VARCHAR(4000) NOT NULL,
    kind            VARCHAR(40),
    reference       VARCHAR(80),
    status          VARCHAR(20)   NOT NULL DEFAULT 'QUEUED',
    attempts        INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP     NOT NULL,
    last_error      VARCHAR(500),
    provider_ref    VARCHAR(100),
    sent_at         TIMESTAMP,
    created_at      TIMESTAMP     NOT NULL,
    updated_at      TIMESTAMP     NOT NULL
);
CREATE INDEX idx_outbound_status_next ON messaging.outbound_messages (status, next_attempt_at);

--changeset hospio:005-row-level-security
CALL app.protect('messaging', 'templates');
CALL app.protect('messaging', 'conversations');
CALL app.protect('messaging', 'messages');
CALL app.protect('messaging', 'notifications');
CALL app.protect('messaging', 'outbound_messages');
