#!/usr/bin/env bash
# =============================================================================
# confirm-role.sh — Have bot players confirm their role in ROLE_REVEAL phase.
#
# Usage:
#   ./scripts/confirm-role.sh <GAME_ID> [ROOM_CODE|--room-code CODE]
#
# Arguments:
#   GAME_ID     Numeric game ID shown in the browser URL (/game/:gameId)
#   ROOM_CODE   4-char room code used with join-room.sh (optional if only one
#               state file exists in /tmp)
#
# Examples:
#   ./scripts/confirm-role.sh 3 AB3C       # confirm bots for room AB3C game 3
#   ./scripts/confirm-role.sh 3            # auto-detect room from /tmp state
#   ./scripts/confirm-role.sh 3 --room-code AB3C
# =============================================================================

set -euo pipefail

BASE="http://localhost:8080/api"
STATE_DIR="/tmp"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

pass()    { echo -e "  ${GREEN}✔  $*${RESET}"; }
fail()    { echo -e "  ${RED}✗  $*${RESET}"; exit 1; }
info()    { echo -e "  ${CYAN}→  $*${RESET}"; }
section() { echo -e "\n${BOLD}${YELLOW}══  $*${RESET}"; }

usage() {
  echo "Usage: $0 <GAME_ID> [ROOM_CODE|--room-code CODE]"
  exit 1
}

# ── arg parsing ───────────────────────────────────────────────────────────────
GAME_ID=""
ROOM_CODE=""

while [[ $# -gt 0 ]]; do
  case $1 in
    --room-code) ROOM_CODE=$(echo "$2" | tr 'a-z' 'A-Z'); shift 2 ;;
    -*)          echo "Unknown flag: $1"; usage ;;
    *)
      if [[ "$1" =~ ^[0-9]+$ ]] && [ -z "$GAME_ID" ]; then
        GAME_ID="$1"
      elif [ -z "$ROOM_CODE" ]; then
        ROOM_CODE=$(echo "$1" | tr 'a-z' 'A-Z')
      else
        echo "Unexpected argument: $1"; usage
      fi
      shift ;;
  esac
done

[ -z "$GAME_ID" ] && usage

# ── resolve state file ────────────────────────────────────────────────────────
if [ -z "$ROOM_CODE" ]; then
  # Auto-detect: find any werewolf state file in /tmp
  FILES=("$STATE_DIR"/werewolf-*.json)
  if [ ${#FILES[@]} -eq 1 ] && [ -f "${FILES[0]}" ]; then
    STATE_FILE="${FILES[0]}"
    ROOM_CODE=$(python3 -c "import json; print(json.load(open('$STATE_FILE'))['roomCode'])")
    info "Auto-detected room $ROOM_CODE from state file"
  elif [ ${#FILES[@]} -gt 1 ]; then
    fail "Multiple state files found — specify ROOM_CODE:\n$(ls "$STATE_DIR"/werewolf-*.json 2>/dev/null)"
  else
    fail "No state file found in $STATE_DIR — run join-room.sh first"
  fi
else
  STATE_FILE="$STATE_DIR/werewolf-${ROOM_CODE}.json"
  [ -f "$STATE_FILE" ] || fail "No state file for room $ROOM_CODE (expected $STATE_FILE)"
fi

BOTS_JSON=$(python3 -c "import json; print(json.dumps(json.load(open('$STATE_FILE'))['bots']))")
BOT_COUNT=$(echo "$BOTS_JSON" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')

[ "$BOT_COUNT" -eq 0 ] && fail "No bots saved for room $ROOM_CODE"

# ── helpers ───────────────────────────────────────────────────────────────────
post() { curl -s -X POST "$BASE$2" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3"; }

# ── confirm roles ─────────────────────────────────────────────────────────────
section "Confirming roles — game $GAME_ID — room $ROOM_CODE — $BOT_COUNT bot(s)"

CONFIRMED=0
SKIPPED=0

for idx in $(seq 0 $(( BOT_COUNT - 1 ))); do
  NICK=$(echo "$BOTS_JSON"  | python3 -c "import json,sys; print(json.load(sys.stdin)[$idx]['nick'])")
  SEAT=$(echo "$BOTS_JSON"  | python3 -c "import json,sys; print(json.load(sys.stdin)[$idx]['seat'])")
  TOKEN=$(echo "$BOTS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[$idx]['token'])")

  RESP=$(post "$TOKEN" "/game/action" "{\"gameId\":$GAME_ID,\"actionType\":\"CONFIRM_ROLE\"}")
  OK=$(echo "$RESP" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("success", d.get("status","") == "ok"))' 2>/dev/null)

  if [ "$OK" = "True" ] || [ "$OK" = "true" ]; then
    pass "$(printf '%-16s  seat %-3s  confirmed' "$NICK" "$SEAT")"
    CONFIRMED=$(( CONFIRMED + 1 ))
  else
    ERR=$(echo "$RESP" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("message", d.get("error", str(d))))' 2>/dev/null || echo "$RESP")
    info "$(printf '%-16s  seat %-3s  skipped: %s' "$NICK" "$SEAT" "$ERR")"
    SKIPPED=$(( SKIPPED + 1 ))
  fi
done

# ── summary ───────────────────────────────────────────────────────────────────
section "Summary"
echo -e "  Confirmed : ${GREEN}$CONFIRMED${RESET}"
[ "$SKIPPED" -gt 0 ] && echo -e "  Skipped   : ${YELLOW}$SKIPPED${RESET} (already confirmed or wrong phase)"

# ── current game state ────────────────────────────────────────────────────────
section "Game state"
# Use first bot token to fetch state
FIRST_TOKEN=$(echo "$BOTS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['token'])")
curl -s "$BASE/game/$GAME_ID/state" -H "Authorization: Bearer $FIRST_TOKEN" | python3 -c "
import json, sys
try:
    s = json.load(sys.stdin)
except Exception as e:
    print(f'  (could not parse state: {e})')
    sys.exit(0)

phase = s.get('phase', '?')
rr    = s.get('roleReveal') or {}
confirmed = rr.get('confirmedCount', '?')
total     = rr.get('totalCount', '?')

print(f'  Phase     : {phase}')
if phase == 'ROLE_REVEAL':
    print(f'  Confirmed : {confirmed} / {total}')
else:
    print(f'  (phase advanced — role reveal complete)')
"
