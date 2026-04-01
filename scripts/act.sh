#!/usr/bin/env bash
# =============================================================================
# act.sh — Send any game action on behalf of one or all bot players.
#
# Usage:
#   ./scripts/act.sh <ACTION_TYPE> [PLAYER] [--target PLAYER] [--payload JSON] [--room CODE]
#
# ACTION_TYPE (case-insensitive):
#   Night phase (sequence: WEREWOLF → SEER → WITCH → GUARD):
#     WOLF_SELECT       wolf pre-selects kill target (shared with teammates)    (requires --target)
#     WOLF_KILL         wolf CONFIRMS kill target (advances sub-phase)          (requires --target)
#     SEER_CHECK        seer checks a player                                    (requires --target)
#     SEER_CONFIRM      seer confirms result (advances sub-phase)
#     WITCH_ACT         witch acts                                              (requires --payload)
#       --payload '{"useAntidote":true}'                        save wolf-kill victim
#       --payload '{"useAntidote":false}'                       skip antidote
#       --payload '{"useAntidote":false,"poisonTargetUserId":"<userId>"}'  poison a player
#       NOTE: "poisonTargetUserId" is the correct field (NOT "poisonTargetId" or "targetId")
#     GUARD_PROTECT     guard protects a player                                 (requires --target)
#     GUARD_SKIP        guard skips this night
#   Day phase:
#     REVEAL_NIGHT_RESULT   host reveals who died last night
#     DAY_ADVANCE           host advances day (end of speech round)
#     CONFIRM_ROLE      player confirms their role card
#     START_NIGHT       host starts the night phase
#   Voting phase:
#     SUBMIT_VOTE       player votes for target              (requires --target)
#     VOTING_REVEAL_TALLY   host reveals vote tally
#     VOTING_CONTINUE   host continues after tied vote / normal elimination
#     IDIOT_REVEAL      idiot reveals identity when voted out (first time only, stays alive)
#   Hunter / Badge:
#     HUNTER_SHOOT      hunter shoots a target               (requires --target)
#     HUNTER_SKIP       hunter skips shooting
#     BADGE_PASS        sheriff passes badge to target       (requires --target)
#     BADGE_DESTROY     sheriff destroys the badge
#   Sheriff election (also in sheriff.sh):
#     SHERIFF_CAMPAIGN          run for sheriff
#     SHERIFF_PASS              opt out (SIGNUP phase)
#     SHERIFF_QUIT              candidate drops out before speeches start (SIGNUP phase)
#     SHERIFF_QUIT_CAMPAIGN     candidate drops out DURING their own speech (SPEECH phase)
#     SHERIFF_VOTE              vote for sheriff candidate   (requires --target)
#     SHERIFF_ABSTAIN           abstain from sheriff vote
#     SHERIFF_CONFIRM_VOTE      confirm sheriff vote (no-op; vote already submitted)
#     SHERIFF_START_SPEECH      host advances to SPEECH sub-phase
#     SHERIFF_ADVANCE_SPEECH    host advances to next speaker
#     SHERIFF_REVEAL_RESULT     host reveals election result
#     SHERIFF_APPOINT           host appoints sheriff after tied vote  (requires --target)
#
# PSEUDO-ACTIONS:
#   STATUS            print phase/subPhase/dayNumber for the current game and exit
#
# PLAYER (optional, default = all bots):
#   all         every bot in the state file
#   <INDEX>     1-based position in the bot list (e.g. 1 = first bot joined)
#   <SEAT>      seat number (fallback when INDEX is out of range)
#   <NICK>      case-insensitive nickname substring
#
# TARGET (required for actions that need a target):
#   <SEAT>      resolved via live game state (works for host too)
#   <NICK>      nickname substring (state file first, then pass-through)
#   <USER_ID>   raw userId string
#
# PAYLOAD:
#   Raw JSON string merged into the request body, e.g.:
#     --payload '{"useAntidote":true}'
#     --payload '{"useAntidote":false,"poisonTargetUserId":"<userId>"}'
#
# OPTIONS:
#   --room CODE   room code (auto-detect if only one state file in /tmp)
#   --target P    target player (same as second positional arg after PLAYER)
#   --payload J   extra JSON payload fields
#
# Examples:
#   ./scripts/act.sh STATUS                                       # show phase/subPhase/day
#   ./scripts/act.sh WOLF_SELECT --target 3                       # wolf pre-selects seat 3 (visible to teammates)
#   ./scripts/act.sh WOLF_KILL --target 3                         # wolf confirms kill seat 3
#   ./scripts/act.sh SEER_CHECK Bot2 --target 4                   # Bot2 checks seat 4
#   ./scripts/act.sh SEER_CONFIRM                                 # seer confirms result
#   ./scripts/act.sh WITCH_ACT --payload '{"useAntidote":true}'
#   ./scripts/act.sh WITCH_ACT --payload '{"useAntidote":false}'
#   ./scripts/act.sh WITCH_ACT --payload '{"useAntidote":false,"poisonTargetUserId":"<userId>"}'
#   ./scripts/act.sh GUARD_PROTECT --target 2                     # guard protects seat 2
#   ./scripts/act.sh GUARD_SKIP                                   # guard skips
#   ./scripts/act.sh CONFIRM_ROLE                                 # all bots confirm their roles
#   ./scripts/act.sh START_NIGHT                                  # host starts night
#   ./scripts/act.sh REVEAL_NIGHT_RESULT                          # host reveals night deaths
#   ./scripts/act.sh DAY_ADVANCE                                  # host advances day
#   ./scripts/act.sh SUBMIT_VOTE --target 3                       # all bots vote for seat 3
#   ./scripts/act.sh SUBMIT_VOTE 2 --target 5                     # bot at seat 2 votes seat 5
#   ./scripts/act.sh IDIOT_REVEAL <idiot-nick>                    # idiot reveals identity (stays alive)
#   ./scripts/act.sh VOTING_REVEAL_TALLY                          # host reveals tally
#   ./scripts/act.sh VOTING_CONTINUE                              # host continues after result
#   ./scripts/act.sh HUNTER_SHOOT --target 4                      # hunter shoots seat 4
#   ./scripts/act.sh HUNTER_SKIP                                  # hunter skips
#   ./scripts/act.sh BADGE_PASS --target 3                        # sheriff passes badge to seat 3
#   ./scripts/act.sh BADGE_DESTROY                                # sheriff destroys badge
#   ./scripts/act.sh SHERIFF_QUIT <nick>                          # candidate drops out (SIGNUP)
#   ./scripts/act.sh SHERIFF_QUIT_CAMPAIGN <nick>                 # candidate quits mid-speech (SPEECH)
#   ./scripts/act.sh SHERIFF_APPOINT --target 3                   # host appoints seat 3 after tie
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

