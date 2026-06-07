import { describe, it, expect } from 'vitest'
import { pickAppVersion } from '@/utils/appVersion'

// The badge version is resolved at build time with this precedence:
//   1. an injected APP_VERSION (set by the build pipeline / Docker build-arg),
//   2. otherwise the local `git describe` result (plain `npm` builds in a checkout),
//   3. otherwise the literal 'dev'.
// This keeps it dynamic everywhere: containers (which have no .git) get the
// injected value; local dev reads git directly. Nothing is hardcoded.
describe('pickAppVersion', () => {
  it('prefers a non-empty injected APP_VERSION over git', () => {
    expect(pickAppVersion('v9.9.9', 'v0.8.0')).toBe('v9.9.9')
  })

  it('falls back to the git value when APP_VERSION is unset or blank', () => {
    expect(pickAppVersion(undefined, 'v0.8.0')).toBe('v0.8.0')
    expect(pickAppVersion('   ', 'v0.8.0')).toBe('v0.8.0')
  })

  it("falls back to 'dev' when neither source is available", () => {
    expect(pickAppVersion(undefined, undefined)).toBe('dev')
    expect(pickAppVersion('', '')).toBe('dev')
  })

  it('trims surrounding whitespace from the resolved value', () => {
    expect(pickAppVersion(' v1.2.3 ', undefined)).toBe('v1.2.3')
    expect(pickAppVersion(undefined, '  v0.8.0\n')).toBe('v0.8.0')
  })
})
