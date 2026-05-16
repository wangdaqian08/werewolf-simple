import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import {fileURLToPath, URL} from 'node:url'
import {execSync} from 'node:child_process'

function resolveAppVersion(): string {
    try {
        const tag = execSync('git describe --tags --abbrev=0', {
            stdio: ['ignore', 'pipe', 'ignore'],
        }).toString().trim()
        return tag || 'dev'
    } catch {
        return 'dev'
    }
}

export default defineConfig({
    plugins: [
        vue(),
        tailwindcss(),
    ],
    resolve: {
        alias: {
            '@': fileURLToPath(new URL('./src', import.meta.url)),
        },
    },
    define: {
        __APP_VERSION__: JSON.stringify(resolveAppVersion()),
        // Fix for SockJS: add global polyfill
        global: 'globalThis',
    },
    server: {
        proxy: {
            '/api': 'http://localhost:8080',
            '/ws': {
                target: 'ws://localhost:8080',
                ws: true,
            },
            '/audio': 'http://localhost:8080',
        },
    },
})