Usage: $0 <ACTION_TYPE> [PLAYER] [--target PLAYER] [--payload JSON] [--room CODE]

  Pseudo  : STATUS
  Actions : WOLF_SELECT WOLF_KILL SEER_CHECK SEER_CONFIRM WITCH_ACT GUARD_PROTECT GUARD_SKIP
            CONFIRM_ROLE START_NIGHT REVEAL_NIGHT_RESULT DAY_ADVANCE
            SUBMIT_VOTE VOTING_REVEAL_TALLY VOTING_CONTINUE IDIOT_REVEAL
            HUNTER_SHOOT HUNTER_SKIP BADGE_PASS BADGE_DESTROY
            SHERIFF_CAMPAIGN SHERIFF_PASS SHERIFF_QUIT SHERIFF_QUIT_CAMPAIGN
            SHERIFF_VOTE SHERIFF_ABSTAIN SHERIFF_CONFIRM_VOTE
            SHERIFF_START_SPEECH SHERIFF_ADVANCE_SPEECH SHERIFF_REVEAL_RESULT SHERIFF_APPOINT
  Player  : all (default) | <index> | <seat> | <nick>
  Target  : <seat> | <nick> | <userId>   (required for some actions)
  Payload : raw JSON object fields, e.g. '{"useAntidote":true}'
            WITCH_ACT payload must use field "poisonTargetUserId" (not "poisonTargetId")

Examples:
  $0 STATUS
  $0 WOLF_SELECT --target 3
  $0 WOLF_KILL --target 3
  $0 SEER_CHECK Bot2 --target 4
  $0 WITCH_ACT --payload '{"useAntidote":true}'
  $0 WITCH_ACT --payload '{"useAntidote":false,"poisonTargetUserId":"<userId>"}'
  $0 SUBMIT_VOTE --target 3
  $0 CONFIRM_ROLE
  $0 START_NIGHT
  $0 SHERIFF_APPOINT --target 3
EOF
  exit 1
}

# ── Arg parsing ───────────────────────────────────────────────────────────────
ACTION_TYPE=""
PLAYER_SEL="all"
TARGET_SEL=""
PAYLOAD_JSON=""
ROOM_CODE=""

