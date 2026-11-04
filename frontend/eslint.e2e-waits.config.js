// Bans fixed sleeps in the Playwright suites. A sleep passes when the machine
// is fast and fails when it is slow (CI is ~2× slower): wait for the state the
// next step needs instead — a backend phase/sub-phase (waitForPhaseIn,
// waitForNightSubPhase, waitForDayRevealed), the rendered sub-phase
// (waitForUiSubPhase), an element (locator.waitFor / expect(...).toBeVisible),
// a log line (waitForBackendLog) or any predicate (waitForCondition, expect.poll).
//
// Run: npx eslint -c eslint.e2e-waits.config.js e2e   (CI: "No sleeps in E2E")
// The two poll primitives themselves carry an eslint-disable with the reason.
import tseslint from 'typescript-eslint'

export default tseslint.config({
    files: ['e2e/**/*.ts'],
    languageOptions: { parser: tseslint.parser },
    rules: {
        'no-restricted-syntax': [
            'error',
            {
                selector: "CallExpression[callee.property.name='waitForTimeout']",
                message:
                    'page.waitForTimeout is a blind sleep. Wait for the phase, element or log line the next step needs (waitForCondition / waitForPhaseIn / waitForUiSubPhase / expect.poll / locator.waitFor).',
            },
            {
                selector: "CallExpression[callee.name='setTimeout']",
                message:
                    'A setTimeout sleep is a blind wait. Poll intervals live only inside waitForCondition (state-polling.ts) and waitForBackendLog (backend-log.ts).',
            },
        ],
    },
})
