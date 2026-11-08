--liquibase formatted sql

--changeset hospio:app-functions runOnChange:true splitStatements:false
-- Shared helpers for row-level security. Every service schema uses app.can_see in its policies.
-- Session settings app.business_id, app.hotel_id and app.scope are bound per connection by common's
-- RowLevelSecurityDataSource. CREATE OR REPLACE keeps this idempotent across the services that include it.

-- Several services share this changeset and may start at the same moment (a reboot, a first deploy); each
-- has its own Liquibase lock, so this transaction-scoped lock is what keeps them from creating the
-- functions concurrently ("duplicate key value violates unique constraint pg_proc_proname_args_nsp_index").
SELECT pg_advisory_xact_lock(hashtext('hospio.app-functions'));

CREATE SCHEMA IF NOT EXISTS app;

CREATE OR REPLACE FUNCTION app.current_business() RETURNS text LANGUAGE sql STABLE AS $$
  SELECT NULLIF(current_setting('app.business_id', true), '')
$$;

CREATE OR REPLACE FUNCTION app.current_hotel() RETURNS text LANGUAGE sql STABLE AS $$
  SELECT NULLIF(current_setting('app.hotel_id', true), '')
$$;

CREATE OR REPLACE FUNCTION app.is_platform() RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT current_setting('app.scope', true) = 'platform'
$$;

-- A row is visible when the caller is the platform, or belongs to the row's business and either
-- looks across the business (no hotel bound) or is bound to the row's hotel.
CREATE OR REPLACE FUNCTION app.can_see(p_business text, p_hotel text) RETURNS boolean LANGUAGE sql STABLE AS $$
  SELECT app.is_platform()
      OR (p_business IS NOT NULL AND p_business = app.current_business()
          AND (p_hotel IS NULL OR app.current_hotel() IS NULL OR p_hotel = app.current_hotel()))
$$;

-- Applies the standard policy to a table with business_id (and optionally hotel_id) columns.
CREATE OR REPLACE PROCEDURE app.protect(p_schema text, p_table text, p_hotel_column text DEFAULT 'hotel_id')
LANGUAGE plpgsql AS $$
BEGIN
  EXECUTE format('ALTER TABLE %I.%I ENABLE ROW LEVEL SECURITY', p_schema, p_table);
  EXECUTE format('ALTER TABLE %I.%I FORCE ROW LEVEL SECURITY', p_schema, p_table);
  EXECUTE format('DROP POLICY IF EXISTS tenant_rw ON %I.%I', p_schema, p_table);
  IF p_hotel_column IS NULL THEN
    EXECUTE format('CREATE POLICY tenant_rw ON %I.%I USING (app.can_see(business_id, NULL)) WITH CHECK (app.can_see(business_id, NULL))', p_schema, p_table);
  ELSE
    EXECUTE format('CREATE POLICY tenant_rw ON %I.%I USING (app.can_see(business_id, %I)) WITH CHECK (app.can_see(business_id, %I))', p_schema, p_table, p_hotel_column, p_hotel_column);
  END IF;
END $$;
