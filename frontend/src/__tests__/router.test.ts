/**
 * Browser-compat guard in router/index.ts. A fresh router per test so each
 * navigation starts from the initial route.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const WECHAT_IOS =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 ' +
  '(KHTML, like Gecko) Mobile/15E148 MicroMessenger/8.0.47(0x18002f2c) NetType/WIFI ' +
  'Language/zh_CN'
const FIREFOX = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 Firefox/120.0'
const IOS_SAFARI =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 ' +
  '(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1'

const ORIGINAL_UA = navigator.userAgent

function setUa(ua: string) {
  Object.defineProperty(navigator, 'userAgent', { value: ua, configurable: true })
}

async function freshRouter() {
  vi.resetModules()
  return (await import('@/router')).default
}

describe('router browser-compat guard', () => {
  beforeEach(() => setActivePinia(createPinia()))
  afterEach(() => setUa(ORIGINAL_UA))

  it.each([
    '/',
    '/demo',
    '/auth/callback/wechat',
    '/create-room',
    '/room/1',
    '/game/1',
    '/result/1',
    '/account',
    '/pay/result?session_id=cs_test_1',
    '/no-such-page',
  ])('WeChat on %s → unsupported', async (path) => {
    setUa(WECHAT_IOS)
    const router = await freshRouter()
    await router.push(path)
    expect(router.currentRoute.value.name).toBe('unsupported')
  })

  it('WeChat on /unsupported stays there (no redirect loop)', async () => {
    setUa(WECHAT_IOS)
    const router = await freshRouter()
    await router.push('/unsupported?from=/account')
    expect(router.currentRoute.value.name).toBe('unsupported')
  })

  // WeChat's "open in browser" reopens the current (/unsupported) URL in Safari/Chrome.
  it('the URL WeChat is sent to reopens the original page in a supported browser', async () => {
    setUa(WECHAT_IOS)
    const wechat = await freshRouter()
    await wechat.push('/account?tab=credits#top')
    const blockedUrl = wechat.currentRoute.value.fullPath
    expect(wechat.currentRoute.value.name).toBe('unsupported')

    setUa(IOS_SAFARI)
    const safari = await freshRouter()
    await safari.push(blockedUrl)
    expect(safari.currentRoute.value.fullPath).toBe('/account?tab=credits#top')
  })

  it('supported browser on /unsupported without a return path → lobby', async () => {
    setUa(IOS_SAFARI)
    const router = await freshRouter()
    await router.push('/unsupported')
    expect(router.currentRoute.value.name).toBe('lobby')
  })

  it.each(['//evil.example', '/\\evil.example', 'https://evil.example', 'account'])(
    'supported browser ignores unsafe return path %s → lobby',
    async (from) => {
      setUa(IOS_SAFARI)
      const router = await freshRouter()
      await router.push(`/unsupported?from=${encodeURIComponent(from)}`)
      expect(router.currentRoute.value.name).toBe('lobby')
    },
  )

  it('other unsupported browsers can still open /demo', async () => {
    setUa(FIREFOX)
    const router = await freshRouter()
    await router.push('/demo')
    expect(router.currentRoute.value.name).toBe('demo')
  })
})
