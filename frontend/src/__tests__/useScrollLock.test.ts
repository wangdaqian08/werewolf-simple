import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { useScrollLock } from '@/composables/useScrollLock'

// A modal teleported to <body> chains any pan it cannot consume to the document,
// so the page behind it scrolls. The lock pins <body> while a modal is open.
function host(active: () => boolean) {
  return defineComponent({
    setup() {
      useScrollLock(active)
      return () => h('div')
    },
  })
}

describe('useScrollLock', () => {
  afterEach(() => {
    document.body.removeAttribute('style')
    vi.restoreAllMocks()
  })

  it('pins the body at the current scroll offset while active', () => {
    Object.defineProperty(window, 'scrollY', { value: 300, configurable: true })
    const w = mount(host(() => true))
    expect(document.body.style.position).toBe('fixed')
    expect(document.body.style.top).toBe('-300px')
    expect(document.body.style.width).toBe('100%')
    w.unmount()
  })

  it('restores the body and the scroll offset on unmount', () => {
    Object.defineProperty(window, 'scrollY', { value: 300, configurable: true })
    const scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
    const w = mount(host(() => true))
    w.unmount()
    expect(document.body.style.position).toBe('')
    expect(document.body.style.top).toBe('')
    expect(scrollTo).toHaveBeenCalledWith(0, 300)
  })

  it('follows the active getter', async () => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
    const open = ref(false)
    const w = mount(host(() => open.value))
    expect(document.body.style.position).toBe('')
    open.value = true
    await nextTick()
    expect(document.body.style.position).toBe('fixed')
    open.value = false
    await nextTick()
    expect(document.body.style.position).toBe('')
    w.unmount()
  })

  it('stays locked until the last of nested modals closes', () => {
    vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
    const outer = mount(host(() => true))
    const inner = mount(host(() => true))
    inner.unmount()
    expect(document.body.style.position).toBe('fixed')
    outer.unmount()
    expect(document.body.style.position).toBe('')
  })
})
