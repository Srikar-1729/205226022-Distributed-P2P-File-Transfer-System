#!/usr/bin/env bash
# One-time: creates a local certificate authority and a "localhost" certificate used by every gRPC server.
# All clients trust certs/ca.crt. Run automatically by the start scripts when certs/ is missing.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p certs && cd certs
if [[ -f server.crt ]]; then echo "certs/ already exists"; exit 0; fi
openssl req -x509 -newkey rsa:2048 -nodes -days 825 -subj "/CN=P2P Local CA" \
  -keyout ca.key -out ca.crt 2>/dev/null
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out server.key 2>/dev/null
openssl req -new -key server.key -subj "/CN=localhost" -out server.csr 2>/dev/null
printf "subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n" > ext.cnf
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 825 \
  -extfile ext.cnf -out server.crt 2>/dev/null
rm -f server.csr ext.cnf ca.srl
echo "created certs/ca.crt, certs/server.crt, certs/server.key"
