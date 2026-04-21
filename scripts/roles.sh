#!/usr/bin/env bash
# =============================================================================
# roles.sh — Print every bot's seat, nickname, and role for the active game.
#
# Usage:
#   ./scripts/roles.sh [--room CODE]
#
# Output:
#   seat  1  Bot-abc  WEREWOLF  ← use for WOLF_KILL / WOLF_SELECT
#   seat  2  Bot-def  SEER      ← use for SEER_CHECK
#   seat  3  Bot-ghi  WITCH     ← use for WITCH_ACT
#   seat  4  Bot-jkl  GUARD     ← use for GUARD_PROTECT
#   seat  5  Bot-mno  VILLAGER
#   seat  7  Bot-stu  HUNTER    ← use for HUNTER_SHOOT
#   seat  8  Bot-vwx  IDIOT     ← use for IDIOT_REVEAL
#
# Run this right after CONFIRM_ROLE to know which bot to drive for each phase.
# Store the results as shell variables, e.g.:
#   WOLF_NICK=$(./scripts/roles.sh | grep WEREWOLF | awk '{print $3}')
#   SEER_NICK=$(./scripts/roles.sh | grep SEER | awk '{print $3}')
# =============================================================================

set -euo pipefail

BASE="${BACKEND_BASE:-http://localhost:8080/api}"
STATE_DIR="/tmp"

CYAN='\033[0;36m'; RED='\033[0;31m'; RESET='\033[0m'
info() { echo -e "  ${CYAN}→  $*${RESET}"; }
fail() { echo -e "  ${RED}✗  $*${RESET}"; exit 1; }

ROOM_CODE=""
while [[ $# -gt 0 ]]; do
  case $1 in
    --room) ROOM_CODE=$(echo "$2" | tr 'a-z' 'A-Z'); shift 2 ;;
    -h|--help) echo "Usage: $0 [--room CODE]"; exit 0 ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

# Resolve state file
if [ -z "$ROOM_CODE" ]; then
  FILES=("$STATE_DIR"/werewolf-*.json)
  if [ ${#FILES[@]} -eq 1 ] && [ -f "${FILES[0]}" ]; then
    STATE_FILE="${FILES[0]}"
    ROOM_CODE=$(python3 -c "import json; print(json.load(open('$STATE_FILE'))['roomCode'])")
  elif [ ${#FILES[@]} -gt 1 ]; then
    fail "Multiple state files — specify --room CODE"
  else
    fail "No state file in $STATE_DIR — run join-room.sh first"
  fi
else
  STATE_FILE="$STATE_DIR/werewolf-${ROOM_CODE}.json"
  [ -f "$STATE_FILE" ] || fail "No state file for room $ROOM_CODE (expected $STATE_FILE)"
fi

BOTS_JSON=$(python3 -c "import json; print(json.dumps(json.load(open('$STATE_FILE'))['bots']))")
FIRST_TOKEN=$(echo "$BOTS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['token'])")
BOT_USER_IDS=$(echo "$BOTS_JSON" | python3 -c "
import json,sys
bots=json.load(sys.stdin)
print(','.join(b['userId'] for b in bots))
")

# ALL_PLAYERS = bots + manually-logged-in users (added via: dev-login.sh <nick> --room <code>)
# Filter users to only those actually in the game (have valid seat from API)
ALL_PLAYERS_JSON=$(python3 - "$STATE_FILE" "$FIRST_TOKEN" << 'PYEOF'
import json, urllib.request, sys, base64

path = sys.argv[1]
token = sys.argv[2]

state = json.load(open(path))
bots = state['bots']
users = state.get('users', [])

# Get actual game players from API to validate users
valid_user_ids = set()
try:
    # Find game ID by checking bots
    bot_ids = {b['userId'] for b in bots}
    for gid in range(1, 10000):
        try:
            req = urllib.request.Request(
                f"{BASE}/game/{gid}/state",
                headers={"Authorization": f"Bearer {token}"}
            )
            with urllib.request.urlopen(req) as r:
                game = json.loads(r.read())
                if "phase" in game:
                    game_player_ids = {p["userId"] for p in game.get("players", [])}
                    if bot_ids.issubset(game_player_ids):
                        valid_user_ids = game_player_ids
                        break
        except:
            continue
except:
    pass

# Filter users to only those actually in the game
valid_users = [u for u in users if u.get('userId') in valid_user_ids]

# Do NOT default null seats to 0 - only include users with valid seats
valid_users = [u for u in valid_users if u.get('seat') is not None]

print(json.dumps(bots + valid_users))
PYEOF
)

info "Discovering game ID …"
GAME_ID=$(python3 << PYEOF
import json, urllib.request, urllib.error

base     = "$BASE"
token    = "$FIRST_TOKEN"
bot_ids  = set("$BOT_USER_IDS".split(","))

def get(gid):
    req = urllib.request.Request(
        f"{base}/game/{gid}/state",
        headers={"Authorization": f"Bearer {token}"}
    )
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError:
        return {}

last_match = None
misses = 0
for gid in range(1, 10000):
    d = get(gid)
    if "phase" not in d:
        misses += 1
        if last_match is not None and misses >= 5:
            break
        continue
    misses = 0
    game_ids = {p["userId"] for p in d.get("players", [])}
    if bot_ids.issubset(game_ids):
        last_match = gid

if last_match is not None:
    print(last_match)
PYEOF
)
[ -z "$GAME_ID" ] && fail "Could not find active game for room $ROOM_CODE"
info "Game $GAME_ID  (room $ROOM_CODE)"
echo ""

ROLE_HINTS='{
  "WEREWOLF": "← use for WOLF_KILL / WOLF_SELECT",
  "SEER":     "← use for SEER_CHECK",
  "WITCH":    "← use for WITCH_ACT",
  "GUARD":    "← use for GUARD_PROTECT",
  "HUNTER":   "← use for HUNTER_SHOOT",
  "IDIOT":    "← use for IDIOT_REVEAL",
  "VILLAGER": ""
}'

python3 << PYEOF
import json, urllib.request, urllib.error

base        = "$BASE"
gid         = "$GAME_ID"
all_players = json.loads(r"""$ALL_PLAYERS_JSON""")
hints       = $ROLE_HINTS

# Fetch alive status once using the first token
alive_by_uid = {}
try:
    req = urllib.request.Request(
        f"{base}/game/{gid}/state",
        headers={"Authorization": "Bearer " + all_players[0]["token"]}
    )
    with urllib.request.urlopen(req) as r:
        alive_by_uid = {p["userId"]: p.get("isAlive", True)
                        for p in json.loads(r.read()).get("players", [])}
except Exception:
    pass

for b in sorted(all_players, key=lambda x: (x.get("seat") or 0, x["nick"])):
    try:
        req = urllib.request.Request(
            f"{base}/game/{gid}/state",
            headers={"Authorization": "Bearer " + b["token"]}
        )
        with urllib.request.urlopen(req) as r:
            role = json.loads(r.read()).get("myRole") or "?"
    except Exception:
        role = "?"

    seat         = b["seat"] or 0
    alive_marker = "  [DEAD]" if alive_by_uid.get(b["userId"]) is False else ""
    hint         = hints.get(role, "")
    print(f'  seat {seat:2d}  {b["nick"]:<20}  {role:<12}{alive_marker}  {hint}')
PYEOF
echo ""
