import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import PlayerAvatar from '@/components/PlayerAvatar.vue'
import { DEFAULT_AVATAR } from '@/assets/iconManifest'

describe('PlayerAvatar', () => {
  it('renders the provided src', () => {
    const wrapper = mount(PlayerAvatar, { props: { src: '/uploads/u42.png' } })
    expect(wrapper.find('img').attributes('src')).toBe('/uploads/u42.png')
  })

  it('renders DEFAULT_AVATAR when src is undefined', () => {
    const wrapper = mount(PlayerAvatar, { props: {} })
    expect(wrapper.find('img').attributes('src')).toBe(DEFAULT_AVATAR)
  })

  it('renders DEFAULT_AVATAR when src is null', () => {
    const wrapper = mount(PlayerAvatar, { props: { src: null } })
    expect(wrapper.find('img').attributes('src')).toBe(DEFAULT_AVATAR)
  })

  it('renders DEFAULT_AVATAR when src is an empty / whitespace string', () => {
    const blank = mount(PlayerAvatar, { props: { src: '' } })
    expect(blank.find('img').attributes('src')).toBe(DEFAULT_AVATAR)

    const ws = mount(PlayerAvatar, { props: { src: '   ' } })
    expect(ws.find('img').attributes('src')).toBe(DEFAULT_AVATAR)
  })

  it('falls back to DEFAULT_AVATAR after the image @error fires', async () => {
    const wrapper = mount(PlayerAvatar, { props: { src: '/uploads/broken.png' } })
    await wrapper.find('img').trigger('error')
    expect(wrapper.find('img').attributes('src')).toBe(DEFAULT_AVATAR)
  })

  it('clears the error state when src changes to a new value', async () => {
    const wrapper = mount(PlayerAvatar, { props: { src: '/uploads/broken.png' } })
    await wrapper.find('img').trigger('error')
    expect(wrapper.find('img').attributes('src')).toBe(DEFAULT_AVATAR)

    await wrapper.setProps({ src: '/uploads/working.png' })
    expect(wrapper.find('img').attributes('src')).toBe('/uploads/working.png')
  })

  it('passes alt through to the <img>', () => {
    const wrapper = mount(PlayerAvatar, { props: { src: '/x.png', alt: 'Alice' } })
    expect(wrapper.find('img').attributes('alt')).toBe('Alice')
  })
})
