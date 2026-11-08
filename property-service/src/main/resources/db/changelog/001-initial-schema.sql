--liquibase formatted sql

--changeset hospio:001-areas
CREATE TABLE property.areas
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    kind        VARCHAR(30)  NOT NULL DEFAULT 'FLOOR',
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);

--changeset hospio:002-room-types
CREATE TABLE property.room_types
(
    id                VARCHAR(36)    PRIMARY KEY,
    business_id       VARCHAR(36)    NOT NULL,
    hotel_id          VARCHAR(36)    NOT NULL,
    name              VARCHAR(100)   NOT NULL,
    description       VARCHAR(500),
    base_price        NUMERIC(14, 2) NOT NULL,
    capacity_adults   INTEGER        NOT NULL DEFAULT 2,
    capacity_children INTEGER        NOT NULL DEFAULT 0,
    facilities        TEXT,
    active            BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP      NOT NULL,
    updated_at        TIMESTAMP      NOT NULL
);
CREATE INDEX idx_room_types_hotel ON property.room_types (hotel_id);

--changeset hospio:003-special-prices
-- A different price for a room type on a date range (weekend rate, Detty December...).
CREATE TABLE property.special_prices
(
    id           VARCHAR(36)    PRIMARY KEY,
    business_id  VARCHAR(36)    NOT NULL,
    hotel_id     VARCHAR(36)    NOT NULL,
    room_type_id VARCHAR(36)    NOT NULL REFERENCES property.room_types (id),
    name         VARCHAR(100)   NOT NULL,
    price        NUMERIC(14, 2) NOT NULL,
    from_date    DATE           NOT NULL,
    to_date      DATE           NOT NULL,
    created_at   TIMESTAMP      NOT NULL,
    updated_at   TIMESTAMP      NOT NULL
);

--changeset hospio:004-rooms
CREATE TABLE property.rooms
(
    id                  VARCHAR(36)  PRIMARY KEY,
    business_id         VARCHAR(36)  NOT NULL,
    hotel_id            VARCHAR(36)  NOT NULL,
    room_type_id        VARCHAR(36)  NOT NULL REFERENCES property.room_types (id),
    area_id             VARCHAR(36)  REFERENCES property.areas (id),
    number              VARCHAR(20)  NOT NULL,
    floor               VARCHAR(20),
    status              VARCHAR(20)  NOT NULL DEFAULT 'IN_SERVICE',
    out_of_order_reason VARCHAR(300),
    out_of_order_until  DATE,
    notes               VARCHAR(500),
    created_at          TIMESTAMP    NOT NULL,
    updated_at          TIMESTAMP    NOT NULL,
    UNIQUE (hotel_id, number)
);
CREATE INDEX idx_rooms_hotel_type ON property.rooms (hotel_id, room_type_id);

--changeset hospio:005-row-level-security
CALL app.protect('property', 'areas');
CALL app.protect('property', 'room_types');
CALL app.protect('property', 'special_prices');
CALL app.protect('property', 'rooms');
