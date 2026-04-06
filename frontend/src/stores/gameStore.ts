import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { GameEvent, GameState } from '@/types'

export const useGameStore = defineStore('game', () => {
  const state = ref<GameState | null>(null)

  function setState(s: GameState) {
    console.log('[gameStore] setState被调用')
    console.log('[gameStore] 新状态 - phase:', s.phase, 'nightPhase.subPhase:', s.nightPhase?.subPhase)
    console.log('[gameStore] 旧状态 - phase:', state.value?.phase, 'nightPhase.subPhase:', state.value?.nightPhase?.subPhase)
    const newState = JSON.parse(JSON.stringify(s))
    state.value = { ...newState, events: s.events ?? [] }
    console.log('[gameStore] state.value已更新')
    console.log('[gameStore] 更新后 - phase:', state.value?.phase, 'nightPhase.subPhase:', state.value?.nightPhase?.subPhase)
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

  function updateNightPhaseSelection(selectedTargetId: string | undefined) {
    if (!state.value?.nightPhase) return
    state.value.nightPhase = { ...state.value.nightPhase, selectedTargetId }
  }

  return {
    state,
    setState,
    addEvent,
    incrementConfirmedCount,
    clearGame,
    updateNightPhaseSelection,
  }
})
