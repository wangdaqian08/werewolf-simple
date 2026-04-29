import { onMounted, onUnmounted } from 'vue'

export interface ConnectionLifecycleOptions {
  /**
   * Called when the page transitions back to foreground after being hidden,
   * or when the browser regains network after being offline. Caller should
   * force-reconnect the STOMP client and refetch authoritative state.
   */
  onResume: () => void | Promise<void>
}

/**
 * iOS Safari freezes WebSockets when tabs are backgrounded and cellular
 * handovers can drop sockets without surfacing a close event — STOMP's
 * heartbeat watchdog only catches this 20-30s later, leaving the UI
 * "stuck" until the next action fails. Force-reconnecting the moment the
 * tab/network resumes closes that gap.
 */
export function useConnectionLifecycle(options: ConnectionLifecycleOptions) {
  let wasHidden = false
  let wasOffline = false

  function handleVisibilityChange() {
    if (document.visibilityState === 'hidden') {
      wasHidden = true
      return
    }
    if (document.visibilityState === 'visible' && wasHidden) {
      wasHidden = false
      void options.onResume()
    }
  }

  function handleOnline() {
    if (!wasOffline) return
    wasOffline = false
    void options.onResume()
  }

  function handleOffline() {
    wasOffline = true
  }

  onMounted(() => {
    document.addEventListener('visibilitychange', handleVisibilityChange)
    window.addEventListener('online', handleOnline)
    window.addEventListener('offline', handleOffline)
  })

  onUnmounted(() => {
    document.removeEventListener('visibilitychange', handleVisibilityChange)
    window.removeEventListener('online', handleOnline)
    window.removeEventListener('offline', handleOffline)
  })
}
