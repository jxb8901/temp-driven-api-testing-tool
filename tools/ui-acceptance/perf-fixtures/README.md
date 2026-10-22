# UI performance fixtures

`scenario.json` is the reviewed workload matrix. `api.json` contains the
stable REST responses shared by the scenarios. `ui-perf.cjs` expands the
declared counts into deterministic Jobs, Schedules, metric buckets, and SSE
records, then serves those responses through Playwright route stubs. This keeps
the browser fixture repeatable without embedding data in the built UI.
