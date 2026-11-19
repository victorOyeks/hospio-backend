--liquibase formatted sql

--changeset hospio:payment-012-no-fee-debit
-- The daily fee debit is gone (19 Nov 2026): Hospio360 bills one monthly invoice and debits nothing (docs/plan-monthly-invoices.md).
-- payment.fee_debits recorded the debits payment-service ran for platform-service; none was ever run (the debit was never switched
-- on), so the table is dropped with its row-security policy.
DROP TABLE IF EXISTS payment.fee_debits;
