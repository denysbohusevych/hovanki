#!/bin/bash
# EC2 user data for the Hovanki server VM (Ubuntu 24.04, t3.micro): swap, the kernel's limits for many phones, Docker
# with Compose, /opt/hovanki with the certificate bundle of Amazon RDS (the database's TLS certificate is checked
# against it), /etc/hovanki/tls for the server's own certificate.
# Runs once on the first boot; lego, the certificate and the server are set up by hand afterwards, see docs/deploy.md.
set -euxo pipefail

# 1 GB swap: a t3.micro has 1 GB of RAM for the JVM and the OS (and Caddy, when it is back in front).
if [ ! -f /swapfile ]; then
  fallocate -l 1G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

# Many phones straight to the server (docs/live-v2/server.md §2.4): every connection and every UDP flow through Docker's
# published port is an entry of conntrack, and UDP's socket buffers need room. nf_conntrack is loaded at boot, before
# the settings: otherwise its limit does not exist yet when they are applied. The same lines on a VM set up before:
# docs/deploy.md, «Без Caddy».
echo nf_conntrack > /etc/modules-load.d/hovanki.conf
modprobe nf_conntrack
cat > /etc/sysctl.d/90-hovanki.conf <<'SYSCTL'
net.core.rmem_max = 8388608
net.core.wmem_max = 8388608
net.netfilter.nf_conntrack_max = 262144
net.core.somaxconn = 4096
SYSCTL
sysctl --system

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y docker.io docker-compose-v2
systemctl enable --now docker
usermod -aG docker ubuntu

install -d -o ubuntu -g ubuntu /opt/hovanki
# Certificate authorities of Amazon RDS, all regions (compose.yaml mounts it into the server). AWS rotates them rarely;
# refreshing: the same command (docs/deploy.md).
curl -fsSL -o /opt/hovanki/rds-ca.pem https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem
chown ubuntu:ubuntu /opt/hovanki/rds-ca.pem
# The server's own certificate (compose.yaml mounts it read-only): written by hovanki-cert-deploy.sh, readable by the
# server's user in the image (uid and gid 10001, server/Dockerfile).
install -d -m 0755 -o root -g root /etc/hovanki
install -d -m 0750 -o root -g 10001 /etc/hovanki/tls
