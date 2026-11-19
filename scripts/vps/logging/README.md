# Server log retention (all Hospio services)

Every Hospio service runs as a systemd unit and writes to the systemd journal, so one journald setting caps them all.

| What | Where on the server | Rule |
|---|---|---|
| Service logs (journal) | `/etc/systemd/journald.conf.d/hospio-retention.conf` (this folder's `journald-hospio-retention.conf`) | kept 3 days, at most 1 GB, always leaves 10 GB of disk free; one file per day |
| syslog, auth, kern, mail, user, cron | `/etc/logrotate.d/rsyslog` | rotated daily (or at 200 MB), 3 kept, compressed |
| nginx access/error | `/etc/logrotate.d/nginx` | daily, 3 kept, compressed |
| Kafka container logs | inside the Kafka container | already capped by Kafka at 10 × 100 MB |

Apply on a new server (as root):

```sh
mkdir -p /etc/systemd/journald.conf.d
cp journald-hospio-retention.conf /etc/systemd/journald.conf.d/hospio-retention.conf
systemctl restart systemd-journald && journalctl --vacuum-time=3d --vacuum-size=1G
sed -i -E 's/^(\s*)weekly/\1daily/; s/^(\s*)rotate [0-9]+/\1rotate 3/' /etc/logrotate.d/rsyslog
grep -q maxsize /etc/logrotate.d/rsyslog || sed -i -E '0,/\{/s//{\n\tmaxsize 200M/' /etc/logrotate.d/rsyslog
sed -i -E 's/^(\s*)rotate [0-9]+/\1rotate 3/' /etc/logrotate.d/nginx
logrotate -d /etc/logrotate.conf   # check the config
```

Applied on staging 19 Nov 2026 (journal 4.0 GB → 448 MB, /var/log 5.4 GB → 1.2 GB). Backup of the previous config: `/root/log-config-backup-*`.
To read older logs than 3 days you can't — they're deleted by design. Read recent ones with `journalctl -u hospio-<service> --since "2 days ago"`.
