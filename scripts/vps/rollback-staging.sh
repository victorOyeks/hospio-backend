#!/usr/bin/env bash
# Undo cutover-staging.sh: nginx back to the old backend on :8278 and his-be-staging started again.
# The new services keep running on 9200–9214; stop them with `sudo systemctl stop 'hospio-*'` if needed.
set -euo pipefail
SITE=/etc/nginx/sites-available/api-staging.hospio360.com
[ -f "$SITE.his-be.bak" ] || { echo "no $SITE.his-be.bak to restore"; exit 1; }
sudo systemctl enable --now his-be-staging
sudo cp "$SITE.his-be.bak" "$SITE"
sudo nginx -t && sudo systemctl reload nginx
echo "api-staging.hospio360.com is back on his-be-staging (:8278)"
