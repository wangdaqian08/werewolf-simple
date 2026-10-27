import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import en from '@/i18n/locales/en'
import zh from '@/i18n/locales/zh'

type Tree = { [k: string]: string | Tree }

function flatten(tree: Tree, prefix = ''): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(tree)) {
    const key = prefix ? `${prefix}.${k}` : k
    if (typeof v === 'string') out[key] = v
    else Object.assign(out, flatten(v, key))
  }
  return out
}

const placeholders = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1] as string).sort()

describe('i18n catalogs', () => {
  const E = flatten(en as Tree)
  const Z = flatten(zh as Tree)

  it('English and Traditional Chinese define exactly the same keys', () => {
    expect(Object.keys(Z).sort()).toEqual(Object.keys(E).sort())
  })

  it('no message is empty', () => {
    for (const [k, v] of [...Object.entries(E), ...Object.entries(Z)]) {
      expect(v.trim(), k).not.toBe('')
    }
  })

  it('a language may drop a {placeholder} its grammar does not need, never invent one', () => {
    for (const k of Object.keys(E)) {
      const [a, b] = [placeholders(E[k] ?? ''), placeholders(Z[k] ?? '')]
      const nested = a.every((p) => b.includes(p)) || b.every((p) => a.includes(p))
      expect(nested, `${k}: ${a} vs ${b}`).toBe(true)
    }
  })

  it('the Chinese file is Traditional, not Simplified', () => {
    // A few characters whose Simplified and Traditional forms differ.
    const simplified = /[这个们说话时间对战开关见门]|[号让从为]/
    expect(Object.values(Z).filter((v) => simplified.test(v))).toEqual([])
  })

  it('every literal message key used in the source exists in the catalog', () => {
    const roots = Object.keys(en)
    const keyLiteral = new RegExp(`['\`]((?:${roots.join('|')})\\.[A-Za-z0-9_.]+)['\`]`, 'g')
    const missing: string[] = []
    const walk = (dir: string) => {
      for (const name of readdirSync(dir)) {
        const path = join(dir, name)
        if (statSync(path).isDirectory()) {
          if (!['__tests__', 'i18n', 'mocks'].includes(name)) walk(path)
        } else if (/\.(vue|ts)$/.test(name)) {
          for (const m of readFileSync(path, 'utf8').matchAll(keyLiteral)) {
            const key = m[1] as string
            if (!(key in E)) missing.push(`${path}: ${key}`)
          }
        }
      }
    }
    walk(join(__dirname, '..'))
    expect(missing).toEqual([])
  })

  it('has a name and a description for every role', () => {
    for (const id of [
      'WEREWOLF',
      'VILLAGER',
      'SEER',
      'WITCH',
      'HUNTER',
      'GUARD',
      'IDIOT',
      'WHITE_WOLF_KING',
      'BLACK_WOLF_KING',
    ]) {
      expect(E[`role.${id}`], id).toBeTruthy()
      expect(E[`role.desc.${id}`], id).toBeTruthy()
    }
  })
})
