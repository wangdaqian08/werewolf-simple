VotingPhase.vue:686 [Vue warn]: Unhandled error during execution of component event handler
at <VotingPhase key="voting-HUNTER_SHOOT-1" voting-phase=
{subPhase: 'HUNTER_SHOOT', dayNumber: 1, phaseDeadline: 0, phaseStarted: 0, canVote: true, …}
players=
(12) [{…}, {…}, {…}, {…}, {…}, {…}, {…}, {…}, {…}, {…}, {…}, {…}]
0
:
{userId: 'guest:977773bb-ef44-434d-b0e2-57dc92381348', nickname: 'Bot2-7784', seatIndex: 2, isAlive: true, isSheriff: false, …}
1
:
{userId: 'guest:6c84a101-149e-407b-a58d-c84c2a890762', nickname: 'Bot3-7784', seatIndex: 3, isAlive: true, isSheriff: false, …}
2
:
{userId: 'guest:87c25310-3401-48c7-a9ca-15191bc10e26', nickname: 'Bot4-7784', seatIndex: 4, isAlive: true, isSheriff: false, …}
3
:
{userId: 'guest:193499eb-7a87-49cd-b74f-7fdd0e111ea9', nickname: 'Bot5-7784', seatIndex: 5, isAlive: true, isSheriff: false, …}
4
:
{userId: 'guest:72284617-f8cf-458b-8a0f-77b576126e96', nickname: 'Bot6-7784', seatIndex: 6, isAlive: true, isSheriff: false, …}
5
:
{userId: 'guest:cf838a46-d510-4ffe-8a4f-47335c50c117', nickname: 'Bot7-7784', seatIndex: 7, isAlive: true, isSheriff: false, …}
6
:
{userId: 'guest:918993fb-5620-4f1b-8fea-eaaec3082cd6', nickname: 'Bot9-7784', seatIndex: 9, isAlive: true, isSheriff: false, …}
7
:
{userId: 'guest:df6d49ee-315a-4942-a92a-e860e54d54ad', nickname: 'Bot11-7784', seatIndex: 11, isAlive: true, isSheriff: false, …}
8
:
{userId: 'guest:2e65536b-3ed5-4ca4-8ab4-f3f9aa76236c', nickname: 'Peter2L', seatIndex: 12, isAlive: true, isSheriff: false, …}
9
:
{userId: 'guest:3a9e2f2f-1e89-43e7-a0bc-b0768684e243', nickname: 'Bot1-7784', seatIndex: 1, isAlive: false, isSheriff: false, …}
10
:
{userId: 'guest:37856c9a-7151-445f-a06f-dea36ab6ce87', nickname: 'Bot8-7784', seatIndex: 8, isAlive: false, isSheriff: false, …}
11
:
{userId: 'guest:1beb23cc-e544-4118-a763-80bb88715fb3', nickname: 'Bot10-7784', seatIndex: 10, isAlive: false, isSheriff: false, …}
length
:
12
[[Prototype]]
:
Array(0)
... >
at <GameView onVnodeUnmounted=fn<onVnodeUnmounted> ref=Ref<
Proxy(Object) {__v_skip: true}
[[Handler]]
:
Object
[[Target]]
:
Proxy(Object)
[[IsRevoked]]
:
false
> >
at <RouterView>
at <App>
axios.js?v=4f728c48:1319 Uncaught (in promise) AxiosError: Request failed with status code 400
at async Object.submitAction (gameService.ts:11:22)
at async action (GameView.vue:484:17)
at async handleVotingContinue (GameView.vue:754:3)
Promise.then		
submitAction	@	gameService.ts:11
action	@	GameView.vue:484
handleVotingContinue	@	GameView.vue:754
(anonymous)	@	VotingPhase.vue:686
setTimeout		
watch.immediate	@	VotingPhase.vue:685
setup	@	VotingPhase.vue:680
Promise.then		
setState	@	gameStore.ts:10
(anonymous)	@	GameView.vue:932
