--liquibase formatted sql

--changeset hospio:platform-024-system-health
--comment: Platform → System health (docs/system-health.md): a snapshot of the server every 5 minutes, kept 7 days, for the trends and the "disk full in N days" estimate; and one row per alert condition so each condition alerts once until it clears (then a "recovered" note). Platform-only tables (no business).
SELECT set_config('app.scope', 'platform', true);
CREATE TABLE IF NOT EXISTS platform.system_snapshots
(
    taken_at        TIMESTAMP     PRIMARY KEY,
    cpu_pct         NUMERIC(5, 1),
    load1           NUMERIC(7, 2),
    mem_pct         NUMERIC(5, 1),
    swap_pct        NUMERIC(5, 1),
    disk_pct        NUMERIC(5, 1),
    disk_used       BIGINT,
    disk_total      BIGINT,
    db_bytes        BIGINT,
    services_up     INTEGER,
    services_total  INTEGER
);
ALTER TABLE platform.system_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.system_snapshots FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS platform_rw ON platform.system_snapshots;
CREATE POLICY platform_rw ON platform.system_snapshots USING (app.is_platform()) WITH CHECK (app.is_platform());

CREATE TABLE IF NOT EXISTS platform.system_alert_states
(
    condition_key   VARCHAR(120)  PRIMARY KEY,
    level           VARCHAR(10)   NOT NULL,
    title           VARCHAR(200)  NOT NULL,
    detail          VARCHAR(1000),
    raised_at       TIMESTAMP     NOT NULL,
    alert_id        VARCHAR(36),
    updated_at      TIMESTAMP     NOT NULL
);
ALTER TABLE platform.system_alert_states ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.system_alert_states FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS platform_rw ON platform.system_alert_states;
CREATE POLICY platform_rw ON platform.system_alert_states USING (app.is_platform()) WITH CHECK (app.is_platform());
