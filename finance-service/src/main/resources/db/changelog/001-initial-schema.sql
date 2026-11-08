--liquibase formatted sql

--changeset hospio:001-tills
-- One till per role per shift: opened with a float, fed by approved cash payments, counted and closed.
CREATE TABLE finance.tills
(
    id             VARCHAR(36)    PRIMARY KEY,
    business_id    VARCHAR(36)    NOT NULL,
    hotel_id       VARCHAR(36)    NOT NULL,
    role           VARCHAR(30)    NOT NULL,
    opened_by      VARCHAR(36)    NOT NULL,
    opened_by_name VARCHAR(150),
    opened_at      TIMESTAMP      NOT NULL,
    opening_float  NUMERIC(14, 2) NOT NULL DEFAULT 0,
    cash_in        NUMERIC(14, 2) NOT NULL DEFAULT 0,
    expected_cash  NUMERIC(14, 2) NOT NULL DEFAULT 0,
    counted_cash   NUMERIC(14, 2),
    variance       NUMERIC(14, 2),
    status         VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    closed_at      TIMESTAMP,
    closed_by      VARCHAR(36),
    note           VARCHAR(500),
    created_at     TIMESTAMP      NOT NULL,
    updated_at     TIMESTAMP      NOT NULL
);
CREATE INDEX idx_tills_hotel_status ON finance.tills (hotel_id, status);
CREATE TABLE finance.till_entries
(
    id          VARCHAR(36)    PRIMARY KEY,
    business_id VARCHAR(36)    NOT NULL,
    hotel_id    VARCHAR(36)    NOT NULL,
    till_id     VARCHAR(36)    NOT NULL REFERENCES finance.tills (id),
    payment_id  VARCHAR(36)    UNIQUE,
    amount      NUMERIC(14, 2) NOT NULL,
    method      VARCHAR(20)    NOT NULL,
    description VARCHAR(300),
    created_at  TIMESTAMP      NOT NULL,
    updated_at  TIMESTAMP      NOT NULL
);

--changeset hospio:002-revenue-ledger
-- Every approved payment, by method, so today's money and the P&L need no other service.
CREATE TABLE finance.revenue_entries
(
    id            VARCHAR(36)    PRIMARY KEY,
    business_id   VARCHAR(36)    NOT NULL,
    hotel_id      VARCHAR(36)    NOT NULL,
    payment_id    VARCHAR(36)    NOT NULL UNIQUE,
    business_date DATE           NOT NULL,
    amount        NUMERIC(14, 2) NOT NULL,
    method        VARCHAR(20)    NOT NULL,
    role          VARCHAR(30),
    folio_id      VARCHAR(36),
    matched       BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP      NOT NULL,
    updated_at    TIMESTAMP      NOT NULL
);
CREATE INDEX idx_revenue_hotel_date ON finance.revenue_entries (hotel_id, business_date);

--changeset hospio:003-expenses-budgets
CREATE TABLE finance.expenses
(
    id             VARCHAR(36)    PRIMARY KEY,
    business_id    VARCHAR(36)    NOT NULL,
    hotel_id       VARCHAR(36)    NOT NULL,
    category       VARCHAR(60)    NOT NULL,
    amount         NUMERIC(14, 2) NOT NULL,
    description    VARCHAR(500)   NOT NULL,
    paid_from      VARCHAR(20),
    receipt_key    VARCHAR(255),
    expense_date   DATE           NOT NULL,
    status         VARCHAR(20)    NOT NULL DEFAULT 'RECORDED',
    approval_id    VARCHAR(36)    UNIQUE,
    requested_by   VARCHAR(36),
    requested_by_name VARCHAR(150),
    decided_by     VARCHAR(36),
    decision_note  VARCHAR(500),
    created_at     TIMESTAMP      NOT NULL,
    updated_at     TIMESTAMP      NOT NULL
);
CREATE INDEX idx_expenses_hotel_date ON finance.expenses (hotel_id, expense_date);
CREATE TABLE finance.budgets
(
    id          VARCHAR(36)    PRIMARY KEY,
    business_id VARCHAR(36)    NOT NULL,
    hotel_id    VARCHAR(36)    NOT NULL,
    category    VARCHAR(60)    NOT NULL,
    month       VARCHAR(7)     NOT NULL,
    amount      NUMERIC(14, 2) NOT NULL,
    created_at  TIMESTAMP      NOT NULL,
    updated_at  TIMESTAMP      NOT NULL,
    UNIQUE (hotel_id, category, month)
);

