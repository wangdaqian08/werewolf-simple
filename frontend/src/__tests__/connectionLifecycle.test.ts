import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { useConnectionLifecycle } from '@/composables/useConnectionLifecycle'
import { useWakeLock } from '@/composables/useWakeLock'

// Auto-unmount between tests so document/window listeners from one test
// don't leak into the next. Without this, lifecycle composables stack up
// and a single dispatched event hits every previous test's mounted component.
enableAutoUnmount(afterEach)

// ── useConnectionLifecycle ────────────────────────────────────────────────────

function mountWithLifecycle(onResume: () => void) {
  const Stub = defineComponent({
    setup() {
      useConnectionLifecycle({ onResume })
      return () => h('div')
    },
  })
  return mount(Stub)
}

function setVisibility(state: 'hidden' | 'visible') {
  Object.defineProperty(document, 'visibilityState', {
    value: state,
    configurable: true,
  })
  document.dispatchEvent(new Event('visibilitychange'))
}

describe('useConnectionLifecycle', () => {
  beforeEach(() => {
    setVisibility('visible')
  })

  it('calls onResume when the tab returns to foreground after being hidden', () => {
    const onResume = vi.fn()
    mountWithLifecycle(onResume)

    setVisibility('hidden')
    setVisibility('visible')

    expect(onResume).toHaveBeenCalledTimes(1)
  })

  it('does NOT call onResume when becoming visible without ever having been hidden', () => {
    const onResume = vi.fn()
    mountWithLifecycle(onResume)

    setVisibility('visible')

    expect(onResume).not.toHaveBeenCalled()
  })

  it('calls onResume when network returns from offline to online', () => {
    const onResume = vi.fn()
    mountWithLifecycle(onResume)

    window.dispatchEvent(new Event('offline'))
    window.dispatchEvent(new Event('online'))

    expect(onResume).toHaveBeenCalledTimes(1)
  })

  it('does NOT call onResume on initial online without prior offline', () => {
    const onResume = vi.fn()
    mountWithLifecycle(onResume)

    window.dispatchEvent(new Event('online'))

    expect(onResume).not.toHaveBeenCalled()
  })

  it('removes all listeners on unmount', () => {
    const onResume = vi.fn()
    const wrapper = mountWithLifecycle(onResume)

    wrapper.unmount()

    setVisibility('hidden')
    setVisibility('visible')
    window.dispatchEvent(new Event('offline'))
    window.dispatchEvent(new Event('online'))

    expect(onResume).not.toHaveBeenCalled()
  })

  it('handles repeated hide/show cycles, calling onResume each time visibility flips back', () => {
    const onResume = vi.fn()
    mountWithLifecycle(onResume)

    setVisibility('hidden')
    setVisibility('visible')
    setVisibility('hidden')
    setVisibility('visible')

    expect(onResume).toHaveBeenCalledTimes(2)
  })
})

// ── useWakeLock ───────────────────────────────────────────────────────────────

interface FakeSentinel {
  release: ReturnType<typeof vi.fn>
  released: boolean
}

function installFakeWakeLock(): { request: ReturnType<typeof vi.fn>; sentinel: FakeSentinel } {
  const sentinel: FakeSentinel = {
    release: vi.fn(async () => {
      sentinel.released = true
    }),
    released: false,
  }
  const request = vi.fn(async () => sentinel)
  Object.defineProperty(navigator, 'wakeLock', {
    value: { request },
    configurable: true,
  })
  return { request, sentinel }
}

function uninstallWakeLock() {
  // Some environments forbid deleting; assigning undefined on a configurable
  // property keeps later tests honest about the API being absent.
  Object.defineProperty(navigator, 'wakeLock', {
    value: undefined,
    configurable: true,
  })
}

function mountWithWakeLock() {
  const Stub = defineComponent({
    setup() {
      useWakeLock()
      return () => h('div')
    },
  })
  return mount(Stub)
}

describe('useWakeLock', () => {
  afterEach(() => {
    uninstallWakeLock()
  })

  it("requests a 'screen' wake lock on mount", async () => {
    const { request } = installFakeWakeLock()
    mountWithWakeLock()

    await Promise.resolve()
    await Promise.resolve()
    expect(request).toHaveBeenCalledWith('screen')
  })

  it('releases the sentinel on unmount', async () => {
    const { sentinel } = installFakeWakeLock()
    const wrapper = mountWithWakeLock()
    await Promise.resolve()
    await Promise.resolve()

    wrapper.unmount()

    await Promise.resolve()
    expect(sentinel.release).toHaveBeenCalled()
  })

  it('re-acquires the lock when the tab returns to foreground (Wake Lock auto-releases when hidden)', async () => {
    const { request } = installFakeWakeLock()
    mountWithWakeLock()
    await Promise.resolve()
    await Promise.resolve()
    expect(request).toHaveBeenCalledTimes(1)

    setVisibility('visible')
    await Promise.resolve()

    expect(request).toHaveBeenCalledTimes(2)
  })

  it('silently no-ops when navigator.wakeLock is unavailable', () => {
    uninstallWakeLock()
    expect(() => mountWithWakeLock()).not.toThrow()
  })

  it('silently swallows request rejections (e.g. denied, low battery)', async () => {
    const sentinel: FakeSentinel = {
      release: vi.fn(),
      released: false,
    }
    const request = vi.fn(async () => {
      throw new Error('NotAllowedError')
    })
    Object.defineProperty(navigator, 'wakeLock', { value: { request }, configurable: true })

    expect(() => mountWithWakeLock()).not.toThrow()
    await Promise.resolve()
    await Promise.resolve()
    // sentinel never assigned because request rejected; release is a no-op
    expect(sentinel.release).not.toHaveBeenCalled()
  })
})
