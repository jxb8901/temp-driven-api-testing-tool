# Curated UI performance reports

This directory stores reviewed, machine-labelled before/after reports produced
by `tools/ui-acceptance/ui-perf.cjs`. Keep the matching raw JSON report and
Markdown summary in the review artifact when practical, and record the ATT
commit, browser, Playwright version, OS, CPU count, reduced-motion setting, and
the fixture label in each report. The fixture is synthetic; it is evidence for
browser-side comparisons, not a Server or Worker throughput benchmark.

## #266 reports

Both reports were captured on the same Apple-silicon Mac (8 CPUs, macOS
darwin 25.1.0, Chromium 155.0.8059.85, Playwright 1.62.1, no reduced motion),
3 repeats each, back to back, with the harness at commit `3e03207a`. The
`environment.gitCommit` field names that harness commit; the UI under test is
recorded in `environment.uiDist`:

- `before/`: the static UI built from `b3eea0f5` (the #269 head, before any
  #266 product change), kept as a separate dist copy so later builds could not
  change it. It was re-captured after the harness fix in `269e2f05` (real
  `Last-Event-ID` reconnect and first-frame display latency), so both reports
  use the same measurement method. In this build every metrics scenario hit
  React error #185 (maximum update depth) and the 10,000-event burst timed out
  waiting for the Events list. These are recorded as page errors in the
  report; #266 PR2 removes them.
- `after/`: the build of `3e03207a` (#266 PR0-PR6).
- `compare-before-after.md`: `npm run perf -- --compare before after`.

Steady-stream display delay rises from about 1-2 ms to about 8-12 ms because
ordinary events now publish on the 100 ms cadence; the measured value is
low because the newest event in each scenario is the terminal `result`,
which publishes at once.
