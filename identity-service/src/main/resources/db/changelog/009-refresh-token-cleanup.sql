--liquibase formatted sql

--changeset hospio:identity-009-refresh-token-cleanup
-- The nightly clean-up deletes refresh tokens that expired or were revoked more than 30 days ago, 10,000 at a time;
-- these let each batch find its rows without reading the whole table.
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires ON identity.refresh_tokens (expires_at);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_revoked ON identity.refresh_tokens (revoked_at) WHERE revoked_at IS NOT NULL;
