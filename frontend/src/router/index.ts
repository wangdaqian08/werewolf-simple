import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/userStore'
import { isSupportedBrowser } from '@/composables/useBrowserCompat'

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

router.beforeEach((to) => {
  // Browser compatibility check first — short-circuits before any auth /
  // STOMP / store work. Skip if the user is already on the unsupported page
  // (avoids redirect loops) and skip the dev `/dev/*` routes so contributors
  // can still preview those in any browser.
  if (
    to.name !== 'unsupported' &&
    !String(to.name ?? '').startsWith('dev-') &&
    !isSupportedBrowser()
  ) {
    return { name: 'unsupported' }
  }

  const userStore = useUserStore()
  if (to.meta.requiresAuth && !userStore.isLoggedIn) {
    return { name: 'lobby' }
  }
})

export default router
