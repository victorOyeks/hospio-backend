--liquibase formatted sql

--changeset hospio:platform-015-legal-acceptance splitStatements:false
-- Online acceptance of the legal documents (docs/legal-acceptance.md): the versions of each document (Terms, Privacy, Data Processing,
-- daily debit authority, Devices, Support, Referral partner, Staff terms) and an append-only record of every acceptance (who, which
-- document and version, when, IP, browser, how) plus the back office's note of how the links were shared with an onboarded owner.
SELECT set_config('app.scope', 'platform', true);

CREATE TABLE IF NOT EXISTS platform.legal_documents
(
    id              VARCHAR(36)  PRIMARY KEY,
    doc_key         VARCHAR(20)  NOT NULL,
    version         VARCHAR(40)  NOT NULL,
    title           VARCHAR(120) NOT NULL,
    slug            VARCHAR(60)  NOT NULL,
    effective_from  TIMESTAMP    NOT NULL,
    material        BOOLEAN      NOT NULL DEFAULT FALSE,
    summary         VARCHAR(500),
    created_by_name VARCHAR(150),
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_legal_documents_key_version ON platform.legal_documents (doc_key, version);

CREATE TABLE IF NOT EXISTS platform.legal_acceptances
(
    id               VARCHAR(36)  PRIMARY KEY,
    event            VARCHAR(20)  NOT NULL,
    method           VARCHAR(30)  NOT NULL,
    doc_key          VARCHAR(20)  NOT NULL,
    version          VARCHAR(40)  NOT NULL,
    user_id          VARCHAR(36),
    user_name        VARCHAR(150),
    user_email       VARCHAR(200),
    role             VARCHAR(30),
    business_id      VARCHAR(36),
    business_name    VARCHAR(150),
    hotel_id         VARCHAR(36),
    hotel_name       VARCHAR(150),
    reference        VARCHAR(120),
    channel          VARCHAR(40),
    shared_on        DATE,
    note             VARCHAR(300),
    ip               VARCHAR(64),
    user_agent       VARCHAR(400),
    recorded_by      VARCHAR(36),
    recorded_by_name VARCHAR(150),
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_legal_acceptances_user ON platform.legal_acceptances (user_id, doc_key, version);
CREATE INDEX IF NOT EXISTS idx_legal_acceptances_business ON platform.legal_acceptances (business_id, created_at DESC);

-- Append-only: a record is never changed or removed.
CREATE OR REPLACE FUNCTION platform.legal_acceptances_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'legal_acceptances is append-only';
END;
$$;
DROP TRIGGER IF EXISTS trg_legal_acceptances_append_only ON platform.legal_acceptances;
CREATE TRIGGER trg_legal_acceptances_append_only BEFORE UPDATE OR DELETE ON platform.legal_acceptances
    FOR EACH ROW EXECUTE FUNCTION platform.legal_acceptances_append_only();

ALTER TABLE platform.legal_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.legal_documents FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS platform_rw ON platform.legal_documents;
CREATE POLICY platform_rw ON platform.legal_documents USING (app.is_platform()) WITH CHECK (app.is_platform());
ALTER TABLE platform.legal_acceptances ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform.legal_acceptances FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS platform_rw ON platform.legal_acceptances;
CREATE POLICY platform_rw ON platform.legal_acceptances USING (app.is_platform()) WITH CHECK (app.is_platform());

-- The owner's acceptance of the device terms for a package, before it is installed (or a signed schedule's reference instead).
ALTER TABLE platform.device_packages ADD COLUMN IF NOT EXISTS terms_accepted_at TIMESTAMP;
ALTER TABLE platform.device_packages ADD COLUMN IF NOT EXISTS terms_accepted_by VARCHAR(36);
ALTER TABLE platform.device_packages ADD COLUMN IF NOT EXISTS terms_accepted_name VARCHAR(150);
ALTER TABLE platform.device_packages ADD COLUMN IF NOT EXISTS terms_version VARCHAR(40);
ALTER TABLE platform.device_packages ADD COLUMN IF NOT EXISTS signed_schedule_ref VARCHAR(120);

-- The first published versions (18 Nov 2026), from the contract templates (docs/legal-acceptance.md).
INSERT INTO platform.legal_documents (id, doc_key, version, title, slug, effective_from, material, summary, created_by_name, created_at, updated_at)
VALUES ('legal-terms-2026-11', 'TERMS', 'TOS-2026-11', 'Terms of Service', 'terms', '2026-11-17 23:00:00', FALSE, 'First published version', 'Hospio360', now(), now()),
       ('legal-privacy-2026-11', 'PRIVACY', 'PRIV-2026-11', 'Privacy Policy', 'privacy', '2026-11-17 23:00:00', FALSE, 'First published version', 'Hospio360', now(), now()),
       ('legal-dpa-2026-11', 'DPA', 'DPA-2026-11', 'Data Processing Terms', 'data-processing', '2026-11-17 23:00:00', FALSE, 'First published version', 'Hospio360', now(), now()),
       ('legal-debit-2026-11', 'DEBIT', 'FEES-2026-11', 'Fees and daily debit authorisation', 'fees', '2026-11-17 23:00:00', FALSE, 'First published version', 'Hospio360', now(), now()),
       ('legal-devices-2026-11', 'DEVICES', 'DEV-2026-11', 'Devices & equipment terms', 'devices', '2026-11-17 23:00:00', FALSE, 'First published version', 'Hospio360', now(), now()),
       ('legal-support-2026-11', 'SUPPORT', 'SUP-2026-11', 'Support & services', 'support', '2026-11-17 23:00:00', FALSE, 'First published version', 'Hospio360', now(), now()),
       ('legal-rpa-2026-11', 'RPA', 'RPA-2026-11', 'Referral partner terms', 'referral-partners', '2026-11-17 23:00:00', FALSE, 'First published version', 'Hospio360', now(), now()),
       ('legal-staff-2026-11', 'STAFF', 'STAFF-2026-11', 'Staff terms of use', 'staff-terms', '2026-11-17 23:00:00', FALSE, 'First published version', 'Hospio360', now(), now())
ON CONFLICT DO NOTHING;

-- Marketers who already accepted RPA-2026-11 in the portal keep that acceptance as a record (method PORTAL; IP and browser unknown).
INSERT INTO platform.legal_acceptances (id, event, method, doc_key, version, user_id, user_name, role, note, created_at, updated_at)
SELECT gen_random_uuid()::text, 'ACCEPTED', 'PORTAL', 'RPA', p.agreement_version, p.agreement_accepted_by, p.agreement_accepted_name, 'MARKETER',
       'Accepted in the marketer portal before the acceptance records (copied 18 Nov 2026)', p.agreement_accepted_at, p.agreement_accepted_at
FROM platform.marketer_profiles p
WHERE p.agreement_version IS NOT NULL AND p.agreement_accepted_at IS NOT NULL;
