#!/usr/bin/env bash
# One-time preparation of the Hospio360 PRODUCTION app server (Ubuntu 24.04, already hardened with prod-harden.sh).
# Mirrors the staging box one-for-one with "production" in place of "staging":
#   /opt/hospio-staging/.env.staging      → /opt/hospio-production/.env.production   (root:root 600 here)
#   hospio-<service>.service (15)         → same names, same ports 9200-9214
#   his-fe-staging / hospio-admin-staging / hospio-landing-staging (3001/3002/3003)
#                                         → his-fe-production / hospio-admin-production / hospio-landing-production
# Run as root:  bash bootstrap-production.sh <deploy_user> <hotel_host> <admin_host> <api_host> <landing_host> [<landing_alias>]
# Real server addresses and user names never go in this repo; pass them on the command line.
# Safe to run again: every step checks before it changes anything. It never writes the env file's values (see
# docs/deployment-production.md) and never touches the database server.
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "run as root"; exit 1; }
DEPLOY_USER=${1:?deploy user}; HOTEL_HOST=${2:?hotel app host}; ADMIN_HOST=${3:?admin host}; API_HOST=${4:?api host}
LANDING_HOST=${5:?landing host}; LANDING_ALIAS=${6:-}
ROOT=/opt/hospio-production
ENV_FILE=$ROOT/.env.production
KAFKA_DIR=/opt/hospio-kafka
SERVICES=(api-gateway identity-service tenant-service hotel-config-service property-service reservation-service folio-service payment-service fnb-service housekeeping-service maintenance-service inventory-service finance-service messaging-service platform-service)
# unit:port:app-folder:description
FRONTENDS=("his-fe-production:3001:hotel:Hotel app" "hospio-admin-production:3002:admin:Admin portal" "hospio-landing-production:3003:landing:Landing site")
export DEBIAN_FRONTEND=noninteractive
say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()  { printf '  ✓ %s\n' "$*"; }

say "1. Packages (same as staging: Ubuntu OpenJDK 21, Node 22 from NodeSource, nginx, certbot, Docker CE, Redis 7)"
apt-get update -qq
apt-get install -y -qq openjdk-21-jdk-headless nginx certbot python3-certbot-nginx redis-server psmisc curl wget ca-certificates gnupg postgresql-client >/dev/null
if ! command -v node >/dev/null || ! node -v | grep -q '^v22\.'; then
  install -d -m 755 /etc/apt/keyrings
  curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor --yes -o /etc/apt/keyrings/nodesource.gpg
  echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_22.x nodistro main" > /etc/apt/sources.list.d/nodesource.list
  apt-get update -qq && apt-get install -y -qq nodejs >/dev/null
fi
if ! command -v docker >/dev/null; then
  install -d -m 755 /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release; echo "$VERSION_CODENAME") stable" > /etc/apt/sources.list.d/docker.list
  apt-get update -qq && apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin >/dev/null
fi
systemctl enable -q --now redis-server docker nginx
ok "java $(java -version 2>&1 | head -1 | cut -d'"' -f2), node $(node -v), $(nginx -v 2>&1 | cut -d' ' -f3), docker $(docker --version | cut -d' ' -f3 | tr -d ,), redis $(redis-server --version | cut -d' ' -f3)"

say "2. Deploy user $DEPLOY_USER (CI logs in as it; the services run as it, as on staging)"
id "$DEPLOY_USER" >/dev/null 2>&1 || adduser --disabled-password --gecos "" "$DEPLOY_USER" >/dev/null
usermod -aG systemd-journal "$DEPLOY_USER"
install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/$DEPLOY_USER/.ssh"
touch "/home/$DEPLOY_USER/.ssh/authorized_keys"; chown "$DEPLOY_USER:$DEPLOY_USER" "/home/$DEPLOY_USER/.ssh/authorized_keys"; chmod 600 "/home/$DEPLOY_USER/.ssh/authorized_keys"
ok "add the CI public key to /home/$DEPLOY_USER/.ssh/authorized_keys"

