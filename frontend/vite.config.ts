import {defineConfig} from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import {fileURLToPath, URL} from 'node:url'

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
    server: {
        proxy: {
            '/api': 'http://localhost:8080',
            '/ws': {
                target: 'ws://localhost:8080',
                ws: true,
            },
        },
    },
})
