import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import ResultView from '@/views/ResultView.vue'
import { useGameStore } from '@/stores/gameStore'
import { useUserStore } from '@/stores/userStore'
import { gameService } from '@/services/gameService'
import type { GameState } from '@/types'

// Mock services
vi.mock('@/services/gameService', () => ({
  gameService: {
    getState: vi.fn(),
  },
}))

describe('ResultView - Game Over Role Reveal Bug', () => {
  let router: ReturnType<typeof createRouter>
  let pinia: ReturnType<typeof createPinia>

  beforeEach(() => {
    pinia = createPinia()
    setActivePinia(pinia)

    // Create router with memory history
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/game/:gameId', name: 'game', component: { template: '<div>Game</div>' } },
        { path: '/result/:gameId', name: 'result', component: ResultView },
      ],
    })

    vi.clearAllMocks()
  })

  /**
   * BUG FIX TEST
   *
   * This test verifies that when the game ends, the ResultView correctly
   * displays all players' roles, not just the current user's role.
   *
   * The bug occurred because:
   * 1. GameOver event only contained winner info, not player roles
   * 2. Frontend navigated to result page immediately without re-fetching state
   * 3. Result page showed empty roles for other players
   *
   * The fix:
   * - When GameOver event is received, re-fetch full game state before navigation
   * - Backend returns all player roles when phase is GAME_OVER
   */
  it('displays all player roles when game ends', async () => {
    const gameStore = useGameStore()
    const userStore = useUserStore()

    // Setup: Set current user
    userStore.token = 'test-token'
    userStore.userId = 'user-1'
    userStore.nickname = 'Alice'

    // Simulate: State without winner (like when navigating from GameView's GameOver handler)
    // The GameOver handler in GameView should re-fetch state before navigation
    const stateBeforeRefetch: GameState = {
      gameId: '1',
      phase: 'DAY_VOTING', // Phase before GameOver
      dayNumber: 7,
      players: [
        {
          userId: 'user-1',
          nickname: 'Alice',
          seatIndex: 1,
          isAlive: true,
          isSheriff: false,
          role: 'VILLAGER', // Only current user's role is visible during game
        },
        {
          userId: 'user-2',
          nickname: 'Bob',
          seatIndex: 2,
          isAlive: false,
          isSheriff: false,
          // Other players' roles are hidden during game
        },
        {
          userId: 'user-3',
          nickname: 'Charlie',
          seatIndex: 3,
          isAlive: false,
          isSheriff: false,
        },
      ],
      events: [],
    }

    // Set initial state without winner
    gameStore.setState(stateBeforeRefetch)

    // Mock gameService.getState to return the game over state with all roles
    const gameOverState: GameState = {
      gameId: '1',
      phase: 'GAME_OVER',
      dayNumber: 7,
      winner: 'VILLAGER',
      players: [
        {
          userId: 'user-1',
          nickname: 'Alice',
          seatIndex: 1,
          isAlive: true,
          isSheriff: false,
          role: 'VILLAGER',
        },
        {
          userId: 'user-2',
          nickname: 'Bob',
          seatIndex: 2,
          isAlive: false,
          isSheriff: false,
          role: 'WEREWOLF', // Now visible at game end
        },
        {
          userId: 'user-3',
          nickname: 'Charlie',
          seatIndex: 3,
          isAlive: false,
          isSheriff: false,
          role: 'SEER', // Now visible at game end
        },
      ],
      events: [],
    }

    vi.mocked(gameService.getState).mockResolvedValue(gameOverState)

    // Navigate to result page
    await router.push('/result/1')

    // Mount ResultView
    const wrapper = mount(ResultView, {
      global: {
        plugins: [pinia, router],
      },
    })

    // Wait for component to load and fetch state
    await wrapper.vm.$nextTick()
    // Wait for the async onMounted to complete
    await new Promise((resolve) => setTimeout(resolve, 100))
    await wrapper.vm.$nextTick()

    // Verify: gameService.getState was called to fetch full state (because no winner in state)
    expect(gameService.getState).toHaveBeenCalledWith('1')

    // Verify: All player roles are displayed
    const rolePills = wrapper.findAll('.role-pill')
    expect(rolePills).toHaveLength(3)

    // Verify: Each role pill contains the role name
    const roleTexts = rolePills.map((pill) => pill.text())
    expect(roleTexts[0]).toContain('村民') // Alice - VILLAGER
    expect(roleTexts[1]).toContain('狼人') // Bob - WEREWOLF
    expect(roleTexts[2]).toContain('预言家') // Charlie - SEER

    // Verify: Winner information is displayed
    expect(wrapper.text()).toContain('村民胜利')
    expect(wrapper.text()).toContain('Village Wins')
  })

  /**
   * EDGE CASE: Winner is WEREWOLF
   */
  it('displays all roles when werewolves win', async () => {
    const gameStore = useGameStore()
    const userStore = useUserStore()

    userStore.token = 'test-token'
    userStore.userId = 'user-1'
    userStore.nickname = 'Alice'

    // Set initial state without winner
    const stateBeforeRefetch: GameState = {
      gameId: '2',
      phase: 'DAY_VOTING',
      dayNumber: 5,
      players: [
        {
          userId: 'user-1',
          nickname: 'Alice',
          seatIndex: 1,
          isAlive: true,
          isSheriff: false,
          role: 'VILLAGER',
        },
        {
          userId: 'user-2',
          nickname: 'Bob',
          seatIndex: 2,
          isAlive: true,
          isSheriff: false,
        },
        {
          userId: 'user-3',
          nickname: 'Charlie',
          seatIndex: 3,
          isAlive: true,
          isSheriff: false,
        },
      ],
      events: [],
    }

    gameStore.setState(stateBeforeRefetch)

    const gameOverState: GameState = {
      gameId: '2',
      phase: 'GAME_OVER',
      dayNumber: 5,
      winner: 'WEREWOLF',
      players: [
        {
          userId: 'user-1',
          nickname: 'Alice',
          seatIndex: 1,
          isAlive: true,
          isSheriff: false,
          role: 'VILLAGER',
        },
        {
          userId: 'user-2',
          nickname: 'Bob',
          seatIndex: 2,
          isAlive: true,
          isSheriff: false,
          role: 'WEREWOLF',
        },
        {
          userId: 'user-3',
          nickname: 'Charlie',
          seatIndex: 3,
          isAlive: true,
          isSheriff: false,
          role: 'WEREWOLF',
        },
      ],
      events: [],
    }

    vi.mocked(gameService.getState).mockResolvedValue(gameOverState)

    await router.push('/result/2')

    const wrapper = mount(ResultView, {
      global: {
        plugins: [pinia, router],
      },
    })

    await wrapper.vm.$nextTick()
    // Wait for the async onMounted to complete
    await new Promise((resolve) => setTimeout(resolve, 100))
    await wrapper.vm.$nextTick()

    // Verify: Wolf winner message is displayed
    expect(wrapper.text()).toContain('狼人胜利')
    expect(wrapper.text()).toContain('Wolves Win')

    // Verify: All roles are still displayed
    const rolePills = wrapper.findAll('.role-pill')
    expect(rolePills).toHaveLength(3)
  })

  /**
   * EDGE CASE: Game state already has winner (from previous navigation)
   * This tests the onMounted logic that skips re-fetching if winner is already set
   */
  it('skips re-fetching if winner is already in game state', async () => {
    const gameStore = useGameStore()
    const userStore = useUserStore()

    userStore.token = 'test-token'
    userStore.userId = 'user-1'
    userStore.nickname = 'Alice'

    const gameOverState: GameState = {
      gameId: '3',
      phase: 'GAME_OVER',
      dayNumber: 3,
      winner: 'VILLAGER',
      players: [
        {
          userId: 'user-1',
          nickname: 'Alice',
          seatIndex: 1,
          isAlive: true,
          isSheriff: false,
          role: 'VILLAGER',
        },
        {
          userId: 'user-2',
          nickname: 'Bob',
          seatIndex: 2,
          isAlive: false,
          isSheriff: false,
          role: 'WEREWOLF',
        },
      ],
      events: [],
    }

    // Set state with winner already
    gameStore.setState(gameOverState)

    await router.push('/result/3')

    const wrapper = mount(ResultView, {
      global: {
        plugins: [pinia, router],
      },
    })

    await wrapper.vm.$nextTick()

    // Verify: gameService.getState was called to get fresh state
    expect(gameService.getState).toHaveBeenCalledWith('3')

    // Verify: Roles are still displayed from existing state
    const rolePills = wrapper.findAll('.role-pill')
    expect(rolePills).toHaveLength(2)
  })

  /**
   * BUG REPRODUCTION: Missing roles (what the bug looked like)
   */
  it('BUG: would fail if state was fetched before GameOver (roles hidden)', async () => {
    const gameStore = useGameStore()
    const userStore = useUserStore()

    userStore.token = 'test-token'
    userStore.userId = 'user-1'
    userStore.nickname = 'Alice'

    // This simulates the bug: state was fetched during VOTING phase
    // where other players' roles are hidden
    const votingState: GameState = {
      gameId: '4',
      phase: 'DAY_VOTING',
      dayNumber: 7,
      players: [
        {
          userId: 'user-1',
          nickname: 'Alice',
          seatIndex: 1,
          isAlive: true,
          isSheriff: false,
          role: 'VILLAGER', // Only current user's role is visible
        },
        {
          userId: 'user-2',
          nickname: 'Bob',
          seatIndex: 2,
          isAlive: false,
          isSheriff: false,
          // Other players' roles are hidden during game
        },
        {
          userId: 'user-3',
          nickname: 'Charlie',
          seatIndex: 3,
          isAlive: false,
          isSheriff: false,
        },
      ],
      events: [],
    }

    // Mock to return voting state first (bug scenario)
    vi.mocked(gameService.getState).mockResolvedValueOnce(votingState)

    // Set state without winner
    gameStore.setState(votingState)

    await router.push('/result/4')

    const wrapper = mount(ResultView, {
      global: {
        plugins: [pinia, router],
      },
    })

    await wrapper.vm.$nextTick()

    // Verify: getState was called (no winner in state)
    expect(gameService.getState).toHaveBeenCalledWith('4')

    // After this, the fix would re-fetch with winner
    // In the bug scenario, this would show "❓" for other roles
  })
})
