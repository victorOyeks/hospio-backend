# System health (Platform → System health)

One page in the admin portal that answers "is the server running out of anything, and is every Hospio service up?", plus alerts
to the Super admins when something goes wrong. Built 19 Nov 2026.

- **Page:** admin portal → Platform → **System health** (`/dashboard/system`). Refreshes itself every 30 seconds while open;
  **Refresh now** asks the server for a new look.
- **Who:** Super admin and Support (Support takes the calls when something is down). Finance admins, marketers and every
  business role are refused (the menu hides it; the API answers 403).
- **API:** `GET /platform/admin/system-health[?fresh=true]` (platform-service, `AdminSystemHealthController`). The answer is
  kept 15 s, so several people with the page open cost one collection; `fresh=true` gets a new one at most every 5 s.
- **Alert limits:** Settings → **System health** (`PUT /platform/admin/settings/system`, Super admin).
- **Code:** `platform-service/src/main/java/com/hospio/platform/system/`. QA script: hospio-frontend `docs/qa/75-system-health.md`.

## Why in platform-service

platform-service already gathers the admin portal's connection checks (`IntegrationsService`), owns the alerts table, the
platform settings and the Super admins' notifications, and has Postgres, Kafka and Redis clients. A separate module would be
another JVM (~400 MB) on a box that is already at ~85% memory. It runs on the same server as every other unit, so what it
reads about "this server" is the server Hospio runs on. If services ever move to several servers, each server's figures need
an agent of their own (this page then shows the platform-service's server only).

## What each part shows

### Banner

**All good** (green), **Warning** (amber) or **Problem** (red), with one line per reason, problems first. A note under it
says **"No scheduled backup"** while there is none (see Database).

### Headline tiles

| Tile | Meaning |
| --- | --- |
| Disk space left | Free space on the main disk (`/`), % used, total. Amber from 80% used, red from 90%. |
| Memory | Memory in use (total minus what Linux says is available, like `free`), and swap. |
| CPU | % of all cores busy since the previous look, and the 1 / 5 / 15-minute load against the number of cores. |
| Services up | Hospio units running and healthy, of all of them. |
| Database | Size of the Hospio database, connections in use of Postgres' maximum. |

### Services

Every Hospio unit on the server: the 14 services, the API gateway and the three web apps (`his-fe-staging`,
`hospio-admin-staging`, `hospio-landing-staging`). Problems first. Per unit:

- **Status** from systemd: running, failed (stopped after an error), stopped, restarting, not installed.
- **Health:** a service's own `/actuator/health` on its local port (4 s limit); a web app is healthy when its page answers
  (any HTTP status under 400, e.g. 307 to the sign-in page).
- **Version:** the commit CI deployed (`/opt/hospio-staging/<svc>/app.sha`; `~/apps/<unit>/current/.sha` for the web apps,
  written by the web deploys since 19 Nov 2026); hover shows when.
- **Up for**, **Restarts** (automatic restarts since the unit was last started; 3 or more turn the row amber),
  **Memory** (the process' resident memory) and **CPU** (% of the whole server since the previous look).

Other apps on the same server (CoRyd, Postgres, Docker/Kafka, nginx, Redis) are not in the list, but count in the server's
totals; "Also running on this server" lists the larger ones with their memory.

### Server

- **Disks:** every real disk (one row per device), used / free / % used, big bars. Free is what ordinary programs may still
  write (like `df` "Avail"), so used + free is a little under the size (ext4 keeps ~5% for root).
- **"Full in about N days":** from the 5-minute snapshots, a straight line through the main disk's use. Shown once there are
  6 hours of history and the disk has grown at least 50 MB; otherwise "steady" or "not enough history".
- **Memory and swap**, how much of the used memory is Hospio's own services, **waiting for memory** (`/proc/pressure/memory`
  "some avg60": the share of the last minute some program was stalled for memory; above a few % the server is short).
- **What uses the disk:** sizes of `/var/log`, the system journal, `/opt/hospio-staging` (service jars and uploaded files),
  `~/apps` (web apps), `~/backups`, all databases and Kafka's event logs. Folders are measured in the background every
  15 minutes ("Being measured" right after a restart); a folder the service may not fully read is marked "the real size is
  larger".
- CPU load, uptime, OS, kernel and the server's time.

### Database

PostgreSQL version; the Hospio database's size and every database's size; size per service schema; the five largest tables;
connections in use of `max_connections` (every app on the server shares them); the longest running query (Hospio's own
connections only: the database user can't see other users' queries); the cache hit ratio (should stay above 99%); rows waiting
for autovacuum.

**Backups:** shows **No scheduled backup** in red until there is one. Nothing copies the database off this server today, so a
failed disk loses everything. When backups are set up, set `SYSTEM_HEALTH_BACKUPS` in `/opt/hospio-staging/.env.staging` to a
short description (e.g. `Nightly pg_dump to Backblaze, kept 30 days`) and restart platform-service.

### Kafka, Redis, certificates

- **Kafka:** brokers, topics, size of the event logs (the whole broker, shared with CoRyd), and for every Hospio consumer group
  how many messages wait to be read, whether that is growing, and "no progress N min" when it has stopped reading.
- **Redis:** version, memory used and the highest so far, the limit (none set on staging), keys, uptime.
- **TLS certificates:** days until each public address's certificate expires (amber under 21 days, red under 7). Read from this
  server's nginx (127.0.0.1:443 naming the address), i.e. the certificate certbot has to renew here, also for hospio360.com
  which the public reaches through Cloudflare. Checked once an hour.

### Trends

A snapshot every 5 minutes (CPU %, memory %, swap %, main disk % and bytes, Hospio database size, services up), kept 7 days
in `platform.system_snapshots`. **24 h** shows every snapshot, **7 days** hourly averages. Hover a line to read a point.

## Alerts

Checked once a minute (`SystemHealthJob`, one instance). Each condition alerts **once** when it starts: an alert on the
Dashboard's System alerts ("Server · …", with an Open link to this page), the **bell** of every Super admin and, unless
switched off, an **email** to each Super admin (Brevo, the usual mail path). It alerts again only if it gets **worse**
(warning → critical) and once more when it **clears** ("Recovered: … back to normal after N minutes"); the dashboard alert is
then dismissed automatically. Dismissing the dashboard alert by hand does not re-raise it while the problem lasts. Never SMS or
WhatsApp. The raised conditions are kept in `platform.system_alert_states`, so a restart doesn't alert again.

| Condition | Default limit (Settings → System health) | Wait |
| --- | --- | --- |
| A disk filling up | warning at **85%** used, critical at **95%** (clears 1 point under the warning) | none |
| Memory running out | memory **90%** used **and** swap **75%** used (or memory 97% alone) | **10 min** |
| A service down | not running (failed, stopped, restarting) or unhealthy | **2 min** |
| A certificate expiring | under **14 days** left (critical under 7) | none |
| Kafka consumer stuck | **500** messages waiting with **no progress for 10 min** | (in the rule) |
| Database connections | over **80%** of `max_connections` | 2 min |
| Postgres, Kafka or Redis unreachable | — | 2 min (the service wait) |

How long a condition has lasted is counted in memory, so after a platform-service restart the wait starts again. While
platform-service itself is down nothing is checked (its own unit shows on the page once it is back). Emails and the bell go
through Kafka and messaging-service, so with Kafka down only the Dashboard alert is made.

Alerts go to the **Super admins** only (on staging: amoria@hospio360.com, a test inbox, and victor@oyeksglobal.com). The
**Email** switch in the settings leaves the bell and dashboard on.

## How to act

| Seen | What to do |
| --- | --- |
| Disk filling | Look at "What uses the disk". **Journal:** `sudo journalctl --vacuum-size=500M` (and set `SystemMaxUse=500M` in `/etc/systemd/journald.conf`, then `sudo systemctl restart systemd-journald`). **Logs:** `sudo find /var/log -name "*.gz" -mtime +14 -delete`; check `/etc/logrotate.d/` covers nginx. **Old backups:** move `~/backups/*` off the server. **Docker:** `docker system df`, then `docker image prune` (never `compose down` the Kafka stack). If use keeps growing, grow the disk in the Contabo panel (then `sudo growpart /dev/sda 1 && sudo resize2fs /dev/sda1`). |
| Memory running out | Look at the services' memory; a service far above the others may leak: restart it (`sudo systemctl restart hospio-<svc>`). Lasting pressure means the box needs more RAM or fewer JVMs (each Hospio service has a heap cap in `JAVA_OPTS`). |
| A service down | `sudo systemctl status hospio-<svc>` and `sudo journalctl -u hospio-<svc> -n 200`; fix the cause, then `sudo systemctl restart hospio-<svc>`. A failed deploy: push a `[deploy-all]` commit to `staging`. |
| CPU busy | Short peaks during deploys are normal. Lasting load above the cores: find the busy service in the table. |
| Certificate expiring | `sudo certbot renew` (certbot's timer should do it; check `systemctl list-timers | grep certbot`). |
| Kafka stuck | `sudo journalctl -u hospio-<svc>` of the reader for an error on one message; restarting the service resumes reading. |
| Database connections high | Look for a service holding many (each has a pool of up to 6); restart it. Raising `max_connections` needs more memory. |
| No scheduled backup | Set up a nightly `pg_dump` copied off the server, then set `SYSTEM_HEALTH_BACKUPS`. |

## Safety

- Read-only. The service runs **`systemctl show`** and **`systemctl list-units`** only, started directly (no shell), with every
  argument checked against a strict pattern, a 4-second limit and a fixed unit list from configuration (`CommandRunner`,
  `UnitProbe`). Nothing in a request can change what is run.
- Postgres: catalog and statistics views only, with a 3-second statement limit. Kafka: the admin API's describe/list calls.
  Redis: `INFO`. Certificates: a TLS handshake whose certificate is read and nothing sent.
- Nothing on the page is a secret: no environment values, keys, passwords or connection strings; query texts are not shown.
- `/actuator/health` stays as before: each service answers only `{"status":"UP"}` (details off, Spring's default), also through
  the gateway, so nothing more leaks.
- **No change to the server was needed:** everything is readable by the `devoyeks` user the services run as (`/proc`,
  `systemctl show`, `/var/log` sizes, the deploy markers). No sudo rule, group or permission was added.

## Configuration (platform-service, `application.yml` → `hospio.system-health`)

| Env | Default | Meaning |
| --- | --- | --- |
| `SYSTEM_HEALTH_ENABLED` | `true` | The page and the alert job. |
| `SYSTEM_HEALTH_UNIT_PREFIX` | `hospio-` | Units are `<prefix><svc>-service` and `<prefix>api-gateway`. |
| `SYSTEM_HEALTH_GATEWAY_URL` | `http://localhost:9200` | The gateway's health. Services use `services.<svc>.url`. |
| `SYSTEM_HEALTH_DEPLOY_DIR` / `SYSTEM_HEALTH_APPS_DIR` | `/opt/hospio-staging` / `/home/devoyeks/apps` | Deploy markers. |
| `SYSTEM_HEALTH_FRONTENDS` | the three staging web apps | `unit=port=Name,...` |
| `SYSTEM_HEALTH_CERT_HOSTS` | hospio360.com, www, web-staging, admin-staging, api-staging | Addresses whose certificate is checked. |
| `SYSTEM_HEALTH_DIRS` | logs, journal, /opt/hospio-staging, ~/apps, ~/backups | `Label=/path,...` |
| `SYSTEM_HEALTH_BACKUPS` | empty | Backup description; empty = "No scheduled backup". |
