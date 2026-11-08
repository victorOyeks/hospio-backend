--liquibase formatted sql

--changeset hospio:001-cleaning-tasks
CREATE TABLE housekeeping.cleaning_tasks
(
    id               VARCHAR(36)  PRIMARY KEY,
    business_id      VARCHAR(36)  NOT NULL,
    hotel_id         VARCHAR(36)  NOT NULL,
    room_id          VARCHAR(36)  NOT NULL,
    room_number      VARCHAR(20),
    kind             VARCHAR(20)  NOT NULL,
    due_date         DATE         NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'TODO',
    assigned_to      VARCHAR(36),
    assigned_to_name VARCHAR(150),
    checklist        TEXT,
    started_at       TIMESTAMP,
    done_at          TIMESTAMP,
    done_by          VARCHAR(36),
    inspected_by     VARCHAR(36),
    inspected_at     TIMESTAMP,
    inspection_note  VARCHAR(500),
    skip_reason      VARCHAR(300),
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL
);
CREATE INDEX idx_cleaning_tasks_hotel_date ON housekeeping.cleaning_tasks (hotel_id, due_date, status);
CREATE INDEX idx_cleaning_tasks_room ON housekeeping.cleaning_tasks (room_id, status);

--changeset hospio:002-room-status
-- The latest cleanliness of each room, for the room board.
CREATE TABLE housekeeping.room_status
(
    id          VARCHAR(36) PRIMARY KEY,
    business_id VARCHAR(36) NOT NULL,
    hotel_id    VARCHAR(36) NOT NULL,
    room_id     VARCHAR(36) NOT NULL UNIQUE,
    room_number VARCHAR(20),
    status      VARCHAR(20) NOT NULL DEFAULT 'DIRTY',
    changed_by  VARCHAR(36),
    created_at  TIMESTAMP   NOT NULL,
    updated_at  TIMESTAMP   NOT NULL
);

--changeset hospio:003-problems-linen-minibar
CREATE TABLE housekeeping.problem_reports
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    room_id     VARCHAR(36),
    room_number VARCHAR(20),
    description VARCHAR(500) NOT NULL,
    photo_key   VARCHAR(255),
    repair_id   VARCHAR(36),
    reported_by VARCHAR(36),
    status      VARCHAR(20)  NOT NULL DEFAULT 'SENT',
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE TABLE housekeeping.linen_requests
(
    id           VARCHAR(36)  PRIMARY KEY,
    business_id  VARCHAR(36)  NOT NULL,
    hotel_id     VARCHAR(36)  NOT NULL,
    room_id      VARCHAR(36),
    room_number  VARCHAR(20),
    items        VARCHAR(500) NOT NULL,
    requested_by VARCHAR(36),
    status       VARCHAR(20)  NOT NULL DEFAULT 'REQUESTED',
    created_at   TIMESTAMP    NOT NULL,
    updated_at   TIMESTAMP    NOT NULL
);
CREATE TABLE housekeeping.minibar_restocks
(
    id          VARCHAR(36)    PRIMARY KEY,
    business_id VARCHAR(36)    NOT NULL,
    hotel_id    VARCHAR(36)    NOT NULL,
    room_id     VARCHAR(36)    NOT NULL,
    room_number VARCHAR(20),
    booking_id  VARCHAR(36),
    items       TEXT           NOT NULL,
    total       NUMERIC(14, 2) NOT NULL,
    charged     BOOLEAN        NOT NULL DEFAULT FALSE,
    restocked_by VARCHAR(36),
    created_at  TIMESTAMP      NOT NULL,
    updated_at  TIMESTAMP      NOT NULL
);

--changeset hospio:004-lost-and-found
CREATE TABLE housekeeping.lost_found_items
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    description VARCHAR(300) NOT NULL,
    found_where VARCHAR(150),
    room_id     VARCHAR(36),
    found_by    VARCHAR(36),
    found_at    TIMESTAMP    NOT NULL,
    photo_key   VARCHAR(255),
    status      VARCHAR(20)  NOT NULL DEFAULT 'FOUND',
    returned_to VARCHAR(150),
    returned_at TIMESTAMP,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);

--changeset hospio:005-laundry
CREATE TABLE housekeeping.laundry_orders
(
    id              VARCHAR(36)    PRIMARY KEY,
    business_id     VARCHAR(36)    NOT NULL,
    hotel_id        VARCHAR(36)    NOT NULL,
    booking_id      VARCHAR(36),
    guest_name      VARCHAR(150),
    room_id         VARCHAR(36),
    room_number     VARCHAR(20),
    items           TEXT           NOT NULL,
    total           NUMERIC(14, 2) NOT NULL,
    status          VARCHAR(20)    NOT NULL DEFAULT 'RECEIVED',
    charged_to_room BOOLEAN        NOT NULL DEFAULT FALSE,
    paid            BOOLEAN        NOT NULL DEFAULT FALSE,
    payment_id      VARCHAR(36),
    pocket_note     VARCHAR(300),
    received_by     VARCHAR(36),
    delivered_at    TIMESTAMP,
    created_at      TIMESTAMP      NOT NULL,
    updated_at      TIMESTAMP      NOT NULL
);
CREATE TABLE housekeeping.linen_movements
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    direction   VARCHAR(10)  NOT NULL,
    item        VARCHAR(100) NOT NULL,
    quantity    INTEGER      NOT NULL,
    note        VARCHAR(300),
    recorded_by VARCHAR(36),
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);
CREATE TABLE housekeeping.damaged_linen
(
    id          VARCHAR(36)  PRIMARY KEY,
    business_id VARCHAR(36)  NOT NULL,
    hotel_id    VARCHAR(36)  NOT NULL,
    item        VARCHAR(100) NOT NULL,
    quantity    INTEGER      NOT NULL,
    reason      VARCHAR(300) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'REPORTED',
    approval_id VARCHAR(36)  UNIQUE,
    reported_by VARCHAR(36),
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL
);

--changeset hospio:006-row-level-security
CALL app.protect('housekeeping', 'cleaning_tasks');
CALL app.protect('housekeeping', 'room_status');
CALL app.protect('housekeeping', 'problem_reports');
CALL app.protect('housekeeping', 'linen_requests');
CALL app.protect('housekeeping', 'minibar_restocks');
CALL app.protect('housekeeping', 'lost_found_items');
CALL app.protect('housekeeping', 'laundry_orders');
CALL app.protect('housekeeping', 'linen_movements');
CALL app.protect('housekeeping', 'damaged_linen');
