#!/bin/bash
# 手动测试白痴翻牌流程

ROOM_CODE="TEST"
GAME_ID=$(curl -s "http://localhost:8080/api/room/by-code/$ROOM_CODE" | grep -o '"gameId":[0-9]*' | grep -o '[0-9]*')

if [ -z "$GAME_ID" ]; then
    echo "游戏不存在，请先创建房间"
    exit 1
fi

echo "Game ID: $GAME_ID"

# 1. 查询当前游戏状态
echo "=== 查询游戏状态 ==="
curl -s "http://localhost:8080/api/game/$GAME_ID/state" | python3 -m json.tool

# 2. 模拟投票（假设白痴是 seat 1）
echo "=== 模拟投票给白痴 ==="
HOST_TOKEN=$(cat /tmp/werewolf-${ROOM_CODE}.json | grep -o '"hostToken":"[^"]*"' | cut -d'"' -f4)
curl -s -X POST "http://localhost:8080/api/game/action" \
  -H "Authorization: Bearer $HOST_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"gameId\":$GAME_ID,\"actionType\":\"VOTING_REVEAL_TALLY\"}"

echo ""
echo "=== 查询翻牌后的状态 ==="
sleep 2
curl -s "http://localhost:8080/api/game/$GAME_ID/state" | python3 -m json.tool | grep -A 5 "idiotRevealed"