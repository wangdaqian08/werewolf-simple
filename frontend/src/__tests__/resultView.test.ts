import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import ResultView from '@/views/ResultView.vue'
import { useGameStore } from '@/stores/gameStore'
import { useUserStore } from '@/stores/userStore'
import { gameService } from '@/services/gameService'
import type { GamePlayer, GameState, PlayerRole } from '@/types'

vi.mock('@/services/gameService', () => ({
  gameService: {
    getState: vi.fn(),
  },
}))

function makePlayer(
  seatIndex: number,
  userId: string,
  nickname: string,
  role: PlayerRole,
  isAlive = true,
): GamePlayer {
  return { userId, nickname, seatIndex, isAlive, isSheriff: false, role }
}

async function mountResultView(state: GameState, options: { authedUserId?: string } = {}) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'lobby', component: { template: '<div>Lobby</div>' } },
      { path: '/result/:gameId', name: 'result', component: ResultView },
    ],
  })

  const userStore = useUserStore()
  userStore.token = 'test-token'
  userStore.userId = options.authedUserId ?? 'user-1'
  userStore.nickname = 'Alice'

  const gameStore = useGameStore()
  gameStore.setState(state)
  vi.mocked(gameService.getState).mockResolvedValue(state)

  await router.push(`/result/${state.gameId}`)
  const wrapper = mount(ResultView, { global: { plugins: [pinia, router] } })
  await wrapper.vm.$nextTick()
  await new Promise((resolve) => setTimeout(resolve, 50))
  await wrapper.vm.$nextTick()
  return { wrapper, router, gameStore }
}

