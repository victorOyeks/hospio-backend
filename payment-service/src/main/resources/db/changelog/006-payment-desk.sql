--liquibase formatted sql

--changeset hospio:011-payment-income-desk
-- A payment for a kitchen or bar order names the desk whose income it is (BAR or KITCHEN, from the order's outlet), whoever took
-- the money; role stays the payer's desk (their till). Older order payments are filled from fnb when its tables are there.
ALTER TABLE payment.payment_requests ADD COLUMN IF NOT EXISTS desk VARCHAR(30);
--changeset hospio:012-payment-income-desk-backfill splitStatements:false
SELECT set_config('app.scope', 'platform', true);
DO $$
BEGIN
  IF to_regclass('fnb.orders') IS NOT NULL AND to_regclass('fnb.outlets') IS NOT NULL THEN
    UPDATE payment.payment_requests p
       SET desk = CASE WHEN ou.kind = 'BAR' THEN 'BAR' ELSE 'KITCHEN' END
      FROM fnb.orders o JOIN fnb.outlets ou ON ou.id = o.outlet_id
     WHERE p.order_id = o.id AND p.desk IS NULL;
  END IF;
EXCEPTION WHEN insufficient_privilege THEN
  RAISE NOTICE 'payment desk backfill skipped: no access to fnb';
END $$;
