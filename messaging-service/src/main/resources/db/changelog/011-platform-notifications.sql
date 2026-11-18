--liquibase formatted sql

--changeset hospio:messaging-011-platform-notifications
--comment: A bell notice for Hospio360's own staff about something that belongs to no business (a new partner application, docs/plan-marketers.md). business_id becomes nullable, as on platform.alerts: the tenant_rw policy still fits, because app.can_see(NULL, NULL) is true only for the platform.
SELECT set_config('app.scope', 'platform', true);
ALTER TABLE messaging.notifications ALTER COLUMN business_id DROP NOT NULL;
