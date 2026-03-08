import {createRouter, createWebHistory} from 'vue-router'
import {useUserStore} from '@/stores/userStore'

const router = createRouter({
    history: createWebHistory(import.meta.env.BASE_URL),
    routes: [
        {
            path: '/',
            name: 'lobby',
            component: () => import('@/views/LobbyView.vue'),
        },
        {
            path: '/room/:roomId',
            name: 'room',
            component: () => import('@/views/RoomView.vue'),
            meta: {requiresAuth: true},
        },
        {
            path: '/game/:gameId',
            name: 'game',
            component: () => import('@/views/GameView.vue'),
            meta: {requiresAuth: true},
        },
        {
            path: '/result/:gameId',
            name: 'result',
            component: () => import('@/views/ResultView.vue'),
            meta: {requiresAuth: true},
        },
    ],
})

router.beforeEach((to) => {
    const userStore = useUserStore()
    if (to.meta.requiresAuth && !userStore.isLoggedIn) {
        return {name: 'lobby'}
    }
})

export default router
