#!/usr/bin/env bash
# =============================================================================
# join-room.sh — Add bots to an existing room and optionally ready them up.
#
# Usage:
#   ./scripts/join-room.sh <ROOM_CODE|ROOM_ID> [PLAYER_NUM] [--ready [SEATS]] [--prefix NAME]
#
# First argument (required):
#   ROOM_CODE   4-char code shown in the browser  e.g. AB3C
#   ROOM_ID     numeric DB id                      e.g. 12
#               (auto-detected: all-digits → room_id, otherwise → room_code)
#
# PLAYER_NUM (default: 3):
#   N>0 → join N bots, claim seats, then optionally ready some.
#   0   → ready-only: no new bots join; uses tokens from the last join run.
#
# Flags:
#   --ready [SEATS]  mark bots as READY.
#                    SEATS = comma-separated seat indices (e.g. 1,3,5).
#                    Omit SEATS to ready all bots.
#   --prefix NAME    nickname prefix (default: "Bot")
#   --room-id N      explicit numeric room id
#   --room-code CODE explicit room code
#   --player-num N   explicit player count
#
# Examples:
#   ./scripts/join-room.sh AB3C 5               # join 5 bots, no ready
#   ./scripts/join-room.sh AB3C 5 --ready       # join 5 bots, all ready
#   ./scripts/join-room.sh AB3C 5 --ready 1,3   # join 5 bots, only seats 1+3 ready
#   ./scripts/join-room.sh AB3C 0 --ready 1,3   # ready-only: seats 1+3 (no new join)
#   ./scripts/join-room.sh AB3C 0 --ready       # ready-only: all bots
# =============================================================================

set -euo pipefail

BASE="${BACKEND_BASE:-http://localhost:8080/api}"
STATE_DIR="/tmp"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

pass()    { echo -e "  ${GREEN}✔  $*${RESET}"; }
fail()    { echo -e "  ${RED}✗  $*${RESET}"; exit 1; }
info()    { echo -e "  ${CYAN}→  $*${RESET}"; }
section() { echo -e "\n${BOLD}${YELLOW}══  $*${RESET}"; }

usage() {
  echo "Usage: $0 <ROOM_CODE|ROOM_ID> [PLAYER_NUM] [--ready [SEATS]] [--prefix NAME]"
  exit 1
}

# ── arg parsing ───────────────────────────────────────────────────────────────
ROOM_CODE=""
ROOM_ID=""
PLAYER_NUM=3
READY_MODE="none"   # none | all | seats
READY_SEATS=""
PREFIX="Bot"
POSITIONAL_DONE=false
HOST_TOKEN_SAVE=""
HOST_NICK_SAVE=""

while [[ $# -gt 0 ]]; do
  case $1 in
    --ready)
      shift
      if [[ $# -gt 0 && "$1" =~ ^[0-9]+(,[0-9]+)*$ ]]; then
        READY_MODE="seats"; READY_SEATS="$1"; shift
      else
        READY_MODE="all"
      fi ;;
    --prefix)       PREFIX="$2"; shift 2 ;;
    --room-id)      ROOM_ID="$2"; shift 2 ;;
    --room-code)    ROOM_CODE=$(echo "$2" | tr 'a-z' 'A-Z'); shift 2 ;;
    --player-num)   PLAYER_NUM="$2"; shift 2 ;;
    --host-token)   HOST_TOKEN_SAVE="$2"; shift 2 ;;
    --host-nick)    HOST_NICK_SAVE="$2"; shift 2 ;;
    -*)             echo "Unknown flag: $1"; usage ;;
    *)
      if ! $POSITIONAL_DONE; then
        if [[ "$1" =~ ^[0-9]+$ ]]; then ROOM_ID="$1"
        else ROOM_CODE=$(echo "$1" | tr 'a-z' 'A-Z'); fi
        POSITIONAL_DONE=true
      elif [[ "$1" =~ ^[0-9]+$ ]]; then
        PLAYER_NUM="$1"
      else echo "Unexpected argument: $1"; usage; fi
      shift ;;
  esac
done

[ -z "$ROOM_CODE" ] && [ -z "$ROOM_ID" ] && usage

