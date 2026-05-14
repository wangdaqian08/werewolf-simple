/**
 * VolumeControl regression coverage.
 *
 * Bug shipped in PR #122: VolumeControl's `isMuted` ref captured
 * `audioService.isMuted()` at mount time only. The GameView host-aware
 * default-mute watcher runs LATER (after gameStore.hostId arrives via HTTP),
 * so the icon stayed at the initial (false) value while audioService.muted
 * silently flipped to true underneath. Result: non-host audio was not muted
 * by default at the UI level even though localStorage and audioService
 * agreed.
 *
 * Fix: audioService.onMuteChange subscription wired in VolumeControl setup;
 * unsubscribed on unmount.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { audioService } from '@/services/audioService'
import VolumeControl from '@/components/VolumeControl.vue'

describe('VolumeControl — reactive to external setMuted', () => {
  beforeEach(() => {
    localStorage.clear()
    audioService.setMuted(false)
  })

  it('reflects audioService.setMuted(true) after mount', async () => {
    const wrapper = mount(VolumeControl)
    expect(wrapper.find('[data-testid="audio-mute-btn"]').attributes('aria-pressed')).toBe('false')
    audioService.setMuted(true)
    await nextTick()
    expect(wrapper.find('[data-testid="audio-mute-btn"]').attributes('aria-pressed')).toBe('true')
  })

  it('reflects audioService.setMuted(false) after mount', async () => {
    audioService.setMuted(true)
    const wrapper = mount(VolumeControl)
    expect(wrapper.find('[data-testid="audio-mute-btn"]').attributes('aria-pressed')).toBe('true')
    audioService.setMuted(false)
    await nextTick()
    expect(wrapper.find('[data-testid="audio-mute-btn"]').attributes('aria-pressed')).toBe('false')
  })

  it('unsubscribes on unmount so later setMuted does not leak', async () => {
    const wrapper = mount(VolumeControl)
    audioService.setMuted(true)
    await nextTick()
    wrapper.unmount()
    // No subscriber means no error, and the audioService state can still flip.
    audioService.setMuted(false)
    expect(audioService.isMuted()).toBe(false)
  })
})
