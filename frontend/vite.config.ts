import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import {fileURLToPath, URL} from 'node:url'
import {execSync} from 'node:child_process'
import {pickAppVersion} from './src/utils/appVersion'

// Local `git describe`, or undefined when git/.git is unavailable (e.g. inside a
// container build). Container builds get the version from APP_VERSION instead.
function gitDescribe(): string | undefined {
    try {
        return execSync('git describe --tags --abbrev=0', {
            stdio: ['ignore', 'pipe', 'ignore'],
        }).toString().trim()
    } catch {
        return undefined
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
        __APP_VERSION__: JSON.stringify(pickAppVersion(process.env.APP_VERSION, gitDescribe())),
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
