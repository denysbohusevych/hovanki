#!/bin/bash
# lego's deploy hook (hovanki-cert.sh), run as root after a new certificate: puts it where the server reads it,
# /etc/hovanki/tls, which compose.yaml mounts read-only into the container (application-tls.yaml). The chain and the key
# are each written next to their place and moved over it, so the server never reads half a file; it takes the new pair
# after 10 quiet seconds, without a restart. The key belongs to the server's user in the image (uid and gid 10001,
# server/Dockerfile) and nobody else reads it.
set -euo pipefail

target="${HOVANKI_TLS_DIR:-/etc/hovanki/tls}"
# The server's user in the image.
server=10001

# lego's .crt is the whole chain: lego bundles the issuer unless told --no-bundle.
install -d -m 0750 -o root -g "$server" "$target"
install -m 0444 -o root -g root "$LEGO_HOOK_CERT_PATH" "$target/.fullchain.pem.next"
install -m 0400 -o "$server" -g "$server" "$LEGO_HOOK_CERT_KEY_PATH" "$target/.privkey.pem.next"
mv -f "$target/.privkey.pem.next" "$target/privkey.pem"
mv -f "$target/.fullchain.pem.next" "$target/fullchain.pem"
echo "The certificate of $LEGO_HOOK_CERT_DOMAINS is in $target"
