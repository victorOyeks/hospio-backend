--liquibase formatted sql

--changeset hospio:001-organizations
-- Companies that book and are billed later: agreement, credit limit, daily cap, their staff list.
CREATE TABLE folio.organizations
(
    id                 VARCHAR(36)    PRIMARY KEY,
    business_id        VARCHAR(36)    NOT NULL,
    hotel_id           VARCHAR(36)    NOT NULL,
    name               VARCHAR(200)   NOT NULL,
    contact_name       VARCHAR(150),
    contact_phone      VARCHAR(20),
    contact_email      VARCHAR(255),
    address            VARCHAR(255),
    credit_limit       NUMERIC(14, 2) NOT NULL DEFAULT 0,
    daily_cap          NUMERIC(14, 2),
    billing_cycle      VARCHAR(20)    NOT NULL DEFAULT 'MONTHLY',
    payment_terms_days INTEGER        NOT NULL DEFAULT 30,
    agreement_notes    VARCHAR(2000),
    status             VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    created_at         TIMESTAMP      NOT NULL,
    updated_at         TIMESTAMP      NOT NULL
);
CREATE TABLE folio.organization_members
(
    id              VARCHAR(36)  PRIMARY KEY,
    business_id     VARCHAR(36)  NOT NULL,
    hotel_id        VARCHAR(36)  NOT NULL,
    organization_id VARCHAR(36)  NOT NULL REFERENCES folio.organizations (id),
    full_name       VARCHAR(150) NOT NULL,
    staff_ref       VARCHAR(60),
    phone           VARCHAR(20),
    email           VARCHAR(255),
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);

--changeset hospio:002-folios
CREATE TABLE folio.folios
(
    id              VARCHAR(36)    PRIMARY KEY,
    business_id     VARCHAR(36)    NOT NULL,
    hotel_id        VARCHAR(36)    NOT NULL,
    booking_id      VARCHAR(36),
    guest_id        VARCHAR(36),
    guest_name      VARCHAR(150)   NOT NULL,
    guest_phone     VARCHAR(20),
    guest_email     VARCHAR(255),
    room_id         VARCHAR(36),
    room_number     VARCHAR(20),
    organization_id VARCHAR(36),
    status          VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    total_charges   NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total_tax       NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total_discounts NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total_payments  NUMERIC(14, 2) NOT NULL DEFAULT 0,
    balance         NUMERIC(14, 2) NOT NULL DEFAULT 0,
    opened_at       TIMESTAMP      NOT NULL,
    closed_at       TIMESTAMP,
    created_at      TIMESTAMP      NOT NULL,
    updated_at      TIMESTAMP      NOT NULL
);
CREATE INDEX idx_folios_booking ON folio.folios (booking_id);
CREATE INDEX idx_folios_room_status ON folio.folios (room_id, status);
CREATE INDEX idx_folios_hotel_status ON folio.folios (hotel_id, status);

--changeset hospio:003-folio-lines
CREATE TABLE folio.folio_lines
(
    id             VARCHAR(36)    PRIMARY KEY,
    business_id    VARCHAR(36)    NOT NULL,
    hotel_id       VARCHAR(36)    NOT NULL,
    folio_id       VARCHAR(36)    NOT NULL REFERENCES folio.folios (id),
    kind           VARCHAR(20)    NOT NULL,
    source_module  VARCHAR(40),
    source_ref     VARCHAR(80),
    category       VARCHAR(60),
    description    VARCHAR(300)   NOT NULL,
    amount         NUMERIC(14, 2) NOT NULL,
    tax_amount     NUMERIC(14, 2) NOT NULL DEFAULT 0,
    posted_by      VARCHAR(36),
    posted_by_name VARCHAR(150),
    posted_at      TIMESTAMP      NOT NULL,
    voided         BOOLEAN        NOT NULL DEFAULT FALSE,
    void_reason    VARCHAR(300),
    voided_by      VARCHAR(36),
    moved_from     VARCHAR(36),
    created_at     TIMESTAMP      NOT NULL,
    updated_at     TIMESTAMP      NOT NULL
);
CREATE INDEX idx_folio_lines_folio ON folio.folio_lines (folio_id);
CREATE UNIQUE INDEX uq_folio_lines_source ON folio.folio_lines (source_module, source_ref) WHERE source_ref IS NOT NULL;

--changeset hospio:004-invoices-and-org-payments
CREATE TABLE folio.invoices
(
    id              VARCHAR(36)    PRIMARY KEY,
    business_id     VARCHAR(36)    NOT NULL,
    hotel_id        VARCHAR(36)    NOT NULL,
    organization_id VARCHAR(36)    NOT NULL REFERENCES folio.organizations (id),
    number          VARCHAR(20)    NOT NULL UNIQUE,
    period_from     DATE           NOT NULL,
    period_to       DATE           NOT NULL,
    amount          NUMERIC(14, 2) NOT NULL,
    paid_amount     NUMERIC(14, 2) NOT NULL DEFAULT 0,
    due_date        DATE           NOT NULL,
    status          VARCHAR(20)    NOT NULL DEFAULT 'DRAFT',
    pdf_key         VARCHAR(255),
    sent_at         TIMESTAMP,
    created_at      TIMESTAMP      NOT NULL,
    updated_at      TIMESTAMP      NOT NULL
);
CREATE TABLE folio.organization_payments
(
    id              VARCHAR(36)    PRIMARY KEY,
    business_id     VARCHAR(36)    NOT NULL,
    hotel_id        VARCHAR(36)    NOT NULL,
    organization_id VARCHAR(36)    NOT NULL REFERENCES folio.organizations (id),
    invoice_id      VARCHAR(36)    REFERENCES folio.invoices (id),
    amount          NUMERIC(14, 2) NOT NULL,
    method          VARCHAR(20)    NOT NULL,
    reference       VARCHAR(80),
    received_at     TIMESTAMP      NOT NULL,
    recorded_by     VARCHAR(36),
    created_at      TIMESTAMP      NOT NULL,
    updated_at      TIMESTAMP      NOT NULL
);

--changeset hospio:005-pending-discounts
-- A discount over the manager's limit waits for the owner (ApprovalRequested → ApprovalDecided).
CREATE TABLE folio.pending_discounts
(
    id          VARCHAR(36)    PRIMARY KEY,
    business_id VARCHAR(36)    NOT NULL,
    hotel_id    VARCHAR(36)    NOT NULL,
    folio_id    VARCHAR(36)    NOT NULL REFERENCES folio.folios (id),
    approval_id VARCHAR(36)    NOT NULL UNIQUE,
    amount      NUMERIC(14, 2) NOT NULL,
    reason      VARCHAR(300)   NOT NULL,
    status      VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMP      NOT NULL,
    updated_at  TIMESTAMP      NOT NULL
);

--changeset hospio:006-row-level-security
CALL app.protect('folio', 'organizations');
CALL app.protect('folio', 'organization_members');
CALL app.protect('folio', 'folios');
CALL app.protect('folio', 'folio_lines');
CALL app.protect('folio', 'invoices');
CALL app.protect('folio', 'organization_payments');
CALL app.protect('folio', 'pending_discounts');
