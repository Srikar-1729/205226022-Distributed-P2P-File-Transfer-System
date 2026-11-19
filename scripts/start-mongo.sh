#!/usr/bin/env bash
# Runs MongoDB in a Docker container "p2p-mongo" on localhost:27017 (data kept in volume p2p-mongo-data).
# Only the database uses Docker; the services and peers are plain Java processes.
set -euo pipefail
if docker ps --format '{{.Names}}' | grep -qx p2p-mongo; then
  echo "p2p-mongo already running"
elif docker ps -a --format '{{.Names}}' | grep -qx p2p-mongo; then
  docker start p2p-mongo >/dev/null && echo "started existing p2p-mongo"
else
  docker run -d --name p2p-mongo --restart unless-stopped -p 127.0.0.1:27017:27017 -v p2p-mongo-data:/data/db mongo:7 >/dev/null
  echo "created p2p-mongo (mongo:7) on localhost:27017"
fi
for _ in $(seq 1 60); do
  if docker exec p2p-mongo mongosh --quiet --eval 'db.runCommand({ping:1}).ok' 2>/dev/null | grep -q 1; then
    echo "MongoDB ready"; exit 0
  fi
  sleep 0.5
done
echo "MongoDB did not become ready - check: docker logs p2p-mongo" >&2; exit 1
