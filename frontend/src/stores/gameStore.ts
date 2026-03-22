import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { GameEvent, GameState } from '@/types'

export const useGameStore = defineStore('game', () => {
  const state = ref<GameState | null>(null)

  function setState(s: GameState) {
    state.value = { ...s, events: state.value?.events ?? s.events ?? [] }
  }

  function addEvent(event: GameEvent) {
    state.value?.events.push(event)
  }

  function incrementConfirmedCount() {
    if (!state.value?.roleReveal) return
    state.value.roleReveal = {
      ...state.value.roleReveal,
      confirmedCount: (state.value.roleReveal.confirmedCount ?? 0) + 1,
    }
  }

  function clearGame() {
    state.value = null
  }

  return { state, setState, addEvent, incrementConfirmedCount, clearGame }
})
