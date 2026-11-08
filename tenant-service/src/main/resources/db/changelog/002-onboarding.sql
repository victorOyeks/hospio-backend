--liquibase formatted sql

--changeset hospio:006-business-profile
-- How the business came in (self-serve sign-up or back office), its own contacts and brand, the contract,
-- and a hotel count kept current by tenant-service.
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS source VARCHAR(20) NOT NULL DEFAULT 'BACK_OFFICE';
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS city VARCHAR(100);
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS address VARCHAR(255);
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS email VARCHAR(255);
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS phone VARCHAR(20);
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS logo_url VARCHAR(500);
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS brand_colour VARCHAR(20);
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS contract_starts_on DATE;
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS contract_notes VARCHAR(2000);
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS hotel_count INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_businesses_source ON tenant.businesses (source);

--changeset hospio:007-business-hotel-count-backfill
-- The tables force row-level security on every role, the migration's included, so the backfill binds the
-- platform scope for this transaction first; without it the UPDATE would silently touch no rows.
SELECT set_config('app.scope', 'platform', true);
UPDATE tenant.businesses b SET hotel_count = (SELECT count(*) FROM tenant.hotels h WHERE h.business_id = b.id);

--changeset hospio:008-hotel-profile
-- Status gains PAUSED (with a reason), plus check-in/out times, star rating and the manager in charge.
ALTER TABLE tenant.hotels ADD COLUMN IF NOT EXISTS status_reason VARCHAR(500);
ALTER TABLE tenant.hotels ADD COLUMN IF NOT EXISTS check_in_time VARCHAR(5) NOT NULL DEFAULT '14:00';
ALTER TABLE tenant.hotels ADD COLUMN IF NOT EXISTS check_out_time VARCHAR(5) NOT NULL DEFAULT '12:00';
ALTER TABLE tenant.hotels ADD COLUMN IF NOT EXISTS star_rating INTEGER;
ALTER TABLE tenant.hotels ADD COLUMN IF NOT EXISTS manager_user_id VARCHAR(36);

--changeset hospio:009-business-referral
-- The marketer referral (platform-service) a business came from; the BusinessCreated event carries it too.
ALTER TABLE tenant.businesses ADD COLUMN IF NOT EXISTS referral_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_businesses_marketer ON tenant.businesses (marketer_id);
