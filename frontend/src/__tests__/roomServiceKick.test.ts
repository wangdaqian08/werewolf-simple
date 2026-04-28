import { describe, expect, it, vi } from 'vitest'
import { roomService } from '@/services/roomService'

vi.mock('@/services/http', () => ({
  default: {
    post: vi.fn().mockResolvedValue({ data: { success: true } }),
  },
}))

import http from '@/services/http'

/**
 * Contract tests for the kick endpoint shape. The frontend's kickPlayer
 * coerces the roomId to Number (the backend DTO is Int) and passes the
 * targetUserId as-is. If the wire format ever changes, these tests fail
 * before the live request hits the backend.
 */
describe('roomService.kickPlayer', () => {
  it('POSTs /room/kick with numeric roomId and targetUserId', async () => {
    await roomService.kickPlayer('42', 'guest:bot1-1234')

    expect(http.post).toHaveBeenCalledWith('/room/kick', {
      roomId: 42,
      targetUserId: 'guest:bot1-1234',
    })
  })

  it('coerces string-formatted roomId to a number', async () => {
    await roomService.kickPlayer('7', 'u1')

    expect(http.post).toHaveBeenCalledWith('/room/kick', {
      roomId: 7,
      targetUserId: 'u1',
    })
  })
})