describe('ResultView - new dashboard-style gameover screen', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders title 好人胜 + WOLVES → 狼人胜 with sub WOLVES WIN', async () => {
    const wolvesWin: GameState = {
      gameId: 'g-wolves',
      phase: 'GAME_OVER',
      dayNumber: 5,
      winner: 'WEREWOLF',
      players: [makePlayer(1, 'user-1', 'Alice', 'VILLAGER')],
      events: [],
    }
    const { wrapper } = await mountResultView(wolvesWin)
    expect(wrapper.text()).toContain('狼人胜')
    expect(wrapper.text()).not.toContain('狼人胜利')
    expect(wrapper.text()).toContain('WOLVES WIN')
    expect(wrapper.find('.result-wolves').exists()).toBe(true)
  })

  it('renders title 好人胜 + sub VILLAGE WINS for villager win', async () => {
    const villageWin: GameState = {
      gameId: 'g-village',
      phase: 'GAME_OVER',
      dayNumber: 5,
      winner: 'VILLAGER',
      players: [makePlayer(1, 'user-1', 'Alice', 'VILLAGER')],
      events: [],
    }
    const { wrapper } = await mountResultView(villageWin)
    expect(wrapper.text()).toContain('好人胜')
    expect(wrapper.text()).not.toContain('村民胜利')
    expect(wrapper.text()).toContain('VILLAGE WINS')
    expect(wrapper.find('.result-wolves').exists()).toBe(false)
  })

  it('shows GAME OVER label and ROLES REVEALED section header', async () => {
    const state: GameState = {
      gameId: 'g-1',
      phase: 'GAME_OVER',
      dayNumber: 1,
      winner: 'VILLAGER',
      players: [makePlayer(1, 'user-1', 'Alice', 'VILLAGER')],
      events: [],
    }
    const { wrapper } = await mountResultView(state)
    expect(wrapper.text()).toContain('GAME OVER')
    expect(wrapper.text()).toContain('ROLES REVEALED')
  })

  it('renders one .reveal-card per player (no .role-pill any more)', async () => {
    const state: GameState = {
      gameId: 'g-2',
      phase: 'GAME_OVER',
      dayNumber: 1,
      winner: 'VILLAGER',
      players: [
        makePlayer(1, 'user-1', 'Alice', 'VILLAGER'),
        makePlayer(2, 'user-2', 'Bob', 'WEREWOLF'),
        makePlayer(3, 'user-3', 'Charlie', 'SEER'),
      ],
      events: [],
    }
    const { wrapper } = await mountResultView(state)
    expect(wrapper.findAll('.reveal-card')).toHaveLength(3)
    expect(wrapper.findAll('.role-pill')).toHaveLength(0)
  })

  it('sorts cards by seatIndex ascending regardless of input order', async () => {
    const state: GameState = {
      gameId: 'g-3',
      phase: 'GAME_OVER',
      dayNumber: 1,
      winner: 'VILLAGER',
      players: [
        // intentionally shuffled
        makePlayer(7, 'user-7', 'Gina', 'WITCH'),
        makePlayer(1, 'user-1', 'Alice', 'VILLAGER'),
        makePlayer(4, 'user-4', 'Dan', 'WEREWOLF'),
        makePlayer(2, 'user-2', 'Bob', 'WEREWOLF'),
        makePlayer(3, 'user-3', 'Charlie', 'SEER'),
      ],
      events: [],
    }
    const { wrapper } = await mountResultView(state)
    const cards = wrapper.findAll('.reveal-card')
    const seats = cards.map((c) => Number(c.attributes('data-testid')?.split('-').pop()))
    expect(seats).toEqual([1, 2, 3, 4, 7])
  })

  it('marks ONLY WEREWOLF cards with .reveal-wolf', async () => {
    const state: GameState = {
      gameId: 'g-4',
      phase: 'GAME_OVER',
      dayNumber: 1,
      winner: 'WEREWOLF',
      players: [
        makePlayer(1, 'user-1', 'Alice', 'VILLAGER'),
        makePlayer(2, 'user-2', 'Bob', 'WEREWOLF'),
        makePlayer(3, 'user-3', 'Charlie', 'SEER'),
        makePlayer(4, 'user-4', 'Dan', 'WEREWOLF'),
        makePlayer(5, 'user-5', 'Eve', 'GUARD'),
      ],
      events: [],
    }
    const { wrapper } = await mountResultView(state)
    const cards = wrapper.findAll('.reveal-card')
    const wolfStates = cards.map((c) => c.classes().includes('reveal-wolf'))
    expect(wolfStates).toEqual([false, true, false, true, false])
  })

  it('formats meta line as "{paddedSeat} · {nickname}"', async () => {
    const state: GameState = {
      gameId: 'g-5',
      phase: 'GAME_OVER',
      dayNumber: 1,
      winner: 'VILLAGER',
      players: [
        makePlayer(1, 'user-1', 'Alice', 'VILLAGER'),
        makePlayer(10, 'user-10', 'Jay', 'WEREWOLF'),
      ],
      events: [],
    }
    // authed as a non-player to avoid the "我" alias replacing the nickname
    const { wrapper } = await mountResultView(state, { authedUserId: 'spectator-x' })
    const texts = wrapper.findAll('.reveal-card').map((c) => c.text())
    expect(texts[0]).toContain('01 · Alice')
    expect(texts[1]).toContain('10 · Jay')
  })

  it('renders Chinese role name in each card', async () => {
    const state: GameState = {
      gameId: 'g-6',
      phase: 'GAME_OVER',
      dayNumber: 1,
      winner: 'VILLAGER',
      players: [
        makePlayer(1, 'user-1', 'Alice', 'VILLAGER'),
        makePlayer(2, 'user-2', 'Bob', 'WEREWOLF'),
        makePlayer(3, 'user-3', 'Charlie', 'SEER'),
        makePlayer(4, 'user-4', 'Dan', 'WITCH'),
        makePlayer(5, 'user-5', 'Eve', 'HUNTER'),
      ],
      events: [],
    }
    const { wrapper } = await mountResultView(state)
    const texts = wrapper.findAll('.reveal-card').map((c) => c.text())
    expect(texts[0]).toContain('村民')
    expect(texts[1]).toContain('狼人')
    expect(texts[2]).toContain('预言家')
    expect(texts[3]).toContain('女巫')
    expect(texts[4]).toContain('猎人')
  })

  it('greys out reveal-cards for players who died during the game', async () => {
    // 2026-05-11 behaviour change: the GAME_OVER reveal grid must indicate
    // which players were killed during the game. Dead players' cards get a
    // `reveal-dead` class so they render desaturated/grey.
    const state: GameState = {
      gameId: 'g-dead',
      phase: 'GAME_OVER',
      dayNumber: 3,
      winner: 'VILLAGER',
      players: [
        makePlayer(1, 'user-1', 'Alice', 'VILLAGER', true),
        makePlayer(2, 'user-2', 'Bob', 'WEREWOLF', false), // killed
        makePlayer(3, 'user-3', 'Charlie', 'SEER', false), // killed
        makePlayer(4, 'user-4', 'Dan', 'WEREWOLF', true),
      ],
      events: [],
    }
    const { wrapper } = await mountResultView(state)
    const cards = wrapper.findAll('.reveal-card')
    const deadStates = cards.map((c) => c.classes().includes('reveal-dead'))
    expect(deadStates).toEqual([false, true, true, false])
  })

  it('dead WEREWOLF keeps reveal-wolf class so wolf identity stays readable beneath the grey', async () => {
    // Death is rendered as a grey overlay/desaturation; the role-team colour
    // underneath is still meaningful (e.g. for "who was on which team").
    const state: GameState = {
      gameId: 'g-dead-wolf',
      phase: 'GAME_OVER',
      dayNumber: 3,
      winner: 'VILLAGER',
      players: [makePlayer(1, 'user-1', 'Wolfie', 'WEREWOLF', false)],
      events: [],
    }
    const { wrapper } = await mountResultView(state)
    const card = wrapper.find('.reveal-card')
    expect(card.classes()).toContain('reveal-wolf')
    expect(card.classes()).toContain('reveal-dead')
  })

  it('Play Again button triggers router push to lobby', async () => {
    const state: GameState = {
      gameId: 'g-7',
      phase: 'GAME_OVER',
      dayNumber: 1,
      winner: 'VILLAGER',
      players: [makePlayer(1, 'user-1', 'Alice', 'VILLAGER')],
      events: [],
    }
    const { wrapper, router } = await mountResultView(state)
    const pushSpy = vi.spyOn(router, 'push')
    await wrapper.find('[data-testid="play-again"]').trigger('click')
    expect(pushSpy).toHaveBeenCalledWith({ name: 'lobby' })
  })
})
