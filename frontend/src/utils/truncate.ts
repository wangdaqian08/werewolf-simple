/**
 * Truncate a nickname for display in tight UI spots (player tile,
 * vote-row, etc.) without splitting CJK characters or surrogate-pair
 * emojis. CSS ellipsis remains the safety net at the layout level —
 * this helper guarantees a sane character cap before CSS clipping.
 */
export function truncateNickname(input: string, max = 16): string {
  if (!input) return ''
  // Array.from iterates by code point, not UTF-16 unit, so emoji
  // surrogate pairs (e.g. 🐺 = U+1F43A which is two UTF-16 units) are
  // counted as one character.
  const chars = Array.from(input)
  if (chars.length <= max) return input
  return chars.slice(0, max).join('') + '…'
}
