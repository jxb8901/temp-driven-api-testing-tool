# UI performance report: before

Generated at 2026-10-21T22:47:54.588Z. This report has no numeric pass/fail thresholds.

## Environment

- Commit: `6b319979db7475bbe482e9ef1fafdbca463fb6d8`
- OS: darwin 25.1.0 arm64
- CPU count: 8
- Browser: chromium 155.0.8059.85
- Playwright: 1.62.1
- Reduced motion: no
- UI dist: `/Users/jeffrey/dev/temp-driven-api-testing-tool/.git/att-review/dist-before`

## Scenario summary

| Scenario | Crash | Route ready p50 / p95 (ms) | Input-to-next-paint p50 / p95 (ms) | Long tasks >50 ms | DOM nodes | Incoming / rendered | Latest event delay p95 (ms) | Terminal delay p95 (ms) | EventSource instances |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| live-steady-10/s | no | 894.2 / 910.6 | 28.1 / 32.8 | 0.0 | 194.0 | 11.0 / 10.0 | 673.1 / 695.3 | 673.1 / 695.4 | 1.0 |
| live-steady-50/s | no | 893.5 / 894.4 | 28.3 / 28.7 | 0.0 | 194.0 | 51.0 / 48.0 | 654.4 / 662.3 | 654.4 / 662.4 | 1.0 |
| live-steady-200/s | no | 891.2 / 893.9 | 29.8 / 33.3 | 1.0 | 194.0 | 201.0 / 190.0 | 604.0 / 604.5 | 604.0 / 604.5 | 1.0 |
| live-burst-10k-replay-reconnect | no | 331899.1 / 422306.8 | 7973.9 / 15030.2 | 17.5 | 4000.0 | 10001.0 / 474.0 | 23709.4 / 42725.1 | 23709.8 / 42725.4 | 1.0 |
| job-detail-500-retained-events | no | 5301.6 / 5317.4 | 23.5 / 34.6 | 2.0 | 4000.0 | 501.0 / 474.0 | 336.9 / 365.5 | 337.1 / 365.8 | 1.0 |
| metrics-100-buckets | no | 4524.6 / 7494.3 | no input | 1.0 | 5068.0 | 501.0 / 473.0 | 1435.9 / 1628.3 | 1436.1 / 1628.6 | 1.0 |
| metrics-1000-buckets | no | 4523.7 / 44095.4 | no input | 3.0 | 11368.0 | 501.0 / 473.0 | 1927.1 / 2986.6 | 1927.4 / 2987.1 | 1.0 |
| metrics-4096-buckets | no | 5183.6 / 5204.7 | no input | 3.0 | 33040.0 | 501.0 / 473.0 | 2636.6 / 2766.0 | 2638.4 / 2767.6 | 1.0 |
| jobs-list-100-filter-sort | no | 898.3 / 900.9 | no input | 0.0 | 604.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| schedules-100-countdown | no | 913.9 / 915.4 | 51.5 / 51.9 | 0.0 | 5927.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-dashboard | no | 126.4 / 134.2 | no input | 0.0 | 258.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-explorer | no | 126.0 / 127.3 | 26.8 / 27.0 | 0.0 | 214.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-configuration | no | 124.6 / 128.3 | 26.1 / 26.3 | 0.0 | 237.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-load | no | 145.6 / 151.4 | 23.6 / 29.4 | 0.0 | 4099.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-jobs | no | 892.9 / 897.6 | no input | 0.0 | 604.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-schedules | no | 910.4 / 912.9 | 24.4 / 38.0 | 0.0 | 5927.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-settings | no | 894.8 / 898.3 | no input | 0.0 | 275.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-warm-core-routes | no | 127.7 / 133.2 | no input | 0.0 | 265.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| resilience-hidden-tab-and-paused-scroll | no | 890.9 / 894.0 | 28.7 / 29.4 | 0.0 | 194.0 | 51.0 / 48.0 | 659.9 / 664.9 | 659.9 / 665.0 | 1.0 |

## Integrity and resources

| Scenario | Duplicate IDs | Missing/order mismatches | Journal order | SSE requests | JS heap p50 (bytes) | JS/CSS transferred p50 (bytes) |
| --- | ---: | ---: | --- | ---: | ---: | ---: |
| live-steady-10/s | 0.0 | 0.0 | unavailable | 1.0 | 5407029.0 | 582430.0 |
| live-steady-50/s | 0.0 | 0.0 | unavailable | 1.0 | 6303960.0 | 582430.0 |
| live-steady-200/s | 0.0 | 0.0 | unavailable | 1.0 | 5477191.0 | 582430.0 |
| live-burst-10k-replay-reconnect | 0.0 | 0.0 | false | 1.0 | 33058649.0 | 582430.0 |
| job-detail-500-retained-events | 0.0 | 0.0 | true | 1.0 | 10787755.0 | 582430.0 |
| metrics-100-buckets | 0.0 | 0.0 | true | 1.0 | 10754912.0 | 629713.0 |
| metrics-1000-buckets | 0.0 | 0.0 | true | 1.0 | 14176771.0 | 629713.0 |
| metrics-4096-buckets | 0.0 | 0.0 | true | 1.0 | 44208380.0 | 629713.0 |
| jobs-list-100-filter-sort | 0.0 | 0.0 | unavailable | 0.0 | 5449628.0 | 629713.0 |
| schedules-100-countdown | 0.0 | 0.0 | unavailable | 0.0 | 10355250.0 | 618899.0 |
| navigation-cold-dashboard | 0.0 | 0.0 | unavailable | 0.0 | 4199138.0 | 557025.0 |
| navigation-cold-explorer | 0.0 | 0.0 | unavailable | 0.0 | 4321645.0 | 557025.0 |
| navigation-cold-configuration | 0.0 | 0.0 | unavailable | 0.0 | 4415074.0 | 557025.0 |
| navigation-cold-load | 0.0 | 0.0 | unavailable | 0.0 | 9040561.0 | 557025.0 |
| navigation-cold-jobs | 0.0 | 0.0 | unavailable | 0.0 | 4877081.0 | 629713.0 |
| navigation-cold-schedules | 0.0 | 0.0 | unavailable | 0.0 | 9509532.0 | 618899.0 |
| navigation-cold-settings | 0.0 | 0.0 | unavailable | 0.0 | 4301230.0 | 582150.0 |
| navigation-warm-core-routes | 0.0 | 0.0 | unavailable | 0.0 | 8179096.0 | 716712.0 |
| resilience-hidden-tab-and-paused-scroll | 0.0 | 0.0 | unavailable | 1.0 | 6084876.0 | 582430.0 |

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

