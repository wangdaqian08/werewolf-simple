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
#   ./scripts/dev-login.sh Host --print          # print token to stdout (also cache)
#   ./scripts/dev-login.sh Host --refresh        # force new token even if cached
#   ./scripts/dev-login.sh --clear               # remove all cached tokens
#
#   # Save to room state file so act.sh / roles.sh can resolve this user by nick:
#   ./scripts/dev-login.sh Alice --room ABCD
#   ./scripts/dev-login.sh Host  --room ABCD --seat 0   # with known seat index
#
# If you logged in via the browser as "Alice", run the same command —
# same nickname = same userId, so the script and browser share the same game seat:
#   ./scripts/dev-login.sh Alice --room ABCD            # no seat known yet
#   ./scripts/dev-login.sh Alice --room ABCD --seat 2   # seat known
#
# After --room is used, act.sh resolves the user by nickname just like a bot:
#   ./scripts/act.sh CONFIRM_ROLE Alice
#   ./scripts/act.sh SUBMIT_VOTE  Alice --target 3
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
ROOM_CODE=""
SEAT=""

while [[ $# -gt 0 ]]; do
  case $1 in
    --refresh)    REFRESH=true; shift ;;
    --print)      PRINT=true;   shift ;;
    --clear)      CLEAR=true;   shift ;;
    --room)       ROOM_CODE="$2"; shift 2 ;;
    --seat)       SEAT="$2";    shift 2 ;;
    -*)           echo "Unknown flag: $1" >&2; exit 1 ;;
    *)            NICKNAME="$1"; shift ;;
  esac
done

if $CLEAR; then
  rm -f "$CACHE_DIR"/werewolf-token-*.txt
  echo -e "${GREEN}✔ Cleared all cached dev tokens${RESET}" >&2
  exit 0
fi

[ -z "$NICKNAME" ] && { echo "Usage: $0 <NICKNAME> [--room CODE] [--seat N] [--refresh] [--print]" >&2; exit 1; }

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

# ── Save to room state file (--room CODE) ─────────────────────────────────────
if [ -n "$ROOM_CODE" ]; then
  STATE_FILE="$CACHE_DIR/werewolf-${ROOM_CODE}.json"
  [ -f "$STATE_FILE" ] || { echo -e "${RED}✗ No state file for room $ROOM_CODE — run join-room.sh first${RESET}" >&2; exit 1; }

  # Extract roomId from state file
  ROOM_ID=$(python3 -c "import json; print(json.load(open('$STATE_FILE'))['roomId'])")

  # Get totalPlayers from API (state file may not have it)
  ROOM_INFO=$(curl -s "$BASE/room/$ROOM_ID" -H "Authorization: Bearer $TOKEN")
  TOTAL=$(echo "$ROOM_INFO" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("config",{}).get("totalPlayers",12))' 2>/dev/null)

  # ── Actually join the room ─────────────────────────────────────────────────
  JOIN_RESP=$(curl -s -X POST "$BASE/room/join" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"roomCode\":\"$ROOM_CODE\"}")
  JOIN_ERR=$(echo "$JOIN_RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("error",""))' 2>/dev/null)
  if [ -n "$JOIN_ERR" ]; then
    # Check if already joined (error might be "already in room" or similar)
    echo -e "${YELLOW}⚠ Join returned: $JOIN_ERR (may already be in room)${RESET}" >&2
  fi

  # ── Select a seat ───────────────────────────────────────────────────────────
  ACTUAL_SEAT=""
  if [ -n "$SEAT" ] && [ "$SEAT" -ge 1 ] 2>/dev/null; then
    # Use provided seat
    SEAT_RESP=$(curl -s -X POST "$BASE/room/seat" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d "{\"seatIndex\":$SEAT,\"roomId\":$ROOM_ID}")
    OK=$(echo "$SEAT_RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("success",False))' 2>/dev/null)
    if [ "$OK" = "True" ]; then
      ACTUAL_SEAT=$SEAT
    else
      echo -e "${YELLOW}⚠ Seat $SEAT not available, trying to find another...${RESET}" >&2
    fi
  fi

  # If no seat specified or failed, find first available
  if [ -z "$ACTUAL_SEAT" ]; then
    for s in $(seq 1 "$TOTAL"); do
      SEAT_RESP=$(curl -s -X POST "$BASE/room/seat" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"seatIndex\":$s,\"roomId\":$ROOM_ID}")
      OK=$(echo "$SEAT_RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("success",False))' 2>/dev/null)
      if [ "$OK" = "True" ]; then
        ACTUAL_SEAT=$s
        break
      fi
    done
  fi

  if [ -z "$ACTUAL_SEAT" ]; then
    echo -e "${RED}✗ Could not claim any seat in room $ROOM_CODE (room may be full)${RESET}" >&2
    exit 1
  fi

  # ── Save to state file ──────────────────────────────────────────────────────
  python3 - "$STATE_FILE" "$NICKNAME" "$TOKEN" "$ACTUAL_SEAT" << 'PYEOF'
import json, sys, base64

path, nick, token, seat = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

# Decode userId from JWT payload (no external libraries needed)
def decode_user_id(t):
    payload = t.split('.')[1]
    payload += '=' * (-len(payload) % 4)   # pad to multiple of 4
    return json.loads(base64.b64decode(payload))['sub']

user_id = decode_user_id(token)
seat_val = int(seat)

state = json.load(open(path))
users = state.get('users', [])

# Upsert: replace existing entry for same nick, otherwise append
entry = {'nick': nick, 'token': token, 'userId': user_id, 'seat': seat_val}
users = [u for u in users if u.get('nick', '').lower() != nick.lower()]
users.append(entry)
state['users'] = users

with open(path, 'w') as f:
    json.dump(state, f, indent=2)

print(f"  Saved {nick} (userId={user_id[:12]}..., seat={seat_val}) → {path}")
PYEOF

  echo -e "${GREEN}✔ User '$NICKNAME' joined room $ROOM_CODE at seat $ACTUAL_SEAT${RESET}" >&2
  echo -e "  act.sh now resolves '$NICKNAME' by nickname (e.g. ./scripts/act.sh CONFIRM_ROLE $NICKNAME)" >&2
fi

# Print token to stdout so callers can do: TOKEN=$(./scripts/dev-login.sh Host)
echo "$TOKEN"
