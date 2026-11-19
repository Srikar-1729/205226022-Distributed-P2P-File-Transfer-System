#!/usr/bin/env bash
# Usage: scripts/start-peers.sh [count=4] [upload-limit e.g. 2MB] [extra --peer.x=... args]
#        scripts/start-peers.sh 3-5          (only peers 3..5)
# Peer N: web UI http://localhost:(8080+N), gRPC :(50050+N), data in peers/peer-N/
set -euo pipefail
source "$(dirname "$0")/common.sh"
range="${1:-4}"; shift || true
limit="${1:-0}"; shift || true
if [[ "$range" == *-* ]]; then from="${range%-*}"; to="${range#*-}"; else from=1; to="$range"; fi
for n in $(seq "$from" "$to"); do
  start_java "peer-$n" peer-node/target/peer-node.jar "--peer.index=$n" "--peer.upload-limit=$limit" "$@"
done
for n in $(seq "$from" "$to"); do
  wait_port $((8080 + n)) "peer-$n" && echo "  peer-$n  ->  http://localhost:$((8080 + n))"
done
