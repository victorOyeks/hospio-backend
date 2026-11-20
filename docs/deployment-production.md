# Production deployment

Production mirrors staging (`deployment-staging.md`) one-for-one, with "production" in place of "staging". Server addresses, user
names and keys are never written in this repo (it is public); they live in the GitHub environment `production` and on the servers.

## Branches

- Push to `staging`: deploys staging, as before. `develop` is no longer part of the flow (kept, not deleted).
- Open a pull request `staging` → `production`; merging it deploys production. `production` takes no direct pushes.
- Both repos work the same way (hospio-backend: `deploy-production.yml`; frontend: `deploy-{hotel,admin,landing}-production.yml`).
- `[deploy-all]` in the merge commit message, or running the workflow by hand with `services=all`, redeploys every service.

## Hosts

| Host | What | Port on the app server |
|---|---|---|
| app.hospio360.com | hotel app (apps/hotel) | 3001 (`his-fe-production`) |
| backoffice.hospio360.com | admin console + marketer area (apps/admin) | 3002 (`hospio-admin-production`) |
| hospio360.com, www | landing site (apps/landing), behind Cloudflare (proxied) | 3003 (`hospio-landing-production`) |
| api-prod.hospio360.com | api-gateway → services | 9200 → 9201–9214 (`hospio-<service>`) |

Two servers: an app server (everything above, Kafka + Zookeeper in Docker on 127.0.0.1 only, Redis) and a database server
(PostgreSQL 16, reachable from the app server's private address only, TLS required: `DB_SSLMODE=require`).

## Layout on the app server

- `/opt/hospio-production/<service>/app.jar` (+ `app.sha`), `/opt/hospio-production/storage`
- `/opt/hospio-production/.env.production`: root-only (600), same variable names as `.env.staging`
- `~<deploy user>/apps/<unit>/current`: the web apps' standalone bundles
- `/opt/hospio-kafka/docker-compose.yml`: Kafka and Zookeeper (cp-kafka/cp-zookeeper 7.6.0, topics auto-created)
- `/etc/sudoers.d/hospio-deploy`: the deploy user may only stop/start/restart/enable the Hospio units (exact commands)

`scripts/vps/bootstrap-production.sh` builds all of this on a fresh Ubuntu 24.04 server hardened with `prod-harden.sh`.

## Production values (differences from staging)

- `SPRING_PROFILES_ACTIVE=prod`: the backup one-time code is refused (identity-service will not start with `OTP_FIXED_CODE` set).
- Off: `SEED_DEMO_DATA`, `HOSPIO_LOG_BODIES`, `DELIVERIES_ECHO`, `TEXTS_ALLOW_ONLY`, `QA_FEE_SIMULATION`, `QA_TOOLS`, `BILLING_QA_ONLY`;
  `LEGAL_MATERIAL_NOTICE_DAYS` at its default (30).
- `HOTEL_APP_URL`, `ADMIN_APP_URL`, `ALLOWED_ORIGINS`, `INFOBIP_NOTIFY_URL` and `SYSTEM_HEALTH_*` point at the production hosts and folders.
- `JWT_SECRET`, `INTERNAL_API_KEY`, `POS_SECRETS_KEY`, `INFOBIP_WEBHOOK_KEY` are production's own.
- Paystack: a live key, entered in the backoffice (Settings → Integrations) or as `PAYSTACK_SECRET_KEY`.
- The first super admin is created by identity-service on the empty database (`BOOTSTRAP_ADMIN_EMAIL`, see admin-portal.md); its
  temporary password is emailed, so `MAIL_ENABLED=true` and the Brevo key must be in place before the first deploy.
- The web apps build with `NEXT_PUBLIC_DEMO_MODE=false`, `NEXT_PUBLIC_DEMO_AUTH=false`, `NEXT_PUBLIC_API_LOG=false`,
  `NEXT_PUBLIC_SHOW_DELIVERIES=false` and the production addresses (fixed in the workflows, not Actions variables).

## GitHub environment `production` (each repo)

Secrets `PROD_VPS_HOST`, `PROD_VPS_USER`, `PROD_VPS_SSH_KEY` (a key used only by production CI). They are named apart from staging's
`VPS_*` so a missing one can never fall back to the staging box; every production workflow stops before building when one is missing.

## Backups

Not scheduled yet. Slot: `/var/backups/hospio` on the app server and `SYSTEM_HEALTH_BACKUPS` (shown on Platform → System health).
