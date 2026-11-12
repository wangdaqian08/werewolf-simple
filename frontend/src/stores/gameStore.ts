import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { GameEvent, GameState, PlayerRole, TimerState } from '@/types'

export const useGameStore = defineStore('game', () => {
  const state = ref<GameState | null>(null)
  // Bumped by every STOMP TimerUpdated. A caller that issues GET /state reads it
  // first and passes it to setState, so a fetch whose snapshot predates a timer
  // start/stop that landed while it was in flight cannot overwrite the newer timer.
  const timerSeq = ref(0)

  function setState(s: GameState, opts?: { timerSeqAtFetch?: number }) {
    const newState = JSON.parse(JSON.stringify(s))
    const timerRaced =
      opts?.timerSeqAtFetch !== undefined && opts.timerSeqAtFetch !== timerSeq.value
    const timer = timerRaced ? state.value?.timer : newState.timer
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
    // Role confirmations only grow during ROLE_REVEAL. A fetch whose snapshot predates
    // a RoleConfirmed event that landed while it was in flight must not drop it — that
    // left the host one short, without the Start Night button.
    const prevIds =
      state.value?.gameId === newState.gameId
        ? state.value?.roleReveal?.confirmedUserIds
        : undefined
    if (newState.roleReveal && prevIds?.length) {
      const ids = [...new Set([...(newState.roleReveal.confirmedUserIds ?? []), ...prevIds])]
      newState.roleReveal = {
        ...newState.roleReveal,
        confirmedUserIds: ids,
        confirmedCount: Math.max(newState.roleReveal.confirmedCount, ids.length),
      }
    }
    state.value = {
      ...newState,
      events: s.events ?? [],
      audioSequence,
      audioReplayBuffer,
      timer,
    }
  }

  function addEvent(event: GameEvent) {
    state.value?.events.push(event)
  }

  function markRoleConfirmed(userId: string) {
    const s = state.value
    const reveal = s?.roleReveal
    if (!s || !reveal) return
    const ids = reveal.confirmedUserIds ?? []
    if (ids.includes(userId)) return // a repeat RoleConfirmed must not count twice
    s.roleReveal = {
      ...reveal,
      confirmedCount: (reveal.confirmedCount ?? 0) + 1,
      confirmedUserIds: [...ids, userId],
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

  /**
   * Update daySkipVoting via a store action to guarantee Vue reactivity.
   * Per pinia-nested-mutation memory: NEVER mutate this directly from STOMP
   * callbacks — always go through this action with object spread.
   */
  function setDaySkipVoting(value: boolean) {
    if (!state.value) return
    state.value = { ...state.value, daySkipVoting: value }
  }

  function setTimer(payload: TimerState) {
    timerSeq.value++
    if (!state.value) return
    state.value = { ...state.value, timer: { ...payload } }
  }

  return {
    state,
    timerSeq,
    setState,
    addEvent,
    markRoleConfirmed,
    clearGame,
    updateNightPhaseSelection,
    setMyRole,
    setDaySkipVoting,
    setTimer,
  }
})
