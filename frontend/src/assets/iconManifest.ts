// Icon manifest — semantic-key → image-path map.
// Images live under frontend/public/images/ and are served as static URLs.
// Missing files (404) trigger the @error path on <img> and the consumer
// component falls back to FALLBACK_ICON or DEFAULT_AVATAR.

// Pre-shipped SVG fallbacks ensure the UI never shows a broken image.
// Override these by replacing the files at the paths above with PNGs and
// updating the constants — but the SVGs work as-is on first run.
export const FALLBACK_ICON = '/images/_placeholder.svg'
export const DEFAULT_AVATAR = '/images/_default-avatar.svg'

export const ICON_MANIFEST: Record<string, string> = {
  // ── Roles ──────────────────────────────────────────
  'role-werewolf': '/images/roles/werewolf.png',
  'role-villager': '/images/roles/villager.png',
  'role-seer': '/images/roles/seer.png',
  'role-witch': '/images/roles/witch.png',
  'role-hunter': '/images/roles/hunter.png',
  'role-guard': '/images/roles/guard.png',
  'role-idiot': '/images/roles/idiot.png',

  // ── Phases ─────────────────────────────────────────
  'phase-night': '/images/phases/night.png',
  'phase-day': '/images/phases/day.png',
  'phase-deaths': '/images/phases/deaths.png',

  // ── Status ─────────────────────────────────────────
  'status-sheriff': '/images/status/sheriff.png',
  'status-dead': '/images/status/dead.png',
  'status-peaceful': '/images/status/peaceful.png',
  'status-quit': '/images/status/quit.png',
  'status-speaking': '/images/status/speaking.png',
  'status-waiting': '/images/status/waiting.png',
  'status-good': '/images/status/good.png',
  'status-eliminated': '/images/status/eliminated.png',
  'status-medal': '/images/status/medal.png',

  // ── Outcomes (game-over screen) ────────────────────
  'outcome-wolves-win': '/images/status/wolves-win.png',
  'outcome-village-win': '/images/status/village-win.png',
  'outcome-cancelled': '/images/status/cancelled.png',

  // ── Actions ────────────────────────────────────────
  'action-vote': '/images/actions/vote.png',
  'action-battle': '/images/actions/battle.png',
  'action-music': '/images/actions/music.png',
  'action-shoot': '/images/actions/shoot.png',
  'action-revote': '/images/actions/revote.png',
  'action-list': '/images/actions/list.png',
  'action-locked': '/images/actions/locked.png',
  'action-officer': '/images/actions/officer.png',
}

export type IconName = keyof typeof ICON_MANIFEST
