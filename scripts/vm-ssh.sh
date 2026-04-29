#!/usr/bin/env bash
# =============================================================================
# vm-ssh.sh — run a command on the production VM over a reused SSH connection.
#
# First call opens an SSH ControlMaster via `gcloud compute ssh` (handles auth
# + key propagation). Subsequent calls bypass gcloud and speak ssh directly
# over the existing socket (~0.5s per call vs. ~3.5s with gcloud every time).
# Master auto-expires 30 minutes after the last command.
#
# Configuration: VM coordinates are NOT hardcoded. They live in
# `scripts/.env.vm` (gitignored). Copy `scripts/.env.vm.example` and fill in
# the real values once.
#
# Usage:
#   ./scripts/vm-ssh.sh '<remote command>'
#
# Examples:
#   ./scripts/vm-ssh.sh 'curl -sf http://127.0.0.1:8080/api/health'
#   ./scripts/vm-ssh.sh 'cd /opt/werewolf-simple && sudo docker compose --env-file .env.prod ps'
#
# Requires gcloud auth + project set. If either is missing:
#   gcloud auth login
#   gcloud config set project "$VM_PROJECT"
# =============================================================================

set -euo pipefail

[ $# -eq 0 ] && { echo "usage: $0 '<remote command>'" >&2; exit 1; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env.vm"

if [ ! -f "$ENV_FILE" ]; then
  cat >&2 <<EOF
ERROR: $ENV_FILE not found.

VM coordinates aren't checked into git. Create them once:

    cp $SCRIPT_DIR/.env.vm.example $ENV_FILE
    \$EDITOR $ENV_FILE   # fill in VM_NAME, VM_ZONE, VM_PROJECT, VM_USER, VM_IP, VM_HOST_KEY_ALIAS
EOF
  exit 1
fi

# shellcheck source=/dev/null
source "$ENV_FILE"

for var in VM_NAME VM_ZONE VM_PROJECT VM_USER VM_IP VM_HOST_KEY_ALIAS; do
  if [ -z "${!var:-}" ]; then
    echo "ERROR: $var is empty in $ENV_FILE" >&2
    exit 1
  fi
done

SOCKET="/tmp/ssh-${VM_NAME}-cm"

# Fast path: master is alive → direct ssh over the existing socket.
if ssh -O check -o ControlPath="$SOCKET" "$VM_NAME" 2>/dev/null; then
  exec ssh \
    -o ControlPath="$SOCKET" \
    -o HostKeyAlias="$VM_HOST_KEY_ALIAS" \
    -o UserKnownHostsFile="$HOME/.ssh/google_compute_known_hosts" \
    -i "$HOME/.ssh/google_compute_engine" \
    "${VM_USER}@${VM_IP}" "$*"
fi

# Cold path: bootstrap via gcloud (handles auth + SSH key metadata), leaving
# behind a ControlMaster the fast path can reuse on subsequent calls.
exec gcloud compute ssh "$VM_NAME" --zone="$VM_ZONE" --project="$VM_PROJECT" \
  --ssh-flag="-o ControlMaster=auto" \
  --ssh-flag="-o ControlPath=$SOCKET" \
  --ssh-flag="-o ControlPersist=30m" \
  --command="$*"
