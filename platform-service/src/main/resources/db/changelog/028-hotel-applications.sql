--liquibase formatted sql

--changeset hospio:platform-028-hotel-applications
--comment: "Book a demo" on the landing site replaces self sign-up (docs/onboarding.md, "Applications"): one row per request from a hotel, lounge, bar or restaurant, worked in the admin portal's Hotel applications (NEW, CONTACTED, MEETING_BOOKED, APPROVED, ONBOARDED or DECLINED) until the team onboards the business with the back-office wizard. Notes are append-only. Platform-only tables (no business column), like partner_applications.
SELECT set_config('app.scope', 'platform', true);
CREATE TABLE IF NOT EXISTS platform.hotel_applications
(
    id                   VARCHAR(36)  PRIMARY KEY,
    owner_name           VARCHAR(150) NOT NULL,
    email                VARCHAR(255) NOT NULL,
    phone                VARCHAR(20)  NOT NULL,
    business_name        VARCHAR(150) NOT NULL,
    business_type        VARCHAR(20)  NOT NULL,
    city                 VARCHAR(100) NOT NULL,
    state                VARCHAR(60)  NOT NULL,
    rooms                INTEGER,
    heard_from           VARCHAR(30)  NOT NULL,
    preferred_time       VARCHAR(20),
    message              VARCHAR(1000),
    referral_code        VARCHAR(20),
    marketer_profile_id  VARCHAR(36),
    marketer_name        VARCHAR(150),
    status               VARCHAR(20)  NOT NULL DEFAULT 'NEW',
    status_changed_at    TIMESTAMP,
    assignee_id          VARCHAR(36),
    assignee_name        VARCHAR(150),
    meeting_at           TIMESTAMP,
    decline_reason       VARCHAR(500),
    business_id          VARCHAR(36),
    business_name_linked VARCHAR(255),
    onboarded_at         TIMESTAMP,
    ip                   VARCHAR(64),
    user_agent           VARCHAR(300),
    created_at           TIMESTAMP    NOT NULL,
    updated_at           TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_hotel_applications_status ON platform.hotel_applications (status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_hotel_applications_email ON platform.hotel_applications (LOWER(email));
CREATE INDEX IF NOT EXISTS idx_hotel_applications_marketer ON platform.hotel_applications (marketer_profile_id);
-- One open request per email, whatever two requests arriving together do (a repeat becomes a note on the open one).
CREATE UNIQUE INDEX IF NOT EXISTS uq_hotel_applications_open_email ON platform.hotel_applications (LOWER(email))
    WHERE status NOT IN ('ONBOARDED', 'DECLINED');
ALTER TABLE platform.hotel_applications ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.hotel_applications FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS platform_rw ON platform.hotel_applications;
CREATE POLICY platform_rw ON platform.hotel_applications USING (app.is_platform()) WITH CHECK (app.is_platform());

-- The application's timeline: notes the team adds, status changes and what the website adds (a repeat request). Never edited or deleted.
CREATE TABLE IF NOT EXISTS platform.hotel_application_notes
(
    id               VARCHAR(36)   PRIMARY KEY,
    application_id   VARCHAR(36)   NOT NULL REFERENCES platform.hotel_applications (id),
    kind             VARCHAR(20)   NOT NULL,
    body             VARCHAR(2000) NOT NULL,
    author_id        VARCHAR(36),
    author_name      VARCHAR(150)  NOT NULL,
    created_at       TIMESTAMP     NOT NULL,
    updated_at       TIMESTAMP     NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_hotel_application_notes_app ON platform.hotel_application_notes (application_id, created_at);
ALTER TABLE platform.hotel_application_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.hotel_application_notes FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS platform_rw ON platform.hotel_application_notes;
CREATE POLICY platform_rw ON platform.hotel_application_notes USING (app.is_platform()) WITH CHECK (app.is_platform());
