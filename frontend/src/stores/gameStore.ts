import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { GameEvent, GameState, PlayerRole } from '@/types'

export const useGameStore = defineStore('game', () => {
  const state = ref<GameState | null>(null)

  function setState(s: GameState) {
    const newState = JSON.parse(JSON.stringify(s))
    // Preserve audioSequence across polled-state writes. STOMP `AudioSequence`
    // events push the live audio onto the store; HTTP `getGameState` polls
    // (fired after PhaseChanged / NightSubPhaseChanged) compute the field via
    // `AudioService.calculateGameStateAudio`, which returns an empty list for
    // most steady-states (NIGHT in a sub-phase, DAY_DISCUSSION, etc.). Letting
    // an empty polled value overwrite the live one clobbers fast-arriving
    // open/close pairs — e.g. witch_open_eyes / witch_close_eyes broadcast
    // ~3s apart get coalesced to just close_eyes if the poll lands between
    // the open frame and useAudioService's watcher flush. Only adopt the
    // incoming audioSequence when it actually carries files.
    const incomingHasAudio = (newState.audioSequence?.audioFiles?.length ?? 0) > 0
    const audioSequence = incomingHasAudio ? newState.audioSequence : state.value?.audioSequence
    // audioReplayBuffer is a per-game ring buffer surfaced by the backend so
    // a STOMP-reconnect's refreshState() can replay every cue missed during
    // the disconnect window. Always adopt the incoming list (or undefined if
    // the backend dropped the field, e.g. mock mode) — useAudioService dedup
    // by sequence id ensures polls do not double-fire still-online audio.
    const audioReplayBuffer = newState.audioReplayBuffer
    state.value = {
      ...newState,
      events: s.events ?? [],
      audioSequence,
      audioReplayBuffer,
    }
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

  function setMyRole(role: PlayerRole) {
    if (!state.value) return
    state.value = { ...state.value, myRole: role }
  }

  return {
    state,
    setState,
    addEvent,
    incrementConfirmedCount,
    clearGame,
    updateNightPhaseSelection,
    setMyRole,
  }
})
