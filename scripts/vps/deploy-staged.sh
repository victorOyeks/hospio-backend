#!/usr/bin/env bash
# Runs on the staging VPS (piped over SSH by .github/workflows/deploy-staging.yml): swaps in the jars staged as
# /opt/hospio-staging/<svc>/app-new.jar for this commit, one service at a time in dependency order, each with a
# health-checked restart and automatic rollback if it doesn't come back up.
# Needs CHANGED_SERVICES (JSON array or comma list) and DEPLOY_SHA in the environment.
set -e
changed=$(echo "$CHANGED_SERVICES" | tr -d '[]" ' | tr ',' '\n')
missing=""
echo "Changed services: $(echo $changed | tr '\n' ' ')"

declare -A health_urls
health_urls["api-gateway"]="http://localhost:9200/actuator/health"
health_urls["identity-service"]="http://localhost:9201/identity/actuator/health"
health_urls["tenant-service"]="http://localhost:9202/tenant/actuator/health"
health_urls["hotel-config-service"]="http://localhost:9203/hotel-config/actuator/health"
health_urls["property-service"]="http://localhost:9204/property/actuator/health"
health_urls["reservation-service"]="http://localhost:9205/reservation/actuator/health"
health_urls["folio-service"]="http://localhost:9206/folio/actuator/health"
health_urls["payment-service"]="http://localhost:9207/payment/actuator/health"
health_urls["fnb-service"]="http://localhost:9208/fnb/actuator/health"
health_urls["housekeeping-service"]="http://localhost:9209/housekeeping/actuator/health"
health_urls["maintenance-service"]="http://localhost:9210/maintenance/actuator/health"
health_urls["inventory-service"]="http://localhost:9211/inventory/actuator/health"
health_urls["finance-service"]="http://localhost:9212/finance/actuator/health"
health_urls["messaging-service"]="http://localhost:9213/messaging/actuator/health"
health_urls["platform-service"]="http://localhost:9214/platform/actuator/health"

ordered=(
  identity-service
  tenant-service
  hotel-config-service
  property-service
  reservation-service
  folio-service
  payment-service
  fnb-service
  housekeeping-service
  maintenance-service
  inventory-service
  finance-service
  messaging-service
  platform-service
  api-gateway
)

for svc in "${ordered[@]}"; do
  if ! echo "$changed" | grep -qx "$svc"; then
    echo "  — $svc unchanged, skipping"
    continue
  fi
  staged_sha=$(cat "/opt/hospio-staging/$svc/app-new.sha" 2>/dev/null || echo none)
  if [ ! -f "/opt/hospio-staging/$svc/app-new.jar" ] || [ "$staged_sha" != "$DEPLOY_SHA" ]; then
    if [ "$(cat "/opt/hospio-staging/$svc/app.sha" 2>/dev/null)" = "$DEPLOY_SHA" ]; then
      # A rerun of failed jobs: this service already shipped from this commit on an earlier attempt.
      echo "  — $svc already running this commit, skipping"
      rm -f "/opt/hospio-staging/$svc/app-new.jar" "/opt/hospio-staging/$svc/app-new.sha"
      continue
    fi
    echo "  ✗ No JAR from this commit for $svc (staged: $staged_sha; its build job failed?) — leaving it as it is"
    rm -f "/opt/hospio-staging/$svc/app-new.jar" "/opt/hospio-staging/$svc/app-new.sha"
    missing="$missing $svc"
    continue
  fi
  rm -f "/opt/hospio-staging/$svc/app-new.sha"
  echo "→ Deploying $svc"
  [ -f /opt/hospio-staging/$svc/app.jar ] && cp /opt/hospio-staging/$svc/app.jar /opt/hospio-staging/$svc/app.jar.bak
  mv /opt/hospio-staging/$svc/app-new.jar /opt/hospio-staging/$svc/app.jar
  echo "$DEPLOY_SHA" > /opt/hospio-staging/$svc/app.sha
  sudo systemctl enable -q hospio-$svc
  sudo systemctl restart hospio-$svc
  ok=false
  for i in $(seq 1 60); do
    status=$(wget -qO- "${health_urls[$svc]}" 2>/dev/null | grep -o '"status":"UP"' || echo "")
    if [ -n "$status" ]; then
      echo "  ✓ $svc healthy"
      ok=true
      break
    fi
    sleep 5
  done
  if [ "$ok" = "false" ]; then
    echo "  ✗ $svc did not become healthy in 300s — rolling back"
    [ -f /opt/hospio-staging/$svc/app.jar.bak ] && cp /opt/hospio-staging/$svc/app.jar.bak /opt/hospio-staging/$svc/app.jar
    sudo systemctl restart hospio-$svc
    exit 1
  fi
  rm -f /opt/hospio-staging/$svc/app.jar.bak
done
if [ -n "$missing" ]; then
  echo "✗ Deployed everything that built, but these did not ship:$missing"
  exit 1
fi
echo "✓ Staging deployment complete"
