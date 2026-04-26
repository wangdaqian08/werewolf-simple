/**
 * Unit tests for the E2E browser-error sentinel.
 *
 * The sentinel is exercised by real-backend Playwright runs at integration
 * time, but its logic (filtering, formatting, the assertNoBrowserErrors
 * decision) is pure and easy to verify here so we can iterate on it
 * without spinning up the backend.
 */
import { describe, it, expect, vi } from 'vitest'
import {
  assertNoBrowserErrors,
  resetBrowserErrors,
  type BrowserError,
} from '../../e2e/real/helpers/error-sentinel'

type TestInfoMock = {
  attach: ReturnType<typeof vi.fn>
}

const mkErr = (over: Partial<BrowserError> = {}): BrowserError => ({
  role: 'HOST',
  type: 'pageerror',
  message: 'boom',
  capturedAt: '2026-04-26T00:00:00Z',
  ...over,
})

const mkTestInfo = (): TestInfoMock => ({ attach: vi.fn().mockResolvedValue(undefined) })

describe('error-sentinel', () => {
  it('passes silently when the buffer is empty', async () => {
    const errors: BrowserError[] = []
    const info = mkTestInfo()
    await expect(assertNoBrowserErrors(errors, info as never)).resolves.toBeUndefined()
    expect(info.attach).not.toHaveBeenCalled()
  })

  it('throws and attaches when any pageerror is recorded', async () => {
    const errors: BrowserError[] = [mkErr({ message: 'TypeError: x is undefined' })]
    const info = mkTestInfo()
    await expect(assertNoBrowserErrors(errors, info as never)).rejects.toThrow(
      /TypeError: x is undefined/,
    )
    expect(info.attach).toHaveBeenCalledTimes(1)
    const attachArg = info.attach.mock.calls[0]?.[1]
    expect(String(attachArg?.body)).toContain('pageerror')
    expect(String(attachArg?.body)).toContain('TypeError: x is undefined')
  })

  it('throws on a 5xx response', async () => {
    const errors: BrowserError[] = [
      mkErr({
        type: 'response-5xx',
        url: 'http://localhost:5174/api/game/123/state',
        status: 503,
        message: 'GET http://localhost:5174/api/game/123/state → 503',
      }),
    ]
    const info = mkTestInfo()
    await expect(assertNoBrowserErrors(errors, info as never)).rejects.toThrow(/503/)
    expect(info.attach).toHaveBeenCalledTimes(1)
  })

  it('respects the urlIncludes allow-list', async () => {
    const errors: BrowserError[] = [
      mkErr({
        type: 'response-5xx',
        url: 'http://localhost:5174/api/admin/test-only',
        status: 500,
        message: 'allowed',
      }),
    ]
    const info = mkTestInfo()
    await expect(
      assertNoBrowserErrors(errors, info as never, { urlIncludes: ['/api/admin/'] }),
    ).resolves.toBeUndefined()
    expect(info.attach).not.toHaveBeenCalled()
  })

  it('respects the messageIncludes allow-list', async () => {
    const errors: BrowserError[] = [mkErr({ message: 'expected: known-flake' })]
    const info = mkTestInfo()
    await expect(
      assertNoBrowserErrors(errors, info as never, {
        messageIncludes: ['known-flake'],
      }),
    ).resolves.toBeUndefined()
  })

  it('only filters specifically-allowed errors and still throws on others', async () => {
    const errors: BrowserError[] = [
      mkErr({ message: 'expected: known-flake' }),
      mkErr({ message: 'real bug' }),
    ]
    const info = mkTestInfo()
    await expect(
      assertNoBrowserErrors(errors, info as never, {
        messageIncludes: ['known-flake'],
      }),
    ).rejects.toThrow(/real bug/)
  })

  it('summary lists every error not just the first', async () => {
    const errors: BrowserError[] = [
      mkErr({ role: 'WEREWOLF', message: 'first' }),
      mkErr({ role: 'SEER', message: 'second' }),
    ]
    const info = mkTestInfo()
    await expect(assertNoBrowserErrors(errors, info as never)).rejects.toThrow()
    const body = String(info.attach.mock.calls[0]?.[1]?.body)
    expect(body).toContain('[WEREWOLF]')
    expect(body).toContain('first')
    expect(body).toContain('[SEER]')
    expect(body).toContain('second')
  })

  it('resetBrowserErrors clears the buffer in place', () => {
    const errors: BrowserError[] = [mkErr(), mkErr()]
    const same = errors
    resetBrowserErrors(errors)
    expect(errors.length).toBe(0)
    expect(same).toBe(errors) // mutated in place, not replaced
  })
})
