import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import http from '@/services/http'
import { userService } from '@/services/userService'

vi.mock('@/services/http', () => ({
  default: {
    post: vi.fn(),
    get: vi.fn(),
  },
}))

const mockedHttp = http as unknown as { post: ReturnType<typeof vi.fn>; get: ReturnType<typeof vi.fn> }

describe('userService', () => {
  beforeEach(() => {
    mockedHttp.post.mockReset()
    mockedHttp.get.mockReset()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('loginWithGoogle posts the auth code to /auth/google and returns LoginResponse', async () => {
    const expected = {
      token: 'jwt-google',
      user: { userId: 'google:abc', nickname: 'Daniel', avatarUrl: 'https://x.png' },
    }
    mockedHttp.post.mockResolvedValueOnce({ data: expected })

    const result = await userService.loginWithGoogle('the-google-code')

    expect(mockedHttp.post).toHaveBeenCalledWith('/auth/google', { code: 'the-google-code' })
    expect(result).toEqual(expected)
  })

  it('loginWithWechat posts the auth code to /auth/wechat and returns LoginResponse', async () => {
    const expected = {
      token: 'jwt-wechat',
      user: { userId: 'wechat:xyz', nickname: '微信用户', avatarUrl: 'https://w.png' },
    }
    mockedHttp.post.mockResolvedValueOnce({ data: expected })

    const result = await userService.loginWithWechat('the-wechat-code')

    expect(mockedHttp.post).toHaveBeenCalledWith('/auth/wechat', { code: 'the-wechat-code' })
    expect(result).toEqual(expected)
  })

  it('getProviders fetches /auth/providers and returns the provider configs', async () => {
    const expected = {
      google: { clientId: 'g.apps.googleusercontent.com' },
      wechat: null,
      guest: true,
    }
    mockedHttp.get.mockResolvedValueOnce({ data: expected })

    const result = await userService.getProviders()

    expect(mockedHttp.get).toHaveBeenCalledWith('/auth/providers')
    expect(result).toEqual(expected)
  })
})
