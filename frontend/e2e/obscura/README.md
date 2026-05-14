# Obscura E2E scaffolding (parked)

This dir wires Playwright to drive [Obscura](https://github.com/h4ckf0r0day/obscura) over CDP.

**Status: known-broken against `localhost`.** Obscura 0.1.0 hard-blocks loopback / RFC1918 targets in `crates/obscura-net/src/client.rs` (added as the fix for SSRF issue #4). No CLI flag disables it. Upstream issue [#33 "Allow trusted localhost targets"](https://github.com/h4ckf0r0day/obscura/issues/33) is open and unfixed.

Smoke result against `http://localhost:5174`:
- `obscura: lobby loads...` → **FAILS** (`Access to localhost domain 'localhost' is not allowed`)
- `obscura: CDP target reports Obscura, not Chrome` → **PASSES** (proves the wiring is correct)

## Next move when you revisit

`git switch chore/playwright-obscura`, then either patch + rebuild Obscura or wait on upstream.

### Patch + rebuild path

```bash
git clone https://github.com/h4ckf0r0day/obscura.git /tmp/obscura
# Comment out lines 82-113 in crates/obscura-net/src/client.rs (the loopback gate)
cd /tmp/obscura && cargo build --release --features stealth
cp target/release/obscura target/release/obscura-worker frontend/.obscura/
```

Then:
```bash
cd frontend
npm run obscura:serve            # in one terminal
npm run test:e2e:obscura         # in another
```

### Wait-on-upstream path

Watch [issue #33](https://github.com/h4ckf0r0day/obscura/issues/33). When merged + released, `curl -LO` the new tarball into `frontend/.obscura/` and re-run the smoke.

## Scaffolding files

- `frontend/playwright.obscura.config.ts` — opt-in config, `testDir: e2e/obscura/`
- `frontend/e2e/fixtures/obscura.ts` — `chromium.connectOverCDP` worker fixture
- `frontend/e2e/obscura/smoke.spec.ts` — 2-test smoke
- `frontend/package.json` — `obscura:serve`, `test:e2e:obscura` scripts
- `frontend/.gitignore` — ignores `.obscura/` (downloaded binary)

The default `playwright.config.ts` and `playwright.real.config.ts` are untouched.
