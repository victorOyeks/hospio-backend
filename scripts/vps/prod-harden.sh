#!/bin/bash
# Hardens a fresh Ubuntu 24.04 server for Hospio360.
# usage: prod-harden.sh <hostname> <role app|db> <app_private_ip> <admin_user>
#  - root: SSH key only. <admin_user>: sudo, and the ONLY account allowed to log in with a password.
#  - ufw: app opens 80/443; db opens 5432 only on the private interface (eth1) from <app_private_ip>.
#  - fail2ban (3 tries / 24 h), unattended-upgrades, 4 GB swap, journald 3 days / 1 GB, TZ Africa/Lagos.
# After running, set the admin password interactively: ssh -t root@<server> passwd <admin_user>
# Keep real server addresses and usernames out of this repo.
set -euo pipefail
NAME=$1; ROLE=$2; APP_IP=$3; ADMIN=${4:?admin user required}
export DEBIAN_FRONTEND=noninteractive
hostnamectl set-hostname "$NAME"
timedatectl set-timezone Africa/Lagos
apt-get update -qq
apt-get -y -qq upgrade
apt-get -y -qq install ufw fail2ban unattended-upgrades
# SSH: keys only (file sorts first so it wins over cloud-init's)
cat > /etc/ssh/sshd_config.d/00-hospio-hardening.conf <<C
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin prohibit-password
PubkeyAuthentication yes
MaxAuthTries 3
LoginGraceTime 30
X11Forwarding no
C
sshd -t && systemctl reload ssh
# firewall
ufw --force reset >/dev/null
ufw default deny incoming; ufw default allow outgoing
ufw limit 22/tcp
if [ "$ROLE" = app ]; then ufw allow 80/tcp; ufw allow 443/tcp; fi
if [ "$ROLE" = db ]; then ufw allow in on eth1 from "$APP_IP" to any port 5432 proto tcp; fi
ufw --force enable
# fail2ban for ssh
cat > /etc/fail2ban/jail.d/hospio.conf <<C
[sshd]
enabled = true
maxretry = 3
findtime = 10m
bantime = 24h
C
systemctl enable --now fail2ban; systemctl restart fail2ban
# automatic security updates
cat > /etc/apt/apt.conf.d/20auto-upgrades <<C
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
C
# owner admin user: own password over SSH (password login for this user ONLY), sudo
id "$ADMIN" >/dev/null 2>&1 || adduser --disabled-password --gecos "" "$ADMIN" >/dev/null
usermod -aG sudo "$ADMIN"
install -d -m 700 -o "$ADMIN" -g "$ADMIN" /home/"$ADMIN"/.ssh
install -m 600 -o "$ADMIN" -g "$ADMIN" /root/.ssh/authorized_keys /home/"$ADMIN"/.ssh/authorized_keys
grep -q "^Match User $ADMIN$" /etc/ssh/sshd_config || printf "\n# Hospio: password login allowed for %s only\nMatch User %s\n    PasswordAuthentication yes\n" "$ADMIN" "$ADMIN" >> /etc/ssh/sshd_config
sshd -t && systemctl reload ssh
# swap 4G
if ! swapon --show | grep -q /swapfile; then fallocate -l 4G /swapfile; chmod 600 /swapfile; mkswap /swapfile >/dev/null; swapon /swapfile; echo '/swapfile none swap sw 0 0' >> /etc/fstab; fi
sysctl -qw vm.swappiness=10; echo 'vm.swappiness=10' > /etc/sysctl.d/99-hospio.conf
# journald caps (same as staging)
mkdir -p /etc/systemd/journald.conf.d
printf '[Journal]\nSystemMaxUse=1G\nSystemKeepFree=10G\nSystemMaxFileSize=100M\nMaxRetentionSec=3day\nMaxFileSec=1day\n' > /etc/systemd/journald.conf.d/hospio-retention.conf
systemctl restart systemd-journald
echo "== done $NAME"; ufw status | head -20; sshd -T | grep -E '^(passwordauthentication|permitrootlogin|kbdinteractiveauthentication) '; fail2ban-client status sshd | tail -3; swapon --show
