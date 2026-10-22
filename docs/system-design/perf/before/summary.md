# UI performance report: before

Generated at 2026-10-21T23:50:54.629Z. This report has no numeric pass/fail thresholds.

## Environment

- Commit: `3e03207ab92e61b2faa506c3b29489bc73aacdea`
- OS: darwin 25.1.0 arm64
- CPU count: 8
- Browser: chromium 155.0.8059.85
- Playwright: 1.62.1
- Reduced motion: no
- UI dist: `/Users/jeffrey/dev/temp-driven-api-testing-tool/.git/att-review/dist-before`

## Scenario summary

| Scenario | Crash | Route ready p50 / p95 (ms) | Input-to-next-paint p50 / p95 (ms) | Long tasks >50 ms | DOM nodes | Incoming / rendered | Latest event delay p95 (ms) | Terminal delay p95 (ms) | EventSource instances |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| live-steady-10/s | no | 899.3 / 978.1 | 33.1 / 36.6 | 0.0 | 194.0 | 11.0 / 10.0 | 1.0 / 1.1 | 0.7 / 0.8 | 1.0 |
| live-steady-50/s | no | 897.3 / 897.5 | 30.4 / 32.0 | 0.0 | 194.0 | 51.0 / 48.0 | 1.4 / 1.5 | 1.1 / 1.3 | 1.0 |
| live-steady-200/s | no | 899.2 / 902.0 | 28.5 / 29.5 | 1.0 | 194.0 | 201.0 / 190.0 | 1.7 / 2.1 | 1.3 / 1.6 | 1.0 |
| live-burst-10k-replay-reconnect | no | 389295.0 / 414399.9 | 1795.6 / 1897.4 | 20.0 | 4000.0 | 10001.0 / 474.0 | 207.6 / 222.0 | 208.1 / 222.9 | 1.0 |
| job-detail-500-retained-events | no | 5935.2 / 6285.7 | 34.1 / 34.6 | 2.0 | 4000.0 | 501.0 / 474.0 | 25.4 / 28.2 | 25.7 / 28.4 | 1.0 |
| metrics-100-buckets | no | 4911.5 / 4984.4 | no input | 1.0 | 5068.0 | 501.0 / 473.0 | 21.8 / 23.5 | 22.0 / 23.9 | 1.0 |
| metrics-1000-buckets | no | 4906.7 / 4952.1 | no input | 3.0 | 11368.0 | 501.0 / 473.0 | 16.3 / 37.2 | 16.5 / 37.4 | 1.0 |
| metrics-4096-buckets | no | 4820.7 / 4931.8 | no input | 4.0 | 33040.0 | 501.0 / 473.0 | 18.3 / 27.1 | 18.7 / 27.3 | 1.0 |
| jobs-list-100-filter-sort | no | 894.2 / 917.0 | no input | 0.0 | 604.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| schedules-100-countdown | no | 921.4 / 926.3 | 38.6 / 49.2 | 0.0 | 5927.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-dashboard | no | 135.6 / 138.3 | no input | 0.0 | 258.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-explorer | no | 126.3 / 142.9 | 26.0 / 27.4 | 0.0 | 214.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-configuration | no | 127.1 / 143.6 | 25.6 / 27.1 | 0.0 | 237.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-load | no | 156.6 / 157.2 | 30.3 / 30.6 | 0.0 | 4099.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-jobs | no | 897.0 / 900.1 | no input | 0.0 | 604.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-schedules | no | 916.1 / 922.0 | 25.3 / 26.8 | 0.0 | 5927.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-settings | no | 898.2 / 925.9 | no input | 0.0 | 275.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-warm-core-routes | no | 141.3 / 171.5 | no input | 0.0 | 265.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| resilience-hidden-tab-and-paused-scroll | no | 904.1 / 908.0 | 29.2 / 29.4 | 0.0 | 194.0 | 51.0 / 48.0 | 1.2 / 1.9 | 1.1 / 1.8 | 1.0 |

## Integrity and resources

