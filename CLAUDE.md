# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a **Werewolf (狼人杀) mini-game** for WeChat, built with **Cocos Creator 3.8.8**. The game is developed in TypeScript and uses a 2D scene approach with a design resolution of 417x614 (portrait mode for mobile).

### Architecture Philosophy

This project follows a **client-server architecture**:
- **Cocos Creator (Frontend)**: Handles UI rendering, animations, user interactions, and presentation logic
- **Java Backend**: Manages user authentication, payment processing, room management, game rules, and data persistence
- **Communication**: Frontend calls backend APIs via HTTP/WebSocket for all business logic

**Critical**: The frontend should NEVER implement game logic. All game state, rule validation, and business decisions must be handled by the backend API.

## Cocos MCP Server Integration

This project includes the **Cocos Creator MCP Server** extension (`extensions/cocos-mcp-server/`), which enables AI-assisted development through the Model Context Protocol. This extension provides 50+ tools for scene manipulation, node management, component operations, prefab handling, asset management, and debugging.

### Using the MCP Server Extension

The MCP server must be running in Cocos Creator to interact with the editor:

1. **Start the server**: Open Cocos Creator, go to `Extension > Cocos MCP Server`, and start the server (default port: 3000)
2. **MCP endpoint**: `http://localhost:3000/mcp`
3. **Available tools**: Use MCP tools prefixed with `mcp__cocos-creator__` to interact with:
   - Scene management (open/save/create scenes)
   - Node operations (create/delete/transform nodes)
   - Component management (add/remove components, set properties)
   - Prefab operations (create/instantiate/update prefabs)
   - Asset management (import/query/analyze assets)
   - Debug tools (console logs, scene validation)

When making changes to scenes, nodes, or components, **always use the MCP tools** rather than directly editing .scene files, as Cocos Creator scenes are binary-like JSON formats that require proper UUID and reference management.

## Project Structure

### Scenes
- **Load.scene**: Initial loading screen with progress bar, preloads resources and the Game scene
- **Game.scene**: Main game scene where Werewolf gameplay occurs
- **Join.scene**: Room joining interface with seat selection

Scene flow: `Load → Game` or `Load → Join → Game`

### Current Scripts Organization (In Progress)

**Current structure** (basic framework exists):
```
assets/Scripts/
├── Load.ts                 # Loading screen controller
├── CreateRoom.ts          # Room creation logic (needs API integration)
├── JoinRoom/
│   ├── JoinRoom.ts       # Room joining controller (needs API integration)
│   ├── NumberInput.ts    # Room number input handler
│   └── SeatPanel.ts      # Seat selection UI
└── manager/
    ├── ResLoader.ts      # Resource loading coordinator
    └── PrefabManager.ts  # Centralized prefab loading and access
```

### Recommended Complete Architecture

**Target structure** (to be implemented):
```
assets/Scripts/
├── scenes/                 # Scene controllers (move current scene scripts here)
│   ├── LoadScene.ts       # Loading screen controller
│   ├── GameScene.ts       # Main game scene controller
│   └── JoinScene.ts       # Join room scene controller
├── ui/                     # UI components (pure presentation)
│   ├── JoinRoom/          # Room joining UI components
│   └── components/        # Reusable UI components
├── manager/                # ✅ EXISTS - Resource and system managers
│   ├── ResLoader.ts       # Resource loading coordinator
│   ├── PrefabManager.ts   # Centralized prefab loading
│   └── SceneManager.ts    # Scene transition management (to add)
├── network/                # ❌ MISSING - Network communication layer
│   ├── HttpClient.ts      # HTTP request wrapper
│   ├── WebSocketClient.ts # WebSocket for real-time game updates
│   ├── ApiConfig.ts       # API endpoint configuration
│   └── RequestQueue.ts    # Request queue and retry management
├── services/               # ❌ MISSING - Business logic API calls
│   ├── UserService.ts     # User API (login, profile, auth)
│   ├── RoomService.ts     # Room API (create, join, leave)
│   ├── GameService.ts     # Game API (actions, state sync)
│   └── PaymentService.ts  # Payment API (purchase, transactions)
├── data/                   # ❌ MISSING - Data models and state
│   ├── UserData.ts        # User information from backend
│   ├── RoomData.ts        # Room state and player data
│   ├── GameData.ts        # Game state and history
│   └── ConfigData.ts      # Game configuration from backend
├── platform/               # ❌ MISSING - WeChat SDK integration
│   ├── WeChatSDK.ts       # WeChat API wrapper
│   ├── WeChatAuth.ts      # WeChat login and authorization
│   └── WeChatPay.ts       # WeChat payment integration
├── utils/                  # Utility classes
│   ├── EventManager.ts    # Global event system
│   ├── Logger.ts          # Logging utility
│   └── Constants.ts       # Game constants
└── config/                 # Configuration files
    └── GameConfig.ts      # Static game configuration
```

