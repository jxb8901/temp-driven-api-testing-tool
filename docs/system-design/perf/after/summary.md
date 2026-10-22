# UI performance report: after

Generated at 2026-10-22T00:13:19.538Z. This report has no numeric pass/fail thresholds.

## Environment

- Commit: `3e03207ab92e61b2faa506c3b29489bc73aacdea`
- OS: darwin 25.1.0 arm64
- CPU count: 8
- Browser: chromium 155.0.8059.85
- Playwright: 1.62.1
- Reduced motion: no
- UI dist: `/Users/jeffrey/dev/temp-driven-api-testing-tool/att-web/frontend/dist`

## Scenario summary

| Scenario | Crash | Route ready p50 / p95 (ms) | Input-to-next-paint p50 / p95 (ms) | Long tasks >50 ms | DOM nodes | Incoming / rendered | Latest event delay p95 (ms) | Terminal delay p95 (ms) | EventSource instances |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| live-steady-10/s | no | 888.9 / 922.7 | 28.7 / 30.2 | 0.0 | 194.0 | 11.0 / 10.0 | 6.4 / 7.6 | 6.5 / 7.6 | 1.0 |
| live-steady-50/s | no | 888.6 / 896.4 | 26.7 / 35.8 | 0.0 | 194.0 | 51.0 / 48.0 | 10.1 / 10.4 | 10.1 / 10.4 | 1.0 |
| live-steady-200/s | no | 895.4 / 896.3 | 30.4 / 33.5 | 0.0 | 194.0 | 201.0 / 190.0 | 10.7 / 11.7 | 10.7 / 11.7 | 1.0 |
| live-burst-10k-replay-reconnect | no | 888.2 / 889.5 | 33.2 / 33.6 | 0.0 | 282.0 | 10001.0 / 9.0 | 9.0 / 9.3 | 9.1 / 9.3 | 1.0 |
| job-detail-500-retained-events | no | 889.7 / 895.2 | 31.7 / 32.4 | 0.0 | 282.0 | 501.0 / 9.0 | 17.9 / 18.7 | 12.4 / 13.0 | 1.0 |
| metrics-100-buckets | no | 901.9 / 904.3 | no input | 0.0 | 1160.0 | 501.0 / 473.0 | 8.5 / 9.3 | 8.5 / 9.2 | 1.0 |
| metrics-1000-buckets | no | 902.4 / 903.2 | no input | 0.0 | 1183.0 | 501.0 / 473.0 | 8.5 / 18.5 | 8.5 / 18.5 | 1.0 |
| metrics-4096-buckets | no | 911.8 / 915.1 | no input | 0.0 | 1183.0 | 501.0 / 473.0 | 7.1 / 8.0 | 7.1 / 8.1 | 1.0 |
| jobs-list-100-filter-sort | no | 896.8 / 897.3 | no input | 0.0 | 604.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| schedules-100-countdown | no | 913.4 / 914.0 | 39.2 / 40.3 | 0.0 | 5927.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-dashboard | no | 129.8 / 129.9 | no input | 0.0 | 258.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-explorer | no | 887.1 / 892.6 | 26.0 / 38.8 | 0.0 | 215.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-configuration | no | 124.9 / 126.0 | 26.0 / 27.2 | 0.0 | 237.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-load | no | 180.4 / 190.2 | 21.9 / 35.0 | 0.0 | 4099.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-jobs | no | 896.9 / 897.5 | no input | 0.0 | 604.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-schedules | no | 916.4 / 923.4 | 25.0 / 26.4 | 0.0 | 5927.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-cold-settings | no | 895.8 / 896.9 | no input | 0.0 | 275.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| navigation-warm-core-routes | no | 129.6 / 131.3 | no input | 0.0 | 266.0 | 0.0 / 0.0 | n/a | n/a | 0.0 |
| resilience-hidden-tab-and-paused-scroll | no | 891.9 / 898.0 | 27.1 / 29.6 | 0.0 | 194.0 | 51.0 / 48.0 | 10.1 / 11.1 | 10.2 / 11.1 | 1.0 |

## Integrity and resources

