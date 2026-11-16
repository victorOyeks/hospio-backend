--liquibase formatted sql

--changeset hospio:hotelconfig-002-default-brand-indigo
-- Hospio360's default look is now indigo on navy (November 2026). Hotels that saved the old default maroon (#4c091e) as their
-- "colour" never chose it, so the row goes: they show Hospio360's default from now on. A hotel can pick any colour again,
-- maroon included.
SELECT set_config('app.scope', 'platform', true);
DELETE FROM hotelconfig.settings WHERE section = 'BRAND' AND key = 'primaryColour' AND lower(value) = '"#4c091e"';
