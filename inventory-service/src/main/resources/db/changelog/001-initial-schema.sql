--liquibase formatted sql

--changeset hospio:001-stores-items-levels
CREATE TABLE inventory.stores
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    kind        VARCHAR(30)  NOT NULL DEFAULT 'MAIN',
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE TABLE inventory.stock_items
(
    id          VARCHAR(36)    PRIMARY KEY,
    business_id VARCHAR(36)    NOT NULL,
    hotel_id    VARCHAR(36)    NOT NULL,
    name        VARCHAR(150)   NOT NULL,
    category    VARCHAR(60),
    unit        VARCHAR(20)    NOT NULL DEFAULT 'pcs',
    min_level   NUMERIC(14, 2) NOT NULL DEFAULT 0,
    unit_cost   NUMERIC(14, 2),
    supplier_id VARCHAR(36),
    active      BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP      NOT NULL,
    updated_at  TIMESTAMP      NOT NULL
);
CREATE TABLE inventory.stock_levels
(
    id          VARCHAR(36)    PRIMARY KEY,
    business_id VARCHAR(36)    NOT NULL,
    hotel_id    VARCHAR(36)    NOT NULL,
    item_id     VARCHAR(36)    NOT NULL REFERENCES inventory.stock_items (id),
    store_id    VARCHAR(36)    NOT NULL REFERENCES inventory.stores (id),
    quantity    NUMERIC(14, 2) NOT NULL DEFAULT 0,
    created_at  TIMESTAMP      NOT NULL,
    updated_at  TIMESTAMP      NOT NULL,
    UNIQUE (item_id, store_id)
);
CREATE TABLE inventory.stock_movements
(
    id          VARCHAR(36)    PRIMARY KEY,
    business_id VARCHAR(36)    NOT NULL,
    hotel_id    VARCHAR(36)    NOT NULL,
    item_id     VARCHAR(36)    NOT NULL,
    store_id    VARCHAR(36)    NOT NULL,
    kind        VARCHAR(20)    NOT NULL,
    quantity    NUMERIC(14, 2) NOT NULL,
    reference   VARCHAR(80),
    note        VARCHAR(300),
    by_user     VARCHAR(36),
    created_at  TIMESTAMP      NOT NULL,
    updated_at  TIMESTAMP      NOT NULL
);
CREATE INDEX idx_stock_movements_item ON inventory.stock_movements (item_id, created_at);

--changeset hospio:002-requests
-- A team (kitchen, bar, housekeeping, maintenance) asks the store; the store issues or declines.
CREATE TABLE inventory.stock_requests
(
    id           VARCHAR(36)  PRIMARY KEY,
    business_id  VARCHAR(36)  NOT NULL,
    hotel_id     VARCHAR(36)  NOT NULL,
    from_team    VARCHAR(30)  NOT NULL,
    store_id     VARCHAR(36),
    lines        TEXT         NOT NULL,
    note         VARCHAR(300),
    status       VARCHAR(20)  NOT NULL DEFAULT 'REQUESTED',
    requested_by VARCHAR(36),
    issued_by    VARCHAR(36),
    issued_at    TIMESTAMP,
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);

--changeset hospio:003-suppliers-orders-deliveries
CREATE TABLE inventory.suppliers
(
    id            VARCHAR(36)  PRIMARY KEY,
    business_id   VARCHAR(36)  NOT NULL,
    hotel_id      VARCHAR(36)  NOT NULL,
    name          VARCHAR(150) NOT NULL,
    contact_name  VARCHAR(150),
    phone         VARCHAR(20),
    email         VARCHAR(255),
    payment_terms VARCHAR(100),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL
);
CREATE TABLE inventory.purchase_orders
(
    id          VARCHAR(36)    PRIMARY KEY,
    business_id VARCHAR(36)    NOT NULL,
    hotel_id    VARCHAR(36)    NOT NULL,
    number      VARCHAR(20)    NOT NULL,
    supplier_id VARCHAR(36)    NOT NULL REFERENCES inventory.suppliers (id),
    store_id    VARCHAR(36),
    lines       TEXT           NOT NULL,
    total       NUMERIC(14, 2) NOT NULL DEFAULT 0,
    status      VARCHAR(20)    NOT NULL DEFAULT 'DRAFT',
    approval_id VARCHAR(36)    UNIQUE,
    expected_on DATE,
    created_by  VARCHAR(36),
    sent_at     TIMESTAMP,
    created_at  TIMESTAMP      NOT NULL,
    updated_at  TIMESTAMP      NOT NULL
);
CREATE TABLE inventory.deliveries
(
    id                VARCHAR(36)    PRIMARY KEY,
    business_id       VARCHAR(36)    NOT NULL,
    hotel_id          VARCHAR(36)    NOT NULL,
    purchase_order_id VARCHAR(36)    REFERENCES inventory.purchase_orders (id),
    supplier_name     VARCHAR(150),
    store_id          VARCHAR(36)    NOT NULL,
    lines             TEXT           NOT NULL,
    total             NUMERIC(14, 2) NOT NULL DEFAULT 0,
    note              VARCHAR(300),
    received_by       VARCHAR(36),
    created_at        TIMESTAMP      NOT NULL,
    updated_at        TIMESTAMP      NOT NULL
);

--changeset hospio:004-counts
CREATE TABLE inventory.stock_counts
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    store_id    VARCHAR(36)  NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    lines       TEXT,
    started_by  VARCHAR(36),
    counted_by  VARCHAR(36),
    approved_by VARCHAR(36),
    closed_at   TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);

--changeset hospio:005-row-level-security
CALL app.protect('inventory', 'stores');
CALL app.protect('inventory', 'stock_items');
CALL app.protect('inventory', 'stock_levels');
CALL app.protect('inventory', 'stock_movements');
CALL app.protect('inventory', 'stock_requests');
CALL app.protect('inventory', 'suppliers');
CALL app.protect('inventory', 'purchase_orders');
CALL app.protect('inventory', 'deliveries');
CALL app.protect('inventory', 'stock_counts');
