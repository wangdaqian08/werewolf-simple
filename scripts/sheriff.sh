#!/usr/bin/env bash
# =============================================================================
# sheriff.sh — Debug helper for the SHERIFF_ELECTION phase.
#
# Sends a sheriff action on behalf of one or all bot players.
# Room code is enough — game ID is auto-detected from the state file.
#
# Usage:
#   ./scripts/sheriff.sh <ACTION> [--player NAME] [--target NAME] [--room CODE]
#
# ACTION:
#   campaign    player runs for sheriff              (sub-phase: SIGNUP)
#   pass        player opts out of running           (sub-phase: SIGNUP)
#   vote        player votes for TARGET              (sub-phase: VOTING, requires --target)
#   abstain     player casts a null vote             (sub-phase: VOTING)
#
# --player NAME  (optional, default = all bots):
#   all         every bot in the state file
#   <NAME>      case-insensitive nickname substring  e.g. --player Bot2
#   <SEAT>      seat number                          e.g. --player 3
#   <INDEX>     1-based index in bot list            e.g. --player 1
#
# --target NAME  (required for 'vote'):
#   <NAME>      nickname substring                   e.g. --target Bot3
#   <SEAT>      seat number                          e.g. --target 4
#   <USER_ID>   raw userId string
#
# OPTIONS:
#   --room CODE   room code (auto-detect if only one state file in /tmp)
#
# Examples:
#   ./scripts/sheriff.sh campaign                          # all bots campaign
#   ./scripts/sheriff.sh campaign --player Bot1            # Bot1 runs
#   ./scripts/sheriff.sh pass --player Bot2                # Bot2 opts out
#   ./scripts/sheriff.sh vote --target Bot3                # all bots vote for Bot3
#   ./scripts/sheriff.sh vote --player Bot2 --target Bot3  # Bot2 votes for Bot3
#   ./scripts/sheriff.sh vote --player 2 --target 3        # seat 2 votes for seat 3
#   ./scripts/sheriff.sh abstain --player Bot1
#   ./scripts/sheriff.sh campaign --room AB3C
# =============================================================================

set -euo pipefail

BASE="http://localhost:8080/api"
STATE_DIR="/tmp"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

ok()      { echo -e "  ${GREEN}✔  $*${RESET}"; }
fail()    { echo -e "  ${RED}✗  $*${RESET}"; exit 1; }
info()    { echo -e "  ${CYAN}→  $*${RESET}"; }
section() { echo -e "\n${BOLD}${YELLOW}══  $*${RESET}"; }

usage() {
  cat <<EOF

Usage: $0 <ACTION> [--player NAME] [--target NAME] [--room CODE]

  Actions : campaign | pass | vote | abstain
  --player: all (default) | <name> | <seat>   who performs the action
  --target: <name> | <seat> | <userId>        required for 'vote'

Examples:
  $0 campaign
  $0 campaign --player Bot1
  $0 pass     --player Bot2
  $0 vote     --target Bot3
  $0 vote     --player Bot2  --target Bot3
  $0 vote     --player 2     --target 3
  $0 abstain  --player Bot1
EOF
  exit 1
}

# ── Arg parsing ───────────────────────────────────────────────────────────────
ACTION=""
PLAYER_SEL="all"
TARGET_SEL=""
ROOM_CODE=""

while [[ $# -gt 0 ]]; do
  case $1 in
    --room|-r)     ROOM_CODE=$(echo "$2" | tr 'a-z' 'A-Z'); shift 2 ;;
    --target|-t)   TARGET_SEL="$2"; shift 2 ;;
    --player|-p)   PLAYER_SEL="$2"; shift 2 ;;
    -h|--help)     usage ;;
    -*)            echo "Unknown flag: $1"; usage ;;
    *)
      if [ -z "$ACTION" ]; then ACTION=$(echo "$1" | tr 'A-Z' 'a-z')
      else echo "Unexpected argument '$1'. Use --player and --target flags."; usage
      fi
      shift ;;
  esac
done

[ -z "$ACTION" ] && usage

case "$ACTION" in
  campaign|pass|vote|abstain) ;;
  *) fail "Unknown action '$ACTION'. Valid: campaign | pass | vote | abstain" ;;
esac

[ "$ACTION" = "vote" ] && [ -z "$TARGET_SEL" ] && \
  fail "'vote' requires --target <name|seat|userId>"

