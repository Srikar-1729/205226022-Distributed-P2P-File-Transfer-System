#!/usr/bin/env bash
# Stops every service/peer started by the scripts. "scripts/stop-all.sh peer-2" stops only that one.
set -uo pipefail
source "$(dirname "$0")/common.sh"
shopt -s nullglob
targets=("$@")
if [[ ${#targets[@]} -eq 0 ]]; then targets=(); for f in run/*.pid; do targets+=("$(basename "$f" .pid)"); done; fi
for name in "${targets[@]}"; do
  pidf="run/$name.pid"
  [[ -f "$pidf" ]] || { echo "  $name: not running"; continue; }
  pid="$(cat "$pidf")"
  if ! is_ours "$name"; then echo "  $name: stale pid file removed"; rm -f "$pidf"; continue; fi
  if kill "$pid" 2>/dev/null; then
    for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.2; done
    echo "  stopped $name"
  fi
  rm -f "$pidf"
done
