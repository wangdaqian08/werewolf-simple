/**
 * ShareButton — one tap invites people: the phone's share sheet when the
 * browser has one, otherwise the link is copied (WeChat's in-app browser).
 */
import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ShareButton from '@/components/ShareButton.vue'

const LINK = `${window.location.origin}/?room=123`

function stubNavigator(nav: Partial<Navigator>) {
  for (const [k, v] of Object.entries(nav))
    Object.defineProperty(navigator, k, { value: v, configurable: true })
}

describe('ShareButton', () => {
  afterEach(() => {
    stubNavigator({ share: undefined, clipboard: undefined } as unknown as Partial<Navigator>)
  })

  it('opens the share sheet with the room link', async () => {
    const share = vi.fn().mockResolvedValue(undefined)
    stubNavigator({ share })
    const w = mount(ShareButton, { props: { roomCode: '123' } })

    await w.trigger('click')

    expect(share).toHaveBeenCalledWith(expect.objectContaining({ url: LINK }))
  })

  it('copies the link when there is no share sheet', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    stubNavigator({ clipboard: { writeText } as unknown as Clipboard })
    const w = mount(ShareButton, { props: { roomCode: '123' } })

    await w.trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith(LINK)
    expect(w.text()).toContain('已複製')
  })

  it('a cancelled share sheet does not fall back to copying', async () => {
    const share = vi.fn().mockRejectedValue(new DOMException('cancel', 'AbortError'))
    const writeText = vi.fn()
    stubNavigator({ share, clipboard: { writeText } as unknown as Clipboard })
    const w = mount(ShareButton, { props: { roomCode: '123' } })

    await w.trigger('click')
    await flushPromises()

    expect(writeText).not.toHaveBeenCalled()
  })
})
