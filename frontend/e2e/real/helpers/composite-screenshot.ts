/**
 * Composite screenshot utility — tiles multiple browser page screenshots
 * into a single image for easy side-by-side comparison.
 *
 * Uses Playwright's own page rendering as an image compositor:
 * creates a temporary HTML page with a CSS grid of base64-encoded screenshots,
 * then captures that page. No external image libraries needed.
 */
import type {Page, TestInfo} from '@playwright/test'

/**
 * Capture a composite screenshot tiling all provided pages into one image.
 *
 * @param pages - Map of label → Page (e.g., "WEREWOLF (seat 3)" → page)
 * @param outputPath - file path to save the composite PNG
 * @returns the path to the saved image
 */
export async function takeCompositeScreenshot(
  pages: Map<string, Page>,
  outputPath: string,
): Promise<string> {
  if (pages.size === 0) return outputPath

  // 1. Take individual screenshots as base64
  const shots: { label: string; b64: string }[] = []
  for (const [label, page] of Array.from(pages.entries())) {
    try {
      const buffer = await page.screenshot({ fullPage: false, timeout: 5_000 })
      shots.push({ label, b64: buffer.toString('base64') })
    } catch {
      // Page might be closed or crashed — skip
      shots.push({ label: `${label} [FAILED]`, b64: '' })
    }
  }

  // 2. Build composite HTML
  const cols = Math.min(shots.length, 3) // max 3 columns
  const imgTags = shots
    .map((s) => {
      if (!s.b64) {
        return `<div class="cell"><div class="label">${s.label}</div><div class="no-img">Screenshot failed</div></div>`
      }
      return `<div class="cell"><div class="label">${s.label}</div><img src="data:image/png;base64,${s.b64}"/></div>`
    })
    .join('')

  const html = `<!DOCTYPE html>
<html><head><style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    background: #1a1a2e;
    display: grid;
    grid-template-columns: repeat(${cols}, 1fr);
    gap: 6px;
    padding: 6px;
    font-family: monospace;
  }
  .cell { position: relative; background: #16213e; border-radius: 4px; overflow: hidden; }
  .label {
    position: absolute; top: 0; left: 0; right: 0;
    background: rgba(0,0,0,0.75); color: #0f0;
    padding: 4px 10px; font-size: 14px; font-weight: bold;
    z-index: 1; text-shadow: 1px 1px 0 #000;
  }
  img { width: 100%; display: block; }
  .no-img { color: #f55; padding: 40px 20px; text-align: center; font-size: 14px; }
</style></head><body>${imgTags}</body></html>`

  // 3. Use a temporary page from the first available context to render
  const firstPage = Array.from(pages.values()).find((p) => !p.isClosed())
  if (!firstPage) return outputPath

  const compositorPage = await firstPage.context().newPage()
  try {
    await compositorPage.setContent(html, { waitUntil: 'load' })
    await compositorPage.screenshot({ path: outputPath, fullPage: true })
  } finally {
    await compositorPage.close()
  }

  return outputPath
}

/**
 * Take a composite screenshot and attach it to the Playwright test report.
 *
 * Call this in test.afterEach when testInfo.status === 'failed'.
 */
export async function attachCompositeOnFailure(
  pages: Map<string, Page>,
  testInfo: TestInfo,
  label = 'composite-all-players',
): Promise<void> {
  if (pages.size === 0) return

  const outputPath = testInfo.outputPath(`${label}.png`)
  await takeCompositeScreenshot(pages, outputPath)
  await testInfo.attach(label, { path: outputPath, contentType: 'image/png' })
}

/**
 * Take a composite screenshot at a milestone and attach to report.
 * Unlike failure-only, this always captures.
 */
export async function captureSnapshot(
  pages: Map<string, Page>,
  testInfo: TestInfo,
  label: string,
): Promise<void> {
  if (pages.size === 0) return

  const outputPath = testInfo.outputPath(`${label}.png`)
  await takeCompositeScreenshot(pages, outputPath)
  await testInfo.attach(label, { path: outputPath, contentType: 'image/png' })
}
