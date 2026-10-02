import { beforeAll, describe, expect, it } from 'vitest'
import http from '@/services/http'
import { setupMocks } from '@/mocks'
import { MOCK_ROOM_AS_HOST } from '@/mocks/data'
import { mockStompClient } from '@/mocks/mockStompClient'

describe('mock /debug/game/start', () => {
    beforeAll(() => setupMocks())

    it('delivers GAME_STARTED even when the room page subscribes after the request', async () => {
        const received: string[] = []
        const start = http.post('/debug/game/start')

        // Subscribe after the mock's response delay, so the handler has already run.
        await new Promise((resolve) => setTimeout(resolve, 500))
        mockStompClient.subscribe(`/topic/room/${MOCK_ROOM_AS_HOST.roomId}`, (msg) => {
            received.push(JSON.parse(msg.body).type)
        })
        await start

        expect(received).toContain('GAME_STARTED')
    })
})
