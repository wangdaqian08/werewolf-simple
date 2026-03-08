import { onMounted, onUnmounted } from 'vue'

/**
 * Prevents accidental browser back/forward navigation and page refresh.
 * Use in views where leaving mid-session would disrupt gameplay (Room, Game).
 */
export function useNavigationGuard() {
  function blockPopState() {
    history.pushState(null, '', location.href)
  }

  function handleBeforeUnload(e: BeforeUnloadEvent) {
    e.preventDefault()
    // Chrome requires returnValue to be set; Safari and Firefox respect preventDefault()
    e.returnValue = ''
  }

  onMounted(() => {
    // Push current URL so the first back-press is intercepted by popstate
    history.pushState(null, '', location.href)
    window.addEventListener('popstate', blockPopState)
    window.addEventListener('beforeunload', handleBeforeUnload)
  })

  onUnmounted(() => {
    window.removeEventListener('popstate', blockPopState)
    window.removeEventListener('beforeunload', handleBeforeUnload)
  })
}
