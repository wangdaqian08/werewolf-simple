import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import DemoView from '@/views/DemoView.vue'

describe('DemoView — public no-backend showcase', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('renders the demo page container', () => {
    const wrapper = mount(DemoView)
    expect(wrapper.find('[data-testid="demo-page"]').exists()).toBe(true)
  })

  it('renders every demo section', () => {
    const wrapper = mount(DemoView)
    const ids = [
      'demo-section-lobby',
      'demo-section-room',
      'demo-section-roles',
      'demo-section-sheriff',
      'demo-section-night-wolf',
      'demo-section-night-seer',
      'demo-section-night-witch',
      'demo-section-night-guard',
      'demo-section-night-waiting',
      'demo-section-day',
      'demo-section-vote',
      'demo-section-end',
    ]
    for (const id of ids) {
      expect(wrapper.find(`[data-testid="${id}"]`).exists()).toBe(true)
    }
  })

  it('roles cycler swaps the displayed role card', async () => {
    const wrapper = mount(DemoView)
    const section = wrapper.find('[data-testid="demo-section-roles"]')
    expect(section.text()).toContain('狼人')
    await section.find('[data-testid="demo-role-cycler-SEER"]').trigger('click')
    expect(section.text()).toContain('预言家')
    await section.find('[data-testid="demo-role-cycler-WITCH"]').trigger('click')
    expect(section.text()).toContain('女巫')
  })

  it('wolf section: confirm starts disabled, enables after target tap', async () => {
    const wrapper = mount(DemoView)
    const section = wrapper.find('[data-testid="demo-section-night-wolf"]')
    const confirm = section.find('[data-testid="wolf-confirm-kill"]')
    expect(confirm.attributes('disabled')).toBeDefined()
    // Pick any non-self target slot (seat 3)
    const target = section.find('[data-seat="3"]')
    expect(target.exists()).toBe(true)
    await target.trigger('click')
    expect(section.find('[data-testid="wolf-confirm-kill"]').attributes('disabled')).toBeUndefined()
  })

  it('wolf section: clicking confirm advances to acted state and shows demo reset button', async () => {
    const wrapper = mount(DemoView)
    const section = wrapper.find('[data-testid="demo-section-night-wolf"]')
    await section.find('[data-seat="3"]').trigger('click')
    await section.find('[data-testid="wolf-confirm-kill"]').trigger('click')
    expect(section.find('[data-testid="demo-reset-night-wolf"]').exists()).toBe(true)
  })

  it('witch section: clicking antidote advances state and shows demo reset', async () => {
    const wrapper = mount(DemoView)
    const section = wrapper.find('[data-testid="demo-section-night-witch"]')
    expect(section.find('[data-testid="witch-antidote"]').exists()).toBe(true)
    await section.find('[data-testid="witch-antidote"]').trigger('click')
    expect(section.find('[data-testid="demo-reset-night-witch"]').exists()).toBe(true)
  })

  it('seer section: pick → check transitions to SEER_RESULT card', async () => {
    const wrapper = mount(DemoView)
    const section = wrapper.find('[data-testid="demo-section-night-seer"]')
    await section.find('[data-seat="2"]').trigger('click')
    await section.find('[data-testid="seer-check"]').trigger('click')
    expect(section.find('[data-testid="seer-result-card"]').exists()).toBe(true)
  })

  it('guard section: confirm enables after pick and advances on click', async () => {
    const wrapper = mount(DemoView)
    const section = wrapper.find('[data-testid="demo-section-night-guard"]')
    await section.find('[data-seat="3"]').trigger('click')
    await section.find('[data-testid="guard-confirm-protect"]').trigger('click')
    expect(section.find('[data-testid="demo-reset-night-guard"]').exists()).toBe(true)
  })

  it('sheriff sub-phase tab swaps SIGNUP → VOTING content', async () => {
    const wrapper = mount(DemoView)
    const section = wrapper.find('[data-testid="demo-section-sheriff"]')
    expect(section.text()).toContain('Sheriff Election')
    await section.find('[data-testid="demo-sheriff-tab-VOTING"]').trigger('click')
    // VOTING phase renders a different chip label and progress info
    expect(section.text()).not.toContain('Sheriff Election')
  })

  it('day sub-phase tab swaps RESULT_HIDDEN → RESULT_REVEALED', async () => {
    const wrapper = mount(DemoView)
    const section = wrapper.find('[data-testid="demo-section-day"]')
    // RESULT_HIDDEN: log FAB hidden
    expect(section.find('.log-fab').exists()).toBe(false)
    await section.find('[data-testid="demo-day-tab-RESULT_REVEALED"]').trigger('click')
    expect(section.find('.log-fab').exists()).toBe(true)
  })
})
