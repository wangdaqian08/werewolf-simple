import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import InstallToHomeScreenPrompt from '@/components/InstallToHomeScreenPrompt.vue'

const IOS_SAFARI_UA =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1'

const CHROME_IOS_UA =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/130.0.0.0 Mobile/15E148 Safari/604.1'

const ORIGINAL_UA = navigator.userAgent
const ORIGINAL_STANDALONE = (navigator as { standalone?: boolean }).standalone
const ORIGINAL_MATCH_MEDIA = window.matchMedia

function stubUa(ua: string) {
  Object.defineProperty(navigator, 'userAgent', { value: ua, configurable: true })
}

function stubStandalone(value: boolean | undefined) {
  Object.defineProperty(navigator, 'standalone', { value, configurable: true, writable: true })
}

function stubMatchMedia(matches: boolean) {
  window.matchMedia = (query: string) =>
    ({
      matches: query === '(display-mode: standalone)' ? matches : false,
      media: query,
      addEventListener: () => {},
      removeEventListener: () => {},
    }) as unknown as MediaQueryList
}

beforeEach(() => {
  localStorage.clear()
  // Default to iOS Safari, non-standalone, no dismissal.
  stubUa(IOS_SAFARI_UA)
  stubStandalone(undefined)
  stubMatchMedia(false)
})

afterEach(() => {
  Object.defineProperty(navigator, 'userAgent', { value: ORIGINAL_UA, configurable: true })
  Object.defineProperty(navigator, 'standalone', {
    value: ORIGINAL_STANDALONE,
    configurable: true,
    writable: true,
  })
  window.matchMedia = ORIGINAL_MATCH_MEDIA
  localStorage.clear()
})

describe('InstallToHomeScreenPrompt', () => {
  it('is hidden when UA is Chrome iOS (CriOS)', () => {
    stubUa(CHROME_IOS_UA)
    const wrapper = mount(InstallToHomeScreenPrompt)
    expect(wrapper.find('[data-testid="install-prompt"]').exists()).toBe(false)
  })

  it('is hidden when navigator.standalone is true', () => {
    stubStandalone(true)
    const wrapper = mount(InstallToHomeScreenPrompt)
    expect(wrapper.find('[data-testid="install-prompt"]').exists()).toBe(false)
  })

  it('is hidden when matchMedia display-mode standalone matches', () => {
    stubMatchMedia(true)
    const wrapper = mount(InstallToHomeScreenPrompt)
    expect(wrapper.find('[data-testid="install-prompt"]').exists()).toBe(false)
  })

  it('is hidden when localStorage pwa-install-dismissed is true', () => {
    localStorage.setItem('pwa-install-dismissed', 'true')
    const wrapper = mount(InstallToHomeScreenPrompt)
    expect(wrapper.find('[data-testid="install-prompt"]').exists()).toBe(false)
  })

  it('is visible when iOS Safari, non-standalone, no dismissal', () => {
    const wrapper = mount(InstallToHomeScreenPrompt)
    expect(wrapper.find('[data-testid="install-prompt"]').exists()).toBe(true)
  })

  it('click never-show sets localStorage and removes prompt from DOM', async () => {
    const wrapper = mount(InstallToHomeScreenPrompt)
    expect(wrapper.find('[data-testid="install-prompt"]').exists()).toBe(true)
    await wrapper.find('[data-testid="install-prompt-never"]').trigger('click')
    await nextTick()
    expect(localStorage.getItem('pwa-install-dismissed')).toBe('true')
    expect(wrapper.find('[data-testid="install-prompt"]').exists()).toBe(false)
  })

  it('click dismiss removes prompt but does not set localStorage', async () => {
    const wrapper = mount(InstallToHomeScreenPrompt)
    expect(wrapper.find('[data-testid="install-prompt"]').exists()).toBe(true)
    await wrapper.find('[data-testid="install-prompt-dismiss"]').trigger('click')
    await nextTick()
    expect(localStorage.getItem('pwa-install-dismissed')).toBeNull()
    expect(wrapper.find('[data-testid="install-prompt"]').exists()).toBe(false)
  })
})
