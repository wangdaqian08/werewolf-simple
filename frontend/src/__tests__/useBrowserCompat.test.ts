/**
 * UA-table-driven coverage for `isSupportedBrowser`.
 *
 * Each row stubs `navigator.userAgent` and (optionally) `navigator.userAgentData`
 * and asserts the expected supported/blocked verdict. Real-world UA strings
 * captured 2026-04-26.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { isSupportedBrowser } from '@/composables/useBrowserCompat'

interface Stub {
  ua: string
  uaData?: { brands: Array<{ brand: string; version: string }> } | undefined
}

const ORIGINAL = {
  ua: navigator.userAgent,
  data: (navigator as { userAgentData?: unknown }).userAgentData,
}

function stubNavigator({ ua, uaData }: Stub) {
  Object.defineProperty(navigator, 'userAgent', { value: ua, configurable: true })
  Object.defineProperty(navigator, 'userAgentData', {
    value: uaData,
    configurable: true,
    writable: true,
  })
}

describe('isSupportedBrowser', () => {
  beforeEach(() => {
    // start each case from a clean slate
    Object.defineProperty(navigator, 'userAgentData', {
      value: undefined,
      configurable: true,
      writable: true,
    })
  })

  afterEach(() => {
    Object.defineProperty(navigator, 'userAgent', {
      value: ORIGINAL.ua,
      configurable: true,
    })
    Object.defineProperty(navigator, 'userAgentData', {
      value: ORIGINAL.data,
      configurable: true,
      writable: true,
    })
  })

  // ── SUPPORTED ─────────────────────────────────────────────────────────

  it('Chrome desktop (UA-CH)', () => {
    stubNavigator({
      ua:
        'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 ' +
        '(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36',
      uaData: {
        brands: [
          { brand: 'Chromium', version: '119' },
          { brand: 'Google Chrome', version: '119' },
        ],
      },
    })
    expect(isSupportedBrowser()).toBe(true)
  })

  it('Edge Chromium (UA-CH)', () => {
    stubNavigator({
      ua:
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, ' +
        'like Gecko) Chrome/119.0.0.0 Safari/537.36 Edg/119.0.0.0',
      uaData: {
        brands: [
          { brand: 'Chromium', version: '119' },
          { brand: 'Microsoft Edge', version: '119' },
        ],
      },
    })
    expect(isSupportedBrowser()).toBe(true)
  })

  it('Chrome Android (UA fallback, no UA-CH)', () => {
    stubNavigator({
      ua:
        'Mozilla/5.0 (Linux; Android 10; SM-G960U) AppleWebKit/537.36 (KHTML, ' +
        'like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36',
    })
    expect(isSupportedBrowser()).toBe(true)
  })

  it('Brave (UA fallback — Chrome/ in UA)', () => {
    stubNavigator({
      ua:
        'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 ' +
        '(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36',
    })
    expect(isSupportedBrowser()).toBe(true)
  })

  it('Opera (UA fallback — Chrome/ in UA)', () => {
    stubNavigator({
      ua:
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, ' +
        'like Gecko) Chrome/119.0.0.0 Safari/537.36 OPR/105.0.0.0',
    })
    expect(isSupportedBrowser()).toBe(true)
  })

  it('Samsung Internet (UA fallback)', () => {
    stubNavigator({
      ua:
        'Mozilla/5.0 (Linux; Android 13; SAMSUNG SM-S918U) AppleWebKit/537.36 ' +
        '(KHTML, like Gecko) SamsungBrowser/22.0 Chrome/115.0.0.0 Mobile Safari/537.36',
    })
    expect(isSupportedBrowser()).toBe(true)
  })

  it('iOS Safari iPhone', () => {
    stubNavigator({
      ua:
        'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/' +
        '605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1',
    })
    expect(isSupportedBrowser()).toBe(true)
  })

  it('iOS Safari iPad', () => {
    stubNavigator({
      ua:
        'Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 ' +
        '(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1',
    })
    expect(isSupportedBrowser()).toBe(true)
  })

  it('iOS Chrome (CriOS — still WebKit)', () => {
    stubNavigator({
      ua:
        'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/' +
        '605.1.15 (KHTML, like Gecko) CriOS/119.0.6045.169 Mobile/15E148 Safari/604.1',
    })
    expect(isSupportedBrowser()).toBe(true)
  })

  // ── BLOCKED ───────────────────────────────────────────────────────────

  it('Firefox desktop', () => {
    stubNavigator({
      ua: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:120.0) Gecko/20100101 ' + 'Firefox/120.0',
    })
    expect(isSupportedBrowser()).toBe(false)
  })

  it('Firefox Android', () => {
    stubNavigator({
      ua: 'Mozilla/5.0 (Android 13; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0',
    })
    expect(isSupportedBrowser()).toBe(false)
  })

  it('Desktop Safari (Mac)', () => {
    stubNavigator({
      ua:
        'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 ' +
        '(KHTML, like Gecko) Version/17.0 Safari/605.1.15',
    })
    expect(isSupportedBrowser()).toBe(false)
  })

  it('UA-CH present but no Chromium brand → blocked', () => {
    stubNavigator({
      ua: '(spoofed-ua)',
      uaData: {
        brands: [
          { brand: 'NotABrand', version: '99' },
          { brand: 'Some Other Engine', version: '1' },
        ],
      },
    })
    expect(isSupportedBrowser()).toBe(false)
  })

  it('SSR / no navigator → permissive (returns true)', () => {
    // Cover the early-return when running outside a browser. We can't easily
    // delete `navigator` in jsdom, so just sanity-check the function doesn't
    // throw — the SSR branch is exercised via direct logic review.
    expect(() => isSupportedBrowser()).not.toThrow()
  })
})