### Layer Responsibilities

#### 1. **Network Layer** (`network/`)
Handles all communication with the Java backend:
- HTTP requests for stateless operations (login, create room, etc.)
- WebSocket connections for real-time game state synchronization
- Request/response error handling and retry logic
- Token management and authentication headers

#### 2. **Service Layer** (`services/`)
Provides high-level API abstractions:
- **UserService**: Login, logout, get user info, update profile
- **RoomService**: Create room, join room, leave room, get room list
- **GameService**: Send game actions, receive state updates, player operations
- **PaymentService**: Initiate purchase, verify payment status

**Important**: Services call the backend API and return typed data models. No business logic should be implemented here.

#### 3. **Data Layer** (`data/`)
Manages application state from backend:
- Stores user session and profile data
- Caches room information and player states
- Maintains game state received from backend
- Provides reactive data updates to UI components

#### 4. **Platform Layer** (`platform/`)
WeChat-specific integrations:
- WeChat user authentication and authorization
- WeChat payment SDK integration
- WeChat social features (share, invite)
- Platform-specific APIs and capabilities

#### 5. **UI Layer** (`ui/`, `scenes/`)
Pure presentation logic:
- Renders data from Data layer
- Handles user input and forwards to Services
- Animations and visual effects
- NO business logic or game rules

## Key Architecture Patterns

### Resource Loading Flow
1. `Load.ts` → triggers `ResLoader.init()`
2. `ResLoader` → loads all prefab resources via `PrefabManager`
3. Once loaded → preloads Game scene
4. Progress bar completes → transitions to Game scene

### Scene Transitions
- Use `director.loadScene(sceneName, callback)` for scene transitions
- Always check for errors in the callback
- Preload heavy scenes during loading screen with `director.preloadScene()`

### Component Communication
- Use static instances (e.g., `Load.instance`) for cross-scene communication
- Manager classes use static methods for global access

## Development Guidelines

### Working with Cocos Creator

**Opening the project**: Open Cocos Creator 3.8.8+ and select this project directory.

**Scene editing**:
- Use Cocos Creator editor for visual scene editing
- Use MCP server tools for programmatic scene manipulation
- Never manually edit .scene files - they contain complex UUID references

**Testing**:
- Click "Play" button in Cocos Creator editor
- For WeChat mini-game: Use Cocos Creator's build system targeting "Wechat Mini Game" platform

**Building for WeChat**:
- Go to `Project → Build` in Cocos Creator
- Select platform: "Wechat Mini Game"
- Configure WeChat appid if deploying

### TypeScript Configuration

The project uses TypeScript with strict mode disabled (`"strict": false` in tsconfig.json). The base configuration extends `./temp/tsconfig.cocos.json` which is auto-generated by Cocos Creator.

### Adding New Features

When adding game logic, follow the client-server architecture:

1. **Identify the feature type**:
   - UI-only? → Create in `ui/` or `scenes/`
   - Network call? → Add to appropriate `services/` file
   - Data storage? → Add to `data/` models

