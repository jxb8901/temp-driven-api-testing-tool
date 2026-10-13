# ATT Web UI shell design reference

This annotated wireframe describes the 4.1.3 Phase 1 shell and dashboard. It is a handoff reference for the existing static UI, not a source of new API metrics.

## Desktop wireframe

```text
┌───────────────────────┬─────────────────────────────────────────────────────────────┐
│ [A] ATT Studio         │ [B] ATT Studio         Server ● Connected        [Theme]     │
│                       ├─────────────────────────────────────────────────────────────┤
│ WORKSPACE             │ Dashboard / Packages       [Package: All] [Env: Per run]     │
│ [C] Dashboard         │                          [Identity: Server managed] [View packages] │
│     Packages          │                                                             │
│     Explorer and Run  │ Dashboard                                                  │
│     Load testing      │ [D] Configured packages  [E] Active jobs  [F] Latest job   │
│     Jobs and results  │     count from API          latest rows       API status    │
│                       │                                                             │
│                       │ [G] Configured packages       [H] Recent activity            │
│                       │ package actions              latest 100 job rows             │
│                       │                             search / refresh / results table │
└───────────────────────┴─────────────────────────────────────────────────────────────┘
```

| Marker | Purpose |
|---|---|
| A | Home link and sidebar collapse control. The sidebar keeps its state on this browser. |
| B | Server connection status and a persistent system/light/dark theme control. The identity chip says that the current principal is not exposed by the API. |
| C | Stable links for implemented dashboard, package, explorer/run, Load, job, Scheduled Load, and Settings routes. |
| D–F | Summary cards use configured package count, active statuses in returned job rows, latest returned job, and the API version check. |
| G | Configured package list with direct explorer and Advanced Load actions. |
| H | Searchable latest-job table; each status has a readable label as well as color. |

The sidebar is 248 px expanded and 64 px collapsed. The top bar is 56 px high. Cards use 12 px corners, controls use 8 px corners, and spacing follows a 4 px base. Content tables can scroll horizontally instead of shrinking their fields below readable widths.

## Tablet and mobile behavior

At 768 px, the sidebar becomes a dismissible navigation drawer. The menu button opens it; Escape and the close control dismiss it. Summary cards use two columns and content panels stack. At phone widths, cards use two compact columns, context chips wrap, and tables remain horizontally scrollable.

## Design tokens

| Token | Light | Dark | Use |
|---|---|---|---|
| `--att-color-primary` | `#2563EB` | `#60A5FA` | Links, selected navigation, primary actions |
| `--att-color-canvas` | `#F8FAFC` | `#0B1220` | Workspace background |
| `--att-color-surface` | `#FFFFFF` | `#111827` | Sidebar, panels, controls |
| `--att-color-text` | `#0F172A` | `#F1F5F9` | Main text |
| `--att-color-muted` | `#64748B` | `#94A3B8` | Supporting text |
| `--att-color-border` | `#E2E8F0` | `#334155` | Dividers and control borders |
| `--att-color-success` | `#15803D` | `#4ADE80` | PASS status with its text label |
| `--att-color-danger` | `#B91C1C` | `#F87171` | Failure status with its text label |
| `--att-color-warning` | `#B45309` | `#FBBF24` | Warnings with explanatory text |
| `--att-space-1`–`--att-space-8` | 4–32 px in 4 px steps | Same | Consistent layout spacing |
| `--att-radius-control` / `--att-radius-card` | 8 / 12 px | Same | Controls and cards |
| `--att-focus-ring` | 2 px visible ring with offset | Same | Keyboard focus |

## Screen states

| State | Package panel | Jobs panel | Summary |
|---|---|---|---|
| Loading | Announces loading and shows a neutral placeholder bar | Announces loading and shows a neutral placeholder bar | Values remain unavailable until the matching response arrives |
| Empty | Explains that no packages are registered | Explains how to submit the first job | Shows zero packages and no jobs only after successful responses |
| Loaded | Shows package IDs and direct actions | Shows returned rows, textual statuses, and job links | Uses only the current response data |
| Error | Announces the failure and exposes Retry | Announces the failure and exposes Refresh | Shows unavailable values instead of treating an error as zero |

## Keyboard and contrast notes

The skip link moves focus to the main content. Navigation is reachable before page content in DOM order. Sidebar, theme, search, refresh, and row links have accessible names and visible focus. Status labels include their status text, so users do not need color to distinguish PASS, FAIL, or RUNNING. Respect the reduced-motion preference for the loading treatment and drawer transition.
