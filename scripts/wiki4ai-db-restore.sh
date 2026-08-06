#!/usr/bin/env bash
#
# wiki4ai-db-restore.sh
#
# DESTRUCTIVE: wipes the wiki4ai database on this host and restores it from
# a pg_dump custom-format file. Run this ON the target host (talks to the
# local Docker daemon). Intended for restoring a prod backup onto dev to
# validate the upgrade path (this host's backend image runs Flyway
# migrations automatically on startup, so restoring an OLDER schema dump
# and letting the backend restart is the standard way to test that new
# migrations apply cleanly against real data before touching prod).
#
# Usage:
#   ./wiki4ai-db-restore.sh /path/to/dump-file.dump --yes-i-am-sure
#
# The compose stack directory (containing docker-compose.yml with the
# backend/frontend/mcp-server/db services) is assumed to be the current
# directory, or set WIKI4AI_COMPOSE_DIR.

set -euo pipefail

DUMP_FILE="${1:-}"
CONFIRM="${2:-}"

CONTAINER="${WIKI4AI_DB_CONTAINER:-wiki4ai-db}"
BACKEND_CONTAINER="${WIKI4AI_BACKEND_CONTAINER:-wiki4ai-backend}"
DB_NAME="${WIKI4AI_DB_NAME:-wiki4ai}"
DB_USER="${WIKI4AI_DB_USER:-wiki4ai}"
COMPOSE_DIR="${WIKI4AI_COMPOSE_DIR:-.}"

if [[ -z "$DUMP_FILE" || ! -f "$DUMP_FILE" ]]; then
  echo "Usage: $0 /path/to/dump-file.dump --yes-i-am-sure" >&2
  exit 1
fi

if [[ "$CONFIRM" != "--yes-i-am-sure" ]]; then
  echo "This will PERMANENTLY WIPE the '${DB_NAME}' database on $(hostname -s 2>/dev/null || hostname)"
  echo "and replace it with the contents of: ${DUMP_FILE}"
  echo
  echo "Re-run with --yes-i-am-sure to proceed."
  exit 1
fi

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "ERROR: container '$CONTAINER' is not running on this host." >&2
  exit 1
fi

echo "== 1/6: Stopping backend (avoid stale connections / partial writes) =="
(cd "$COMPOSE_DIR" && docker compose stop backend mcp-server frontend) || \
  docker stop "$BACKEND_CONTAINER" 2>/dev/null || true

echo "== 2/6: Terminating remaining connections to '${DB_NAME}' =="
docker exec "$CONTAINER" psql -U "$DB_USER" -d postgres -c \
  "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '${DB_NAME}' AND pid <> pg_backend_pid();"

echo "== 3/6: Dropping and recreating '${DB_NAME}' =="
docker exec "$CONTAINER" psql -U "$DB_USER" -d postgres -c "DROP DATABASE IF EXISTS ${DB_NAME};"
docker exec "$CONTAINER" psql -U "$DB_USER" -d postgres -c "CREATE DATABASE ${DB_NAME} OWNER ${DB_USER};"

echo "== 4/6: Copying dump into container and restoring =="
container_path="/tmp/$(basename "$DUMP_FILE")"
docker cp "$DUMP_FILE" "${CONTAINER}:${container_path}"
docker exec "$CONTAINER" pg_restore -U "$DB_USER" -d "$DB_NAME" --no-owner --role="$DB_USER" "$container_path"
docker exec "$CONTAINER" rm -f "$container_path"

echo "== 5/6: Restarting backend (runs pending Flyway migrations automatically) =="
restart_marker="$(date -u +%Y-%m-%dT%H:%M:%S)"
(cd "$COMPOSE_DIR" && docker compose start backend)
echo "Waiting for backend to come up and finish migrating..."
# --since restart_marker avoids matching a stale "Started Wiki4AiApplication" line
# left over from the container's log history before this restart (docker compose
# stop/start reuses the same container, so old logs aren't cleared).
for i in $(seq 1 30); do
  sleep 2
  if docker logs "$BACKEND_CONTAINER" --since "$restart_marker" 2>&1 | grep -q "Started Wiki4AiApplication"; then
    break
  fi
done

echo "== 6/6: Restarting mcp-server and frontend =="
(cd "$COMPOSE_DIR" && docker compose start mcp-server frontend)

echo
echo "Done. Post-restore state:"
docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -c \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -c \
  "SELECT 'users' t, count(*) FROM users UNION ALL SELECT 'projects', count(*) FROM projects UNION ALL SELECT 'documents', count(*) FROM documents;"
