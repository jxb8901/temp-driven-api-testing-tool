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