# ── helpers ───────────────────────────────────────────────────────────────────
post() { curl -s -X POST "$BASE$2" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' -d "$3"; }
get()  { curl -s "$BASE$2" -H "Authorization: Bearer $1"; }
login() {
  curl -s -X POST "$BASE/user/login" -H 'Content-Type: application/json' -d "{\"nickname\":\"$1\"}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])'
}

should_ready() {
  local seat=$1
  [ "$READY_MODE" = "all" ] && return 0
  if [ "$READY_MODE" = "seats" ]; then
    for rs in $(echo "$READY_SEATS" | tr ',' ' '); do
      [ "$rs" = "$seat" ] && return 0
    done
  fi
  return 1
}

# ── resolve ROOM_CODE from ROOM_ID ────────────────────────────────────────────
TOKEN1=""
if [ -n "$ROOM_ID" ] && [ -z "$ROOM_CODE" ]; then
  if [ "$PLAYER_NUM" -gt 0 ]; then
    TOKEN1=$(login "${PREFIX}1")
    RESP=$(get "$TOKEN1" "/room/$ROOM_ID")
  else
    # In ready-only mode we don't have a token yet; resolve after loading state
    RESP=""
  fi
  ROOM_CODE=$(echo "$RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["roomCode"])' 2>/dev/null) \
    || fail "Room $ROOM_ID not found"
fi

STATE_FILE="$STATE_DIR/werewolf-${ROOM_CODE}.json"

# ═══════════════════════════════════════════════════════════════════════════════
# READY-ONLY MODE  (PLAYER_NUM=0)
# ═══════════════════════════════════════════════════════════════════════════════
if [ "$PLAYER_NUM" -eq 0 ]; then
  [ "$READY_MODE" = "none" ] && fail "Specify --ready [SEATS] with --player-num 0"
  [ -f "$STATE_FILE" ] || fail "No saved bots for room $ROOM_CODE — join bots first (player-num > 0)"

  # Resolve ROOM_ID from state if it came in as a numeric arg
  ROOM_ID=$(python3 -c "import json; print(json.load(open('$STATE_FILE'))['roomId'])")
  BOTS_JSON=$(python3 -c "import json; print(json.dumps(json.load(open('$STATE_FILE'))['bots']))")
  BOT_COUNT=$(echo "$BOTS_JSON" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))')

  READY_LABEL="all"
  [ "$READY_MODE" = "seats" ] && READY_LABEL="seats $READY_SEATS"
  section "Ready-only — room $ROOM_CODE — $READY_LABEL"

  VIEW_TOKEN=""
  for idx in $(seq 0 $(( BOT_COUNT - 1 ))); do
    NICK=$(echo "$BOTS_JSON"  | python3 -c "import json,sys; print(json.load(sys.stdin)[$idx]['nick'])")
    SEAT=$(echo "$BOTS_JSON"  | python3 -c "import json,sys; print(json.load(sys.stdin)[$idx]['seat'])")
    TOKEN=$(echo "$BOTS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[$idx]['token'])")
    [ -z "$VIEW_TOKEN" ] && VIEW_TOKEN=$TOKEN

    if should_ready "$SEAT"; then
      READY_RESP=$(post "$TOKEN" "/room/ready" "{\"ready\":true,\"roomId\":$ROOM_ID}")
      OK=$(echo "$READY_RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("success",False))')
      [ "$OK" = "True" ] \
        && pass "$(printf '%-16s  seat %-3s  READY' "$NICK" "$SEAT")" \
        || fail "$NICK ready failed: $READY_RESP"
    else
      info "$(printf '%-16s  seat %-3s  skipped' "$NICK" "$SEAT")"
    fi
  done

# ═══════════════════════════════════════════════════════════════════════════════
# JOIN MODE  (PLAYER_NUM>0)
# ═══════════════════════════════════════════════════════════════════════════════
else
  READY_LABEL="none"
  [ "$READY_MODE" = "all"   ] && READY_LABEL="all"
  [ "$READY_MODE" = "seats" ] && READY_LABEL="seats $READY_SEATS"
  section "Joining room $ROOM_CODE — $PLAYER_NUM bot(s), ready=$READY_LABEL"

  declare -a NICKS TOKENS SEATS
  ROOM_ID=""
  TOTAL=99

  # Per-run suffix keeps nicknames unique across runs so repeated joins
  # don't create duplicate names that visually shadow previously-readied players.
  RUN_ID=$(printf '%04d' $(( RANDOM % 10000 )))

  for i in $(seq 1 "$PLAYER_NUM"); do
    NICK="${PREFIX}${i}-${RUN_ID}"
    NICKS[$i]=$NICK

    if [ "$i" -eq 1 ] && [ -n "$TOKEN1" ]; then TOKEN=$TOKEN1
    else TOKEN=$(login "$NICK"); fi
    TOKENS[$i]=$TOKEN

    JOIN=$(post "$TOKEN" "/room/join" "{\"roomCode\":\"$ROOM_CODE\"}")
    ERR=$(echo "$JOIN" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("error",""))' 2>/dev/null)
    [ -n "$ERR" ] && fail "$NICK join failed: $ERR"

    ROOM_ID=$(echo "$JOIN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["roomId"])')
    TOTAL=$(echo "$JOIN"   | python3 -c 'import json,sys; print(json.load(sys.stdin)["config"]["totalPlayers"])')

    # Claim first available seat — try 1..TOTAL, backend rejects duplicates
    SEAT=""
    for s in $(seq 1 "$TOTAL"); do
      SEAT_RESP=$(post "$TOKEN" "/room/seat" "{\"seatIndex\":$s,\"roomId\":$ROOM_ID}")
      OK=$(echo "$SEAT_RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("success",False))')
      if [ "$OK" = "True" ]; then SEAT=$s; break; fi
    done
    [ -z "$SEAT" ] && fail "$NICK could not claim any seat (room may be full)"
    SEATS[$i]=$SEAT

    STATUS="NOT_READY"
    if should_ready "$SEAT"; then
      READY_RESP=$(post "$TOKEN" "/room/ready" "{\"ready\":true,\"roomId\":$ROOM_ID}")
      OK=$(echo "$READY_RESP" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("success",False))')
      [ "$OK" = "True" ] && STATUS="READY" || fail "$NICK ready failed: $READY_RESP"
    fi

    pass "$(printf '%-16s  seat %-3s  %s' "$NICK" "$SEAT" "$STATUS")"
  done

  # Save state silently for ready-only runs
  SAVED_NICKS=""; SAVED_TOKENS=""; SAVED_SEATS=""
  for i in $(seq 1 "$PLAYER_NUM"); do
    SAVED_NICKS="${SAVED_NICKS}${NICKS[$i]}|"
    SAVED_TOKENS="${SAVED_TOKENS}${TOKENS[$i]}|"
    SAVED_SEATS="${SAVED_SEATS}${SEATS[$i]}|"
  done
  python3 -c "
import json, os, base64

def decode_user_id(token):
    p = token.split('.')[1]
    p += '=' * (4 - len(p) % 4)
    return json.loads(base64.b64decode(p))['sub']

nicks  = '${SAVED_NICKS}'.rstrip('|').split('|')
tokens = '${SAVED_TOKENS}'.rstrip('|').split('|')
seats  = '${SAVED_SEATS}'.rstrip('|').split('|')
new_bots = [{'nick': n, 'token': t, 'seat': int(s), 'userId': decode_user_id(t)} for n,t,s in zip(nicks,tokens,seats)]

# Merge: keep existing bots whose seat is not claimed by a new bot
existing = json.load(open('$STATE_FILE')).get('bots', []) if os.path.exists('$STATE_FILE') else []
new_seats = {b['seat'] for b in new_bots}
merged = [b for b in existing if b['seat'] not in new_seats] + new_bots
merged.sort(key=lambda b: b['seat'])

existing_data = json.load(open('$STATE_FILE')) if os.path.exists('$STATE_FILE') else {}
state = {**existing_data, 'roomCode': '$ROOM_CODE', 'roomId': $ROOM_ID, 'bots': merged}
if '$HOST_TOKEN_SAVE':
    state['hostToken'] = '$HOST_TOKEN_SAVE'
if '$HOST_NICK_SAVE':
    state['hostNick'] = '$HOST_NICK_SAVE'
json.dump(state, open('$STATE_FILE', 'w'))
" 2>/dev/null || true  # best-effort; non-fatal if /tmp is not writable

  VIEW_TOKEN=${TOKENS[1]}

  # Print bot tokens for manual API use
  section "Bot tokens"
  for i in $(seq 1 "$PLAYER_NUM"); do
    echo -e "  ${BOLD}${NICKS[$i]}${RESET}  seat=${SEATS[$i]}"
    echo    "    ${TOKENS[$i]}"
  done
fi

# ── room state ────────────────────────────────────────────────────────────────
section "Room state"
get "$VIEW_TOKEN" "/room/$ROOM_ID" | python3 -c "
import json,sys
room=json.load(sys.stdin)
players=sorted(room['players'], key=lambda p: (not p['isHost'], p['seatIndex'] or 99))
ready=[p for p in players if p['status']=='READY' and not p['isHost']]
guests=[p for p in players if not p['isHost']]
print(f\"  Players  : {len(players)} / {room['config']['totalPlayers']}\")
print(f\"  Ready    : {len(ready)} / {len(guests)} guests\")
print()
print('  {:<20} {:<6} {:<12} {}'.format('Nickname','Seat','Status','Host'))
print('  ' + '─'*48)
for p in players:
    seat=str(p['seatIndex']) if p['seatIndex'] else '—'
    host='✔' if p['isHost'] else ''
    print('  {:<20} {:<6} {:<12} {}'.format(p['nickname'],seat,p['status'],host))
"
echo ""
