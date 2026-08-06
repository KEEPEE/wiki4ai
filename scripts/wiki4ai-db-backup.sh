#!/usr/bin/env bash
#
# wiki4ai-db-backup.sh
#
# Backs up the wiki4ai Postgres database from a running wiki4ai-db container.
# Run this ON the target host (dev or prod) - it talks to the local Docker
# daemon, no remote credentials needed.
#
# Usage:
#   ./wiki4ai-db-backup.sh [output-dir]
#
# Produces: <output-dir>/wiki4ai_<hostname>_<timestamp>.dump
# (pg_dump custom format - restore with wiki4ai-db-restore.sh or `pg_restore`)

set -euo pipefail

CONTAINER="${WIKI4AI_DB_CONTAINER:-wiki4ai-db}"
DB_NAME="${WIKI4AI_DB_NAME:-wiki4ai}"
DB_USER="${WIKI4AI_DB_USER:-wiki4ai}"
OUTPUT_DIR="${1:-$HOME}"

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
  echo "ERROR: container '$CONTAINER' is not running on this host." >&2
  echo "Set WIKI4AI_DB_CONTAINER if it uses a different name." >&2
  exit 1
fi

timestamp="$(date +%Y%m%d_%H%M%S)"
label="$(hostname -s 2>/dev/null || hostname)"
filename="wiki4ai_${label}_${timestamp}.dump"
container_path="/tmp/${filename}"

echo "Backing up '${DB_NAME}' from container '${CONTAINER}'..."
docker exec "$CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" --format=custom -f "$container_path"

mkdir -p "$OUTPUT_DIR"
docker cp "${CONTAINER}:${container_path}" "${OUTPUT_DIR}/${filename}"
docker exec "$CONTAINER" rm -f "$container_path"

echo "Done: ${OUTPUT_DIR}/${filename}"
echo
echo "Contents summary:"
docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -c \
  "SELECT 'users' t, count(*) FROM users UNION ALL SELECT 'projects', count(*) FROM projects UNION ALL SELECT 'documents', count(*) FROM documents;" \
  2>/dev/null || true
echo
echo "Flyway migrations applied:"
docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -c \
  "SELECT version, description FROM flyway_schema_history ORDER BY installed_rank;" 2>/dev/null || true
