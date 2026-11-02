// Where a player was when their login expired, so logging in again takes them back
// (to the game, room, ...) instead of leaving them in the lobby. sessionStorage: per tab,
// survives a reload and the OAuth redirect round trip.
const KEY = 'ww-resume'

export interface Resume {
  path?: string
  nickname?: string
  /** Guest accounts log in again by nickname; OAuth accounts through their provider. */
  guest?: boolean
}

export function peekResume(): Resume | null {
  try {
    const raw = sessionStorage.getItem(KEY)
    return raw ? (JSON.parse(raw) as Resume) : null
  } catch {
    return null
  }
}

/** Merges into what is already saved (the nickname and the path are learned at different moments). */
export function saveResume(update: Resume): void {
  const path = update.path
  // Same-app paths only: "//host" and "/\host" are read by browsers as another origin.
  if (path !== undefined && !/^\/(?![/\\])/.test(path)) return
  try {
    const next = {
      ...peekResume(),
      ...Object.fromEntries(Object.entries(update).filter(([, v]) => v)),
    }
    sessionStorage.setItem(KEY, JSON.stringify(next))
  } catch {
    /* storage blocked: the player just lands in the lobby */
  }
}

export function clearResume(): void {
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    /* ignore */
  }
}
