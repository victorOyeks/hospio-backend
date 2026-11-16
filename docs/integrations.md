# Integrations managed from the admin portal

Third-party keys and addresses (email, SMS, WhatsApp, Paystack, the POS providers' addresses and the daily fee debit) are edited by a
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
| PAYSTACK | PAYMENTS | secretKey | SECRET | `PAYSTACK_SECRET_KEY` | messaging (guest-message invoices and their webhook) |
| MONIEPOINT | POS | baseUrl | URL | `MONIEPOINT_BASE_URL` | payment |
| OPAY | POS | baseUrl | URL | `OPAY_BASE_URL` | payment |
| FEE_DEBIT_OPAY | FEE_DEBIT | enabled, recipientAccount, recipientName, recipientBankCode, payoutType (`OPAY_WALLET`/`BANK_TRANSFER`) | | `FEE_DEBIT_OPAY_*` | payment |
| FEE_DEBIT_MONIEPOINT | FEE_DEBIT | url | URL | `FEE_DEBIT_MONIEPOINT_URL` | payment |

Once a provider's values are saved in the portal, its env variables can be removed from `.env.staging` (keep them until the portal
values are confirmed working: the env is the fallback if a field is cleared). `MAIL_ENABLED`, `SMS_ENABLED` and `WHATSAPP_ENABLED`
are also read by every service's `MailPolicy` for the `DELIVERIES_ECHO` text; that echo keeps following the env.

## API (platform-service, SUPER_ADMIN only; the usual `ApiResponse` envelope, below is `data`)

- `GET /platform/admin/integrations` → `IntegrationView[]`, every provider in the order above.
- `PUT /platform/admin/integrations/{provider}` body `{"fields": {key: value}, "clear": [keys]}` → `IntegrationView`. A blank or absent
  value keeps what is saved; `clear` removes saved values so the env applies again (a key can't be in both). URL needs http(s) and a
  host (a trailing `/` is dropped), NUMBER is 1–65535, BOOLEAN is `true`/`false`, payoutType one of its two values; anything else is a
  400 with a sentence. Clearing every field deletes the row.
- `POST /platform/admin/integrations/{provider}/test` → `{ok, message}`. Runs in the service that uses the provider, with the settings
  in force there (read fresh): Brevo `GET /v3/account`; SMTP signs in and leaves; Infobip lists the WhatsApp sender's templates (or
  reads the balance without a sender); Termii and Paystack read the balance; Moniepoint/OPay only check the address answers. The fee
  debit is not testable (it would move money). Nothing is ever sent or charged.

```
IntegrationView = { provider, label, group: "EMAIL"|"MESSAGING"|"PAYMENTS"|"POS"|"FEE_DEBIT", testable, updatedByName|null,
                    updatedAt (ISO)|null, fields: [ { key, label, type: "TEXT"|"SECRET"|"URL"|"NUMBER"|"BOOLEAN", hint|null,
                    value|null (SECRET masked "••••••3456"), saved } ] }
```

The connection-status list that used to be `GET /platform/admin/integrations` is now `GET /platform/admin/integrations/status`.

Internal: `GET /platform/internal/integrations/{provider}` (internal API key only) → the decrypted typed record
(`IntegrationSettings.*` in common), `null` when nothing is saved. messaging-service and payment-service read it through
`IntegrationSettingsReader` (60 s cache; platform-service unreachable → the last answer, or the env when there never was one).
`POST /{messaging,payment}/internal/integrations/{provider}/test` runs the checks.

Every save that changes something is written to the activity log as `IntegrationChanged` (topic name `hospio-integration-changed`,
actor `Hospio360 · <name>`, no business): which fields were saved or cleared, never their values. Values are never logged.