say "3. Directories"
install -d -m 755 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$ROOT" "$ROOT/storage"
for svc in "${SERVICES[@]}"; do install -d -m 755 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$ROOT/$svc"; done
install -d -m 755 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/$DEPLOY_USER/apps"
for fe in "${FRONTENDS[@]}"; do install -d -m 755 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "/home/$DEPLOY_USER/apps/${fe%%:*}"; done
# Slot for database backups (off-server object storage is set up separately).
install -d -m 700 /var/backups/hospio
[ -f "$ENV_FILE" ] || install -m 600 -o root -g root /dev/null "$ENV_FILE"
ok "$ROOT/<service>, $ROOT/storage, /home/$DEPLOY_USER/apps/<unit>, $ENV_FILE (root 600; systemd reads it as root)"

say "4. systemd units"
for svc in "${SERVICES[@]}"; do
  cat > "/etc/systemd/system/hospio-$svc.service" <<EOF
[Unit]
Description=Hospio360 $svc (production)
After=network.target redis-server.service docker.service
# Enabled on every box, but only runs once CI has shipped a jar for this service.
ConditionPathExists=$ROOT/$svc/app.jar

[Service]
User=$DEPLOY_USER
Group=$DEPLOY_USER
WorkingDirectory=$ROOT/$svc
EnvironmentFile=$ENV_FILE
ExecStart=/usr/bin/java \$JAVA_OPTS -jar $ROOT/$svc/app.jar
SuccessExitStatus=143
Restart=always
RestartSec=10
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
done
# The web apps' units are installed here once (staging's CI rewrites them each deploy; production CI has no sudo for that).
domain_for() { case "$1" in hotel) echo "$HOTEL_HOST";; admin) echo "$ADMIN_HOST";; landing) echo "$LANDING_HOST";; esac; }
for fe in "${FRONTENDS[@]}"; do
  IFS=: read -r unit port app desc <<<"$fe"
  cat > "/etc/systemd/system/$unit.service" <<EOF
[Unit]
Description=Hospio360 $desc (production)
After=network.target
ConditionPathExists=/home/$DEPLOY_USER/apps/$unit/current/apps/$app/server.js

[Service]
Type=simple
User=$DEPLOY_USER
WorkingDirectory=/home/$DEPLOY_USER/apps/$unit/current/apps/$app
Environment=NODE_ENV=production
Environment=PORT=$port
Environment=HOSTNAME=0.0.0.0
Environment=DOMAIN_URL=$(domain_for "$app")
ExecStart=/usr/bin/node /home/$DEPLOY_USER/apps/$unit/current/apps/$app/server.js
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
done
systemctl daemon-reload
for svc in "${SERVICES[@]}"; do systemctl enable -q "hospio-$svc"; done
for fe in "${FRONTENDS[@]}"; do systemctl enable -q "${fe%%:*}"; done
ok "15 service units + 3 web units installed and enabled (idle until CI ships code)"

say "5. Sudo for $DEPLOY_USER: only the exact commands the deploy workflows run"
{
  echo "# Hospio360 production deploys (written by bootstrap-production.sh). Exact commands only, no wildcards."
  for svc in "${SERVICES[@]}"; do
    u="hospio-$svc"
    echo "$DEPLOY_USER ALL=(root) NOPASSWD: /usr/bin/systemctl enable -q $u, /usr/bin/systemctl restart $u, /usr/bin/systemctl start $u, /usr/bin/systemctl stop $u"
  done
  for fe in "${FRONTENDS[@]}"; do
    IFS=: read -r unit port _ _ <<<"$fe"
    echo "$DEPLOY_USER ALL=(root) NOPASSWD: /usr/bin/systemctl restart $unit.service, /usr/bin/systemctl start $unit.service, /usr/bin/systemctl stop $unit.service, /usr/bin/fuser -k $port/tcp"
  done
} > /etc/sudoers.d/hospio-deploy.tmp
visudo -cqf /etc/sudoers.d/hospio-deploy.tmp && install -m 440 /etc/sudoers.d/hospio-deploy.tmp /etc/sudoers.d/hospio-deploy
rm -f /etc/sudoers.d/hospio-deploy.tmp
ok "/etc/sudoers.d/hospio-deploy"

