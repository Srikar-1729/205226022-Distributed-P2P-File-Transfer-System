#!/usr/bin/env bash
# Starts the three microservices (needs mongod on localhost:27017). Extra args go to every service.
set -euo pipefail
source "$(dirname "$0")/common.sh"
if ! (exec 3<>/dev/tcp/127.0.0.1/27017) 2>/dev/null; then
  echo "MongoDB is not listening on localhost:27017 - start mongod first." >&2; exit 1
fi
start_java auth-service    auth-service/target/auth-service.jar       "$@"
wait_port 9091 auth-service
start_java catalog-service catalog-service/target/catalog-service.jar "$@"
wait_port 9092 catalog-service
start_java tracker-service tracker-service/target/tracker-service.jar "$@"
wait_port 9093 tracker-service
echo "services up: auth :9091  catalog :9092  tracker :9093"
