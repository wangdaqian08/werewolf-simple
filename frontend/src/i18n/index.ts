import { createI18n } from 'vue-i18n'
import en from './locales/en'
import zh from './locales/zh'

// 'zh' is Traditional Chinese (zh-Hant); 'en' is English.
export type Locale = 'zh' | 'en'

export const LOCALE_STORAGE_KEY = 'ww-locale'
const HTML_LANG: Record<Locale, string> = { zh: 'zh-Hant', en: 'en' }

function readStoredLocale(): Locale | null {
  try {
    const v = localStorage.getItem(LOCALE_STORAGE_KEY)
    return v === 'zh' || v === 'en' ? v : null
  } catch {
    return null
  }
}

function detectLocale(): Locale {
  const stored = readStoredLocale()
  if (stored) return stored
  const nav = typeof navigator !== 'undefined' ? navigator.language : ''
  return nav.toLowerCase().startsWith('zh') ? 'zh' : 'en'
}

export const i18n = createI18n({
  legacy: false,
  locale: detectLocale(),
  fallbackLocale: 'en',
  messages: { en, zh },
})

/** Translate outside a component (stores, utils). */
export const t = i18n.global.t

export function getLocale(): Locale {
  return i18n.global.locale.value as Locale
}

// Some test environments stub `document` without an element; the lang attribute is cosmetic.
function setHtmlLang(locale: Locale) {
  const el = typeof document !== 'undefined' ? document.documentElement : undefined
  if (el) el.lang = HTML_LANG[locale]
}

export function setLocale(locale: Locale) {
  i18n.global.locale.value = locale
  try {
    localStorage.setItem(LOCALE_STORAGE_KEY, locale)
  } catch {
    /* storage unavailable (private mode) — the choice just won't persist */
  }
  setHtmlLang(locale)
}

setHtmlLang(getLocale())
