/**
 * AudioUnlockBanner — the "silent night" affordance.
 *
 * Reproduced on a real iPhone (game 94, 2026-08-22): the host's tab was
 * reloaded mid-game, so the fresh document had no user gesture. Every one of
 * night 1's eight narration cues was blocked, and BGM never even attempted to
 * start (`startBgm` bails on `!userInteracted` before calling play()). Nothing
 * on screen said so. Worse than a delay: a parked backlog is REPLACED by the
 * next sequence rather than stacked, so the night's cues were discarded — after
 * finally tapping, the host heard only the daybreak pair.
 *
 * A host narrating a night has no reason to tap anything, which is precisely
 * why the silence lasted a whole night and then "fixed itself" the next day.
 * This banner is the missing signal that one tap is all it takes.
 *
 * Each test re-imports the service so `userInteracted` starts false — the
 * singleton latches it to true for good on the first gesture.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import type { Component } from 'vue'

const SEL = '[data-testid="audio-unlock-banner"]'

let audioService: typeof import('@/services/audioService').audioService
let AudioUnlockBanner: Component

/** A real document-level click — the capture listener the service registers. */
function gesture(): void {
  document.dispatchEvent(new MouseEvent('click', { bubbles: true }))
}

describe('AudioUnlockBanner', () => {
  beforeEach(async () => {
    localStorage.clear()
    vi.resetModules()
    audioService = (await import('@/services/audioService')).audioService
    AudioUnlockBanner = (await import('@/components/AudioUnlockBanner.vue')).default
  })

  it('stays hidden while nothing is waiting on a gesture', () => {
    const w = mount(AudioUnlockBanner)
    expect(w.find(SEL).exists()).toBe(false)
  })

  it('appears when narration parks before the first gesture', async () => {
    const w = mount(AudioUnlockBanner)
    expect(w.find(SEL).exists()).toBe(false)

    audioService.playSequential(['goes_dark_close_eyes.mp3', 'wolf_howl.mp3'])
    await nextTick()

    expect(w.find(SEL).exists()).toBe(true)
  })

  it('appears when a BGM start is deferred before the first gesture', async () => {
    const w = mount(AudioUnlockBanner)

    audioService.startBgm('suspicion.mp3')
    await nextTick()

    expect(w.find(SEL).exists()).toBe(true)
  })

  it('disappears once a user gesture releases the parked audio', async () => {
    const w = mount(AudioUnlockBanner)
    audioService.playSequential(['goes_dark_close_eyes.mp3'])
    await nextTick()
    expect(w.find(SEL).exists()).toBe(true)

    gesture()
    await nextTick()

    expect(w.find(SEL).exists()).toBe(false)
    expect(audioService.isAudioBlocked()).toBe(false)
  })

  it('mounts already-visible when audio was blocked before it rendered', async () => {
    // Ordering guard: the banner is inside GameView, which mounts after the
    // STOMP cue can already have arrived and parked.
    audioService.playSequential(['wolf_howl.mp3'])
    const w = mount(AudioUnlockBanner)
    await nextTick()

    expect(w.find(SEL).exists()).toBe(true)
  })

  it('unsubscribes on unmount so later state changes do not leak', async () => {
    const w = mount(AudioUnlockBanner)
    audioService.playSequential(['wolf_howl.mp3'])
    await nextTick()
    expect(w.find(SEL).exists()).toBe(true)

    w.unmount()
    gesture()

    expect(audioService.isAudioBlocked()).toBe(false)
  })
})
