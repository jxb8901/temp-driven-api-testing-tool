# ATT Web UI shell architecture decision

**Status:** Accepted for ATT 4.1.3 Phase 1

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
- `ServerVersion.inlineLoadEnabled` is retained as a server capability and passed into the shell and feature routes. The API does not expose the current principal, so the identity chip says “Server managed” and “not exposed by API.”
- `AppRoute` has dashboard, package collection, package/resource, jobs/job, and load/package variants. `useNavigationGuard()` provides `navigate()` and `registerUnsavedChanges()`; the shell presents a keyboard-accessible discard dialog for internal and browser-history navigation.
- Package, environment, and accepted-job callbacks are exposed through shell context so later modules can stay controlled by the application root.
- Dashboard summaries and lists use only `/version`, `/packages`, and `/jobs` responses. Counts describe returned rows; an API error is not displayed as zero.
- External CSS and scripts preserve the existing self-only Content Security Policy. All requests remain same-origin.

## Build and size budget

The frontend dependency versions are pinned in `package.json` and `package-lock.json`. `npm ci`, type checks, component tests, and Vite production builds run in development and CI. The Java 17 CI job provisions pinned Node.js 22.22.2; the Java 8 compatibility job does not require Node. The initial JavaScript and CSS gzip budget is 150 KiB and is checked against Vite output.

## Incremental migration and rollback

The generated Vite `index.html` and bundles are the active application entry. The old DOM-mutating `app.js`, `app-shell.js`, and stylesheet are retained only through `legacy.html` as a temporary rollback artifact; the React entry never loads that page or those scripts. Feature modules replace legacy workflows in phases while preserving existing Java routes, REST contracts, authentication, and safe-source behavior. Remove the rollback page and legacy assets after feature parity is verified.

To roll back the shell, restore the prior `index.html` and static resources in the WAR packaging path, then rebuild the Server WAR. `legacy.html` is a diagnostic fallback page and is not a deployment switch. This rollback does not change server data or API contracts.

## Browser acceptance

The development-only fixture and browser commands are documented in [`tools/ui-acceptance/README.md`](../../tools/ui-acceptance/README.md). The harness targets the built Vite output under `/att/ui/`, supplies deterministic API responses, runs browser smoke and axe checks, and captures 1440 × 1000 and 768 × 1000 light and dark references in [`ui-shell-baseline/`](ui-shell-baseline/). These images record layout; they do not replace Java WAR packaging or non-root Tomcat tests.

| Viewport | Light | Dark |
|---|---|---|
| 1440 × 1000 | [Dashboard](ui-shell-baseline/dashboard-1440-light.png) | [Dashboard](ui-shell-baseline/dashboard-1440-dark.png) |
| 768 × 1000 | [Dashboard](ui-shell-baseline/dashboard-768-light.png) | [Dashboard](ui-shell-baseline/dashboard-768-dark.png) |

To remove the acceptance harness, revert `tools/ui-acceptance/` and `docs/system-design/ui-shell-baseline/`. Keep that artifact rollback separate from the application rollback above.
