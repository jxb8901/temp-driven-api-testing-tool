# Measure UI performance

Use the development-only Playwright harness to compare browser-side ATT Studio
performance with the same built UI and synthetic fixture data.

## Run the harness

Build the UI, install the acceptance dependencies, and run three repetitions in
Chromium:

```sh
cd att-web/frontend && npm run build
cd ../../tools/ui-acceptance && npm ci
npm run perf -- --label before
```

The harness serves the built files below `/att/ui/`, keeps Playwright in this
development package, and does not add Node.js to the production WAR. Use
`ATT_UI_DIST=/path/to/dist` to select another built output directory. Choose
another engine with `--browser firefox` or `--browser webkit`; use
`--reduced-motion` for a separate reduced-motion run. `--repeat N` changes the
number of samples. `--scenario NAME` selects one or more scenario names; the
available names are printed in the issue-266 harness source and the error for an
unknown name.

The built Vite entry remains an external module script with the existing CSP
nonce placeholder. The test-only instrumentation is injected by Playwright and
does not add inline scripts or assets to the served UI.

The run writes `perf.json` (raw samples and summary) and `summary.md` under
`tools/ui-acceptance/artifacts/perf/<label>/`. Raw output is ignored by Git.
After a reviewed change, compare two labels without launching a browser:

```sh
npm run perf -- --compare before after
```

## Scenarios and measurements

The fixture covers steady Live Output/Events streams at 10, 50, and 200 events
per second, a 10,000-event burst with replay and one reconnect, 500 retained
Job events, 100/1,000/4,096 metric buckets, 100 Jobs, 100 Schedules with a
future active countdown, cold and warm core-route navigation, and resilience
actions for hidden tabs, repeated route switching, and paused auto-scroll.

Each sample records route-ready time, long tasks over 50 ms, an input-to-next-
paint proxy for a search/filter action, DOM mutation batches and rAF frame
durations, DOM nodes, JS heap when the browser exposes it, incoming and visible
events, displayed-event and terminal delays, Event-ID order diagnostics,
EventSource instance counts, transferred JS/CSS resource bytes, and per-chunk
gzip sizes from the Vite manifest. The report also records the machine, OS,
CPU count, browser and Playwright versions, commit, and reduced-motion setting.

Displayed-event delay is the time from the newest event reaching the
page's EventSource listener to the first animation frame on which that event
is in the DOM: its `#id` in the Events view, or its console line in Live
Output. Terminal delay is the time from the terminal `result` event to the
first frame that shows a `PASS` status badge or `SSE completed`. The 10,000-
event burst is served by the fixture server rather than Playwright route
fulfilment, because Chromium does not send `Last-Event-ID` when it reconnects
to a fulfilled response; the server records the reconnect cursor, and the
run reports an error unless the reconnect resumed from event 10,000.

The harness reports median (p50) and p95 values while preserving every raw
sample. It has no numeric thresholds yet. It exits non-zero only for browser
page/render errors or more than one EventSource instance for a Job in a tab;
performance numbers and duplicate/missing order diagnostics are evidence for
review, not pass/fail gates.

For a curated baseline, copy the reviewed report to
`docs/system-design/perf/` with its environment metadata. Do not present the
synthetic fixture as Server, Worker, JVM, or end-to-end throughput evidence.

## UI behavior covered by issue #266

### Display chart samples

`TimeSeriesChart` keeps the complete Server-returned metric series for the
table and any future export or tooltip consumers. When a series contains more
than `CHART_DISPLAY_POINT_THRESHOLD` points, the chart uses a display-only
min/max-per-bucket sample. The default threshold is 1,000 points. Sampled
points retain their original values and `epochMs` coordinates; the chart does
not interpolate or change percentiles. The chart visibly and accessibly labels
the result as `display sample: N of M points`, and the paged table remains the
exact returned data.

The PR0/PR3 Chromium baseline showed why this threshold is useful: the
4,096-bucket scenario mounted about 33,040 DOM nodes and recorded three long
tasks, compared with about 11,368 nodes for 1,000 buckets. The threshold is
therefore a measured display guard, not a data-retention limit. The 4,096-point
case is still supported and remains fully available through the table.

### Isolate countdowns and catalog requests

Schedule expiry and LOAD Catalog draft expiry use an isolated countdown hook.
It creates a one-second timer only while a draft is counting down, clears the
timer when the draft expires or the component unmounts, and pauses it while the
document is hidden. Only the countdown/action subtree updates; the page body
does not receive a one-second clock state update. A Catalog refresh fetches up
to four packages concurrently, then flattens results in package order. The
existing generation and abort guards still discard stale or cancelled results.

### Split one route

Explorer's JavaScript is loaded on demand. `explorer.css` stays in the initial
bundle because TypedSidecarForm and ConfigurationExplorer also use it outside
the Explorer route; moving it would change the cascade order, so that split is
a separate follow-up. Navigation continues to use
the existing hash route and navigation guard, so deep links, back/forward
navigation, and dirty-navigation prompts keep their existing behavior. While
the route chunk loads, the UI exposes a `role="status"` message instead of an
empty main region. Production remains a static Vite bundle served under
`/att/ui/`; Node.js is still development-only.

The route figures below are reviewed informational values from the current
build. They are entry chunk plus directly associated CSS gzip sizes and are
not CI failure thresholds.

| Route | Lazy entry gzip | Reviewed informational budget |
| --- | ---: | ---: |
| Explorer | 8,263 bytes | 16 KiB |
| Load | 13,179 bytes | 20 KiB |
| Jobs | 14,772 bytes | 20 KiB |
| Schedules | 15,545 bytes | 20 KiB |
| Settings | 6,192 bytes | 12 KiB |
| Job console | 758 bytes | 4 KiB |

The initial static dependency budget remains 150 KiB gzip. The current build is
144,838 bytes gzip; the PR0 baseline was 150,759 bytes gzip.