2. **Follow the layer pattern**:
   ```
   UI Component → Service → Network → Backend API
         ↓
      Data Model (updates from API response)
         ↓
   UI Component (re-renders with new data)
   ```

3. **Use proper directories**:
   - Scene controllers: `scenes/`
   - Reusable UI: `ui/components/`
   - API calls: `services/`
   - Data models: `data/`
   - Utilities: `utils/`

4. **Always use decorators**:
   - Use `@ccclass` and `@property` for all Cocos components
   - Attach scripts via Cocos Creator editor, not programmatically

5. **Never implement business logic in frontend**:
   - Game rules → Backend
   - Validation → Backend
   - State management → Backend (frontend caches only)

### Code Patterns and Examples

#### Pattern 1: Service Call with Error Handling

```typescript
// Good example - Async service call with proper error handling
async onButtonClick() {
    this.showLoading(true);

    try {
        const result = await UserService.login(code);

        if (result.success) {
            UserData.setUser(result.data);
            this.navigateToLobby();
        } else {
            this.showError(result.message);
        }
    } catch (error) {
        console.error('Login failed:', error);
        this.showError('网络错误，请重试');
    } finally {
        this.showLoading(false);
    }
}
```

#### Pattern 2: WebSocket Event Handling

```typescript
// Good example - Subscribe to game events
onLoad() {
    // Subscribe to WebSocket events
    GameService.on('playerJoined', this.onPlayerJoined.bind(this));
    GameService.on('gameStateChanged', this.onGameStateChanged.bind(this));
}

onDestroy() {
    // Always unsubscribe to prevent memory leaks
    GameService.off('playerJoined', this.onPlayerJoined);
    GameService.off('gameStateChanged', this.onGameStateChanged);
}

onPlayerJoined(player: PlayerInfo) {
    // Update UI with new player data
    this.addPlayerToSeat(player);
}
```

#### Pattern 3: Data-Driven UI

```typescript
// Good example - UI updates based on data model
updateUI() {
    const roomData = RoomData.getCurrentRoom();

    if (!roomData) {
        this.showError('房间数据不存在');
        return;
    }

    // Update UI elements from data
    this.roomCodeLabel.string = roomData.roomCode;
    this.playerCountLabel.string = `${roomData.players.length}/${roomData.maxPlayers}`;

    // Render player list
    roomData.players.forEach((player, index) => {
        this.updatePlayerSeat(index, player);
    });
}
```

#### Anti-Pattern: Client-Side Validation

```typescript
// ❌ BAD - Do not implement game rules in frontend
isValidMove(action: string): boolean {
    if (this.currentPhase !== 'day') return false; // NO!
    if (this.hasActed) return false; // NO!
    return true; // NO!
}

// ✅ GOOD - Let backend validate
async performAction(action: string) {
    const result = await GameService.submitAction(action);
    // Backend determines if action is valid
    return result.success;
}
```

### MCP Extension Development

If modifying the MCP server extension:
```bash
cd extensions/cocos-mcp-server
npm install
npm run build  # or npm run watch for development
```

After building, restart or refresh extensions in Cocos Creator.

## WeChat Mini-Game Specific Considerations

### WeChat Login Flow

```typescript
// Typical WeChat login flow
async performWeChatLogin() {
    try {
        // 1. Get WeChat authorization code
        const wxCode = await WeChatAuth.login();

        // 2. Send code to backend for session
        const result = await UserService.loginWithWeChat(wxCode);

        // 3. Store session token
        UserData.setToken(result.token);
        UserData.setUser(result.userInfo);

        // 4. Navigate to main scene
        director.loadScene('Game');
    } catch (error) {
        console.error('WeChat login failed:', error);
    }
}
```

### WeChat Payment Integration

