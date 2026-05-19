import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import VersionBadge from '@/components/VersionBadge.vue'

vi.stubGlobal('__APP_VERSION__', 'v0.6.6')

describe('VersionBadge', () => {
  it('renders the injected version', () => {
    const wrapper = mount(VersionBadge)
    expect(wrapper.text()).toBe('v0.6.6')
  })

  it('exposes the version-badge testid', () => {
    const wrapper = mount(VersionBadge)
    expect(wrapper.find('[data-testid="version-badge"]').exists()).toBe(true)
  })
})
