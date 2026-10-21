# Curated UI performance reports

This directory stores reviewed, machine-labelled before/after reports produced
by `tools/ui-acceptance/ui-perf.cjs`. Keep the matching raw JSON report and
Markdown summary in the review artifact when practical, and record the ATT
commit, browser, Playwright version, OS, CPU count, reduced-motion setting, and
the fixture label in each report. The fixture is synthetic; it is evidence for
browser-side comparisons, not a Server or Worker throughput benchmark.
