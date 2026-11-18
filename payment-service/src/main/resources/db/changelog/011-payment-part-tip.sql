--liquibase formatted sql

--changeset hospio:payment-011-part-tip
-- Venue type, phase 4: a payment can be for one part of a split bill (part_id, fnb order_split_parts) and can carry a tip
-- (tip_amount, inside amount). The tip is not a sale: the sale's money is amount - tip_amount.
ALTER TABLE payment.payment_requests ADD COLUMN IF NOT EXISTS part_id VARCHAR(36);
ALTER TABLE payment.payment_requests ADD COLUMN IF NOT EXISTS tip_amount NUMERIC(14, 2);
