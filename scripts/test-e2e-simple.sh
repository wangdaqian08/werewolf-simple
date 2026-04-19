#!/bin/bash
set -e

echo "=== E2E Test Script ==="
echo "Step 1: Login as host"
HOST_LOGIN=$(curl -s -X POST "http://localhost:8080/api/user/login" -H "Content-Type: application/json" -d '{"nickname":"E2ETestHost"}')
HOST_TOKEN=$(echo "$HOST_LOGIN" | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")
echo "Host token: ${HOST_TOKEN:0:30}..."

echo "Step 2: Create room"
ROOM_RESP=$(curl -s -X POST "http://localhost:8080/api/room/create" -H "Authorization: Bearer $HOST_TOKEN" -H "Content-Type: application/json" -d '{"config":{"totalPlayers":12,"roles":["WEREWOLF","VILLAGER","SEER","WITCH","HUNTER","GUARD","IDIOT"],"hasSheriff":false,"winCondition":"CLASSIC"}}')
ROOM_CODE=$(echo "$ROOM_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin)['roomCode'])")
ROOM_ID=$(echo "$ROOM_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin)['roomId'])")
echo "Room created: $ROOM_CODE (ID: $ROOM_ID)"

echo "Step 3: Join 11 bots"
cd /Users/dq/workspace/werewolf-simple
./scripts/join-room.sh --room-code "$ROOM_CODE" --player-num 11 --ready --host-token "$HOST_TOKEN" --host-nick E2ETestHost

echo "Step 4: Host claims seat"
HOST_SEAT_RESP=$(curl -s -X POST "http://localhost:8080/api/room/seat" -H "Authorization: Bearer $HOST_TOKEN" -H "Content-Type: application/json" -d "{\"seatIndex\":12,\"roomId\":$ROOM_ID}")
echo "Host seat result: $HOST_SEAT_RESP"

echo "Step 5: Start game"
START_RESP=$(curl -s -X POST "http://localhost:8080/api/game/start" -H "Authorization: Bearer $HOST_TOKEN" -H "Content-Type: application/json" -d "{\"roomId\":$ROOM_ID}")
echo "Game start result: $START_RESP"

echo "Step 6: Check roles"
./scripts/roles.sh --room "$ROOM_CODE"

echo "=== E2E Test Complete ==="