// L2 console patcher for the prod-smoke-test skill.
//
// Paste the IIFE below into chrome-devtools-mcp `evaluate_script` AFTER navigating
// to /game/<id>. It installs a 500-entry ring buffer at `window.__appLog` that
// captures audio-service events, STOMP events, console errors, and unhandled
// promise rejections. Filter regex below intentionally broad — we want signal
// for every potential failure mode.
//
// To dump: evaluate_script returning `window.__appLog`.
// To clear: evaluate_script `() => { window.__appLog = []; }`.
//
// Re-running the patcher is idempotent — protected by `window.__appPatched`.

(() => {
  if (window.__appPatched) return { already: true, count: (window.__appLog || []).length }
  window.__appLog = []
  const KEEP = /AudioService|useAudioService|audioSequence|mp3|stomp|STOMP|websocket|WebSocket|disconnect|reconnect|Error|Exception|stomp\.js|frame|onUnhandledRejection|err/i
  const RING = 500
  const push = (level, args) => {
    try {
      const s = args
        .map((a) => {
          if (a instanceof Error) return a.stack || a.message
          if (typeof a === 'string') return a
          try {
            return JSON.stringify(a)
          } catch {
            return String(a)
          }
        })
        .join(' ')
      if (KEEP.test(s)) {
        window.__appLog.push({ t: new Date().toISOString(), level, msg: s })
        if (window.__appLog.length > RING) window.__appLog.shift()
      }
    } catch {}
  }
  const orig = {
    log: console.log,
    warn: console.warn,
    error: console.error,
    info: console.info,
  }
  console.log = (...a) => {
    push('log', a)
    orig.log.apply(console, a)
  }
  console.warn = (...a) => {
    push('warn', a)
    orig.warn.apply(console, a)
  }
  console.error = (...a) => {
    push('error', a)
    orig.error.apply(console, a)
  }
  console.info = (...a) => {
    push('info', a)
    orig.info.apply(console, a)
  }
  window.addEventListener('error', (e) =>
    push('error', ['window.onerror', e.message, e.filename + ':' + e.lineno]),
  )
  window.addEventListener('unhandledrejection', (e) =>
    push('error', [
      'unhandledrejection',
      e.reason && (e.reason.stack || e.reason.message || String(e.reason)),
    ]),
  )
  window.__appPatched = true
  return { patched: true }
})()