say "6. Kafka + Zookeeper (Docker, same images as staging, loopback only: no firewall rule)"
install -d -m 755 "$KAFKA_DIR"
cat > "$KAFKA_DIR/docker-compose.yml" <<'EOF'
# Hospio360 production Kafka. Same Confluent images as staging; bound to 127.0.0.1 only (never published, no ufw rule).
# Topics are created on first use (prefix "hospio-"), as on staging.
name: hospio-kafka
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.6.0
    container_name: hospio-zookeeper
    restart: unless-stopped
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
      KAFKA_HEAP_OPTS: "-Xms128m -Xmx256m"
    volumes:
      - zk-data:/var/lib/zookeeper/data
      - zk-log:/var/lib/zookeeper/log
  kafka:
    image: confluentinc/cp-kafka:7.6.0
    container_name: hospio-kafka
    restart: unless-stopped
    depends_on: [zookeeper]
    ports:
      - "127.0.0.1:9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:29092,EXTERNAL://0.0.0.0:9092
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://hospio-kafka:29092,EXTERNAL://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,EXTERNAL:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
      KAFKA_LOG_RETENTION_HOURS: 168
      KAFKA_HEAP_OPTS: "-Xms512m -Xmx1g"
    volumes:
      - kafka-data:/var/lib/kafka/data
volumes:
  zk-data:
  zk-log:
  kafka-data:
EOF
docker compose -f "$KAFKA_DIR/docker-compose.yml" up -d --quiet-pull
ok "hospio-kafka on 127.0.0.1:9092"

say "7. nginx vhosts (HTTP now; certbot --nginx adds HTTPS and the redirect, as on staging)"
proxy_common='        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;'
write_site() { # file server_names body
  [ -f "/etc/nginx/sites-available/$1" ] && grep -q 'managed by Certbot' "/etc/nginx/sites-available/$1" && { ok "$1 already has TLS; left alone"; return; }
  printf '%s\n' "$3" > "/etc/nginx/sites-available/$1"
  ln -sf "/etc/nginx/sites-available/$1" "/etc/nginx/sites-enabled/$1"
  ok "$1"
}
write_site "$HOTEL_HOST" "$HOTEL_HOST" "# $HOTEL_HOST → the hotel app (apps/hotel) on :3001. Same as staging's web-staging site.
server {
    server_name $HOTEL_HOST;
    listen 80;
    listen [::]:80;

    location / {
        proxy_pass http://127.0.0.1:3001;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \"upgrade\";
$proxy_common
    }
}"
write_site "$ADMIN_HOST" "$ADMIN_HOST" "# $ADMIN_HOST → the admin console (apps/admin, also the marketer area) on :3002. Same as staging's admin-staging site.
server {
    server_name $ADMIN_HOST;
    listen 80;
    listen [::]:80;

    location / {
        proxy_pass http://127.0.0.1:3002;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \"upgrade\";
$proxy_common
    }
}"
write_site "$API_HOST" "$API_HOST" "# $API_HOST → api-gateway (:9200) → the services (:9201–9214). Same as staging's api-staging site.
server {
    server_name $API_HOST;
    listen 80;
    listen [::]:80;

    # Receipts, photos and handover packs come through as uploads.
    client_max_body_size 10m;

    location / {
        proxy_pass http://127.0.0.1:9200;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \"upgrade\";
$proxy_common
        proxy_set_header X-Forwarded-Host \$host;
        proxy_set_header X-Forwarded-Port \$server_port;
        proxy_read_timeout 120s;
    }
}"
write_site "$LANDING_HOST" "$LANDING_HOST $LANDING_ALIAS" "# $LANDING_HOST → the landing site (apps/landing) on :3003, behind Cloudflare (proxied). Same as staging's site.
server {
    server_name $LANDING_HOST $LANDING_ALIAS;
    listen 80;
    listen [::]:80;

    location / {
        proxy_pass http://127.0.0.1:3003;
        proxy_http_version 1.1;
$proxy_common
    }
}"
# Same global body limit as staging's nginx.conf.
grep -q 'client_max_body_size' /etc/nginx/nginx.conf || sed -i 's#^\(\s*include /etc/nginx/sites-enabled/\*;\)#\1\n\tclient_max_body_size 500M;#' /etc/nginx/nginx.conf
rm -f /etc/nginx/sites-enabled/default
nginx -t -q && systemctl reload nginx
ok "nginx reloaded"

say "Done. Next (docs/deployment-production.md):"
echo "  1. Write $ENV_FILE (names as staging; production values)."
echo "  2. certbot --nginx -d $HOTEL_HOST -d $ADMIN_HOST -d $API_HOST   (DNS-only records must point here first)"
echo "  3. Set the GitHub 'production' environment secrets, then merge staging → production."
