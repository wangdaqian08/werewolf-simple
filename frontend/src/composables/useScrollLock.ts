import { onBeforeUnmount, watch } from 'vue'

// Modals are teleported to <body>, so a swipe they cannot consume (content that
// fits, or a list at its end) scrolls the page behind them. overflow:hidden on
// <html> does not stop that on mobile Safari; pinning <body> does. Ref-counted
// so a modal opened from another modal keeps the page locked.
let holders = 0
let savedY = 0

function lock() {
  if (holders++ > 0) return
  savedY = window.scrollY
  Object.assign(document.body.style, {
    position: 'fixed',
    top: `-${savedY}px`,
    left: '0',
    right: '0',
    width: '100%',
  })
}

function unlock() {
  if (holders === 0 || --holders > 0) return
  Object.assign(document.body.style, { position: '', top: '', left: '', right: '', width: '' })
  window.scrollTo(0, savedY)
}

/** Locks page scroll while `active()` is true and the calling component is mounted. */
export function useScrollLock(active: () => boolean) {
  let held = false
  const set = (on: boolean) => {
    if (on === held) return
    held = on
    if (on) lock()
    else unlock()
  }
  watch(active, set, { immediate: true })
  onBeforeUnmount(() => set(false))
}
