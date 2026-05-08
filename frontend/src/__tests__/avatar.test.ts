import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import Avatar from '@/components/Avatar.vue'

describe('Avatar', () => {
  it('renders <img src=avatarUrl> when an https avatarUrl is provided', () => {
    const wrapper = mount(Avatar, {
      props: { nickname: 'Daniel', avatarUrl: 'https://example.com/x.png' },
    })
    const img = wrapper.find('img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('https://example.com/x.png')
  })

  it('falls back to emoji when no avatarUrl is provided', () => {
    const wrapper = mount(Avatar, { props: { nickname: 'Daniel', emoji: '🐺' } })
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toBe('🐺')
  })

  it('falls back to first character of nickname when neither avatarUrl nor emoji are provided', () => {
    const wrapper = mount(Avatar, { props: { nickname: 'Daniel' } })
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toBe('D')
  })

  it('falls back to ? when nickname is empty and no other source available', () => {
    const wrapper = mount(Avatar, { props: { nickname: '' } })
    expect(wrapper.text()).toBe('?')
  })

  it('falls back without throwing when img fires an error event', async () => {
    const wrapper = mount(Avatar, {
      props: { nickname: 'Daniel', avatarUrl: 'https://example.com/broken.png', emoji: '🐺' },
    })
    expect(wrapper.find('img').exists()).toBe(true)

    await wrapper.find('img').trigger('error')

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toBe('🐺')
  })

  it('rejects non-https avatarUrl (security: no http://, no data:, no javascript:) and falls back', () => {
    const wrapper = mount(Avatar, {
      props: { nickname: 'Daniel', avatarUrl: 'http://example.com/x.png', emoji: '🐺' },
    })
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toBe('🐺')

    const w2 = mount(Avatar, {
      props: { nickname: 'Eve', avatarUrl: 'javascript:alert(1)', emoji: '🦊' },
    })
    expect(w2.find('img').exists()).toBe(false)
    expect(w2.text()).toBe('🦊')
  })

  it('accepts a size prop and applies a size class', () => {
    const wrapper = mount(Avatar, { props: { nickname: 'D', size: 'lg' } })
    expect(wrapper.classes()).toContain('avatar-lg')
  })

  // ── Single `avatar` prop (auto-discriminating URL or emoji) ───────────────

  it('avatar prop: https URL renders as <img>', () => {
    const wrapper = mount(Avatar, {
      props: { nickname: 'Daniel', avatar: 'https://lh3.googleusercontent.com/a/x' },
    })
    const img = wrapper.find('img')
    expect(img.exists()).toBe(true)
    expect(img.attributes('src')).toBe('https://lh3.googleusercontent.com/a/x')
  })

  it('avatar prop: emoji glyph renders as text fallback', () => {
    const wrapper = mount(Avatar, { props: { nickname: 'Daniel', avatar: '🐺' } })
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.text()).toBe('🐺')
  })

  it('avatar prop: null/undefined falls back to nickname initial', () => {
    const wrapper = mount(Avatar, { props: { nickname: 'Daniel', avatar: null } })
    expect(wrapper.text()).toBe('D')
  })

  it('explicit avatarUrl wins over avatar prop when both are provided', () => {
    const wrapper = mount(Avatar, {
      props: {
        nickname: 'Daniel',
        avatar: '🐺',
        avatarUrl: 'https://example.com/y.png',
      },
    })
    const img = wrapper.find('img')
    expect(img.attributes('src')).toBe('https://example.com/y.png')
  })

  it('explicit emoji prop wins over emoji-flavoured avatar prop', () => {
    const wrapper = mount(Avatar, {
      props: { nickname: 'Daniel', avatar: '🦊', emoji: '🐺' },
    })
    expect(wrapper.text()).toBe('🐺')
  })
})
