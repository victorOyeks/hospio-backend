--liquibase formatted sql

--changeset hospio:finance-007-till-cash-tips
-- Venue type, phase 4: cash tips taken with payments sit in the drawer (inside expected_cash) and are counted apart on the till.
ALTER TABLE finance.tills ADD COLUMN IF NOT EXISTS cash_tips NUMERIC(14, 2) NOT NULL DEFAULT 0;
