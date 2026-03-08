import {createApp} from 'vue'
import {createPinia} from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import router from './router'

// ── Mock mode ─────────────────────────────────────────────────────────────────
// Controlled by VITE_MOCK in .env.development.
// Remove this block (and src/mocks/) when the real backend is ready.
if (import.meta.env.VITE_MOCK === 'true') {
    const {setupMocks} = await import('./mocks')
    setupMocks()
}

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus)

app.mount('#app')
