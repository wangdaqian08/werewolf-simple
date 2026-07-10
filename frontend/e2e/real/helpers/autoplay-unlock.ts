import type { Page } from '@playwright/test'

/**
 * Shared pieces for the two audio-autoplay-unlock specs (Chromium +
 * Firefox halves live in separate files because browserName/headless/
 * launchOptions are worker-scoped and cannot vary per describe group).
 */

export const NIGHT1_SEQUENCE = [
  'goes_dark_close_eyes.mp3',
  'wolf_howl.mp3',
  'wolf_open_eyes.mp3',
  'wolf_close_eyes.mp3',
]

export interface SvcSnapshot {
  userInteracted: boolean
  queueLen: number
  isPlayingQueue: boolean
  elements: Array<{ file: string; paused: boolean; currentTime: number }>
}

export function snapshot(page: Page): Promise<SvcSnapshot> {
  return page.evaluate(() => {
    const svc: any = (window as any).__audioService
    const cache: Map<string, HTMLAudioElement> = svc.audioCache
    return {
      userInteracted: !!svc.userInteracted,
      queueLen: svc.audioQueue.length,
      isPlayingQueue: !!svc.isPlayingQueue,
      elements: [...cache.entries()].map(([file, el]) => ({
        file,
        paused: el.paused,
        currentTime: el.currentTime,
      })),
    }
  })
}

/**
 * Trigger playback WITHOUT page.evaluate (evaluate carries a CDP/juggler
 * user gesture that grants sticky activation): an init script fires
 * playSequential a moment after load, exactly like a STOMP push landing on
 * an untouched phone. mode=flagged additionally dispatches an untrusted
 * click first so the service's userInteracted flag is true while the
 * browser still has no gesture.
 */
export async function installTrigger(page: Page, mode: 'pristine' | 'flagged'): Promise<void> {
  await page.addInitScript(
    ({ files, m }: { files: string[]; m: string }) => {
      const wantsRepro = location.hash.includes('unlock-repro')
      const run = () => {
        if (!wantsRepro) return
        setTimeout(async () => {
          try {
            await import(/* @vite-ignore */ '/src/services/audioService.ts' as string)
            if (m === 'flagged') {
              document.dispatchEvent(new MouseEvent('click', { bubbles: true }))
            }
            ;(window as any).__audioService.playSequential(files)
            console.log('[unlock-trigger] playSequential fired')
          } catch (e) {
            console.log(`[unlock-trigger] ERROR ${(e as Error).message}`)
          }
        }, 2_000)
      }
      if (document.readyState === 'complete') run()
      else window.addEventListener('load', run)
    },
    { files: NIGHT1_SEQUENCE, m: mode },
  )
}

export const countIn = (lines: string[], needle: string, from = 0) =>
  lines.slice(from).filter((l) => l.includes(needle)).length
