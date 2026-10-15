#!/bin/bash
# Gets and renews the server's Let's Encrypt certificate with lego over DuckDNS: DNS-01, so no port 80, and the first
# certificate comes while Caddy still holds 80 and 443 (docs/deploy.md, «Без Caddy»; docs/live-v2/server.md §2.3).
# Started twice a day by hovanki-cert.timer through hovanki-cert.service, as root: the DuckDNS token
# (/etc/hovanki/duckdns.token, root:root 600, never in .env: it rules every name of the DuckDNS account) and lego's
# account and certificates (/var/lib/hovanki-cert) are root's. lego (hovanki-lego-install.sh) renews only when a third
# of the certificate's life is left; then its deploy hook, hovanki-cert-deploy.sh, puts the new one in /etc/hovanki/tls,
# where the server takes it without a restart. A failure is not retried in a loop: Let's Encrypt allows 5 identical
# certificates a week, and the timer's next run tries again.
# Extra arguments go to `lego run`, e.g. `--server letsencrypt-staging` with LEGO_PATH=/tmp/… for a dry run.
set -euo pipefail

here="$(dirname "$(readlink -f "$0")")"

# The name the phones reach, from .env next to this script (as compose.yaml reads it), without quotes.
domain=$(sed -n 's/^DOMAIN=//p' "$here/.env" | tail -n 1 | tr -d "\"' ")
if [ -z "$domain" ]; then
  echo "No DOMAIN in $here/.env" >&2
  exit 1
fi

export DUCKDNS_TOKEN_FILE="${DUCKDNS_TOKEN_FILE:-/etc/hovanki/duckdns.token}"
export LEGO_PATH="${LEGO_PATH:-/var/lib/hovanki-cert}"
export LEGO_ACCEPT_TOS=true

# The hook through bash: scp keeps no executable bit (lego splits the hook's command line on spaces).
exec lego --log.format text run \
  --dns duckdns \
  --domains "$domain" \
  --deploy-hook "/usr/bin/bash $here/hovanki-cert-deploy.sh" \
  "$@"
