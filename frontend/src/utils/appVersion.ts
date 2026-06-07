/**
 * Resolve the app version shown in the corner badge, with precedence:
 *   1. an injected `APP_VERSION` (build pipeline / Docker build-arg) — used by
 *      containerized builds, which have no `.git` to run `git describe` against;
 *   2. otherwise the local `git describe` result — plain `npm` builds in a checkout;
 *   3. otherwise the literal `'dev'`.
 *
 * Pure on purpose: `vite.config.ts` does the Node-side `git describe` and passes
 * both candidate strings here, so the precedence is unit-testable without spawning git.
 */
export function pickAppVersion(
  injected: string | undefined,
  gitDescribe: string | undefined,
): string {
  const fromEnv = injected?.trim()
  if (fromEnv) return fromEnv
  const fromGit = gitDescribe?.trim()
  if (fromGit) return fromGit
  return 'dev'
}
