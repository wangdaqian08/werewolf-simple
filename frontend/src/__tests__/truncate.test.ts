import { describe, expect, it } from 'vitest'
import { truncateNickname } from '@/utils/truncate'

describe('truncateNickname', () => {
  it('returns the input unchanged when at or under max length', () => {
    expect(truncateNickname('Daniel', 16)).toBe('Daniel')
    expect(truncateNickname('Daniel Wang', 16)).toBe('Daniel Wang')
    expect(truncateNickname('exactly16chars!!', 16)).toBe('exactly16chars!!')
  })

  it('appends … and truncates when over max length', () => {
    expect(truncateNickname('SuperLongNicknameAlpha', 16)).toBe('SuperLongNicknam…')
  })

  it('handles CJK without splitting characters', () => {
    expect(truncateNickname('王大谦', 16)).toBe('王大谦')
    expect(truncateNickname('狼人杀玩家一二三四五六七八九十一二三', 8)).toBe('狼人杀玩家一二三…')
  })

  it('handles surrogate-pair emojis without splitting', () => {
    // Each emoji is 2 UTF-16 code units; Array.from splits by code point.
    expect(truncateNickname('🐺🐺🐺🐺🐺', 3)).toBe('🐺🐺🐺…')
  })

  it('returns empty string when input is empty', () => {
    expect(truncateNickname('', 16)).toBe('')
  })

  it('defaults max to 16 when not provided', () => {
    expect(truncateNickname('a'.repeat(20))).toBe('aaaaaaaaaaaaaaaa…')
  })
})