# ── Resolve state file ────────────────────────────────────────────────────────
if [ -z "$ROOM_CODE" ]; then
  FILES=("$STATE_DIR"/werewolf-*.json)
  if [ ${#FILES[@]} -eq 1 ] && [ -f "${FILES[0]}" ]; then
    STATE_FILE="${FILES[0]}"
    ROOM_CODE=$(python3 -c "import json; print(json.load(open('$STATE_FILE'))['roomCode'])")
    info "Auto-detected room $ROOM_CODE"
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
BOT_COUNT=$(echo "$BOTS_JSON" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')
[ "$BOT_COUNT" -eq 0 ] && fail "No bots in state file for room $ROOM_CODE"

ROOM_ID=$(python3 -c "import json; print(json.load(open('$STATE_FILE'))['roomId'])")

# ── Auto-detect game ID ───────────────────────────────────────────────────────
# Use any bot token to scan recent game IDs, match on our player set.
FIRST_TOKEN=$(echo "$BOTS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['token'])")
BOT_USER_IDS=$(echo "$BOTS_JSON" | python3 -c "
import json,sys
bots=json.load(sys.stdin)
print(','.join(b['userId'] for b in bots))
")

info "Discovering game ID for room $ROOM_CODE …"
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

# Scan upward and keep the HIGHEST matching game ID (most recent run).
# Stop after 5 consecutive misses so we don't scan past the end.
last_match = None
misses = 0
for gid in range(1, 500):
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
info "Game ID = $GAME_ID"

# ── Resolve acting players ────────────────────────────────────────────────────
PLAYERS_JSON=$(python3 -c "
import json
bots = json.loads('''$BOTS_JSON''')
sel  = '$PLAYER_SEL'.strip()

if sel.lower() == 'all':
    result = bots
elif sel.isdigit():
    idx = int(sel) - 1
    if 0 <= idx < len(bots):
        result = [bots[idx]]
    else:
        result = [b for b in bots if str(b['seat']) == sel]
else:
    lo = sel.lower()
    result = [b for b in bots if lo in b['nick'].lower()]

print(json.dumps(result) if result else '')
")
[ -z "$PLAYERS_JSON" ] && fail "No bots matched '$PLAYER_SEL'"

PLAYER_COUNT=$(echo "$PLAYERS_JSON" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')

# ── Resolve target userId (for vote) ─────────────────────────────────────────
TARGET_UID=""
if [ -n "$TARGET_SEL" ]; then
  TARGET_UID=$(python3 << PYEOF
import json, urllib.request, urllib.error

bots  = json.loads(r"""$BOTS_JSON""")
sel   = "$TARGET_SEL"
base  = "$BASE"
gid   = "$GAME_ID"
token = "$FIRST_TOKEN"

# Try bot state file first ────────────────────────────────────────────────────
if sel.isdigit():
    match = next((b for b in bots if str(b["seat"]) == sel), None) \
         or (bots[int(sel)-1] if 0 <= int(sel)-1 < len(bots) else None)
    if match:
        print(match["userId"]); exit()
elif len(sel) > 20 and ("-" in sel or len(sel) == 36):
    print(sel); exit()   # looks like a raw UUID
else:
    lo    = sel.lower()
    match = next((b for b in bots if lo in b["nick"].lower()), None)
    if match:
        print(match["userId"]); exit()

# Fallback: query game state, match by seatIndex ──────────────────────────────
req = urllib.request.Request(f"{base}/game/{gid}/state",
                             headers={"Authorization": f"Bearer {token}"})
try:
    with urllib.request.urlopen(req) as r:
        state = json.loads(r.read())
except urllib.error.HTTPError as e:
    state = json.loads(e.read())

if sel.isdigit():
    match = next((p for p in state.get("players", [])
                  if str(p.get("seatIndex", "")) == sel), None)
    if match:
        print(match["userId"]); exit()

print(sel)   # last resort: pass through as-is
PYEOF
)
  [ -z "$TARGET_UID" ] && fail "Could not resolve target '$TARGET_SEL'"
  info "Target: $TARGET_SEL → ${TARGET_UID:0:16}…"
fi

# ── Map action to ActionType ──────────────────────────────────────────────────
case "$ACTION" in
  campaign) ACTION_TYPE="SHERIFF_CAMPAIGN" ;;
  pass)     ACTION_TYPE="SHERIFF_PASS"     ;;
  vote)     ACTION_TYPE="SHERIFF_VOTE"     ;;
  abstain)  ACTION_TYPE="SHERIFF_ABSTAIN"  ;;
esac

# ── Send actions ──────────────────────────────────────────────────────────────
section "$ACTION_TYPE  [$PLAYER_COUNT player(s)]  game=$GAME_ID"

for idx in $(seq 0 $(( PLAYER_COUNT - 1 ))); do
  NICK=$(echo  "$PLAYERS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[$idx]['nick'])")
  TOKEN=$(echo "$PLAYERS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[$idx]['token'])")
  SEAT=$(echo  "$PLAYERS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[$idx]['seat'])")

  BODY="{\"gameId\":$GAME_ID,\"actionType\":\"$ACTION_TYPE\""
  [ -n "$TARGET_UID" ] && BODY="${BODY},\"targetUserId\":\"$TARGET_UID\""
  BODY="${BODY}}"

  RESP=$(curl -s -X POST "$BASE/game/action" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -d "$BODY")

  OK=$(echo "$RESP"  | python3 -c 'import json,sys; print(json.load(sys.stdin).get("success",False))' 2>/dev/null)
  ERR=$(echo "$RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("error",""))' 2>/dev/null)

  if [ "$OK" = "True" ]; then
    TGT=""
    [ -n "$TARGET_UID" ] && TGT=" → seat $TARGET_SEL"
    ok "$(printf 'seat %-3s  %-20s  %s%s' "$SEAT" "$NICK" "$ACTION_TYPE" "$TGT")"
  else
    echo -e "  ${YELLOW}!  $(printf 'seat %-3s  %-20s  rejected: %s' "$SEAT" "$NICK" "$ERR")${RESET}"
  fi
done

echo ""
