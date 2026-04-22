#!/usr/bin/env bash
# =============================================================================
# vm-ssh.sh — run a command on the production VM over a reused SSH connection.
#
# First call opens an SSH ControlMaster via `gcloud compute ssh` (handles auth
# + key propagation). Subsequent calls bypass gcloud and speak ssh directly
# over the existing socket (~0.5s per call vs. ~3.5s with gcloud every time).
# Master auto-expires 30 minutes after the last command.
#
# Usage:
#   ./scripts/vm-ssh.sh '<remote command>'
#
# Examples:
#   ./scripts/vm-ssh.sh 'curl -sf http://127.0.0.1:8080/api/health'
#   ./scripts/vm-ssh.sh 'cd /opt/werewolf-simple && sudo docker compose --env-file .env.prod ps'
#   ./scripts/vm-ssh.sh "cd /opt/werewolf-simple && sudo docker compose --env-file .env.prod logs --tail=100 backend"
#
# Requires gcloud auth + project set. If either is missing:
#   gcloud auth login
#   gcloud config set project werewolf-301709
# =============================================================================

set -euo pipefail

VM="werewolf-server"
ZONE="us-east1-d"
PROJECT="werewolf-301709"
SOCKET="/tmp/ssh-werewolf-cm"

[ $# -eq 0 ] && { echo "usage: $0 '<remote command>'" >&2; exit 1; }

# Fast path: master is alive → direct ssh over the existing socket.
if ssh -O check -o ControlPath="$SOCKET" werewolf-server 2>/dev/null; then
  exec ssh \
    -o ControlPath="$SOCKET" \
    -o HostKeyAlias=compute.7050235389789579773 \
    -o UserKnownHostsFile="$HOME/.ssh/google_compute_known_hosts" \
    -i "$HOME/.ssh/google_compute_engine" \
    dq@35.243.171.224 "$*"
fi

# Cold path: bootstrap via gcloud (handles auth + SSH key metadata), leaving
# behind a ControlMaster the fast path can reuse on subsequent calls.
exec gcloud compute ssh "$VM" --zone="$ZONE" --project="$PROJECT" \
  --ssh-flag="-o ControlMaster=auto" \
  --ssh-flag="-o ControlPath=$SOCKET" \
  --ssh-flag="-o ControlPersist=30m" \
  --command="$*"
