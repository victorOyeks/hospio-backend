--liquibase formatted sql

--changeset hospio:fnb-005-order-version
-- Optimistic-lock backstop for orders (every writer also takes the row lock first): a stale save fails instead of re-opening a settled order.
ALTER TABLE fnb.orders ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
