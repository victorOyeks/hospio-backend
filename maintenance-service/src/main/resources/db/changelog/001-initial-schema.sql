--liquibase formatted sql

--changeset hospio:001-repairs
CREATE TABLE maintenance.repairs
(
    id                VARCHAR(36)  PRIMARY KEY,
    business_id       VARCHAR(36)  NOT NULL,
    hotel_id          VARCHAR(36)  NOT NULL,
    room_id           VARCHAR(36),
    room_number       VARCHAR(20),
    area              VARCHAR(100),
    title             VARCHAR(150) NOT NULL,
    description       VARCHAR(1000),
    priority          VARCHAR(10)  NOT NULL DEFAULT 'NORMAL',
    status            VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    source            VARCHAR(30),
    photo_key         VARCHAR(255),
    reported_by       VARCHAR(36),
    assigned_to       VARCHAR(36),
    assigned_to_name  VARCHAR(150),
    room_out_of_order BOOLEAN      NOT NULL DEFAULT FALSE,
    done_at           TIMESTAMP,
    done_by           VARCHAR(36),
    created_at        TIMESTAMP    NOT NULL,
    updated_at        TIMESTAMP    NOT NULL
);
CREATE INDEX idx_repairs_hotel_status ON maintenance.repairs (hotel_id, status);
CREATE INDEX idx_repairs_assigned ON maintenance.repairs (assigned_to, status);

CREATE TABLE maintenance.repair_updates
(
    id          VARCHAR(36)   PRIMARY KEY,
    business_id VARCHAR(36)   NOT NULL,
    hotel_id    VARCHAR(36)   NOT NULL,
    repair_id   VARCHAR(36)   NOT NULL REFERENCES maintenance.repairs (id),
    note        VARCHAR(1000) NOT NULL,
    photo_key   VARCHAR(255),
    by_user     VARCHAR(36),
    by_name     VARCHAR(150),
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL
);

--changeset hospio:002-scheduled-jobs
CREATE TABLE maintenance.scheduled_jobs
(
    id               VARCHAR(36)  PRIMARY KEY,
    business_id      VARCHAR(36)  NOT NULL,
    hotel_id         VARCHAR(36)  NOT NULL,
    title            VARCHAR(150) NOT NULL,
    description      VARCHAR(1000),
    area             VARCHAR(100),
    every_days       INTEGER      NOT NULL,
    next_due         DATE         NOT NULL,
    last_done        DATE,
    assigned_to      VARCHAR(36),
    assigned_to_name VARCHAR(150),
    paused           BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL
);
CREATE TABLE maintenance.scheduled_job_runs
(
    id          VARCHAR(36)   PRIMARY KEY,
    business_id VARCHAR(36)   NOT NULL,
    hotel_id    VARCHAR(36)   NOT NULL,
    job_id      VARCHAR(36)   NOT NULL REFERENCES maintenance.scheduled_jobs (id),
    done_on     DATE          NOT NULL,
    note        VARCHAR(1000),
    done_by     VARCHAR(36),
    created_at  TIMESTAMP     NOT NULL,
    updated_at  TIMESTAMP     NOT NULL
);

--changeset hospio:003-spare-parts
CREATE TABLE maintenance.spare_parts
(
    id           VARCHAR(36)    PRIMARY KEY,
    business_id  VARCHAR(36)    NOT NULL,
    hotel_id     VARCHAR(36)    NOT NULL,
    name         VARCHAR(150)   NOT NULL,
    quantity     INTEGER        NOT NULL DEFAULT 0,
    min_quantity INTEGER        NOT NULL DEFAULT 0,
    unit_cost    NUMERIC(14, 2),
    created_at   TIMESTAMP      NOT NULL,
    updated_at   TIMESTAMP      NOT NULL
);
CREATE TABLE maintenance.part_requests
(
    id             VARCHAR(36)    PRIMARY KEY,
    business_id    VARCHAR(36)    NOT NULL,
    hotel_id       VARCHAR(36)    NOT NULL,
    repair_id      VARCHAR(36)    REFERENCES maintenance.repairs (id),
    part_name      VARCHAR(150)   NOT NULL,
    quantity       INTEGER        NOT NULL,
    estimated_cost NUMERIC(14, 2),
    status         VARCHAR(20)    NOT NULL DEFAULT 'REQUESTED',
    approval_id    VARCHAR(36)    UNIQUE,
    requested_by   VARCHAR(36),
    created_at     TIMESTAMP      NOT NULL,
    updated_at     TIMESTAMP      NOT NULL
);

--changeset hospio:004-row-level-security
CALL app.protect('maintenance', 'repairs');
CALL app.protect('maintenance', 'repair_updates');
CALL app.protect('maintenance', 'scheduled_jobs');
CALL app.protect('maintenance', 'scheduled_job_runs');
CALL app.protect('maintenance', 'spare_parts');
CALL app.protect('maintenance', 'part_requests');
