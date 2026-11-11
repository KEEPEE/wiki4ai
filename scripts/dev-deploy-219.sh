#!/usr/bin/env bash
# WIKI4AI-183 — deploy the wiki4ai-campaign dev stack on 192.168.77.219.
#
# Run this FROM THE DSH CONTAINER / workspace. It does three things:
#   (1) tars the repo source (backend, frontend, mcp-server, embedding) and pipes it
#       over ssh into the host's ~/projects/wiki4ai-campaign/src,
#   (2) ssh-runs the host-side deploy.sh, which does `docker compose build` and
#       `docker compose up -d` inside the dockge stack dir,
#   (3) prints the resulting container status.
#
# The container needs only tar + ssh + sshpass (no docker / java / rsync).
#
# Environment (all overridable):
#   WIKI4AI_SSH_PW    REQUIRED — host ssh password (used by sshpass).
#   WIKI4AI_SUDO_PW   host sudo password (defaults to WIKI4AI_SSH_PW).
#   WIKI4AI_HOST      host address (default 192.168.77.219).
#   WIKI4AI_USER      host user (default keepee).
#   REPO_DIR          repo root (default: parent dir of this script).
#
# Example (secrets come from the environment / vault, never hardcoded here):
#   WIKI4AI_SSH_PW=... WIKI4AI_SUDO_PW=... scripts/dev-deploy-219.sh all
#
# Subcommands passed through to the host deploy.sh: all | build | up | restart | ps | logs [svc]
set -euo pipefail

HOST="${WIKI4AI_HOST:-192.168.77.219}"
USER_="${WIKI4AI_USER:-keepee}"
SSH_PW="${WIKI4AI_SSH_PW:?set WIKI4AI_SSH_PW to the host ssh password}"
SUDO_PW="${WIKI4AI_SUDO_PW:-$SSH_PW}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="${REPO_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"

REMOTE_HOME="/home/$USER_"
SRC_REMOTE="$REMOTE_HOME/projects/wiki4ai-campaign/src"
DEPLOY_REMOTE="$REMOTE_HOME/projects/wiki4ai-campaign/deploy.sh"

SSH_OPTS=(-o StrictHostKeyChecking=accept-new)
ssh_run() { sshpass -p "$SSH_PW" ssh "${SSH_OPTS[@]}" "$USER_@$HOST" "$@"; }

ACTION="${1:-all}"

echo "==> [1/3] syncing source from $REPO_DIR to $USER_@$HOST:$SRC_REMOTE"
ssh_run "mkdir -p '$SRC_REMOTE'"
tar cz -C "$REPO_DIR" \
    --exclude=.git --exclude=node_modules --exclude=target --exclude=dist \
    --exclude=__pycache__ --exclude=.venv \
    backend frontend mcp-server embedding docker-compose.yml \
  | ssh_run "tar xz -C '$SRC_REMOTE'"

echo "==> [2/3] building + starting the stack on the host ($ACTION)"
ssh_run "SUDO_PW='$SUDO_PW' bash '$DEPLOY_REMOTE' '$ACTION'"

echo "==> [3/3] container status"
ssh_run "SUDO_PW='$SUDO_PW' bash '$DEPLOY_REMOTE' ps" || true

echo "dev-deploy-219.sh: done. UI: http://$HOST:3016/  backend health: http://$HOST:8086/actuator/health"
