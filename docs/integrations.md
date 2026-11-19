# Integrations managed from the admin portal

Third-party keys and addresses (email, SMS, WhatsApp, Paystack and the POS providers' addresses) are edited by a
SUPER_ADMIN in the admin portal, **Settings · Integrations**, instead of the server's environment. They are stored encrypted in
`platform.integration_settings` (one row per provider, platform-only RLS) and shown masked.

**Every field falls back to the server's environment.** A field with nothing saved uses its env variable exactly as before, so a
server with nothing saved behaves byte for byte as it did. A value saved in the portal wins over the env value; clearing it hands the
field back to the env. Changes are in force within about a minute (each service caches the settings 60 s), no restart.

Infrastructure stays in the environment: `DB_*`, `JWT_*`, `INTERNAL_API_KEY`, Kafka/Redis, the `*_SERVICE_URL`s, `ALLOWED_ORIGINS`,
`JAVA_OPTS`, `STORAGE_*`, the master key, and the environment guards (`TEXTS_ALLOW_ONLY`, `OTP_DEV_CODE`, `DELIVERIES_ECHO`,
`SEED_DEMO_DATA`, `HOSPIO_LOG_BODIES`).

## Master key

`SECRETS_KEY` (32 bytes, base64: `openssl rand -base64 32`), else `POS_SECRETS_KEY`. It is the same key and format (`SecretBox`,
AES-256-GCM, `v1:`; now in `common`) that already encrypts each hotel's POS keys, so staging needs nothing new: platform-service and
payment-service both read it. Without a key the portal refuses to save. Changing the key makes saved values unreadable: clear them
all and type them again.

## Providers and fields

| Provider | Group | Field | Type | Env fallback | Used by |
|---|---|---|---|---|---|
| BREVO | EMAIL | apiKey | SECRET | `BREVO_API_KEY` | messaging (Brevo API when set, else SMTP) |
| | | fromAddress | TEXT | `MAIL_FROM_ADDRESS` | messaging |
| | | fromName | TEXT | `MAIL_FROM_NAME_DEFAULT` | messaging |
| | | webhookKey | SECRET | `BREVO_WEBHOOK_KEY` | messaging: Brevo's email events (`POST /messaging/webhooks/brevo`, the key as a Bearer token), docs/email.md "Delivery tracking" |
| SMTP | EMAIL | host | TEXT | `SMTP_HOST` | messaging (a sender is built from the saved values over spring.mail.*) |
| | | port | NUMBER | `SMTP_PORT` | |
| | | username | TEXT | `SMTP_USERNAME` | |
| | | password | SECRET | `SMTP_PASSWORD` | |
| | | enabled | BOOLEAN | `MAIL_ENABLED` | messaging: all email on or off |
| INFOBIP | MESSAGING | baseUrl | URL | `INFOBIP_BASE_URL` | messaging |
| | | apiKey | SECRET | `INFOBIP_API_KEY` | |
| | | whatsappSender | TEXT | `INFOBIP_WHATSAPP_SENDER` | |
| | | smsSender | TEXT | `INFOBIP_SMS_SENDER` | |
| | | notifyUrl | URL | `INFOBIP_NOTIFY_URL` | |
| | | webhookKey | SECRET | `INFOBIP_WEBHOOK_KEY` | messaging, also the delivery-report webhook |
| | | smsEnabled | BOOLEAN | `SMS_ENABLED` | messaging |
| | | whatsappEnabled | BOOLEAN | `WHATSAPP_ENABLED` | messaging |
| TERMII | MESSAGING | enabled, baseUrl, apiKey (SECRET), senderId, secretKey (SECRET) | | `TERMII_ENABLED`, `TERMII_BASE_URL`, `TERMII_API_KEY`, `TERMII_SENDER_ID`, `TERMII_SECRET_KEY` | messaging (secretKey: delivery-report webhook) |
| PAYSTACK | PAYMENTS | secretKey | SECRET | `PAYSTACK_SECRET_KEY` | platform (owners paying the monthly invoice, and older device invoices) and messaging (Paystack's webhook, older guest-message invoices). Label "Paystack (monthly invoice payments)" |
| MONIEPOINT | POS | baseUrl | URL | `MONIEPOINT_BASE_URL` | payment |
| OPAY | POS | baseUrl | URL | `OPAY_BASE_URL` | payment |

Once a provider's values are saved in the portal, its env variables can be removed from `.env.staging` (keep them until the portal
values are confirmed working: the env is the fallback if a field is cleared). `MAIL_ENABLED`, `SMS_ENABLED` and `WHATSAPP_ENABLED`
are also read by every service's `MailPolicy` for the `DELIVERIES_ECHO` text; that echo keeps following the env.

## API (platform-service, SUPER_ADMIN only; the usual `ApiResponse` envelope, below is `data`)

- `GET /platform/admin/integrations` → `IntegrationView[]`, every provider in the order above.
- `PUT /platform/admin/integrations/{provider}` body `{"fields": {key: value}, "clear": [keys]}` → `IntegrationView`. A blank or absent
  value keeps what is saved; `clear` removes saved values so the env applies again (a key can't be in both). URL needs http(s) and a
  host (a trailing `/` is dropped), NUMBER is 1–65535, BOOLEAN is `true`/`false`; anything else is a
  400 with a sentence. Clearing every field deletes the row.
- `POST /platform/admin/integrations/brevo/send-test` body `{"to": "name@domain"}` → `{ok, message}` (the Brevo card's **Send a
  test email**). The one action here that sends: messaging-service sends one short plain email, subject "Hospio360 test email —
  delivery tracking check", from the platform (no hotel), recorded in `messaging.deliveries` like any other (kind `TEST_EMAIL`),
  so Brevo's delivered/opened report shows in the connection checks. Refused with a sentence: blank, not an address, a test
  address ("Not sent: that is a test address …"), email switched off, or another test email less than a minute ago (checked in
  the delivery log, so across instances). The answer waits up to 10 s for Brevo: "Brevo accepted the test email…", "The test
  email failed: <Brevo's error>", or "queued". The address is never put in the answer. Internal:
  `POST /messaging/internal/integrations/brevo/send-test`.
- `POST /platform/admin/integrations/{provider}/test` → `{ok, message}`. Runs in the service that uses the provider, with the settings
  in force there (read fresh): Brevo `GET /v3/account`; SMTP signs in and leaves; Infobip lists the WhatsApp sender's templates (or
  reads the balance without a sender); Termii and Paystack read the balance; Moniepoint/OPay only check the address answers. Every
  provider has a test. Nothing is ever sent or charged.

```
IntegrationView = { provider, label, group: "EMAIL"|"MESSAGING"|"PAYMENTS"|"POS", testable, updatedByName|null,
                    updatedAt (ISO)|null, fields: [ { key, label, type: "TEXT"|"SECRET"|"URL"|"NUMBER"|"BOOLEAN", hint|null,
                    value|null (SECRET masked "••••••3456"), saved } ] }
```

**Fee debit removed (19 Nov 2026).** The `FEE_DEBIT_OPAY` and `FEE_DEBIT_MONIEPOINT` providers (group `FEE_DEBIT`) are gone with the
daily debit: Hospio360 bills one monthly invoice (docs/plan-monthly-invoices.md). platform-023 deleted any values saved for them; `PUT`
or `test` on those names answers 404. The admin page shows each provider as a short card (status, last change, Test connection,
Edit) with rarely changed fields under "Advanced".

The connection-status list that used to be `GET /platform/admin/integrations` is now `GET /platform/admin/integrations/status`: live checks made by the service that owns each row (docs/admin-portal.md); the messaging and POS rows reuse the same read-only provider calls as the Test buttons here, with a ~3.5 s limit, and never send or charge anything.

Since 18 Nov 2026 the WhatsApp, Email and SMS rows also read the delivery log (read-only, newest 200 of the last 7 days): "Last message: failed 2 h ago — Message sent not delivered - Account issue (EC_ACCOUNT_ISSUE). Last 7 days: 4 sent, 0 delivered, 4 failed." . Our own refusals (the staging allow-list's "Held back", "Not sent: …", not a phone number) are not outcomes. A row is **ERROR** when its newest three outcomes all failed, even if the key works. Email says when bounces can't be reported (no Brevo webhook key), SMS when Termii's reports can't be believed (no secret key).

**Email health** (honest since 18 Nov 2026, `ConnectionChecks.emailOutcomes`): emails to test addresses (docs/email.md, "Test
addresses are never sent"; older rows from before that rule) are never counted and never the "last email"; the row says how
many were left out ("6 emails to test addresses not counted."). Delivered and bounced are counted only for emails recorded since
**delivery tracking started**, the first event Brevo's webhook reported (`min(provider_event_at)`, 18 Nov 12:30 WAT on staging),
because older emails can only ever say "sent":

- tracked real emails: "Last email: delivered 5 min ago. Tracking since 18 Nov 12:30; last 7 days (real addresses): 4 sent,
  3 delivered, 0 bounced (0%)." (", n failed" for refusals at send time);
- none since tracking started: "Tracking since 18 Nov 12:30. No real emails since delivery tracking started. Before then: last
  email: sent 2 d ago." — **not an error**;
- no report ever: "Last 7 days (real addresses): n sent, n failed." and why deliveries aren't counted.

Email is **ERROR** only when real addresses are failing: the newest 3 real emails all failed, or the bounce rate (hard, soft,
blocked, invalid) of tracked real emails in the 7 days is **10% or more over at least 10 emails** (Brevo and the mailbox
providers treat a few percent as a warning and ~10% as a sending-reputation problem; fewer than 10 emails are too few to
judge). A live Brevo key check that fails is still ERROR. Read-only, as before.

Internal: `GET /platform/internal/integrations/{provider}` (internal API key only) → the decrypted typed record
(`IntegrationSettings.*` in common), `null` when nothing is saved. messaging-service and payment-service read it through
`IntegrationSettingsReader` (60 s cache; platform-service unreachable → the last answer, or the env when there never was one).
`POST /{messaging,payment}/internal/integrations/{provider}/test` runs the checks.

Every save that changes something is written to the activity log as `IntegrationChanged` (topic name `hospio-integration-changed`,
actor `Hospio360 · <name>`, no business): which fields were saved or cleared, never their values. Values are never logged.
