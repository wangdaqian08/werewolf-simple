import { onMounted, onUnmounted } from 'vue'

interface WakeLockSentinel {
  release(): Promise<void>
  released: boolean
}

interface WakeLockNavigator {
  wakeLock?: {
    request(type: 'screen'): Promise<WakeLockSentinel>
  }
}

/**
 * Holds a screen Wake Lock for the lifetime of the calling component so the
 * device doesn't dim or lock during long phases (a 12-player game can run 15+
 * minutes between meaningful taps). Wake Locks are auto-released when the tab
 * is hidden; we re-acquire on visibilitychange so a backgrounded-then-resumed
 * tab keeps the screen on for the rest of the game.
 *
 * Silently no-ops when navigator.wakeLock is missing (insecure context, older
 * Safari before iOS 16.4) — degrade gracefully rather than blocking the view.
 */
export function useWakeLock() {
  let sentinel: WakeLockSentinel | null = null

  async function acquire() {
    const nav = navigator as Navigator & WakeLockNavigator
    if (!nav.wakeLock) return
    try {
      sentinel = await nav.wakeLock.request('screen')
    } catch {
      // user gesture required, low-battery mode, denied — degrade silently
    }
  }

  async function release() {
    if (sentinel && !sentinel.released) {
      try {
        await sentinel.release()
      } catch {
        /* tab already gone */
      }
    }
    sentinel = null
  }

  function handleVisibilityChange() {
    if (document.visibilityState === 'visible') {
      void acquire()
    }
  }

  onMounted(() => {
    void acquire()
    document.addEventListener('visibilitychange', handleVisibilityChange)
  })

  onUnmounted(() => {
    document.removeEventListener('visibilitychange', handleVisibilityChange)
    void release()
  })
}
