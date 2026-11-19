# Shared helpers for the start/stop scripts. Sourced, not run.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p logs run
JAVA_OPTS="${JAVA_OPTS:--Xms32m -Xmx192m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:MaxMetaspaceSize=128m}"
# TLS is always on: create the local CA + certificate the first time
[[ -f certs/server.crt ]] || "$ROOT/scripts/gen-certs.sh"

# is_ours <name>: true if run/<name>.pid points at a live java process started by these scripts.
# (After a reboot a stale pid may belong to an unrelated process, so the command line is checked too.)
is_ours() {
  local pidf="run/$1.pid" pid
  [[ -f "$pidf" ]] || return 1
  pid="$(cat "$pidf")"
  kill -0 "$pid" 2>/dev/null && grep -qa -- "-jar" "/proc/$pid/cmdline" 2>/dev/null
}

# start_java <name> <jar> [args...]  -> runs in background, log in logs/<name>.log, pid in run/<name>.pid
start_java() {
  local name="$1" jar="$2"; shift 2
  if is_ours "$name"; then
    echo "  $name already running (pid $(cat "run/$name.pid"))"; return
  fi
  if [[ ! -f "$jar" ]]; then echo "  $jar missing - run scripts/build.sh first" >&2; exit 1; fi
  # shellcheck disable=SC2086
  nohup java $JAVA_OPTS -jar "$jar" "$@" > "logs/$name.log" 2>&1 &
  echo $! > "run/$name.pid"
  echo "  started $name (pid $!, log logs/$name.log)"
}

# wait_port <port> <name>
wait_port() {
  for _ in $(seq 1 240); do
    if (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; then return 0; fi
    sleep 0.25
  done
  echo "  $2 did not open port $1 - see logs/$2.log" >&2
  tail -5 "logs/$2.log" >&2
  return 1
}
