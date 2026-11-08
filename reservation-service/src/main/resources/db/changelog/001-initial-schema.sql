--liquibase formatted sql

--changeset hospio:001-guests
-- Guest profiles are business-wide so a returning guest is recognised at any hotel of the chain.
CREATE TABLE reservation.guests
(
    id            VARCHAR(36)  PRIMARY KEY,
    business_id   VARCHAR(36)  NOT NULL,
    hotel_id      VARCHAR(36),
    full_name     VARCHAR(150) NOT NULL,
    phone         VARCHAR(20),
    email         VARCHAR(255),
    id_type       VARCHAR(30),
    id_number     VARCHAR(60),
    nationality   VARCHAR(60),
    preferences   VARCHAR(500),
    notes         VARCHAR(1000),
    blacklisted   BOOLEAN      NOT NULL DEFAULT FALSE,
    stays         INTEGER      NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL
);
CREATE INDEX idx_guests_business_phone ON reservation.guests (business_id, phone);
CREATE INDEX idx_guests_business_name ON reservation.guests (business_id, full_name);

--changeset hospio:002-bookings
CREATE TABLE reservation.bookings
(
    id                  VARCHAR(36)    PRIMARY KEY,
    business_id         VARCHAR(36)    NOT NULL,
    hotel_id            VARCHAR(36)    NOT NULL,
    reference           VARCHAR(12)    NOT NULL UNIQUE,
    guest_id            VARCHAR(36)    NOT NULL REFERENCES reservation.guests (id),
    guest_name          VARCHAR(150)   NOT NULL,
    room_type_id        VARCHAR(36)    NOT NULL,
    room_type_name      VARCHAR(100),
    room_id             VARCHAR(36),
    room_number         VARCHAR(20),
    check_in_date       DATE           NOT NULL,
    check_out_date      DATE           NOT NULL,
    adults              INTEGER        NOT NULL DEFAULT 1,
    children            INTEGER        NOT NULL DEFAULT 0,
    status              VARCHAR(20)    NOT NULL DEFAULT 'BOOKED',
    price_kind          VARCHAR(20)    NOT NULL DEFAULT 'NORMAL',
    nightly_rate        NUMERIC(14, 2) NOT NULL,
    total_amount        NUMERIC(14, 2) NOT NULL,
    payment_plan        VARCHAR(20)    NOT NULL DEFAULT 'PAY_IN_FULL',
    organization_id     VARCHAR(36),
    source              VARCHAR(40),
    notes               VARCHAR(1000),
    created_by          VARCHAR(36),
    checked_in_at       TIMESTAMP,
    checked_out_at      TIMESTAMP,
    late_checkout_until TIME,
    cancelled_at        TIMESTAMP,
    cancellation_reason VARCHAR(300),
    created_at          TIMESTAMP      NOT NULL,
    updated_at          TIMESTAMP      NOT NULL
);
CREATE INDEX idx_bookings_hotel_dates ON reservation.bookings (hotel_id, check_in_date, check_out_date);
CREATE INDEX idx_bookings_room ON reservation.bookings (room_id, status);
CREATE INDEX idx_bookings_guest ON reservation.bookings (guest_id);

--changeset hospio:003-row-level-security
CALL app.protect('reservation', 'guests');
CALL app.protect('reservation', 'bookings');
