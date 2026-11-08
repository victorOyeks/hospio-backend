--liquibase formatted sql

--changeset hospio:001-settings
-- Every Hotel setup value: one row per (hotel, section, key), value stored as JSON text.
-- Sections mirror the Hotel setup screens: STAY_RULES, ROOMS_AREAS, RESTAURANTS_TABLES, CHARGES_PAYMENTS,
-- STAFF_SHIFTS, MONEY, STOCK_SUPPLIERS, REASONS_LISTS, FRONT_DESK, HOUSEKEEPING, LAUNDRY, MAINTENANCE,
-- KITCHEN_BAR, INVENTORY_RULES, CASH_NIGHT_AUDIT, ORGANIZATIONS_BILLING, GUEST_FEEDBACK, STAFF_SIGNIN_LEAVE,
-- TAXES_CURRENCY (owner), APPROVAL_LIMITS (owner), PAYMENTS, POS_TERMINALS, BRAND.
CREATE TABLE hotelconfig.settings
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    section     VARCHAR(40)  NOT NULL,
    key         VARCHAR(80)  NOT NULL,
    value       TEXT,
    updated_by  VARCHAR(36),
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    UNIQUE (hotel_id, section, key)
);
CREATE INDEX idx_settings_hotel_section ON hotelconfig.settings (hotel_id, section);

--changeset hospio:002-list-items
-- Reasons & lists: cancellation reasons, void reasons, charge categories, departments, job titles, areas...
-- Items in use are hidden (active = false), never deleted.
CREATE TABLE hotelconfig.list_items
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    list_name   VARCHAR(60)  NOT NULL,
    code        VARCHAR(60),
    label       VARCHAR(150) NOT NULL,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE INDEX idx_list_items_hotel_list ON hotelconfig.list_items (hotel_id, list_name);

--changeset hospio:003-price-lists
-- Price lists for things charged to a folio outside F&B: minibar, laundry, late check-out, extras.
CREATE TABLE hotelconfig.price_lists
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    kind        VARCHAR(40)  NOT NULL,
    name        VARCHAR(150) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    UNIQUE (hotel_id, kind)
);
CREATE TABLE hotelconfig.price_list_items
(
    id            VARCHAR(36)    PRIMARY KEY,
    business_id   VARCHAR(36)    NOT NULL,
    hotel_id      VARCHAR(36)    NOT NULL,
    price_list_id VARCHAR(36)    NOT NULL REFERENCES hotelconfig.price_lists (id),
    name          VARCHAR(150)   NOT NULL,
    price         NUMERIC(14, 2) NOT NULL,
    tax_rate      NUMERIC(5, 2),
    charge_category VARCHAR(60),
    active        BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP      NOT NULL,
    updated_at    TIMESTAMP      NOT NULL
);
CREATE INDEX idx_price_list_items_list ON hotelconfig.price_list_items (price_list_id);

--changeset hospio:004-row-level-security
CALL app.protect('hotelconfig', 'settings');
CALL app.protect('hotelconfig', 'list_items');
CALL app.protect('hotelconfig', 'price_lists');
CALL app.protect('hotelconfig', 'price_list_items');
