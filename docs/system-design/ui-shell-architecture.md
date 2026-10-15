# ATT Web UI shell architecture decision

**Status:** Accepted; Phase 1 implementation is in place

**Date:** 2026-10-13

**Scope:** React application foundation and its static delivery in the existing Server WAR.

## Decision

Build the browser application with React, TypeScript, and Vite. The single mount is `att-web/frontend/src/main.tsx`; `App.tsx` owns route selection and the API-backed application state, `AppShell.tsx` owns persistent navigation and shared workspace context, and feature pages receive typed routes and injected callbacks. `api.ts` provides `request<T>()` using JSON, normalized errors, `AbortSignal`, and a relative `../api/v1/` URL. Hash routes remain usable below `/ui/` and non-root contexts such as `/att/ui/`.

Vite emits static files to `att-web/frontend/dist`. Maven copies those files into `META-INF/resources/ui` in `att-web.jar`, which the existing Server WAR packages and serves. Node.js and npm are build and test tools only. A production installation still runs Java and Apache Tomcat and serves the UI and API from the same origin.

## Options considered

| Option | Benefits | Costs | Decision |
|---|---|---|---|
| React, TypeScript, and Vite | Typed shared routes and API contracts, composable feature pages, component tests | Adds a pinned build-time Node toolchain and requires an incremental migration | Selected |
| HTML, CSS, and vanilla JavaScript | Keeps the current implementation and has no compile step | Shared shell and feature state lack component and type boundaries for the required feature modules | Keep only as a migration rollback artifact |
| Server-rendered Java views | Server-controlled markup and no browser framework | Replaces the existing browser-side workflows and REST-driven UI | Defer |

## State and integration boundaries

- `types.ts`, `api.ts`, `state.ts`, and `router.ts` are the shared TypeScript foundation. Feature operations can be added centrally to `api.ts`; pages do not construct API URLs.
- `ServerVersion.inlineLoadEnabled` and `loadSchedulingEnabled` are retained as server capabilities and passed into the shell and feature routes. The API does not expose the current principal, so the identity chip says “Server managed” and “not exposed by API.”
- `AppRoute` has dashboard, package collection, package/resource, first-level Configuration, single-target Quick Load, jobs/job, Advanced Load/package, Scheduled Load, and Settings variants. `useNavigationGuard()` provides `navigate()` and `registerUnsavedChanges()`; the shell presents a keyboard-accessible discard dialog for internal and browser-history navigation.
- Package, environment, and accepted-job callbacks are exposed through shell context so later modules can stay controlled by the application root.
- Dashboard summaries and lists use only `/version`, `/packages`, and `/jobs` responses. Counts describe returned rows; an API error is not displayed as zero.
- External CSS and scripts preserve the existing self-only Content Security Policy. All requests remain same-origin.

## Browser language and CSP

`uiLocale.ts` translates rendered text and accessibility attributes between English and Traditional Chinese. The preference is browser-managed by default, follows `navigator.language` changes, or stores an explicit choice in browser local storage. The login page reads that same preference for its standalone messages. The language change does not alter API payloads, resource identifiers, source text, or code examples.

`SecurityHeadersFilter` issues a per-response nonce for the Studio document and replaces the matching marker in the packaged Vite HTML. CodeMirror receives nonce-authorized style elements and the Studio document alone permits inline style attributes for editor geometry. API, login, download, and other responses keep inline styles disabled; scripts and network connections remain same-origin. The filter also redirects the context root to `/ui/` when the servlet container passes that request through to the application.

## Build and size budget

The frontend dependency versions are pinned in `package.json` and `package-lock.json`. `npm ci`, type checks, component tests, and Vite production builds run in development and CI. The Java 17 CI job provisions pinned Node.js 22.22.2; the Java 8 compatibility job does not require Node. The initial JavaScript and CSS gzip budget is 150 KiB and is checked against Vite output.

## Incremental migration and rollback

The generated Vite `index.html` and bundles are the active application entry. The old DOM-mutating `app.js`, `app-shell.js`, and stylesheet are retained only through `legacy.html` as a temporary rollback artifact; the React entry never loads that page or those scripts. Feature modules replace legacy workflows in phases while preserving existing Java routes, REST contracts, authentication, and safe-source behavior. Remove the rollback page and legacy assets after feature parity is verified.

