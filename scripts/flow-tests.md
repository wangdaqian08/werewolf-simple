// set room id
ROOM_ID=QKAX

// join the room with 11 bot players and with ready status, room code: VRB7
./scripts/join-room.sh  $ROOM_ID 11 --ready

// all bots confirm roles
./scripts/act.sh CONFIRM_ROLE --room $ROOM_ID

// bot nickname includes Bot1 run for the campaign (such as: Bot10-123,Bot11-5915,Bot1-5915)
./scripts/sheriff.sh campaign --player Bot1 --room $ROOM_ID

// all bots vote Bot1 for the sheriff
./scripts/sheriff.sh vote --target Bot1 --room $ROOM_ID

// bot1 abstain vote for the sheriff voting
./scripts/sheriff.sh abstain --player Bot1 --room $ROOM_ID

// show role for each player
./scripts/roles.sh --room $ROOM_ID

// login as player
./scripts/act.sh CONSOLE_LOGIN Bot2 --room $ROOM_ID

// day phrase all bots abstain vote
./scripts/act.sh SUBMIT_VOTE --room $ROOM_ID