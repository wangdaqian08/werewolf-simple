/**
 * Unit tests for ActionLogDrawer.vue
 *
 * Covers:
 * - SELF_DESTRUCT log entry is rendered as "X号 · nickname 自爆"
 * - The 💥 自爆 section appears with tag-red styling
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ActionLogDrawer from '@/components/ActionLogDrawer.vue'
import { gameService } from '@/services/gameService'
import type { ActionLogEntry } from '@/types'

vi.mock('@/services/gameService', () => ({
  gameService: {
    getActionLog: vi.fn(),
  },
}))

function makeEntry(
  eventType: ActionLogEntry['eventType'],
  payload: Record<string, unknown>,
): ActionLogEntry {
  return {
    id: 1,
    eventType,
    message: JSON.stringify(payload),
    targetUserId: null,
    createdAt: '2026-01-01T00:00:00Z',
  }
}

describe('ActionLogDrawer — SELF_DESTRUCT entry', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('renders a SELF_DESTRUCT entry as "X号 · nickname 自爆"', async () => {
    vi.mocked(gameService.getActionLog).mockResolvedValue([
      makeEntry('SELF_DESTRUCT', {
        dayNumber: 2,
        userId: 'u-wolf',
        nickname: 'Eric',
        seatIndex: 5,
      }),
    ])

    // Mount closed first so the watch fires when we set open=true
    const wrapper = mount(ActionLogDrawer, {
      props: { gameId: 42, open: false },
      attachTo: document.body,
    })

    await wrapper.setProps({ open: true })

    // Wait for the async fetch to resolve
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.vm.$nextTick()

    // The section title should appear
    expect(document.body.textContent).toContain('💥 自爆')

    // Seat badge
    expect(document.body.textContent).toContain('5号')

    // Nickname
    expect(document.body.textContent).toContain('Eric')

    // Tag label
    expect(document.body.textContent).toContain('自爆')

    wrapper.unmount()
  })

  it('does not render the 💥 自爆 section when there are no self-destruct entries', async () => {
    vi.mocked(gameService.getActionLog).mockResolvedValue([
      makeEntry('NIGHT_DEATH', {
        dayNumber: 2,
        userId: 'u1',
        nickname: 'Alice',
        seatIndex: 1,
      }),
    ])

    const wrapper = mount(ActionLogDrawer, {
      props: { gameId: 42, open: false },
      attachTo: document.body,
    })

    await wrapper.setProps({ open: true })
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.vm.$nextTick()

    expect(document.body.textContent).not.toContain('💥 自爆')

    wrapper.unmount()
  })
})
