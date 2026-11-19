#!/usr/bin/env bash
# Demo helper: crash a peer with SIGKILL (no clean shutdown), e.g. scripts/kill-peer.sh 2
set -euo pipefail
source "$(dirname "$0")/common.sh"
pidf="run/peer-$1.pid"
is_ours "peer-$1" || { echo "  peer-$1 is not running"; rm -f "$pidf"; exit 1; }
kill -9 "$(cat "$pidf")" && rm -f "$pidf" && echo "  peer-$1 killed (SIGKILL)"
