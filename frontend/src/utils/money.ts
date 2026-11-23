// Stripe minor units are not always hundredths: zero-decimal currencies store
// whole units, three-decimal currencies store thousandths (per Stripe docs).
const ZERO_DECIMAL_CURRENCIES = new Set([
  'bif',
  'clp',
  'djf',
  'gnf',
  'jpy',
  'kmf',
  'krw',
  'mga',
  'pyg',
  'rwf',
  'ugx',
  'vnd',
  'vuv',
  'xaf',
  'xof',
  'xpf',
])
const THREE_DECIMAL_CURRENCIES = new Set(['bhd', 'jod', 'kwd', 'omr', 'tnd'])

/** A price in Stripe minor units for display: `A$4.99` for AUD, `<amount> <CODE>` otherwise. */
export function formatMoney(amountCents: number, currency: string): string {
  const cur = currency.toLowerCase()
  const amount = ZERO_DECIMAL_CURRENCIES.has(cur)
    ? String(amountCents)
    : THREE_DECIMAL_CURRENCIES.has(cur)
      ? (amountCents / 1000).toFixed(3)
      : (amountCents / 100).toFixed(2)
  return cur === 'aud' ? `A$${amount}` : `${amount} ${cur.toUpperCase()}`
}
