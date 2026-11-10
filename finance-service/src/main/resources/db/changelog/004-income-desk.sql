--liquibase formatted sql

--changeset hospio:011-revenue-income-desk
-- Income by desk: a payment for a kitchen or bar order is that outlet's income (desk BAR or KITCHEN), whoever took it. role stays
-- the payer's desk, whose till the cash lands in. Older order payments are filled from payment and fnb when their tables are there.
ALTER TABLE finance.revenue_entries ADD COLUMN IF NOT EXISTS order_id VARCHAR(36);
ALTER TABLE finance.revenue_entries ADD COLUMN IF NOT EXISTS desk VARCHAR(30);
--changeset hospio:012-revenue-income-desk-backfill splitStatements:false
SELECT set_config('app.scope', 'platform', true);
DO $$
BEGIN
  IF to_regclass('payment.payment_requests') IS NOT NULL AND to_regclass('fnb.orders') IS NOT NULL AND to_regclass('fnb.outlets') IS NOT NULL THEN
    UPDATE finance.revenue_entries r
       SET order_id = p.order_id,
           desk = CASE WHEN ou.kind = 'BAR' THEN 'BAR' ELSE 'KITCHEN' END
      FROM payment.payment_requests p JOIN fnb.orders o ON o.id = p.order_id JOIN fnb.outlets ou ON ou.id = o.outlet_id
     WHERE p.id = r.payment_id AND r.desk IS NULL;
  END IF;
EXCEPTION WHEN insufficient_privilege OR undefined_column THEN
  RAISE NOTICE 'revenue desk backfill skipped';
END $$;
