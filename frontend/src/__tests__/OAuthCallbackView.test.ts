import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, flushPromises } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import OAuthCallbackView from '@/views/OAuthCallbackView.vue'
import { useUserStore } from '@/stores/userStore'
import { OAUTH_STATE_KEY } from '@/utils/oauth'

const loginWithCodeMock = vi.fn()

vi.mock('@/services/userService', () => ({
  userService: {
    login: vi.fn(),
    loginWithGoogle: vi.fn(),
    loginWithWechat: vi.fn(),
    logout: vi.fn(),
    getProviders: vi.fn(),
  },
}))

async function mountAt(
  path: string,
): Promise<{ wrapper: ReturnType<typeof mount>; router: Router }> {
  const pinia = createPinia()
  setActivePinia(pinia)

  const userStore = useUserStore()
  // Real loginWithCode would call userService; we patch the store action so
  // we can assert call args without exercising the HTTP layer.
  userStore.loginWithCode = loginWithCodeMock as unknown as typeof userStore.loginWithCode

  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'lobby', component: { template: '<div data-testid="lobby">lobby</div>' } },
      {
        path: '/auth/callback/:provider',
        name: 'auth-callback',
        component: OAuthCallbackView,
      },
    ],
  })
  await router.push(path)
  await router.isReady()

  const wrapper = mount(OAuthCallbackView, {
    global: { plugins: [pinia, router] },
  })
  await flushPromises()

  return { wrapper, router }
}

describe('OAuthCallbackView', () => {
  beforeEach(() => {
    sessionStorage.clear()
    loginWithCodeMock.mockReset()
    loginWithCodeMock.mockResolvedValue(undefined)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('valid state + code calls loginWithCode("google", code) and navigates to /', async () => {
    sessionStorage.setItem(OAUTH_STATE_KEY, 'valid-state')
    const { router } = await mountAt('/auth/callback/google?code=the-code&state=valid-state')

    expect(loginWithCodeMock).toHaveBeenCalledWith('google', 'the-code')
    expect(router.currentRoute.value.path).toBe('/')
  })

  it('routes wechat provider to loginWithCode("wechat", code)', async () => {
    sessionStorage.setItem(OAUTH_STATE_KEY, 'wx-state')
    await mountAt('/auth/callback/wechat?code=wx-code&state=wx-state')

    expect(loginWithCodeMock).toHaveBeenCalledWith('wechat', 'wx-code')
  })

  it('state mismatch shows an error and does NOT call loginWithCode (CSRF guard)', async () => {
    sessionStorage.setItem(OAUTH_STATE_KEY, 'real-state')
    const { wrapper } = await mountAt('/auth/callback/google?code=c&state=tampered')

    expect(loginWithCodeMock).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="oauth-error"]').exists()).toBe(true)
  })

  it('missing code shows the "login canceled" message and does not call loginWithCode', async () => {
    sessionStorage.setItem(OAUTH_STATE_KEY, 'st')
    const { wrapper } = await mountAt('/auth/callback/google?error=access_denied&state=st')

    expect(loginWithCodeMock).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="oauth-error"]').exists()).toBe(true)
  })

  it('shows a loading indicator while the exchange is in flight', async () => {
    let resolveLogin: () => void = () => {}
    loginWithCodeMock.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          resolveLogin = resolve
        }),
    )
    sessionStorage.setItem(OAUTH_STATE_KEY, 'st')

    const pinia = createPinia()
    setActivePinia(pinia)
    const userStore = useUserStore()
    userStore.loginWithCode = loginWithCodeMock as unknown as typeof userStore.loginWithCode

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', name: 'lobby', component: { template: '<div>lobby</div>' } },
        { path: '/auth/callback/:provider', name: 'auth-callback', component: OAuthCallbackView },
      ],
    })
    await router.push('/auth/callback/google?code=c&state=st')
    await router.isReady()

    const wrapper = mount(OAuthCallbackView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.find('[data-testid="oauth-loading"]').exists()).toBe(true)
    resolveLogin()
    await flushPromises()
    expect(wrapper.find('[data-testid="oauth-loading"]').exists()).toBe(false)
  })

  it('shows an error if loginWithCode rejects (e.g. backend rejects code)', async () => {
    loginWithCodeMock.mockRejectedValueOnce(new Error('invalid_grant'))
    sessionStorage.setItem(OAUTH_STATE_KEY, 'st')

    const { wrapper } = await mountAt('/auth/callback/google?code=bad&state=st')

    expect(wrapper.find('[data-testid="oauth-error"]').exists()).toBe(true)
  })
})
