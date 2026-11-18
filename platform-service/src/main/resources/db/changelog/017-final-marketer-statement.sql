--liquibase formatted sql

--changeset hospio:platform-017-final-marketer-statement
-- An ENDED marketer's last statement (docs/plan-marketers.md): everything still open and every statement carried over under the
-- NGN 5,000 minimum goes on one final statement, made when the marketer is ended, which is approved and paid whatever the amount.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE platform.marketer_statements ADD COLUMN IF NOT EXISTS final_statement BOOLEAN NOT NULL DEFAULT FALSE;
