# ATT Web UI shell architecture decision

**Status:** Accepted for ATT 4.1.3 Phase 1

**Date:** 2026-10-13

**Scope:** The static UI under `att-web` and its delivery in the existing Server WAR.

## Decision

Keep the UI as HTML, CSS, and vanilla JavaScript served as static resources by the ATT Server WAR. Add the persistent shell as a small independent `app-shell.js` module. Keep the existing feature routes and API workflows in `app.js` while later phases add feature modules.

The production installation remains Java and Apache Tomcat only. It does not need Node.js, npm, a frontend server, or dependency downloads. The browser loads only same-origin static resources and calls the existing same-origin `/api/v1/` and SSE endpoints.

## Options considered

| Option | Benefits | Costs for this phase | Decision |
|---|---|---|---|
| Incremental HTML, CSS, and vanilla JavaScript | Preserves the current UI, strict self-only CSP, hash routes, and WAR resource packaging | Components and state need explicit conventions | Selected |
| React, TypeScript, and Vite | Strong component and type systems for a larger rewrite | Adds a build toolchain and migration work before the current routes have feature parity | Defer |
| Server-rendered Java views | Server-controlled markup and no client framework | Replaces the current browser-side resource and job workflows | Defer |

## Constraints and budgets

- Keep scripts and styles external so the existing Content Security Policy can continue to prohibit inline code.
- Keep route fragments such as `#/packages/{id}`, `#/advanced-load/{packageId}`, and `#/jobs/{jobId}` usable under both `/ui/` and a non-root context such as `/att/ui/`.
- Keep all dashboard values derived from existing package, job, and version responses. `/jobs` returns at most 100 recent rows; the UI labels counts derived from those rows accordingly.
- Preserve existing API payloads, authentication behavior, safe-source projections, and feature gates.
- Keep first-load HTML, CSS, and JavaScript below 150 KiB gzip as a review budget. Recheck this budget when adding assets or dependencies.
- Use the shared `--att-*` tokens so phase modules can reuse the same colors, spacing, radii, and focus ring.

## Migration and rollback

Phase 1 wraps the existing screens with the persistent navigation, breadcrumb, context, and action areas. The existing route dispatcher remains the source of feature behavior. Later phases add their own static UI modules and can register routes against this shell without changing the Java API.

If the shell causes a regression, restore `index.html`, `app.css`, and `app.js` from the previous commit and remove the `app-shell.js` reference. The shell does not migrate server data or alter REST contracts.

## Browser acceptance baseline

The development-only harness and repeatable commands are documented in [`tools/ui-acceptance/README.md`](../../tools/ui-acceptance/README.md). It serves the checked-in static UI under `/att/ui/` with synthetic responses and captures 1440 × 1000 and 768 × 1000 light/dark viewport baselines in [`ui-shell-baseline/`](ui-shell-baseline/). These screenshots record visual layout only; the smoke and axe-core accessibility checks are separate commands and the fixture does not replace stock-Tomcat verification.

| Viewport | Light | Dark |
|---|---|---|
| 1440 × 1000 | [Dashboard](ui-shell-baseline/dashboard-1440-light.png) | [Dashboard](ui-shell-baseline/dashboard-1440-dark.png) |
| 768 × 1000 | [Dashboard](ui-shell-baseline/dashboard-768-light.png) | [Dashboard](ui-shell-baseline/dashboard-768-dark.png) |

To remove only the harness and its captures, revert `tools/ui-acceptance/` and `docs/system-design/ui-shell-baseline/`. Keep the shell rollback above separate from the test-artifact rollback.

Reconsider a compile-time component framework only when the UI needs shared state that cannot be kept clear in the current modules. Any later framework must emit static WAR resources; production must still run only Java and Tomcat.
