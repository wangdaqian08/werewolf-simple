import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import LanguageToggle from '@/components/LanguageToggle.vue'
import ShareButton from '@/components/ShareButton.vue'
import { LOCALE_STORAGE_KEY, getLocale, i18n, setLocale } from '@/i18n'
import { useGameStore } from '@/stores/gameStore'
import type { GameState } from '@/types'

const routeName = { value: 'lobby' }
vi.mock('vue-router', () => ({ useRoute: () => ({ name: routeName.value }) }))

function stateIn(phase: GameState['phase']): GameState {
  return { gameId: 'g', phase, dayNumber: 1, players: [], events: [] } as unknown as GameState
}

describe('LanguageToggle', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    setLocale('zh')
    routeName.value = 'lobby'
  })

  it('shows the Chinese flag in Chinese and flips to English on click', async () => {
    const wrapper = mount(LanguageToggle)
    expect(wrapper.find('[data-testid="flag-cn"]').exists()).toBe(true)
    await wrapper.find('[data-testid="lang-toggle"]').trigger('click')
    expect(getLocale()).toBe('en')
    expect(wrapper.find('[data-testid="flag-en"]').exists()).toBe(true)
    await wrapper.find('[data-testid="lang-toggle"]').trigger('click')
    expect(getLocale()).toBe('zh')
  })

  it('persists the choice and updates <html lang>', async () => {
    const wrapper = mount(LanguageToggle)
    await wrapper.find('[data-testid="lang-toggle"]').trigger('click')
    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('en')
    expect(document.documentElement.lang).toBe('en')
    setLocale('zh')
    expect(document.documentElement.lang).toBe('zh-Hant')
  })

  it('re-renders already-mounted text when the language changes', async () => {
    const wrapper = mount(ShareButton, { props: { roomCode: '123' } })
    expect(wrapper.text()).toContain('邀請')
    setLocale('en')
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('Share')
    expect(wrapper.text()).not.toContain('邀請')
  })

  it('is hidden during the night phase and back once it is day', async () => {
    const game = useGameStore()
    game.setState(stateIn('DAY_DISCUSSION'))
    const wrapper = mount(LanguageToggle)
    expect(wrapper.find('[data-testid="lang-toggle"]').exists()).toBe(true)

    game.setState(stateIn('NIGHT'))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="lang-toggle"]').exists()).toBe(false)

    game.setState(stateIn('DAY_DISCUSSION'))
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-testid="lang-toggle"]').exists()).toBe(true)
  })

  it('keeps the chosen language through the night even though the toggle is hidden', async () => {
    const game = useGameStore()
    setLocale('en')
    game.setState(stateIn('NIGHT'))
    mount(LanguageToggle)
    expect(i18n.global.locale.value).toBe('en')
  })

  it('in-game day screens use the header toggle, not the floating one', async () => {
    routeName.value = 'game'
    const game = useGameStore()
    game.setState(stateIn('DAY_DISCUSSION'))
    const floating = mount(LanguageToggle)
    const inline = mount(LanguageToggle, { props: { inline: true } })
    expect(floating.find('[data-testid="lang-toggle"]').exists()).toBe(false)
    expect(inline.find('.lang-toggle-inline').exists()).toBe(true)

    game.setState(stateIn('DAY_VOTING'))
    await floating.vm.$nextTick()
    expect(floating.find('[data-testid="lang-toggle"]').exists()).toBe(false)

    // Role reveal / sheriff election have no header slot: the floating one shows.
    game.setState(stateIn('SHERIFF_ELECTION'))
    await floating.vm.$nextTick()
    expect(floating.find('[data-testid="lang-toggle"]').exists()).toBe(true)
  })

  it('the header toggle is hidden at night too', async () => {
    routeName.value = 'game'
    useGameStore().setState(stateIn('NIGHT'))
    const inline = mount(LanguageToggle, { props: { inline: true } })
    expect(inline.find('[data-testid="lang-toggle"]').exists()).toBe(false)
  })
})
