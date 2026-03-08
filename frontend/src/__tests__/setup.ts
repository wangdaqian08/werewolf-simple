import {beforeEach, vi} from 'vitest'

// happy-dom's localStorage lacks full Storage API implementation.
// This polyfill provides a proper in-memory localStorage for tests.
const makeStorage = () => {
    const store = new Map<string, string>()
    return {
        getItem: (key: string) => store.get(key) ?? null,
        setItem: (key: string, value: string) => store.set(key, value),
        removeItem: (key: string) => store.delete(key),
        clear: () => store.clear(),
        get length() {
            return store.size
        },
        key: (index: number) => [...store.keys()][index] ?? null,
    }
}

vi.stubGlobal('localStorage', makeStorage())

// Reset localStorage before every test
beforeEach(() => {
    localStorage.clear()
})
