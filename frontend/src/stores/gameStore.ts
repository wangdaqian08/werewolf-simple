import {defineStore} from 'pinia'
import {ref} from 'vue'
import type {GameEvent, GameState} from '@/types'

export const useGameStore = defineStore('game', () => {
    const state = ref<GameState | null>(null)

    function setState(s: GameState) {
        state.value = s
    }

    function addEvent(event: GameEvent) {
        state.value?.events.push(event)
    }

    function clearGame() {
        state.value = null
    }

    return {state, setState, addEvent, clearGame}
})
