#!/usr/bin/env bash
# =============================================================================
# dev-login.sh — Get (and cache) a dev JWT token by nickname.
#
# Requires backend running with dev profile (/api/auth/dev endpoint).
# Token is cached to /tmp/werewolf-token-<NICKNAME>.txt so repeated calls
# within the same session reuse it without hitting the server.
#
# Usage:
#   TOKEN=$(./scripts/dev-login.sh Host)
#   TOKEN=$(./scripts/dev-login.sh Alice)
#   ./scripts/dev-login.sh Host --print    # print token to stdout (also cache)
#   ./scripts/dev-login.sh Host --refresh  # force new token even if cached
#   ./scripts/dev-login.sh --clear         # remove all cached tokens
#
# Examples (in shell scripts):
#   TOKEN=$(./scripts/dev-login.sh Host)
#   curl -s -X POST http://localhost:8080/api/game/action \
#     -H "Authorization: Bearer $TOKEN" ...
# =============================================================================

set -euo pipefail

BASE="http://localhost:8080/api"
CACHE_DIR="/tmp"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RESET='\033[0m'

NICKNAME=""
REFRESH=false
PRINT=false
CLEAR=false

while [[ $# -gt 0 ]]; do
  case $1 in
    --refresh) REFRESH=true; shift ;;
    --print)   PRINT=true;   shift ;;
    --clear)   CLEAR=true;   shift ;;
    -*)        echo "Unknown flag: $1" >&2; exit 1 ;;
    *)         NICKNAME="$1"; shift ;;
  esac
done

if $CLEAR; then
  rm -f "$CACHE_DIR"/werewolf-token-*.txt
  echo -e "${GREEN}✔ Cleared all cached dev tokens${RESET}" >&2
  exit 0
fi

[ -z "$NICKNAME" ] && { echo "Usage: $0 <NICKNAME> [--refresh] [--print]" >&2; exit 1; }

CACHE_FILE="$CACHE_DIR/werewolf-token-$(echo "$NICKNAME" | tr 'A-Z' 'a-z').txt"

if ! $REFRESH && [ -f "$CACHE_FILE" ]; then
  TOKEN=$(cat "$CACHE_FILE")
else
  RESP=$(curl -s -X POST "$BASE/auth/dev" \
    -H "Content-Type: application/json" \
    -d "{\"nickname\":\"$NICKNAME\"}")
  TOKEN=$(echo "$RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])' 2>/dev/null) \
    || { echo -e "${RED}✗ Login failed for '$NICKNAME': $RESP${RESET}" >&2; exit 1; }
  echo "$TOKEN" > "$CACHE_FILE"
  echo -e "${GREEN}✔ Logged in as $NICKNAME (cached)${RESET}" >&2
fi

if $PRINT; then
  echo -e "  Token: ${TOKEN:0:30}..." >&2
fi

# Print token to stdout so callers can do: TOKEN=$(./scripts/dev-login.sh Host)
echo "$TOKEN"