```typescript
// WeChat payment flow
async purchaseItem(itemId: string) {
    try {
        // 1. Create order on backend
        const order = await PaymentService.createOrder(itemId);

        // 2. Invoke WeChat payment
        const paymentResult = await WeChatPay.requestPayment({
            timeStamp: order.timeStamp,
            nonceStr: order.nonceStr,
            package: order.package,
            signType: order.signType,
            paySign: order.paySign
        });

        // 3. Verify payment with backend
        const verified = await PaymentService.verifyPayment(order.orderId);

        if (verified.success) {
            this.showSuccess('购买成功');
            this.refreshUserItems();
        }
    } catch (error) {
        console.error('Payment failed:', error);
        this.showError('支付失败');
    }
}
```

### WeChat API Limitations

- **Resource size**: Total package size should be < 4MB for main package, < 20MB for subpackages
- **Network requests**: Use `wx.request()` for HTTP, `wx.connectSocket()` for WebSocket
- **Storage**: Use `wx.setStorage()` for local data persistence
- **No DOM access**: Cannot use browser APIs
- **Authorization**: Requires user consent for accessing profile, payment, etc.

### Building for WeChat

1. **Configure project**:
   - Set appid in Cocos Creator build settings
   - Enable necessary WeChat API permissions

2. **Build process**:
   - `Project → Build` in Cocos Creator
   - Platform: "Wechat Mini Game"
   - Output to `build/wechatgame/`

3. **Test with WeChat DevTools**:
   - Open WeChat Developer Tools
   - Import the `build/wechatgame/` directory
   - Test on simulator or real device

4. **Deploy**:
   - Upload via WeChat Developer Tools
   - Submit for review on WeChat Open Platform

## Design Resolution

The game uses a fixed design resolution of 417x614 (portrait orientation). Ensure all UI elements are designed for this aspect ratio when creating new scenes or prefabs.

### UI Design Guidelines

- **Safe area**: Account for device notches and navigation bars
- **Touch targets**: Minimum 88x88 pixels for buttons
- **Font sizes**: Minimum 24px for readability
- **Adaptation**: Use Cocos Creator's Widget component for responsive layouts

## Room System Architecture

### Current Implementation Issues

**❌ Current problem**: `JoinRoom.ts` uses hardcoded room validation:
```typescript
// JoinRoom.ts:19 - INCORRECT APPROACH
private readonly CORRECT_ROOM_NUMBER: string = "1234"; // Hardcoded!
```

This violates the client-server architecture principle. Room validation and management must be handled by the backend.

### Correct API-Based Implementation

**✅ Proper approach** - Room operations via backend API:

```typescript
// Example: JoinRoom.ts (corrected)
async onJoinButtonClicked() {
    const roomNumber = this.roomNumberInput.string;

    try {
        // Call backend API to validate and join room
        const result = await RoomService.joinRoom(roomNumber);

        if (result.success) {
            // Store room data received from backend
            RoomData.setCurrentRoom(result.data);
            this.showSeatPanel();
        } else {
            this.showError(result.message);
        }
    } catch (error) {
        console.error('Join room failed:', error);
        this.showError('网络错误，请重试');
    }
}
```

### Room Management Flow

1. **Create Room**:
   - User clicks "Create Room" → `RoomService.createRoom()`
   - Backend generates room code, initializes room state
   - Frontend receives room code and navigates to room scene

2. **Join Room**:
   - User enters room code → `RoomService.joinRoom(roomCode)`
   - Backend validates code, checks room capacity
   - If valid, backend returns room info and player seat assignment
   - Frontend updates UI with room data

3. **Room State Sync**:
   - WebSocket connection established after joining
   - Backend pushes real-time updates (players join/leave, game events)
   - Frontend listens and updates UI accordingly

4. **Leave Room**:
   - User leaves → `RoomService.leaveRoom()`
   - Backend updates room state, notifies other players
   - Frontend disconnects WebSocket, returns to lobby

### Backend API Contract (Expected)

The frontend expects these backend APIs:

