--liquibase formatted sql

--changeset hospio:payment-008-receipt-per-payment runAlways:true splitStatements:false
-- One receipt per payment. The webhook and Verify could both approve a payment and issue two receipts; that race is closed in
-- code (the payment row is locked), and this index makes a second receipt impossible. Duplicates already in the table would make
-- CREATE UNIQUE INDEX fail and stop the service starting, and deleting a receipt number a guest may hold is not ours to do here,
-- so the index is only created once no payment has two receipts. The changeset runs on every start (the IF NOT EXISTS makes it a
-- no-op once the index is there): after the duplicates are cleaned up by hand, the next start creates it. Until then a warning
-- names how many payments have more than one, and the code reads a payment's first receipt.
SELECT set_config('app.scope', 'platform', true);
DO $$
DECLARE
    dupes integer;
BEGIN
    IF to_regclass('payment.uq_receipts_payment') IS NOT NULL THEN
        RETURN;
    END IF;
    SELECT count(*) INTO dupes FROM (SELECT payment_id FROM payment.receipts GROUP BY payment_id HAVING count(*) > 1) d;
    IF dupes = 0 THEN
        CREATE UNIQUE INDEX IF NOT EXISTS uq_receipts_payment ON payment.receipts (payment_id);
    ELSE
        RAISE WARNING 'payment.receipts: % payments have more than one receipt; uq_receipts_payment not created yet', dupes;
    END IF;
END $$;
