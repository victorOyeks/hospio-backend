--liquibase formatted sql

--changeset hospio:tenant-005-venue-type
--comment: Venue type per location (docs/plan-venue-type.md): every existing location is a hotel; the business keeps what kind it is (HOTEL, LOUNGE, BAR, RESTAURANT, CLUB or MIXED), which only defaults new locations.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE tenant.hotels ADD COLUMN IF NOT EXISTS venue_type VARCHAR(20) NOT NULL DEFAULT 'HOTEL';
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS venue_kind VARCHAR(20) NOT NULL DEFAULT 'HOTEL';