```typescript
// Room APIs
POST   /api/room/create          // Create new room
POST   /api/room/join            // Join existing room
POST   /api/room/leave           // Leave current room
GET    /api/room/{roomId}        // Get room details
GET    /api/room/list            // List available rooms

// Game APIs
POST   /api/game/action          // Perform game action
GET    /api/game/state           // Get current game state
WS     /ws/game                  // WebSocket for real-time updates

// User APIs
POST   /api/user/login           // WeChat login
GET    /api/user/profile         // Get user profile
POST   /api/user/logout          // Logout

// Payment APIs
POST   /api/payment/create       // Create payment order
GET    /api/payment/status       // Check payment status
```

## Frontend-Backend Separation Principles

### Frontend (Cocos Creator) Responsibilities

**✅ Should do**:
- Render UI and animations
- Handle user input (clicks, touches)
- Display data received from backend
- Play sound effects and visual effects
- Manage resource loading and caching
- Scene transitions and navigation

**❌ Should NOT do**:
- Validate game rules (e.g., "can this player vote?")
- Store sensitive data (e.g., other players' hidden roles)
- Calculate game outcomes (e.g., "who wins?")
- Manage room state independently
- Implement payment logic
- Perform user authentication

### Backend (Java) Responsibilities

**✅ Should do**:
- Validate all game actions
- Enforce game rules
- Manage room state and player sessions
- Store and retrieve persistent data
- Handle user authentication and authorization
- Process payments
- Detect cheating and enforce fair play
- Generate random events (role assignment, etc.)

### Data Flow Example: Player Voting

**❌ Wrong approach** (client-side logic):
```typescript
// Frontend - WRONG!
onVotePlayer(targetPlayer: string) {
    if (this.hasVoted) return; // Client validates
    if (!this.isVotingPhase) return; // Client validates
    this.vote = targetPlayer;
    this.hasVoted = true;
}
```

**✅ Correct approach** (server-authoritative):
```typescript
// Frontend - Correct!
async onVotePlayer(targetPlayer: string) {
    try {
        // Send vote to backend for validation
        const result = await GameService.submitVote(targetPlayer);

        if (result.success) {
            // Backend validates and confirms
            this.updateVoteUI(result.voteData);
        } else {
            // Backend rejected (invalid phase, already voted, etc.)
            this.showError(result.message);
        }
    } catch (error) {
        console.error('Vote failed:', error);
    }
}
```

The backend validates game phase, voting eligibility, target validity, and updates the game state. The frontend only sends the intention and displays the result.

## Development Roadmap

### Phase 1: Network Infrastructure (Priority: High)
**Status**: ❌ Not started

1. Create `network/HttpClient.ts`:
   - Axios or native fetch wrapper
   - Request/response interceptors
   - Error handling and retry logic
   - Authentication token management

2. Create `network/WebSocketClient.ts`:
   - WebSocket connection management
   - Reconnection logic
   - Message serialization/deserialization
   - Event-based message handling

3. Create `network/ApiConfig.ts`:
   - API base URL configuration
   - Endpoint definitions
   - Environment-specific settings

### Phase 2: Platform Integration (Priority: High)
**Status**: ❌ Not started

1. Create `platform/WeChatSDK.ts`:
   - Wrap WeChat API calls
   - Handle platform-specific behaviors

2. Create `platform/WeChatAuth.ts`:
   - Implement WeChat login flow
   - Get user openid and session
   - Token refresh logic

3. Create `platform/WeChatPay.ts`:
   - Integrate WeChat payment SDK
   - Handle payment callbacks

### Phase 3: Service Layer (Priority: High)
**Status**: ❌ Not started

1. Create `services/UserService.ts`:
   - `login()`, `logout()`, `getUserInfo()`

2. Create `services/RoomService.ts`:
   - `createRoom()`, `joinRoom()`, `leaveRoom()`, `getRoomList()`

3. Create `services/GameService.ts`:
   - `submitAction()`, `getGameState()`, `subscribeToUpdates()`

4. Create `services/PaymentService.ts`:
   - `createOrder()`, `checkPaymentStatus()`

### Phase 4: Data Models (Priority: Medium)
**Status**: ❌ Not started

1. Create data model classes with proper typing
2. Implement reactive state management
3. Add data validation and transformation

### Phase 5: Refactor Existing Code (Priority: High)
**Status**: ❌ Not started

1. Refactor `CreateRoom.ts`:
   - Remove stub implementation
   - Call `RoomService.createRoom()`

2. Refactor `JoinRoom.ts`:
   - Remove hardcoded room number validation
   - Call `RoomService.joinRoom()`
   - Handle backend responses properly

3. Refactor scene controllers:
   - Move to `scenes/` directory
   - Implement proper state management

### Phase 6: Game Logic Integration (Priority: Medium)
**Status**: ❌ Not started

1. Implement WebSocket-based real-time updates
2. Create game state synchronization
3. Implement all game actions (vote, skill use, etc.)
4. Add game UI components

### Phase 7: Testing & Optimization (Priority: Low)
**Status**: ❌ Not started

1. Add error handling and edge cases
2. Implement loading states and retries
3. Performance optimization
4. WeChat mini-game optimization

## Asset Organization

```
assets/
├── Scenes/              # Scene files (.scene)
├── Scripts/             # TypeScript source code
└── resources/
    ├── Image/          # Image assets (sprites, backgrounds)
    └── Prefab/         # Reusable prefab components
```

Resources in the `resources/` folder can be loaded dynamically at runtime using Cocos Creator's `resources.load()` API.

## Critical Architecture Principles (Summary)

### 1. Server-Authoritative Design
- **All game logic executes on the backend**
- Frontend is a "dumb client" that displays state and sends user actions
- Backend validates every action before changing state
- Frontend never trusts client-side data

### 2. Layer Separation
```
┌─────────────────────────────────────┐
│  UI Layer (scenes/, ui/)            │  → User interactions
├─────────────────────────────────────┤
│  Service Layer (services/)          │  → API abstractions
├─────────────────────────────────────┤
│  Network Layer (network/)           │  → HTTP/WebSocket
├─────────────────────────────────────┤
│  Data Layer (data/)                 │  → State management
└─────────────────────────────────────┘
         ↕
┌─────────────────────────────────────┐
│  Java Backend (Game Server)         │  → Business logic
└─────────────────────────────────────┘
```

### 3. Development Priorities

**Phase 1 - Infrastructure** (Must complete first):
1. Network communication (HTTP + WebSocket)
2. WeChat SDK integration
3. Service layer scaffolding

**Phase 2 - Refactoring** (Fix existing code):
1. Remove hardcoded logic from CreateRoom/JoinRoom
2. Implement proper API calls
3. Add error handling

**Phase 3 - Features** (Build on solid foundation):
1. Complete game logic via API
2. Real-time synchronization
3. Payment integration

### 4. Common Mistakes to Avoid

❌ **Don't**: Implement game rules in TypeScript
✅ **Do**: Call backend API to validate and execute

❌ **Don't**: Store sensitive data in frontend
✅ **Do**: Request data from backend when needed

❌ **Don't**: Validate user actions client-side
✅ **Do**: Send to backend for authoritative validation

❌ **Don't**: Calculate game outcomes in UI code
✅ **Do**: Receive computed results from backend

### 5. When Implementing New Features

Ask yourself:
1. **Is this just UI?** → Implement in frontend
2. **Does this involve game rules?** → Backend API required
3. **Does this change game state?** → Must go through backend
4. **Is this sensitive data?** → Never store in frontend

If in doubt, **default to backend implementation**.

---

**Remember**: This is a **WeChat mini-game** for **real multiplayer gameplay** with **payments**. A robust client-server architecture is not optional—it's essential for security, fairness, and scalability.
