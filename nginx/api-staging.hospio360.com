# api-staging.hospio360.com → api-gateway (:9200) → the services (:9201–9214).
# Drop-in replacement for the block that pointed at the old single-jar backend on :8278.
# The ssl_* lines are the ones certbot already manages on the box; keep them as they are there.

# Both blocks below live at http level (sites-enabled is included inside http {}); names are prefixed so they don't
# clash with the other product's sites on the same nginx.

# Kept-alive connections to the gateway instead of a new TCP connection per request.
upstream hospio_staging_gateway {
    server 127.0.0.1:9200;
    keepalive 32;
}

# Sign-in, PIN sign-in, sign-up and forgotten password: 30 a minute per client address with a burst of 20, more is 429.
# Generous because a hotel's staff share one public address and PIN sign-ins bunch up at shift change. Refresh,
# device and logout calls are not limited. identity-service has its own per-address limit behind this as a backstop.
limit_req_zone $binary_remote_addr zone=hospio_staging_auth:10m rate=30r/m;

server {
    server_name api-staging.hospio360.com;

    # Receipts, photos and handover packs come through as uploads.
    client_max_body_size 10m;

    gzip on;
    gzip_proxied any;
    gzip_min_length 1024;
    gzip_vary on;
    gzip_types application/json application/problem+json application/javascript text/javascript text/css text/plain;

    # No websockets go through the API (no Upgrade handling anywhere in the services), so every location sends an
    # empty Connection header and the upstream connection stays open for the next request.
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Port $server_port;
    proxy_connect_timeout 5s;

    location ~ ^/identity/auth/(sign-in|pin-sign-in|sign-up|forgot-password)$ {
        limit_req zone=hospio_staging_auth burst=20 nodelay;
        limit_req_status 429;
        proxy_pass http://hospio_staging_gateway;
        proxy_read_timeout 30s;
    }

    # Reports and exports are built on request (the gateway gives them 60s too).
    location ~ ^/(platform/reports|fnb/sales/history/export) {
        proxy_pass http://hospio_staging_gateway;
        proxy_read_timeout 60s;
    }

    location / {
        proxy_pass http://hospio_staging_gateway;
        proxy_read_timeout 30s;
    }

    listen [::]:443 ssl; # managed by Certbot
    listen 443 ssl; # managed by Certbot
    ssl_certificate /etc/letsencrypt/live/api-dev.marketrunn.com/fullchain.pem; # managed by Certbot
    ssl_certificate_key /etc/letsencrypt/live/api-dev.marketrunn.com/privkey.pem; # managed by Certbot
    include /etc/letsencrypt/options-ssl-nginx.conf; # managed by Certbot
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem; # managed by Certbot
}

server {
    if ($host = api-staging.hospio360.com) {
        return 301 https://$host$request_uri;
    } # managed by Certbot

    listen 80;
    listen [::]:80;
    server_name api-staging.hospio360.com;
    return 404; # managed by Certbot
}
