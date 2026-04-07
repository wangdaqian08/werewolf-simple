# Werewolf Frontend

Vue 3 + TypeScript + Vite mobile-web client for the Werewolf game. Design resolution: 417x614 (portrait).

## Prerequisites

- Node.js 24+
- npm

## Development

```bash
npm install              # install dependencies
npm run dev              # dev server with mocked backend (localhost:5174)
npm run dev:real         # dev server with real Spring Boot backend
npm run build            # type-check + production build
npm run lint             # ESLint
npm run format:check     # Prettier check
```

## Testing

Three test categories, each with its own runner and config:

| Category | Command | Framework | Backend | Config |
|---|---|---|---|---|
| Unit | `npm run test:unit` | Vitest + happy-dom | None | `vitest.config.ts` |
| E2E UI | `npm run test:e2e:ui` | Playwright | Mocked (auto-started) | `playwright.config.ts` |
| E2E Integration | `npm run test:e2e:integration` | Playwright | Real Spring Boot (auto-started) | `playwright.real.config.ts` |

### All commands

```bash
# Unit tests
npm run test:unit              # run once
npm run test:unit:watch        # watch mode
npm run test:unit:coverage     # with V8 coverage report

# E2E UI (mocked backend, starts Vite automatically)
npm run test:e2e:ui

# E2E Integration (starts Spring Boot + Vite automatically)
npm run test:e2e:integration

# Run unit + e2e-ui together (no backend required)
npm run test:all
```

### From repo root (Makefile)

```bash
make test-unit
make test-e2e-ui
make test-e2e-integration
make test-all              # unit + e2e-ui
make test                  # alias for test-all
```

### Test directory layout

```
frontend/
├── src/__tests__/             # Unit tests (Vitest + happy-dom)
│   ├── setup.ts               # test setup (localStorage polyfill)
│   ├── gameStore.test.ts
│   ├── roomStore.test.ts
│   ├── userStore.test.ts
│   └── ...
├── e2e/                       # E2E UI tests (Playwright, mocked backend)
│   ├── lobby.spec.ts
│   ├── room.spec.ts
│   ├── day-phase.spec.ts
│   └── ...
└── e2e/real/                  # E2E Integration tests (Playwright, real backend)
    ├── game-flow.spec.ts
    ├── login.spec.ts
    ├── sheriff-flow.spec.ts
    └── helpers/               # multi-browser fixtures, shell runner
```

### IDE setup

**IntelliJ IDEA / WebStorm:**
Shared run configurations are committed in `.run/`. After opening the project, these appear in the Run dropdown automatically:

- **Test - Unit** — `npm run test:unit`
- **Test - Unit (Watch)** — `npm run test:unit:watch`
- **Test - E2E UI** — `npm run test:e2e:ui`
- **Test - E2E Integration** — `npm run test:e2e:integration`
- **Test - All** — `npm run test:all`

**VS Code:**
- Install the [Vitest](https://marketplace.visualstudio.com/items?itemName=vitest.explorer) extension for unit test integration
- Install [Playwright Test for VS Code](https://marketplace.visualstudio.com/items?itemName=ms-playwright.playwright) for e2e tests
- The Playwright extension auto-detects `playwright.config.ts`; switch to `playwright.real.config.ts` in extension settings to run integration tests

### CI pipeline

CI runs these as separate jobs (see `.github/workflows/ci.yml`):

| CI Job | Script | Notes |
|---|---|---|
| `checks` | `npm run test:unit:coverage` | Also runs lint, format, type-check |
| `e2e-ui` | `npm run test:e2e:ui` | Sharded 3-way for speed |
| `e2e-integration` | `npm run test:e2e:integration` | Runs after backend build passes |
