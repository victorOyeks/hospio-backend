--liquibase formatted sql

--changeset hospio:platform-029-hotel-applications-monthly-sales
--comment: Book a demo asks "Average monthly sales (all outlets combined)" (docs/onboarding.md, "Applications"): UNDER_1M, FROM_1M_TO_5M, FROM_5M_TO_20M, ABOVE_20M or PREFER_NOT. Required for new requests by the service; requests sent before the question stay null ("Not asked"). Information only, never scored. Indexed for the Hotel applications filter.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE platform.hotel_applications ADD COLUMN IF NOT EXISTS monthly_sales VARCHAR(20);
CREATE INDEX IF NOT EXISTS idx_hotel_applications_monthly_sales ON platform.hotel_applications (monthly_sales);
