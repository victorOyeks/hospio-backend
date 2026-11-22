--liquibase formatted sql

--changeset hospio:platform-030-hotel-applications-short-form
--comment: The short Book a demo form (22 Nov 2026, docs/onboarding.md, "Applications"): only name, phone, business name and type are required, so email, city, state and heard_from may be null (monthly_sales already may). A request without an email finds its repeat by phone: one open request per phone among those without an email, whatever two requests arriving together do (the email rule of platform-028; NULL emails never meet in that index).
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE platform.hotel_applications ALTER COLUMN email DROP NOT NULL;
ALTER TABLE platform.hotel_applications ALTER COLUMN city DROP NOT NULL;
ALTER TABLE platform.hotel_applications ALTER COLUMN state DROP NOT NULL;
ALTER TABLE platform.hotel_applications ALTER COLUMN heard_from DROP NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_hotel_applications_open_phone_no_email ON platform.hotel_applications (phone)
    WHERE email IS NULL AND status NOT IN ('ONBOARDED', 'DECLINED');
CREATE INDEX IF NOT EXISTS idx_hotel_applications_phone ON platform.hotel_applications (phone);
