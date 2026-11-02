import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import router from '@/router'
import http from '@/services/http'
import { useUserStore } from '@/stores/userStore'

vi.mock('element-plus', () => ({ ElMessage: vi.fn() }))

// A token that is not expired (exp far in the future), so the store boots logged in.
const futureJwt = `x.${btoa(JSON.stringify({ exp: 4102444800 }))}.y`

describe('a 401 mid-game sends the player to log in again', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('jwt', futureJwt)
    localStorage.setItem('userId', 'guest:alice')
    setActivePinia(createPinia())
  })

  it('clears the session and redirects to the lobby once, even if several requests fail', async () => {
    const push = vi.spyOn(router, 'push').mockResolvedValue(undefined)
    const reject = (
      http.interceptors.response as unknown as {
        handlers: { rejected: (e: unknown) => Promise<unknown> }[]
      }
    ).handlers[0]!.rejected

    await expect(reject({ response: { status: 401 } })).rejects.toBeTruthy()
    await expect(reject({ response: { status: 401 } })).rejects.toBeTruthy()

    expect(useUserStore().token).toBeNull()
    expect(push).toHaveBeenCalledTimes(1)
    expect(push).toHaveBeenCalledWith({ name: 'lobby' })
  })

  it('a non-401 error keeps the session', async () => {
    const push = vi.spyOn(router, 'push').mockResolvedValue(undefined)
    const reject = (
      http.interceptors.response as unknown as {
        handlers: { rejected: (e: unknown) => Promise<unknown> }[]
      }
    ).handlers[0]!.rejected

    await expect(reject({ response: { status: 500 } })).rejects.toBeTruthy()

    expect(useUserStore().token).toBe(futureJwt)
    expect(push).not.toHaveBeenCalled()
  })
})