--changeset hospio:004-night-audit-and-banking
CREATE TABLE finance.night_audits
(
    id             VARCHAR(36)    PRIMARY KEY,
    business_id    VARCHAR(36)    NOT NULL,
    hotel_id       VARCHAR(36)    NOT NULL,
    business_date  DATE           NOT NULL,
    status         VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    checklist      TEXT,
    revenue        NUMERIC(14, 2),
    cash_counted   NUMERIC(14, 2),
    variance       NUMERIC(14, 2),
    rooms_occupied INTEGER,
    report_key     VARCHAR(255),
    closed_by      VARCHAR(36),
    closed_at      TIMESTAMP,
    note           VARCHAR(1000),
    created_at     TIMESTAMP      NOT NULL,
    updated_at     TIMESTAMP      NOT NULL,
    UNIQUE (hotel_id, business_date)
);
CREATE TABLE finance.bank_deposits
(
    id           VARCHAR(36)    PRIMARY KEY,
    business_id  VARCHAR(36)    NOT NULL,
    hotel_id     VARCHAR(36)    NOT NULL,
    amount       NUMERIC(14, 2) NOT NULL,
    bank_account VARCHAR(100),
    reference    VARCHAR(80),
    banked_by    VARCHAR(36),
    banked_at    TIMESTAMP      NOT NULL,
    created_at   TIMESTAMP      NOT NULL,
    updated_at   TIMESTAMP      NOT NULL
);

--changeset hospio:005-workforce
CREATE TABLE finance.shifts
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    user_id     VARCHAR(36)  NOT NULL,
    user_name   VARCHAR(150),
    role        VARCHAR(30)  NOT NULL,
    shift_date  DATE         NOT NULL,
    starts_at   TIME         NOT NULL,
    ends_at     TIME         NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED',
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE INDEX idx_shifts_hotel_date ON finance.shifts (hotel_id, shift_date);
CREATE TABLE finance.shift_requests
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    shift_id    VARCHAR(36)  NOT NULL REFERENCES finance.shifts (id),
    kind        VARCHAR(20)  NOT NULL,
    user_id     VARCHAR(36)  NOT NULL,
    user_name   VARCHAR(150),
    with_user_id VARCHAR(36),
    reason      VARCHAR(300),
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    decided_by  VARCHAR(36),
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE TABLE finance.leave_requests
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    user_id     VARCHAR(36)  NOT NULL,
    user_name   VARCHAR(150),
    from_date   DATE         NOT NULL,
    to_date     DATE         NOT NULL,
    reason      VARCHAR(300),
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    decided_by  VARCHAR(36),
    decision_note VARCHAR(300),
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE TABLE finance.clock_entries
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    user_id     VARCHAR(36)  NOT NULL,
    user_name   VARCHAR(150),
    clock_in    TIMESTAMP    NOT NULL,
    clock_out   TIMESTAMP,
    note        VARCHAR(300),
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE INDEX idx_clock_entries_user ON finance.clock_entries (user_id, clock_in);

--changeset hospio:006-row-level-security
CALL app.protect('finance', 'tills');
CALL app.protect('finance', 'till_entries');
CALL app.protect('finance', 'revenue_entries');
CALL app.protect('finance', 'expenses');
CALL app.protect('finance', 'budgets');
CALL app.protect('finance', 'night_audits');
CALL app.protect('finance', 'bank_deposits');
CALL app.protect('finance', 'shifts');
CALL app.protect('finance', 'shift_requests');
CALL app.protect('finance', 'leave_requests');
CALL app.protect('finance', 'clock_entries');
