--liquibase formatted sql

--changeset hospio:001-pos-terminals
-- A POS can serve many roles; a role has exactly one POS (moving it is explicit).
CREATE TABLE payment.pos_terminals
(
    id           VARCHAR(36)  PRIMARY KEY,
    business_id  VARCHAR(36)  NOT NULL,
    hotel_id     VARCHAR(36)  NOT NULL,
    name         VARCHAR(100) NOT NULL,
    provider     VARCHAR(40)  NOT NULL,
    terminal_ref VARCHAR(100) NOT NULL,
    status       VARCHAR(20)  NOT NULL DEFAULT 'ONLINE',
    last_seen_at TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);
CREATE TABLE payment.pos_role_assignments
(
    id          VARCHAR(36) PRIMARY KEY,
    business_id VARCHAR(36) NOT NULL,
    hotel_id    VARCHAR(36) NOT NULL,
    role        VARCHAR(30) NOT NULL,
    terminal_id VARCHAR(36) NOT NULL REFERENCES payment.pos_terminals (id),
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP   NOT NULL,
    UNIQUE (hotel_id, role)
);

--changeset hospio:002-payment-requests
CREATE TABLE payment.payment_requests
(
    id                 VARCHAR(36)    PRIMARY KEY,
    business_id        VARCHAR(36)    NOT NULL,
    hotel_id           VARCHAR(36)    NOT NULL,
    folio_id           VARCHAR(36),
    booking_id         VARCHAR(36),
    guest_name         VARCHAR(150),
    guest_phone        VARCHAR(20),
    guest_email        VARCHAR(255),
    requested_by       VARCHAR(36)    NOT NULL,
    requested_by_name  VARCHAR(150),
    role               VARCHAR(30)    NOT NULL,
    method             VARCHAR(20)    NOT NULL,
    amount             NUMERIC(14, 2) NOT NULL,
    currency           VARCHAR(3)     NOT NULL DEFAULT 'NGN',
    status             VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    terminal_id        VARCHAR(36),
    provider           VARCHAR(40),
    provider_ref       VARCHAR(100),
    description        VARCHAR(300),
    receipt_preference VARCHAR(20),
    failure_reason     VARCHAR(300),
    resolved_at        TIMESTAMP,
    verified_at        TIMESTAMP,
    created_at         TIMESTAMP      NOT NULL,
    updated_at         TIMESTAMP      NOT NULL
);
CREATE INDEX idx_payment_requests_hotel_status ON payment.payment_requests (hotel_id, status);
CREATE INDEX idx_payment_requests_folio ON payment.payment_requests (folio_id);
CREATE INDEX idx_payment_requests_provider_ref ON payment.payment_requests (provider_ref);

--changeset hospio:003-refunds-disputes-receipts
CREATE TABLE payment.refunds
(
    id           VARCHAR(36)    PRIMARY KEY,
    business_id  VARCHAR(36)    NOT NULL,
    hotel_id     VARCHAR(36)    NOT NULL,
    payment_id   VARCHAR(36)    NOT NULL REFERENCES payment.payment_requests (id),
    folio_id     VARCHAR(36),
    amount       NUMERIC(14, 2) NOT NULL,
    reason       VARCHAR(300)   NOT NULL,
    status       VARCHAR(20)    NOT NULL DEFAULT 'PENDING_APPROVAL',
    requested_by VARCHAR(36)    NOT NULL,
    approved_by  VARCHAR(36),
    approval_id  VARCHAR(36),
    decided_at   TIMESTAMP,
    created_at   TIMESTAMP      NOT NULL,
    updated_at   TIMESTAMP      NOT NULL
);
CREATE TABLE payment.disputes
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    payment_id  VARCHAR(36)  NOT NULL REFERENCES payment.payment_requests (id),
    reason      VARCHAR(500) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    resolution  VARCHAR(500),
    opened_by   VARCHAR(36),
    resolved_by VARCHAR(36),
    resolved_at TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE TABLE payment.receipts
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    payment_id  VARCHAR(36)  NOT NULL REFERENCES payment.payment_requests (id),
    number      VARCHAR(20)  NOT NULL UNIQUE,
    storage_key VARCHAR(255),
    sent_to     VARCHAR(255),
    sent_via    VARCHAR(20),
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);

--changeset hospio:004-row-level-security
CALL app.protect('payment', 'pos_terminals');
CALL app.protect('payment', 'pos_role_assignments');
CALL app.protect('payment', 'payment_requests');
CALL app.protect('payment', 'refunds');
CALL app.protect('payment', 'disputes');
CALL app.protect('payment', 'receipts');
