--liquibase formatted sql

--changeset hospio:001-outlets-and-tables
CREATE TABLE fnb.outlets
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    kind        VARCHAR(20)  NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE TABLE fnb.tables
(
    id          VARCHAR(36) PRIMARY KEY,
    business_id VARCHAR(36) NOT NULL,
    hotel_id    VARCHAR(36) NOT NULL,
    outlet_id   VARCHAR(36) NOT NULL REFERENCES fnb.outlets (id),
    number      VARCHAR(20) NOT NULL,
    seats       INTEGER     NOT NULL DEFAULT 4,
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP   NOT NULL
);

--changeset hospio:002-menu
CREATE TABLE fnb.menu_items
(
    id          VARCHAR(36)    PRIMARY KEY,
    business_id VARCHAR(36)    NOT NULL,
    hotel_id    VARCHAR(36)    NOT NULL,
    outlet_id   VARCHAR(36)    NOT NULL REFERENCES fnb.outlets (id),
    category    VARCHAR(60)    NOT NULL,
    name        VARCHAR(150)   NOT NULL,
    description VARCHAR(500),
    price       NUMERIC(14, 2) NOT NULL,
    tax_rate    NUMERIC(5, 2),
    options     TEXT,
    available   BOOLEAN        NOT NULL DEFAULT TRUE,
    active      BOOLEAN        NOT NULL DEFAULT TRUE,
    sort_order  INTEGER        NOT NULL DEFAULT 0,
    created_at  TIMESTAMP      NOT NULL,
    updated_at  TIMESTAMP      NOT NULL
);
CREATE INDEX idx_menu_items_outlet ON fnb.menu_items (outlet_id, category);

CREATE TABLE fnb.price_change_requests
(
    id           VARCHAR(36)    PRIMARY KEY,
    business_id  VARCHAR(36)    NOT NULL,
    hotel_id     VARCHAR(36)    NOT NULL,
    menu_item_id VARCHAR(36)    NOT NULL REFERENCES fnb.menu_items (id),
    old_price    NUMERIC(14, 2) NOT NULL,
    new_price    NUMERIC(14, 2) NOT NULL,
    approval_id  VARCHAR(36)    NOT NULL UNIQUE,
    requested_by VARCHAR(36)    NOT NULL,
    status       VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMP      NOT NULL,
    updated_at   TIMESTAMP      NOT NULL
);

CREATE TABLE fnb.happy_hours
(
    id           VARCHAR(36)   PRIMARY KEY,
    business_id  VARCHAR(36)   NOT NULL,
    hotel_id     VARCHAR(36)   NOT NULL,
    outlet_id    VARCHAR(36)   NOT NULL REFERENCES fnb.outlets (id),
    name         VARCHAR(100)  NOT NULL,
    from_time    TIME          NOT NULL,
    to_time      TIME          NOT NULL,
    days         VARCHAR(60)   NOT NULL,
    discount_pct NUMERIC(5, 2) NOT NULL,
    active       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP     NOT NULL,
    updated_at   TIMESTAMP     NOT NULL
);

--changeset hospio:003-orders
CREATE TABLE fnb.orders
(
    id              VARCHAR(36)    PRIMARY KEY,
    business_id     VARCHAR(36)    NOT NULL,
    hotel_id        VARCHAR(36)    NOT NULL,
    outlet_id       VARCHAR(36)    NOT NULL REFERENCES fnb.outlets (id),
    number          VARCHAR(12)    NOT NULL,
    kind            VARCHAR(20)    NOT NULL DEFAULT 'DINE_IN',
    table_id        VARCHAR(36),
    table_no        VARCHAR(20),
    room_id         VARCHAR(36),
    room_number     VARCHAR(20),
    booking_id      VARCHAR(36),
    guest_name      VARCHAR(150),
    status          VARCHAR(20)    NOT NULL DEFAULT 'OPEN',
    subtotal        NUMERIC(14, 2) NOT NULL DEFAULT 0,
    tax             NUMERIC(14, 2) NOT NULL DEFAULT 0,
    discount        NUMERIC(14, 2) NOT NULL DEFAULT 0,
    total           NUMERIC(14, 2) NOT NULL DEFAULT 0,
    charged_to_room BOOLEAN        NOT NULL DEFAULT FALSE,
    paid            BOOLEAN        NOT NULL DEFAULT FALSE,
    payment_id      VARCHAR(36),
    opened_by       VARCHAR(36),
    opened_by_name  VARCHAR(150),
    sent_at         TIMESTAMP,
    closed_at       TIMESTAMP,
    notes           VARCHAR(500),
    created_at      TIMESTAMP      NOT NULL,
    updated_at      TIMESTAMP      NOT NULL
);
CREATE INDEX idx_orders_outlet_status ON fnb.orders (outlet_id, status);
CREATE INDEX idx_orders_room ON fnb.orders (room_id);

CREATE TABLE fnb.order_lines
(
    id           VARCHAR(36)    PRIMARY KEY,
    business_id  VARCHAR(36)    NOT NULL,
    hotel_id     VARCHAR(36)    NOT NULL,
    order_id     VARCHAR(36)    NOT NULL REFERENCES fnb.orders (id),
    menu_item_id VARCHAR(36)    NOT NULL,
    name         VARCHAR(150)   NOT NULL,
    quantity     INTEGER        NOT NULL,
    unit_price   NUMERIC(14, 2) NOT NULL,
    tax_rate     NUMERIC(5, 2),
    options      VARCHAR(300),
    notes        VARCHAR(300),
    status       VARCHAR(20)    NOT NULL DEFAULT 'NEW',
    created_at   TIMESTAMP      NOT NULL,
    updated_at   TIMESTAMP      NOT NULL
);
CREATE INDEX idx_order_lines_order ON fnb.order_lines (order_id);

--changeset hospio:004-row-level-security
CALL app.protect('fnb', 'outlets');
CALL app.protect('fnb', 'tables');
CALL app.protect('fnb', 'menu_items');
CALL app.protect('fnb', 'price_change_requests');
CALL app.protect('fnb', 'happy_hours');
CALL app.protect('fnb', 'orders');
CALL app.protect('fnb', 'order_lines');
