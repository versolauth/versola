#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

log() {
  printf '[start-local] %s\n' "$1"
}

stop_backend_port() {
  local port="$1"
  local pids

  pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "$pids" ]]; then
    echo "Stopping process(es) listening on port $port: $pids"
    kill $pids 2>/dev/null || true
  fi
}

stop_sbt_project() {
  local env_path="$1"
  local project="$2"
  local pids

  pids="$(pgrep -f "sbt.*-Denv.path=$env_path.*$project" 2>/dev/null || true)"
  if [[ -n "$pids" ]]; then
    echo "Stopping sbt process(es) for $project: $pids"
    kill $pids 2>/dev/null || true
  fi
}

log "Stopping existing backend processes..."
stop_sbt_project "central/dev/env.conf" "central-postgres-impl"
stop_sbt_project "auth/dev/env.conf" "auth-postgres-impl"
stop_sbt_project "edge/dev/env.conf" "edge-postgres-impl"

for port in 9001 9002 9003 9004 9005 9006; do
  stop_backend_port "$port"
done

log "Building forms..."
npm --prefix "$PROJECT_ROOT/central-ui" run build:forms

log "Starting PostgreSQL..."
docker rm -f postgres 2>/dev/null || true
docker-compose -f "$PROJECT_ROOT/services.yml" up -d postgres

log "Starting Central and waiting for it to become available..."
osascript - "$PROJECT_ROOT" <<'APPLESCRIPT'
on run argv
  set projectRoot to item 1 of argv
  set root to quoted form of projectRoot
  set uiRoot to quoted form of (projectRoot & "/central-ui")

  tell application "Terminal"
    do script "echo '[start-local] Starting Central'; cd " & root & " && PORT=9001 DPORT=9002 sbt --no-server -Denv.path=central/dev/env.conf 'project central-postgres-impl; run'"
  end tell

  do shell script "i=0; while [ \"$i\" -lt 180 ]; do /usr/bin/curl -s -o /dev/null --connect-timeout 1 --max-time 2 http://localhost:9001 && exit 0; i=$((i + 1)); sleep 1; done; exit 1"
  delay 10

  tell application "Terminal"
    do script "echo '[start-local] Starting Auth'; cd " & root & " && PORT=9003 DPORT=9004 sbt --no-server -Denv.path=auth/dev/env.conf 'project auth-postgres-impl; run'"
  end tell

  do shell script "i=0; while [ \"$i\" -lt 180 ]; do /usr/bin/curl -s -o /dev/null --connect-timeout 1 --max-time 2 http://localhost:9003 && exit 0; i=$((i + 1)); sleep 1; done; exit 1"
  delay 10

  tell application "Terminal"
    do script "echo '[start-local] Starting Edge'; cd " & root & " && PORT=9005 DPORT=9006 sbt --no-server -Denv.path=edge/dev/env.conf 'project edge-postgres-impl; run'"
    do script "echo '[start-local] Starting Central UI'; cd " & uiRoot & " && npm run dev"
    activate
  end tell
end run
APPLESCRIPT

log "Waiting for Edge and opening the browser..."
login_url="http://localhost:9005/login/central-admin"
for attempt in {1..60}; do
  if curl -s -o /dev/null --connect-timeout 1 --max-time 2 "$login_url"; then
    sleep 10
    open "$login_url"
    exit 0
  fi
  sleep 1
done

echo "Edge did not become available within 60 seconds; open $login_url manually."