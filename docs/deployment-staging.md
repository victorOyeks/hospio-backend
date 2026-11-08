# Hospio360 — Staging deployment runbook

Deploys the 14 services plus `api-gateway` onto the staging VPS (the Contabo box that already runs
`his-fe-staging`, `hospio360-landing` and coryd-backend), replacing the old single-jar backend
`his-be-staging` behind `api-staging.hospio360.com`.

Layout on the box, the same shape as coryd-backend:

| What | Where |
|---|---|
| Jars | `/opt/hospio-staging/<service>/app.jar` (CI drops `app-new.jar`, the deploy step swaps it in) |
| Shared env | `/opt/hospio-staging/.env.staging` (loaded by every unit; chmod 600) |
| Uploads | `/opt/hospio-staging/storage` |
| Units | `/etc/systemd/system/hospio-<service>.service`, one per service, `java $JAVA_OPTS -jar` |
| Ports | gateway 9200, services 9201–9214 (`README.md` has the table) |
| Domain | `api-staging.hospio360.com` → nginx → `:9200` |
| Database | Postgres database `hospio`, user `hospio_user`, one schema per service (Liquibase) |
| Kafka / Redis | the box's existing broker on `:9092` and Redis on `:6379` (logical DB 2); topics `hospio-*`, groups `hospio-<service>` |

Branch: **`staging`**. A push builds whichever services changed (a change in `common` rebuilds all), ships the
jars over SSH and restarts them in dependency order with a health-checked rollback. The first push to the
branch, or a manual run of the workflow with `services=all`, deploys everything.

## One-time setup

### 1. GitHub

Create the repo, push `staging`, and add three repository secrets (Settings → Secrets and variables → Actions):
`VPS_HOST`, `VPS_USER` (`devoyeks`), `VPS_SSH_KEY` — the same values the frontend repo uses.

### 2. The box

```bash
ssh devoyeks@<vps>
git clone <repo-url> ~/src/hospio-backend && cd ~/src/hospio-backend   # or scp the scripts/ systemd/ nginx/ folders
bash scripts/vps/bootstrap-staging.sh
```

The script checks Java 21, Kafka, Redis, Postgres and free ports; creates `/opt/hospio-staging/*`; creates the
`hospio` database and `hospio_user` with a generated password; writes `.env.staging` with a generated
`JWT_SECRET` and `INTERNAL_API_KEY`; installs and enables the 15 units; and stages the nginx site file
without activating it. It changes nothing about `his-be-staging` yet.

Memory: the units run with `JAVA_OPTS=-Xmx320m …`, about 400 MB per JVM, so all 15 services need roughly
6.3 GB free. The staging box (12 GB, no swap) already runs coryd, marketrun, the old backend and three Node
apps and had about 1.6 GB free on 2026-11-08. Deploy a subset first (Actions → Run workflow →
`services=identity-service,tenant-service,api-gateway`), cut over, and add RAM (or a second box) before the
rest; a unit without a jar stays idle (`ConditionPathExists`), so enabling all 15 is safe.

### 3. First deploy

Push `staging` (or Actions → "Deploy to Staging VPS" → Run workflow → `services=all`). Watch the deploy job:
each service is restarted and polled on `/actuator/health` for up to five minutes before the next one starts.

```bash
systemctl list-units 'hospio-*'
journalctl -u hospio-identity-service -n 100 -f
curl -s http://127.0.0.1:9200/identity/actuator/health
```

### 4. Cutover

```bash
bash scripts/vps/cutover-staging.sh
```

Checks the gateway and services, backs up the current nginx site to `api-staging.hospio360.com.his-be.bak`,
swaps in the new site (same certbot lines, upstream `:9200`), reloads nginx, confirms
`https://api-staging.hospio360.com/identity/actuator/health`, then stops and disables `his-be-staging`.
`scripts/vps/rollback-staging.sh` reverses it.

## After cutover

- Swagger for every service: `https://api-staging.hospio360.com/swagger-ui.html`.
- The staging web app (`web-staging.hospio360.com`) runs in demo mode until its screens are wired to the
  API; its build reads `NEXT_PUBLIC_API_URL=https://api-staging.hospio360.com` and the repo variable
  `NEXT_PUBLIC_DEMO_MODE` (set to `false` to switch).
- Real integrations (POS provider, WhatsApp/SMS/email, object storage) are the `logging`/`local` adapters until
  the keys are added to `.env.staging` and the services restarted.
- The old `hisdb` database and `~/apps/his-be-staging` are untouched; remove them once the new backend has been
  in use for a while.