To roll back the shell, restore the prior `index.html` and static resources in the WAR packaging path, then rebuild the Server WAR. `legacy.html` is a diagnostic fallback page and is not a deployment switch. This rollback does not change server data or API contracts.

## Browser acceptance

The development-only fixture and browser commands are documented in [`tools/ui-acceptance/README.md`](../../tools/ui-acceptance/README.md). The harness targets the built Vite output under `/att/ui/`, supplies deterministic API responses, and runs dashboard, Settings, Explorer, Configuration, Quick Load, Load/Jobs, and Scheduled Load flows with axe checks. The cross-browser suite covers Chromium, Firefox, and WebKit at desktop, tablet, and phone widths in light and dark themes; it checks responsive layout and overflow, runs axe at every viewport, compares each Scheduled Load capture with its browser/viewport/theme baseline in [`tools/ui-acceptance/visual-baselines/scheduled-load/`](../../tools/ui-acceptance/visual-baselines/scheduled-load/), and stores screenshots as CI artifacts. Dashboard reference captures remain in [`ui-shell-baseline/`](ui-shell-baseline/). Browser screenshots and axe checks do not replace Java WAR packaging or non-root Tomcat tests.

The cross-browser job runs on `ubuntu-latest` and compares against the `linux` baseline; local macOS runs use `darwin`. The harness verifies the selected manifest platform and fails when that platform's manifest or captures are missing. It does not reuse another platform's screenshots.

Scheduled Load is a lazily loaded React route. The shell advertises it only when `/api/v1/version` reports `loadSchedulingEnabled`; direct navigation still shows the server-disabled state. The page calls the typed `load-schedules` API methods, requires a current server preview before saving, starts new schedules paused, and asks before Run Now or deletion. Occurrence history includes skipped and failed submissions as well as linked jobs. The server remains authoritative for cron validation, timezone and DST behavior, minimum intervals, overlap limits, and authorization.

Quick and Advanced Load can open schedule creation as a dialog over the current editor. Cancel returns to the validated Load draft, and the Server rejects inline snapshots containing classified sensitive values before persistence.

The shell follows `prefers-color-scheme` until a user selects a theme. The Settings route offers System, Light, and Dark preferences; explicit choices are stored in this browser, while System removes the override. `/` and `Ctrl K`/`⌘ K` open the page palette, `?` opens shortcut help, and Escape closes dialogs and mobile navigation. On Advanced Load, `Ctrl Enter`/`⌘ Enter` invokes the existing confirmation for a live validated draft.

## Phase screenshots

The gallery uses synthetic fixture responses and shows the same design system across each phase. Each link opens a 1440 × 1000 PNG capture.

| Phase and screen | Light | Dark |
|---|---|---|
| Phase 1: Dashboard | [Screenshot](ui-shell-baseline/dashboard-1440-light.png) | [Screenshot](ui-shell-baseline/dashboard-1440-dark.png) |
| Phase 2: Explorer and Debug | [Screenshot](ui-shell-baseline/explorer-debug-1440-light.png) | [Screenshot](ui-shell-baseline/explorer-debug-1440-dark.png) |
| Phase 3: Load Builder | [Screenshot](ui-shell-baseline/load-builder-1440-light.png) | [Screenshot](ui-shell-baseline/load-builder-1440-dark.png) |
| Phase 3: Job Results | [Screenshot](ui-shell-baseline/job-results-1440-light.png) | [Screenshot](ui-shell-baseline/job-results-1440-dark.png) |
| Phase 4: Scheduled Load | [Screenshot](ui-shell-baseline/scheduled-load-1440-light.png) | [Screenshot](ui-shell-baseline/scheduled-load-1440-dark.png) |
| Phase 5: Configuration workspace | [Screenshot](ui-shell-baseline/configuration-1440-light.png) | [Screenshot](ui-shell-baseline/configuration-1440-dark.png) |
| Phase 5: Quick Load | [Screenshot](ui-shell-baseline/quick-load-1440-light.png) | [Screenshot](ui-shell-baseline/quick-load-1440-dark.png) |
The [screenshot manifest](ui-shell-baseline/manifest.json) records each route, fixture, browser, viewport, and theme.

To remove the acceptance harness, revert `tools/ui-acceptance/` and `docs/system-design/ui-shell-baseline/`. Keep that artifact rollback separate from the application rollback above.