while [[ $# -gt 0 ]]; do
  case $1 in
    --room)    ROOM_CODE=$(echo "$2" | tr 'a-z' 'A-Z'); shift 2 ;;
    --target)  TARGET_SEL="$2"; shift 2 ;;
    --payload) PAYLOAD_JSON="$2"; shift 2 ;;
    -h|--help) usage ;;
    -*)        echo "Unknown flag: $1"; usage ;;
    *)
      if   [ -z "$ACTION_TYPE"        ]; then ACTION_TYPE=$(echo "$1" | tr 'a-z' 'A-Z')
      elif [ "$PLAYER_SEL" = "all"    ]; then PLAYER_SEL="$1"
      else                                   TARGET_SEL="$1"   # 3rd positional = target
      fi
      shift ;;
  esac
done

[ -z "$ACTION_TYPE" ] && usage

# Validate ACTION_TYPE
case "$ACTION_TYPE" in
  STATUS) ;;  # pseudo-action handled below
  WOLF_SELECT|WOLF_KILL|SEER_CHECK|SEER_CONFIRM|WITCH_ACT|GUARD_PROTECT|GUARD_SKIP| \
  CONFIRM_ROLE|START_NIGHT|REVEAL_NIGHT_RESULT|DAY_ADVANCE| \
  SUBMIT_VOTE|VOTING_REVEAL_TALLY|VOTING_CONTINUE|IDIOT_REVEAL| \
  HUNTER_SHOOT|HUNTER_SKIP|BADGE_PASS|BADGE_DESTROY| \
  SHERIFF_CAMPAIGN|SHERIFF_PASS|SHERIFF_QUIT|SHERIFF_QUIT_CAMPAIGN| \
  SHERIFF_VOTE|SHERIFF_ABSTAIN|SHERIFF_CONFIRM_VOTE| \
  SHERIFF_START_SPEECH|SHERIFF_ADVANCE_SPEECH|SHERIFF_REVEAL_RESULT|SHERIFF_APPOINT) ;;
  *) fail "Unknown action '$ACTION_TYPE'" ;;
esac

# Actions that require --target
case "$ACTION_TYPE" in
  WOLF_SELECT|WOLF_KILL|SEER_CHECK|GUARD_PROTECT|SUBMIT_VOTE|HUNTER_SHOOT|BADGE_PASS|SHERIFF_VOTE|SHERIFF_APPOINT)
    [ -z "$TARGET_SEL" ] && fail "'$ACTION_TYPE' requires --target <seat|nick|userId>" ;;
esac

# WITCH_ACT requires --payload
case "$ACTION_TYPE" in
  WITCH_ACT)
    [ -z "$PAYLOAD_JSON" ] && fail "'WITCH_ACT' requires --payload (e.g. {\"useAntidote\":false})" ;;
esac

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

