import {defineStore} from 'pinia'
import {ref, toRaw} from 'vue'
import type {Room, RoomPlayer} from '@/types'

export const useRoomStore = defineStore('room', () => {
    const room = ref<Room | null>(null)

    function setRoom(r: Room) {
        room.value = r
    }

    function updatePlayers(players: RoomPlayer[]) {
        if (room.value) {
            room.value.players = players
        }
    }

    function updateMyStatus(userId: string, status: RoomPlayer['status']) {
        if (!room.value) return
        // Use toRaw to avoid reactive proxy spread issues across environments
        room.value.players = toRaw(room.value.players).map(p => {
            const raw = toRaw(p)
            return raw.userId === userId ? {...raw, status} : {...raw}
        })
    }

    function clearRoom() {
        room.value = null
    }

    return {room, setRoom, updatePlayers, updateMyStatus, clearRoom}
})
