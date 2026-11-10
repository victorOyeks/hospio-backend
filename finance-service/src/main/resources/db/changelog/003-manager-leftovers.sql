--liquibase formatted sql

--changeset hospio:010-expense-categories
-- Each hotel's expense categories (seeded on first read with the standard list). Expenses and budgets store the code; the
-- code is the label normalised (FUEL_AND_POWER), so older expenses typed as "Fuel & power" or sent as REPAIRS match it.
CREATE TABLE IF NOT EXISTS finance.expense_categories
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    code        VARCHAR(60)  NOT NULL,
    label       VARCHAR(80)  NOT NULL,
    sort_order  INT          NOT NULL DEFAULT 0,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    CONSTRAINT uq_expense_categories_hotel_code UNIQUE (hotel_id, code)
);
CALL app.protect('finance', 'expense_categories');

--changeset hospio:011-bills
-- Bills: simple recurring payables (supplier, utility). Paying one records a paid expense of the same amount and category.
CREATE TABLE IF NOT EXISTS finance.bills
(
    id              VARCHAR(36)    PRIMARY KEY,
    business_id     VARCHAR(36)    NOT NULL,
    hotel_id        VARCHAR(36)    NOT NULL,
    payee           VARCHAR(150)   NOT NULL,
    category        VARCHAR(60)    NOT NULL,
    amount          NUMERIC(14, 2) NOT NULL,
    due_date        DATE           NOT NULL,
    repeats         VARCHAR(10)    NOT NULL DEFAULT 'NONE',
    status          VARCHAR(12)    NOT NULL DEFAULT 'UNPAID',
    note            VARCHAR(500),
    paid_at         TIMESTAMP,
    paid_by         VARCHAR(36),
    paid_by_name    VARCHAR(150),
    paid_from       VARCHAR(20),
    expense_id      VARCHAR(36),
    next_bill_id    VARCHAR(36),
    created_by_name VARCHAR(150),
    created_at      TIMESTAMP      NOT NULL,
    updated_at      TIMESTAMP      NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_bills_hotel_status_due ON finance.bills (hotel_id, status, due_date);
CALL app.protect('finance', 'bills');
