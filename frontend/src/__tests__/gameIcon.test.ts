import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import GameIcon from '@/components/GameIcon.vue'
import { FALLBACK_ICON, ICON_MANIFEST } from '@/assets/iconManifest'

describe('GameIcon', () => {
  it('renders an <img> with the manifest src for a known icon name', () => {
    const wrapper = mount(GameIcon, { props: { name: 'role-werewolf' } })
    const img = wrapper.find('img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe(ICON_MANIFEST['role-werewolf'])
  })

  it('falls back to FALLBACK_ICON when the manifest has no entry for the name', () => {
    const wrapper = mount(GameIcon, { props: { name: 'role-no-such-thing' } })
    expect(wrapper.find('img').attributes('src')).toBe(FALLBACK_ICON)
  })

  it('switches to FALLBACK_ICON when the underlying <img> emits @error', async () => {
    const wrapper = mount(GameIcon, { props: { name: 'role-werewolf' } })
    await wrapper.find('img').trigger('error')
    expect(wrapper.find('img').attributes('src')).toBe(FALLBACK_ICON)
  })

  it('uses the alt prop, or the name as a default alt', () => {
    const named = mount(GameIcon, { props: { name: 'phase-night', alt: 'night' } })
    expect(named.find('img').attributes('alt')).toBe('night')

    const unnamed = mount(GameIcon, { props: { name: 'phase-night' } })
    expect(unnamed.find('img').attributes('alt')).toBe('phase-night')
  })
})
