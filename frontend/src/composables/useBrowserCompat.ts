/**
 * Browser compatibility detection.
 *
 * Allowed list:
 *   - Chromium-based browsers (Chrome, Edge, Brave, Opera, Samsung Internet, …)
 *   - iOS Safari and any iOS browser (all forced through WebKit by Apple, so
 *     they all behave the same — we'd rather have iOS users than not).
 *
 * Blocked list:
 *   - Desktop Safari, Firefox, legacy IE, anything else.
 *
 * Detection precedence:
 *   1. UA Client Hints (`navigator.userAgentData`) — modern Chromium only.
 *      Decisive: if present and brand list mentions any Chromium variant,
 *      allow; if present but no Chromium brand (rare, theoretical), block.
 *   2. iOS WebKit by `userAgent` (covers Safari, CriOS, FxiOS, EdgiOS).
 *   3. UA-string fallback for Chromium-based browsers that don't ship UA-CH.
 *   4. Otherwise blocked.
 *
 * Pure client-side defense — not a security boundary. Goal is to avoid users
 * landing on a UI that doesn't work for them, not to enforce policy.
 */

interface UserAgentBrand {
  brand: string
  version: string
}

interface UserAgentData {
  brands?: UserAgentBrand[]
}

export function isSupportedBrowser(): boolean {
  if (typeof navigator === 'undefined') return true // SSR / non-browser env

  const ua = navigator.userAgent ?? ''

  // 1. UA Client Hints — present on modern Chromium, absent on Firefox/Safari.
  const uaData = (navigator as { userAgentData?: UserAgentData }).userAgentData
  if (uaData?.brands && uaData.brands.length > 0) {
    const brandStr = uaData.brands.map((b) => String(b.brand ?? '').toLowerCase()).join('|')
    return /chromium|chrome|edge|brave|opera/.test(brandStr)
  }

  // 2. iOS — every browser is WebKit; allow them all.
  if (/iPhone|iPad|iPod/.test(ua) && /AppleWebKit/.test(ua)) return true

  // 3. UA-string fallback for Chromium variants without UA-CH (older Android
  //    Chrome, custom WebViews). Edg/ catches Edge Chromium; Chrome/ catches
  //    Chrome, Brave, Opera, Samsung Internet, etc.
  if (/Chrome\/\d+/.test(ua) || /Edg\//.test(ua)) return true

  // 4. Firefox, desktop Safari, anything else.
  return false
}
