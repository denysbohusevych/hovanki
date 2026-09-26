#!/bin/bash
# EC2 user data for the Hovanki server VM (Ubuntu 24.04, t3.micro): swap, Docker with Compose, /opt/hovanki with
# backups/ for the database dumps.
# Runs once on the first boot; the server is started by hand afterwards, see docs/deploy.md.
set -euxo pipefail

# 1 GB swap: a t3.micro has 1 GB of RAM for the JVM, Postgres, Caddy and the OS.
if [ ! -f /swapfile ]; then
  fallocate -l 1G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y docker.io docker-compose-v2
systemctl enable --now docker
usermod -aG docker ubuntu

install -d -o ubuntu -g ubuntu /opt/hovanki
# Written by hovanki-backup.sh as ubuntu; the dumps hold accounts, so only ubuntu may read them.
install -d -o ubuntu -g ubuntu -m 700 /opt/hovanki/backups