| Scenario | Duplicate IDs | Missing/order mismatches | Journal order | SSE requests | JS heap p50 (bytes) | JS/CSS transferred p50 (bytes) |
| --- | ---: | ---: | --- | ---: | ---: | ---: |
| live-steady-10/s | 0.0 | 0.0 | unavailable | 1.0 | 5552487.0 | 582430.0 |
| live-steady-50/s | 0.0 | 0.0 | unavailable | 1.0 | 6119448.0 | 582430.0 |
| live-steady-200/s | 0.0 | 0.0 | unavailable | 1.0 | 5513269.0 | 582430.0 |
| live-burst-10k-replay-reconnect | 0.0 | 0.0 | true | 2.0 | 59067785.0 | 582430.0 |
| job-detail-500-retained-events | 0.0 | 0.0 | true | 1.0 | 10861380.0 | 582430.0 |
| metrics-100-buckets | 0.0 | 0.0 | true | 1.0 | 15733091.0 | 629713.0 |
| metrics-1000-buckets | 0.0 | 0.0 | true | 1.0 | 14488203.0 | 629713.0 |
| metrics-4096-buckets | 0.0 | 0.0 | true | 1.0 | 37230583.0 | 629713.0 |
| jobs-list-100-filter-sort | 0.0 | 0.0 | unavailable | 0.0 | 5673291.0 | 629713.0 |
| schedules-100-countdown | 0.0 | 0.0 | unavailable | 0.0 | 10392828.0 | 618899.0 |
| navigation-cold-dashboard | 0.0 | 0.0 | unavailable | 0.0 | 4105742.0 | 557025.0 |
| navigation-cold-explorer | 0.0 | 0.0 | unavailable | 0.0 | 4439536.0 | 557025.0 |
| navigation-cold-configuration | 0.0 | 0.0 | unavailable | 0.0 | 4415464.0 | 557025.0 |
| navigation-cold-load | 0.0 | 0.0 | unavailable | 0.0 | 8946067.0 | 557025.0 |
| navigation-cold-jobs | 0.0 | 0.0 | unavailable | 0.0 | 4949325.0 | 629713.0 |
| navigation-cold-schedules | 0.0 | 0.0 | unavailable | 0.0 | 9525229.0 | 618899.0 |
| navigation-cold-settings | 0.0 | 0.0 | unavailable | 0.0 | 4290793.0 | 582150.0 |
| navigation-warm-core-routes | 0.0 | 0.0 | unavailable | 0.0 | 8186068.0 | 716712.0 |
| resilience-hidden-tab-and-paused-scroll | 0.0 | 0.0 | unavailable | 1.0 | 6346246.0 | 582430.0 |

## Bundle gzip sizes

| Asset | Lazy entry | Raw bytes | Gzip bytes |
| --- | --- | ---: | ---: |
| assets/JobConsoleView-B3Te_dle.js | no | 13980 | 5006 |
| assets/JobConsoleView-Bg5UxAnQ.css | no | 9099 | 2207 |
| assets/dist-DE1Lrzs1.js | no | 26884 | 8740 |
| assets/jsx-runtime-Dk72oS4N.js | no | 8774 | 3345 |
| assets/preload-helper-CRzLMJPS.js | no | 1534 | 802 |
| assets/types-C3LSnZ5x.js | no | 251 | 165 |
| assets/index-BJ8FU8gP.js | no | 471918 | 134367 |
| assets/index-BCpAGL0g.css | no | 73048 | 12432 |
| assets/dist-CzaXXnCI.js | yes | 2061 | 1267 |
| assets/dist-Cn2dFRcN.js | yes | 15924 | 6888 |
| assets/dist-CuS1qwX2.js | yes | 14381 | 5718 |
| assets/dist-C4dvjZ1J.js | yes | 11628 | 5080 |
| assets/dist-DVzxfVxq.js | yes | 297687 | 95529 |
| assets/shell-DwuoZtxw.js | yes | 2434 | 1186 |
| assets/browser-CraaQKji.js | yes | 95953 | 29580 |
| assets/SettingsPage-zIZvQThq.js | yes | 18693 | 4870 |
| assets/SettingsPage-6aA7xbkL.css | yes | 5832 | 1423 |
| assets/codemirrorRuntime-CpNtfrLp.js | yes | 75215 | 24873 |
| assets/FloatingJobConsole-DYgisyi2.js | yes | 4408 | 1688 |
| assets/JobsPage-B-xiegMv.js | yes | 48709 | 14169 |
| assets/LoadPage-CrRtHbxz.js | yes | 49336 | 13179 |
| assets/StandaloneJobConsolePage-DZFUGwqy.js | yes | 1426 | 755 |
| assets/ScheduledLoadPage-C_BzeHB6.js | yes | 48476 | 12988 |
| assets/ScheduledLoadPage-27rod9YQ.css | yes | 12798 | 2602 |

## Raw samples

The companion JSON file contains every repetition and all captured fields, including long-task and frame samples, duplicate/missing Event-ID diagnostics, JS heap availability, mutation/rAF proxies, transferred JS/CSS bytes, and per-chunk gzip sizes.