| Scenario | Duplicate IDs | Missing/order mismatches | Journal order | SSE requests | JS heap p50 (bytes) | JS/CSS transferred p50 (bytes) |
| --- | ---: | ---: | --- | ---: | ---: | ---: |
| live-steady-10/s | 0.0 | 0.0 | unavailable | 1.0 | 5229176.0 | 562767.0 |
| live-steady-50/s | 0.0 | 0.0 | unavailable | 1.0 | 5361356.0 | 562767.0 |
| live-steady-200/s | 0.0 | 0.0 | unavailable | 1.0 | 5428984.0 | 562767.0 |
| live-burst-10k-replay-reconnect | 0.0 | 0.0 | true | 2.0 | 7505073.0 | 562767.0 |
| job-detail-500-retained-events | 0.0 | 0.0 | true | 1.0 | 5755941.0 | 562767.0 |
| metrics-100-buckets | 0.0 | 0.0 | true | 1.0 | 6457317.0 | 612244.0 |
| metrics-1000-buckets | 0.0 | 0.0 | true | 1.0 | 7361018.0 | 612244.0 |
| metrics-4096-buckets | 0.0 | 0.0 | true | 1.0 | 10177696.0 | 612244.0 |
| jobs-list-100-filter-sort | 0.0 | 0.0 | unavailable | 0.0 | 5528782.0 | 612244.0 |
| schedules-100-countdown | 0.0 | 0.0 | unavailable | 0.0 | 10322502.0 | 593568.0 |
| navigation-cold-dashboard | 0.0 | 0.0 | unavailable | 0.0 | 4156296.0 | 531456.0 |
| navigation-cold-explorer | 0.0 | 0.0 | unavailable | 0.0 | 4647951.0 | 559255.0 |
| navigation-cold-configuration | 0.0 | 0.0 | unavailable | 0.0 | 4377542.0 | 531456.0 |
| navigation-cold-load | 0.0 | 0.0 | unavailable | 0.0 | 9506007.0 | 531456.0 |
| navigation-cold-jobs | 0.0 | 0.0 | unavailable | 0.0 | 4936405.0 | 612244.0 |
| navigation-cold-schedules | 0.0 | 0.0 | unavailable | 0.0 | 9489582.0 | 593568.0 |
| navigation-cold-settings | 0.0 | 0.0 | unavailable | 0.0 | 4180429.0 | 556586.0 |
| navigation-warm-core-routes | 0.0 | 0.0 | unavailable | 0.0 | 7824905.0 | 727285.0 |
| resilience-hidden-tab-and-paused-scroll | 0.0 | 0.0 | unavailable | 1.0 | 5363108.0 | 562767.0 |

## Bundle gzip sizes

| Asset | Lazy entry | Raw bytes | Gzip bytes |
| --- | --- | ---: | ---: |
| assets/JobConsoleView-B3Oy1sG2.js | no | 19800 | 6789 |
| assets/JobConsoleView-C6X2GHD8.css | no | 9185 | 2231 |
| assets/dist-DE1Lrzs1.js | no | 26884 | 8740 |
| assets/jsx-runtime-Dk72oS4N.js | no | 8774 | 3345 |
| assets/preload-helper-CRzLMJPS.js | no | 1534 | 802 |
| assets/types-C3LSnZ5x.js | no | 251 | 165 |
| assets/index-WOAWwU84.js | no | 446349 | 128208 |
| assets/index-BCpAGL0g.css | no | 73048 | 12432 |
| assets/dist-CzaXXnCI.js | yes | 2061 | 1267 |
| assets/dist-Cn2dFRcN.js | yes | 15924 | 6888 |
| assets/dist-CuS1qwX2.js | yes | 14381 | 5718 |
| assets/dist-C4dvjZ1J.js | yes | 11628 | 5080 |
| assets/dist-DVzxfVxq.js | yes | 297687 | 95529 |
| assets/shell-DwuoZtxw.js | yes | 2434 | 1186 |
| assets/browser-CraaQKji.js | yes | 95953 | 29580 |
| assets/SettingsPage-Dka-qb6I.js | yes | 18698 | 4874 |
| assets/SettingsPage-6aA7xbkL.css | yes | 5832 | 1423 |
| assets/codemirrorRuntime-CpNtfrLp.js | yes | 75215 | 24873 |
| assets/ExplorerPage-C-Pyr9zy.js | yes | 27499 | 8263 |
| assets/FloatingJobConsole-6D1S_xZs.js | yes | 4408 | 1690 |
| assets/JobsPage-DFlSe53Y.js | yes | 50903 | 14772 |
| assets/LoadPage-DRjSTEP1.js | yes | 49336 | 13179 |
| assets/StandaloneJobConsolePage-7xkGXEN-.js | yes | 1426 | 758 |
| assets/ScheduledLoadPage-67UsKqW2.js | yes | 48714 | 13073 |
| assets/ScheduledLoadPage-27rod9YQ.css | yes | 12798 | 2602 |

## Raw samples

The companion JSON file contains every repetition and all captured fields, including long-task and frame samples, duplicate/missing Event-ID diagnostics, JS heap availability, mutation/rAF proxies, transferred JS/CSS bytes, and per-chunk gzip sizes.

