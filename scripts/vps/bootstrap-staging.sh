#!/usr/bin/env bash
# One-time preparation of the staging VPS for hospio-backend. Run as the deploy user (devoyeks)
# from a checkout of this repo:  bash scripts/vps/bootstrap-staging.sh
#
# It does not touch the old his-be-staging service or the nginx site; that is scripts/vps/cutover-staging.sh,
# run after the first deploy is healthy. Safe to run again: every step checks before it changes anything.
set -euo pipefail

ROOT=/opt/hospio-staging
ENV_FILE=$ROOT/.env.staging
DB_NAME=${DB_NAME:-hospio}
DB_USER=${DB_USER:-hospio_user}
DEPLOY_USER=${DEPLOY_USER:-$USER}
SERVICES=(api-gateway identity-service tenant-service hotel-config-service property-service reservation-service folio-service payment-service fnb-service housekeeping-service maintenance-service inventory-service finance-service messaging-service platform-service)
HERE=$(cd "$(dirname "$0")/../.." && pwd)

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  ✓ %s\n' "$*"; }
warn() { printf '  ! %s\n' "$*"; }
die()  { printf '  ✗ %s\n' "$*" >&2; exit 1; }

say "1. What the box has"
java_ver=$(java -version 2>&1 | head -1 || true)
echo "  java: ${java_ver:-missing}"
echo "$java_ver" | grep -q '"21' || die "Java 21 is required (found: ${java_ver:-none}). Install temurin-21-jdk and re-run."
mem_mb=$(awk '/MemTotal/ {printf "%d", $2/1024}' /proc/meminfo)
avail_mb=$(awk '/MemAvailable/ {printf "%d", $2/1024}' /proc/meminfo)
fits=$(( avail_mb / 420 ))
echo "  memory: ${mem_mb} MB total, ${avail_mb} MB available → room for about ${fits} services at ~420 MB each (JAVA_OPTS caps the heap at 320 MB)"
[ "$fits" -lt 15 ] && warn "all 15 services need ~6.3 GB free. Deploy a subset (workflow input services=identity-service,tenant-service,api-gateway) and add RAM before the rest; stopping his-be-staging frees what it uses now."
for port in 9092:Kafka 6379:Redis 5432:Postgres; do
  p=${port%%:*}; name=${port##*:}
  if ss -ltn | grep -q ":$p "; then ok "$name is listening on :$p"; else die "$name is not listening on :$p. The services need it (coryd-backend on this box uses the same Kafka and Redis)."; fi
done
busy=$(ss -ltn | grep -E ':92(0[0-9]|1[0-4]) ' || true)
[ -n "$busy" ] && die "Ports 9200–9214 must be free; in use:\n$busy"
ok "ports 9200–9214 are free"
sudo -n true 2>/dev/null || warn "sudo will prompt for your password in the next steps"

say "2. Directories"
sudo mkdir -p "$ROOT/storage"
for svc in "${SERVICES[@]}"; do sudo mkdir -p "$ROOT/$svc"; done
sudo chown -R "$DEPLOY_USER:$DEPLOY_USER" "$ROOT"
ok "$ROOT/<service> owned by $DEPLOY_USER"

say "3. Postgres database $DB_NAME"
if sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='$DB_USER'" | grep -q 1; then
  ok "role $DB_USER exists"
  DB_PASSWORD=${DB_PASSWORD:-}
  [ -z "$DB_PASSWORD" ] && [ -f "$ENV_FILE" ] && DB_PASSWORD=$(sed -n 's/^DB_PASSWORD=//p' "$ENV_FILE")
  [ -z "$DB_PASSWORD" ] && die "Role $DB_USER exists but its password is unknown. Export DB_PASSWORD=... and re-run."
else
  DB_PASSWORD=${DB_PASSWORD:-$(openssl rand -base64 24 | tr -d '/+=')}
  sudo -u postgres psql -qc "CREATE USER $DB_USER WITH PASSWORD '$DB_PASSWORD';"
  ok "role $DB_USER created"
fi
if sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='$DB_NAME'" | grep -q 1; then
  ok "database $DB_NAME exists"
else
  sudo -u postgres psql -qc "CREATE DATABASE $DB_NAME OWNER $DB_USER;"
  ok "database $DB_NAME created"
fi
# Liquibase needs each service's schema to exist before it can write its changelog table into it.
for sch in app identity tenant hotelconfig property reservation folio payment fnb housekeeping maintenance inventory finance messaging platform; do
  sudo -u postgres psql -d "$DB_NAME" -qc "CREATE SCHEMA IF NOT EXISTS $sch AUTHORIZATION $DB_USER;"
done
ok "15 schemas present (app + one per service), owned by $DB_USER"

say "4. Environment file $ENV_FILE"
if [ -f "$ENV_FILE" ]; then
  ok "exists; leaving it alone (edit by hand if a value must change)"
else
  cat > "$ENV_FILE" <<EOF
SPRING_PROFILES_ACTIVE=staging

DB_HOST=localhost
DB_PORT=5432
DB_NAME=$DB_NAME
DB_USERNAME=$DB_USER
DB_PASSWORD=$DB_PASSWORD

# Signs every access token; the same value in every service.
JWT_SECRET=$(openssl rand -base64 48 | tr -d '/+=')
JWT_EXPIRATION_MS=3600000
# Gates the service-to-service /internal/** endpoints.
INTERNAL_API_KEY=$(openssl rand -hex 32)

KAFKA_SERVERS=localhost:9092
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_DATABASE=2

POS_PROVIDER=logging
CHANNELS_PROVIDER=logging
STORAGE_TYPE=local
STORAGE_DIR=$ROOT/storage

MANAGER_SPEND_LIMIT=500000
MANAGER_DISCOUNT_LIMIT=50000
ORG_CREDIT_OWNER_LIMIT=1000000

# Browsers allowed to call the gateway.
ALLOWED_ORIGINS=https://web-staging.hospio360.com,http://localhost:3000

# Every service runs on this box.
IDENTITY_HOST=localhost
TENANT_HOST=localhost
HOTEL_CONFIG_HOST=localhost
PROPERTY_HOST=localhost
RESERVATION_HOST=localhost
FOLIO_HOST=localhost
PAYMENT_HOST=localhost
FNB_HOST=localhost
HOUSEKEEPING_HOST=localhost
MAINTENANCE_HOST=localhost
INVENTORY_HOST=localhost
FINANCE_HOST=localhost
MESSAGING_HOST=localhost
PLATFORM_HOST=localhost

# Keeps 15 JVMs inside the box's memory: ~350 MB each at the cap.
JAVA_OPTS=-Xms64m -Xmx320m -XX:MaxMetaspaceSize=160m -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1 -Djava.security.egd=file:/dev/./urandom
EOF
  chmod 600 "$ENV_FILE"
  ok "written with generated DB password, JWT secret and internal key (chmod 600)"
fi

say "5. systemd units"
sudo cp "$HERE"/systemd/hospio-*.service /etc/systemd/system/
sudo systemctl daemon-reload
ok "15 units installed; the deploy step enables and starts each one when its jar arrives (ConditionPathExists keeps the rest idle)"

say "6. nginx site (staged, not yet active)"
sudo cp "$HERE/nginx/api-staging.hospio360.com" /etc/nginx/sites-available/api-staging.hospio360.com.hospio
ok "copied to /etc/nginx/sites-available/api-staging.hospio360.com.hospio; cutover-staging.sh swaps it in"

say "Done. Next:"
echo "  1. In the GitHub repo add secrets VPS_HOST, VPS_USER, VPS_SSH_KEY (same values as the frontend repo)."
echo "  2. Push the staging branch (or run the 'Deploy to Staging VPS' workflow by hand with services=all)."
echo "  3. When every service is healthy, run: bash scripts/vps/cutover-staging.sh"
