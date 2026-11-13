--liquibase formatted sql

--changeset hospio:inventory-004-purchase-order-numbers
-- Order numbers were "count of the hotel's orders + 1" with nothing stopping two orders made at once from sharing one.
-- Older duplicates keep their number on the first order made and get "-2", "-3" … on the later ones (still readable,
-- never matched by the new PO-{year}-NNNNN numbering), then each hotel's numbers are made unique.
SELECT set_config('app.scope', 'platform', true);
UPDATE inventory.purchase_orders p
SET number = LEFT(p.number, 16) || '-' || d.rn
FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY hotel_id, number ORDER BY created_at, id) AS rn
      FROM inventory.purchase_orders) d
WHERE p.id = d.id AND d.rn > 1;
CREATE UNIQUE INDEX IF NOT EXISTS uq_purchase_orders_hotel_number ON inventory.purchase_orders (hotel_id, number);
