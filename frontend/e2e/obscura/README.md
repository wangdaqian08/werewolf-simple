# Obscura E2E scaffolding (opt-in)

This dir wires Playwright to drive [Obscura](https://github.com/h4ckf0r0day/obscura) — a
headless browser — over CDP instead of bundled Chromium. It is an **opt-in** trial of
Obscura as a Playwright replacement, run via its own config + npm scripts. It is **not**
part of normal CI (excluded from the default `playwright.config.ts` via
`testIgnore: '**/obscura/**'`, the same way the `payment` suite is).

## Status: working as of Obscura v0.1.8

The 2-test smoke passes against `http://localhost:5174`:

- `obscura: lobby loads and renders nickname input` → **PASSES**
- `obscura: CDP target reports Obscura, not Chrome` → **PASSES**

### The localhost block, and the fix

Obscura 0.1.0–0.1.5 hard-blocked loopback / RFC1918 targets (an SSRF guard added for
their issue #4), so driving it against `localhost` failed with
`Access to localhost domain 'localhost' is not allowed`.

The fix is **opt-in** and shipped in **v0.1.6+** (latest v0.1.8): pass
`--allow-private-network` to `obscura serve` (or set `OBSCURA_ALLOW_PRIVATE_NETWORK=1`).
v0.1.8 also adds a DNS-resolver SSRF re-check. The `obscura:serve` npm script already
includes the flag.

> Note: the merged fix is upstream **PR #203** (`feat(cli): --allow-private-network`),
> which *closed* the earlier **PR #33** ("Allow trusted localhost targets"); #33 itself
> was closed unmerged. Cite #203 / v0.1.6, not #33.

## Run it

The Obscura binary is **not committed** (`.gitignore` ignores `.obscura/`). Download a
released build (≥ v0.1.6, prefer v0.1.8) for your platform into `frontend/.obscura/`:

```bash
# Apple Silicon mac (use obscura-x86_64-macos.tar.gz on Intel; obscura-x86_64-linux.tar.gz in CI)
gh release download v0.1.8 -R h4ckf0r0day/obscura -p 'obscura-aarch64-macos.tar.gz' -D /tmp/obs
tar -xzf /tmp/obs/obscura-aarch64-macos.tar.gz -C frontend/.obscura/   # obscura + obscura-worker
chmod +x frontend/.obscura/obscura frontend/.obscura/obscura-worker
```

Then:

```bash
cd frontend
npm run obscura:serve        # terminal 1 — starts CDP server on :9222 with --allow-private-network
npm run test:e2e:obscura     # terminal 2 — runs e2e/obscura/ against the CDP target
```

## Is Obscura a stable Playwright replacement?

What's proven so far: the CDP wiring works (`chromium.connectOverCDP` → Obscura), and with
v0.1.8 + `--allow-private-network` Obscura navigates to the local mock app and renders the
Vue UI. That's a green light to trial it further, **not** a finished migration.

Still untested before calling it a "replacement":

- The real suites (235 default UI specs + the real-backend integration specs) still run on
  bundled Chromium. They'd each need porting to the `e2e/fixtures/obscura.ts` CDP fixture to
  actually exercise Obscura — it is **not** a drop-in swap.
- No data yet on flake rate, performance, or feature parity (Playwright tracing, video,
  auto-waiting semantics, network interception) under Obscura.
- CI does not run Obscura (no binary committed, no CDP server job).

Next step to judge stability: port a representative slice of the UI suite (and ideally one
real-backend flow) to the Obscura fixture and run it repeatedly to measure flake + perf.

## Scaffolding files

- `frontend/playwright.obscura.config.ts` — opt-in config, `testDir: e2e/obscura/`
- `frontend/e2e/fixtures/obscura.ts` — `chromium.connectOverCDP` worker fixture
- `frontend/e2e/obscura/smoke.spec.ts` — 2-test smoke
- `frontend/package.json` — `obscura:serve` (with `--allow-private-network`), `test:e2e:obscura`
- `frontend/.gitignore` — ignores `.obscura/` (downloaded binary)
- `frontend/playwright.config.ts` — excludes `**/obscura/**` so the opt-in smoke never runs in normal CI

The default `playwright.config.ts` and `playwright.real.config.ts` test runs are unaffected.
