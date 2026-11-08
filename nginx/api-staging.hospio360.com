# api-staging.hospio360.com → api-gateway (:9200) → the services (:9201–9214).
# Drop-in replacement for the block that pointed at the old single-jar backend on :8278.
# The ssl_* lines are the ones certbot already manages on the box; keep them as they are there.

server {
    server_name api-staging.hospio360.com;

    # Receipts, photos and handover packs come through as uploads.
    client_max_body_size 10m;

    location / {
        proxy_pass http://127.0.0.1:9200;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port $server_port;
        proxy_read_timeout 120s;
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
