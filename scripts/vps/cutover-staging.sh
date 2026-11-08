#!/usr/bin/env bash
# Switch api-staging.hospio360.com from the old single-jar backend (his-be-staging, :8278) to the
# new api-gateway (:9200), then stop the old service. Run after the first deploy reports every
# service healthy. Reversible: rollback-staging.sh puts the old site file and service back.
set -euo pipefail

SITE=/etc/nginx/sites-available/api-staging.hospio360.com
NEW=$SITE.hospio
OLD_SERVICE=his-be-staging

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  ✓ %s\n' "$*"; }
die()  { printf '  ✗ %s\n' "$*" >&2; exit 1; }

say "1. Is the new backend up?"
for svc in identity-service tenant-service reservation-service platform-service; do
  systemctl is-active --quiet "hospio-$svc" || die "hospio-$svc is not running; deploy first (journalctl -u hospio-$svc -n 50)"
done
curl -fsS http://127.0.0.1:9200/actuator/health | grep -q '"UP"' || die "api-gateway on :9200 is not healthy"
curl -fsS http://127.0.0.1:9200/identity/actuator/health | grep -q '"UP"' || die "gateway cannot reach identity-service"
ok "gateway and services answer on :9200"

say "2. nginx: point api-staging.hospio360.com at :9200"
[ -f "$NEW" ] || die "$NEW is missing; run bootstrap-staging.sh first"
if [ -f "$SITE" ] && [ ! -f "$SITE.his-be.bak" ]; then
  sudo cp "$SITE" "$SITE.his-be.bak"
  ok "old site saved as $SITE.his-be.bak"
fi
sudo cp "$NEW" "$SITE"
sudo nginx -t
sudo systemctl reload nginx
ok "nginx reloaded"
curl -fsS https://api-staging.hospio360.com/identity/actuator/health | grep -q '"UP"' && ok "https://api-staging.hospio360.com/identity/actuator/health is UP" || die "the public URL does not answer; check nginx -t and the DNS record"

say "3. Stop the old backend"
if systemctl list-unit-files | grep -q "^$OLD_SERVICE.service"; then
  sudo systemctl disable --now "$OLD_SERVICE" && ok "$OLD_SERVICE stopped and disabled (unit file kept; its jar stays in ~/apps/his-be-staging)"
else
  ok "$OLD_SERVICE is not installed"
fi

say "Cutover complete."
echo "  Frontend: the staging web app uses NEXT_PUBLIC_API_URL=https://api-staging.hospio360.com; set repo variable NEXT_PUBLIC_DEMO_MODE=false when the screens are wired."
echo "  Rollback: bash scripts/vps/rollback-staging.sh"
