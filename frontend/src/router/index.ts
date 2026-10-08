import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { isSupportedBrowser, isWeChatBrowser } from '@/composables/useBrowserCompat'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/unsupported',
      name: 'unsupported',
      component: () => import('@/views/BrowserUnsupportedView.vue'),
    },
    {
      path: '/',
      name: 'lobby',
      component: () => import('@/views/LobbyView.vue'),
    },
    {
      path: '/demo',
      name: 'demo',
      component: () => import('@/views/DemoView.vue'),
    },
    {
      path: '/auth/callback/:provider',
      name: 'auth-callback',
      component: () => import('@/views/OAuthCallbackView.vue'),
    },
    {
      path: '/create-room',
      name: 'create-room',
      component: () => import('@/views/CreateRoomView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/room/:roomId',
      name: 'room',
      component: () => import('@/views/RoomView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/game/:gameId',
      name: 'game',
      component: () => import('@/views/GameView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/result/:gameId',
      name: 'result',
      component: () => import('@/views/ResultView.vue'),
      meta: { requiresAuth: true },
    },
    {
      // No requiresAuth: the view itself renders a sign-in prompt when the
      // visitor has no session (the lobby ☰ entry point is login-gated anyway).
      path: '/account',
      name: 'account',
      component: () => import('@/views/AccountView.vue'),
    },
    {
      // Stripe Checkout success/cancel redirect target
      path: '/pay/result',
      name: 'pay-result',
      component: () => import('@/views/PayResultView.vue'),
      meta: { requiresAuth: true },
    },
    // ── Dev-only routes (tree-shaken out of production builds) ────────────────
    ...(import.meta.env.DEV
      ? [
          {
            path: '/dev/role-reveal',
            name: 'dev-role-reveal',
            component: () => import('@/views/dev/RoleRevealDevView.vue'),
          },
        ]
      : []),
  ],
})

// Same-app paths only: "//host" and "/\host" are read by browsers as another origin.
function safeReturnPath(from: unknown): string | null {
  return typeof from === 'string' && /^\/(?![/\\])/.test(from) ? from : null
}

router.beforeEach((to) => {
  // Browser compatibility check first — short-circuits before any auth /
  // STOMP / store work. Skip `/demo` and the dev `/dev/*` routes so
  // contributors can still preview those in any browser — except WeChat,
  // which is blocked on every route.
  if (to.name === 'unsupported') {
    // WeChat's "open in browser" reopens this URL in Safari/Chrome: send a
    // supported browser on to the page the user originally asked for.
    if (isSupportedBrowser()) return safeReturnPath(to.query.from) ?? { name: 'lobby' }
  } else {
    const exempt = to.name === 'demo' || String(to.name ?? '').startsWith('dev-')
    if (isWeChatBrowser() || (!exempt && !isSupportedBrowser())) {
      return { name: 'unsupported', query: { from: to.fullPath } }
    }
  }

  const userStore = useUserStore()
  if (to.meta.requiresAuth && !userStore.isLoggedIn) {
    return { name: 'lobby' }
  }
})

export default router