STATE_DATA=$(python3 -c "import json; print(json.dumps(json.load(open('$STATE_FILE'))))")
BOTS_JSON=$(echo "$STATE_DATA" | python3 -c "import json,sys; print(json.dumps(json.load(sys.stdin)['bots']))")
BOT_COUNT=$(echo "$BOTS_JSON" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')
[ "$BOT_COUNT" -eq 0 ] && fail "No bots in state file for room $ROOM_CODE"

# Manually-logged-in users saved via: ./scripts/dev-login.sh <nick> --room <code>
USERS_JSON=$(echo "$STATE_DATA" | python3 -c "import json,sys; print(json.dumps(json.load(sys.stdin).get('users', [])))")
# ALL_PLAYERS = bots + manual users (used for player/target resolution)
ALL_PLAYERS_JSON=$(python3 -c "
import json, sys
bots  = json.loads('''$BOTS_JSON''')
users = json.loads('''$USERS_JSON''')
# users may lack a 'seat' key — normalise to None
for u in users:
    u.setdefault('seat', None)
print(json.dumps(bots + users))
")

# ── Host token (for HOST player selector and host-only actions) ───────────────
HOST_TOKEN=$(echo "$STATE_DATA" | python3 -c "import json,sys; print(json.load(sys.stdin).get('hostToken',''))" 2>/dev/null || true)
HOST_FILE="$STATE_DIR/werewolf-host-${ROOM_CODE}.json"
[ -z "$HOST_TOKEN" ] && [ -f "$HOST_FILE" ] && HOST_TOKEN=$(python3 -c "import json; print(json.load(open('$HOST_FILE')).get('token',''))" 2>/dev/null || true)

# NOTE: Token refresh via re-login is NOT supported — each login creates a new
# guest user UUID. Fix: set expiration-hours: 24 in application.yml (already done).
# This function is kept as a stub so the call sites below don't break.
refresh_token() {
  local nick="$1" old_token="$2"
  echo -e "  ${RED}✗  Token for $nick is expired. Restart the backend or re-run join-room.sh.${RESET}" >&2
  echo "$old_token"
}

# ── Resolve + cache game ID ───────────────────────────────────────────────────
FIRST_TOKEN=$(echo "$BOTS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['token'])")
FIRST_NICK=$(echo "$BOTS_JSON"  | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['nick'])")

# 1. Use cached gameId from state file if present
CACHED_GAME_ID=$(echo "$STATE_DATA" | python3 -c "import json,sys; print(json.load(sys.stdin).get('gameId',''))" 2>/dev/null || true)

if [ -n "$CACHED_GAME_ID" ]; then
  # Verify cached ID is still valid (quick probe, refresh token on 401)
  PROBE=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer $FIRST_TOKEN" "$BASE/game/$CACHED_GAME_ID/state")
  if [ "$PROBE" = "401" ]; then
    echo -e "  ${CYAN}→  Token expired — refreshing …${RESET}" >&2
    FIRST_TOKEN=$(refresh_token "$FIRST_NICK" "$FIRST_TOKEN")
    BOTS_JSON=$(python3 -c "import json; print(json.dumps(json.load(open('$STATE_FILE'))['bots']))")
    PROBE=$(curl -s -o /dev/null -w "%{http_code}" \
      -H "Authorization: Bearer $FIRST_TOKEN" "$BASE/game/$CACHED_GAME_ID/state")
  fi
  if [ "$PROBE" = "200" ]; then
    GAME_ID="$CACHED_GAME_ID"
    echo -e "  ${CYAN}→  Game ID = $GAME_ID (cached)${RESET}" >&2
  else
    CACHED_GAME_ID=""  # stale cache — fall through to scan
  fi
fi

if [ -z "$CACHED_GAME_ID" ]; then
  BOT_USER_IDS=$(echo "$BOTS_JSON" | python3 -c "
import json,sys
bots=json.load(sys.stdin)
print(','.join(b['userId'] for b in bots))
")
  echo -e "  ${CYAN}→  Discovering game ID …${RESET}" >&2
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
  # Cache game ID in state file for all future calls
  python3 - "$STATE_FILE" "$GAME_ID" << 'PYEOF'
import json, sys
path, gid = sys.argv[1], int(sys.argv[2])
d = json.load(open(path))
d["gameId"] = gid
with open(path, "w") as f:
    json.dump(d, f)
PYEOF
  echo -e "  ${CYAN}→  Game ID = $GAME_ID (discovered + cached)${RESET}" >&2
fi

# ── STATUS pseudo-action ──────────────────────────────────────────────────────
if [ "$ACTION_TYPE" = "STATUS" ]; then
  python3 << PYEOF
import json, urllib.request, urllib.error

base  = "$BASE"
token = "$FIRST_TOKEN"
gid   = "$GAME_ID"

req = urllib.request.Request(f"{base}/game/{gid}/state",
                              headers={"Authorization": f"Bearer {token}"})
try:
    with urllib.request.urlopen(req) as r:
        d = json.loads(r.read())
except urllib.error.HTTPError as e:
    print(f"  HTTP error: {e.code}"); exit(1)

phase    = d.get("phase", "?")
sub_val  = (d.get("nightPhase") or d.get("votingPhase") or
            d.get("dayPhase") or d.get("sheriffElection") or {})
sub      = sub_val.get("subPhase", "-")
day      = d.get("dayNumber", "?")
winner   = d.get("winner")
alive    = sum(1 for p in d.get("players", []) if p.get("isAlive", True))
total    = len(d.get("players", []))

print(f"  Game {gid}  phase={phase}  subPhase={sub}  day={day}  alive={alive}/{total}" +
      (f"  winner={winner}" if winner else ""))
PYEOF
  exit 0
fi

# ── Resolve acting players ────────────────────────────────────────────────────
# Special selector: HOST → use host token
USE_HOST_ONLY=false
if [ "$(echo "$PLAYER_SEL" | tr 'a-z' 'A-Z')" = "HOST" ]; then
  [ -z "$HOST_TOKEN" ] && fail "No host token found. Run join-room.sh or set hostToken in state file."
  HOST_NICK=$(echo "$STATE_DATA" | python3 -c "import json,sys; print(json.load(sys.stdin).get('hostNick','Host'))" 2>/dev/null || echo "Host")
  PLAYERS_JSON="[{\"nick\":\"$HOST_NICK\",\"token\":\"$HOST_TOKEN\",\"seat\":\"host\",\"userId\":\"\"}]"
  USE_HOST_ONLY=true
else
  PLAYERS_JSON=$(python3 -c "
import json
all_players = json.loads('''$ALL_PLAYERS_JSON''')
bots        = json.loads('''$BOTS_JSON''')
sel         = '$PLAYER_SEL'.strip()

if sel.lower() == 'all':
    result = all_players
elif sel.isdigit():
    idx = int(sel) - 1
    if 0 <= idx < len(bots):
        result = [bots[idx]]
    else:
        result = [p for p in all_players if str(p.get('seat')) == sel]
else:
    lo = sel.lower()
    result = [p for p in all_players if lo in p['nick'].lower()]

print(json.dumps(result) if result else '')
")
  [ -z "$PLAYERS_JSON" ] && fail "No player matched '$PLAYER_SEL' (bots + manual users)"
fi

PLAYER_COUNT=$(echo "$PLAYERS_JSON" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')

# ── Resolve target userId ─────────────────────────────────────────────────────
TARGET_UID=""
if [ -n "$TARGET_SEL" ]; then
  TARGET_UID=$(python3 << PYEOF
import json, urllib.request, urllib.error

all_players = json.loads(r"""$ALL_PLAYERS_JSON""")
bots        = json.loads(r"""$BOTS_JSON""")
sel         = "$TARGET_SEL"
base        = "$BASE"
gid         = "$GAME_ID"
token       = "$FIRST_TOKEN"

# Try state file (bots + manual users) first
if sel.isdigit():
    match = next((p for p in all_players if str(p.get("seat")) == sel), None) \
         or (bots[int(sel)-1] if 0 <= int(sel)-1 < len(bots) else None)
    if match:
        print(match["userId"]); exit()
elif len(sel) > 20 and ("-" in sel or len(sel) == 36):
    print(sel); exit()   # raw UUID
else:
    lo    = sel.lower()
    match = next((p for p in all_players if lo in p["nick"].lower()), None)
    if match:
        print(match["userId"]); exit()

# Fallback: query game state, match by seatIndex
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

# ── Send actions ──────────────────────────────────────────────────────────────
section "$ACTION_TYPE  [$PLAYER_COUNT player(s)]  game=$GAME_ID"

for idx in $(seq 0 $(( PLAYER_COUNT - 1 ))); do
  NICK=$(echo  "$PLAYERS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[$idx]['nick'])")
  TOKEN=$(echo "$PLAYERS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[$idx]['token'])")
  SEAT=$(echo  "$PLAYERS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[$idx]['seat'])")

  # Build request body
  BODY=$(python3 -c "
import json, sys

body = {'gameId': int('$GAME_ID'), 'actionType': '$ACTION_TYPE'}

target = '$TARGET_UID'
if target:
    body['targetUserId'] = target

payload_str = r\"\"\"$PAYLOAD_JSON\"\"\"
if payload_str.strip():
    try:
        extra = json.loads(payload_str)
        body['payload'] = extra
    except json.JSONDecodeError as e:
        print(f'Invalid --payload JSON: {e}', file=sys.stderr)
        sys.exit(1)

print(json.dumps(body))
")

  RESP=$(curl -s -X POST "$BASE/game/action" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' \
    -d "$BODY")

  # Auto-refresh token on 401 and retry once
  HTTP_STATUS=$(echo "$RESP" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("status",""))' 2>/dev/null)
  if [ "$HTTP_STATUS" = "401" ] && [ "$USE_HOST_ONLY" = "false" ]; then
    TOKEN=$(refresh_token "$NICK" "$TOKEN")
    BOTS_JSON=$(python3 -c "import json; print(json.dumps(json.load(open('$STATE_FILE'))['bots']))")
    RESP=$(curl -s -X POST "$BASE/game/action" \
      -H "Authorization: Bearer $TOKEN" \
      -H 'Content-Type: application/json' \
      -d "$BODY")
  fi

  OK=$(echo "$RESP"  | python3 -c 'import json,sys; print(json.load(sys.stdin).get("success",False))' 2>/dev/null)
  ERR=$(echo "$RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("error",""))' 2>/dev/null)

  if [ "$OK" = "True" ]; then
    TGT=""
    [ -n "$TARGET_SEL" ] && TGT=" → $TARGET_SEL"
    ok "$(printf 'seat %-3s  %-20s  %s%s' "$SEAT" "$NICK" "$ACTION_TYPE" "$TGT")"
  else
    echo -e "  ${YELLOW}!  $(printf 'seat %-3s  %-20s  rejected: %s' "$SEAT" "$NICK" "$ERR")${RESET}"
  fi
done

echo ""
