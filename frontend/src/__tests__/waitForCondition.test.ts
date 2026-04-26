/**
 * Unit tests for the generic effect-poll helper.
 *
 * `waitForCondition` is the foundation for the role-action and vote
 * waits in game-flow.spec.ts. The contract: poll a predicate at fixed
 * intervals up to a deadline; resolve when the predicate returns true;
 * throw a descriptive error on timeout (including the latest predicate
 * error if the predicate itself was throwing).
 */
import { describe, it, expect, vi } from 'vitest'
import { waitForCondition } from '../../e2e/real/helpers/state-polling'

describe('waitForCondition', () => {
  it('resolves immediately when the predicate returns true on first call', async () => {
    const predicate = vi.fn().mockResolvedValue(true)
    await expect(waitForCondition(predicate, 'first-call true', 1_000, 50)).resolves.toBeUndefined()
    expect(predicate).toHaveBeenCalledTimes(1)
  })

  it('polls until the predicate flips to true', async () => {
    let calls = 0
    const predicate = vi.fn().mockImplementation(async () => {
      calls += 1
      return calls >= 3
    })
    await expect(
      waitForCondition(predicate, 'flip after 3 calls', 1_000, 20),
    ).resolves.toBeUndefined()
    expect(calls).toBeGreaterThanOrEqual(3)
  })

  it('throws with the description when the predicate stays false past the deadline', async () => {
    const predicate = vi.fn().mockResolvedValue(false)
    await expect(waitForCondition(predicate, 'never-true predicate', 200, 50)).rejects.toThrow(
      /never-true predicate/,
    )
    // We polled at least once
    expect(predicate.mock.calls.length).toBeGreaterThanOrEqual(1)
  })

  it('includes the last predicate error in the timeout message', async () => {
    const predicate = vi.fn().mockRejectedValue(new Error('backend offline'))
    await expect(waitForCondition(predicate, 'predicate keeps throwing', 200, 50)).rejects.toThrow(
      /backend offline/,
    )
  })

  it('does not abort the poll when the predicate occasionally throws', async () => {
    let calls = 0
    const predicate = vi.fn().mockImplementation(async () => {
      calls += 1
      if (calls === 1) throw new Error('transient')
      if (calls === 2) return false
      return true
    })
    await expect(
      waitForCondition(predicate, 'recovers after a throw', 1_000, 20),
    ).resolves.toBeUndefined()
    expect(calls).toBeGreaterThanOrEqual(3)
  })
})
