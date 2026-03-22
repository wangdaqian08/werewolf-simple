#!/usr/bin/env bash
# =============================================================================
# as-bot.sh — Switch browser identity to a bot player for UI testing.
#
# Prints (and copies) the localStorage one-liner to paste in DevTools console,
# so you can view the game from a specific bot's perspective.
#
# Usage:
#   ./scripts/as-bot.sh                  # list all bots (auto-detect room)
#   ./scripts/as-bot.sh AB3C             # list all bots in room AB3C
#   ./scripts/as-bot.sh AB3C 1           # command for bot at index 1 (1-based)
#   ./scripts/as-bot.sh AB3C Bot2        # command for bot named Bot2
# =============================================================================

set -euo pipefail

STATE_DIR="/tmp"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'

fail()    { echo -e "  ${RED}✗  $*${RESET}"; exit 1; }
info()    { echo -e "  ${CYAN}→  $*${RESET}"; }
section() { echo -e "\n${BOLD}${YELLOW}══  $*${RESET}"; }

usage() {
  echo "Usage: $0 [ROOM_CODE] [BOT_INDEX_OR_NICK]"
  exit 1
}

# ── arg parsing ───────────────────────────────────────────────────────────────
ROOM_CODE=""
BOT_SEL=""   # index (1-based) or nickname

while [[ $# -gt 0 ]]; do
  case $1 in
    -h|--help) usage ;;
    -*)        echo "Unknown flag: $1"; usage ;;
    *)
      if [ -z "$ROOM_CODE" ]; then
        # If it looks like a pure number, treat as bot index with no room code
        if [[ "$1" =~ ^[0-9]+$ ]]; then
          BOT_SEL="$1"
        else
          ROOM_CODE=$(echo "$1" | tr 'a-z' 'A-Z')
        fi
      elif [ -z "$BOT_SEL" ]; then
        BOT_SEL="$1"
      else
        echo "Unexpected argument: $1"; usage
      fi
      shift ;;
  esac
done

# ── resolve state file ────────────────────────────────────────────────────────
if [ -z "$ROOM_CODE" ]; then
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

# ── helper: decode userId from JWT payload ────────────────────────────────────
decode_user_id() {
  local token="$1"
  python3 -c "
import json, base64, sys
p = '$token'.split('.')[1]
p += '=' * (4 - len(p) % 4)
print(json.loads(base64.b64decode(p))['sub'])
"
}

# ── list mode (no bot selector) ───────────────────────────────────────────────
if [ -z "$BOT_SEL" ]; then
  section "Bots — room $ROOM_CODE"
  for idx in $(seq 0 $(( BOT_COUNT - 1 ))); do
    NICK=$(echo "$BOTS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[$idx]['nick'])")
    SEAT=$(echo "$BOTS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[$idx]['seat'])")
    printf "  [%s]  %-16s  seat %s\n" "$(( idx + 1 ))" "$NICK" "$SEAT"
  done
  echo ""
  echo -e "  Run with an index or nickname to get the console command."
  echo -e "  Example: $0 $ROOM_CODE 1"
  exit 0
fi

# ── resolve bot by index or nickname ─────────────────────────────────────────
if [[ "$BOT_SEL" =~ ^[0-9]+$ ]]; then
  # 1-based index
  IDX=$(( BOT_SEL - 1 ))
  if [ "$IDX" -lt 0 ] || [ "$IDX" -ge "$BOT_COUNT" ]; then
    fail "Index $BOT_SEL out of range (1–$BOT_COUNT)"
  fi
  NICK=$(echo "$BOTS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[$IDX]['nick'])")
  SEAT=$(echo "$BOTS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[$IDX]['seat'])")
  TOKEN=$(echo "$BOTS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[$IDX]['token'])")
else
  # nickname match (case-insensitive)
  SEL_LOWER=$(echo "$BOT_SEL" | tr 'A-Z' 'a-z')
  FOUND=$(echo "$BOTS_JSON" | python3 -c "
import json, sys
bots = json.load(sys.stdin)
sel  = '$SEL_LOWER'
for i, b in enumerate(bots):
    if b['nick'].lower() == sel:
        print(i)
        break
else:
    print(-1)
")
  if [ "$FOUND" = "-1" ]; then
    fail "No bot named '$BOT_SEL' in room $ROOM_CODE"
  fi
  IDX="$FOUND"
  NICK=$(echo "$BOTS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[$IDX]['nick'])")
  SEAT=$(echo "$BOTS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[$IDX]['seat'])")
  TOKEN=$(echo "$BOTS_JSON" | python3 -c "import json,sys; print(json.load(sys.stdin)[$IDX]['token'])")
fi

# ── decode userId from JWT ────────────────────────────────────────────────────
USER_ID=$(decode_user_id "$TOKEN")

# ── build JS one-liner ────────────────────────────────────────────────────────
JS="localStorage.setItem('jwt','${TOKEN}');localStorage.setItem('userId','${USER_ID}');localStorage.setItem('nickname','${NICK}');location.reload();"

# ── output ────────────────────────────────────────────────────────────────────
section "Switch to $NICK (seat $SEAT) — room $ROOM_CODE"
echo ""
echo -e "  Paste in browser DevTools console:\n"
echo -e "  ${BOLD}${JS}${RESET}"
echo ""

# copy to clipboard on macOS
if command -v pbcopy &>/dev/null; then
  echo "$JS" | pbcopy
  echo -e "  ${GREEN}✔ Copied to clipboard${RESET}"
fi
