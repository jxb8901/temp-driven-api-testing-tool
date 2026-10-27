# LOAD time-series retention follow-up

**Status:** Design follow-up for [#299](https://github.com/jxb8901/temp-driven-api-testing-tool/issues/299). This note records the retention question raised by #298; it does not implement a long soak or change LOAD scheduling semantics.

## Current behavior

`LoadMetrics` keeps at most 4,096 one-second buckets in a rolling ring. When a
slot is reused, the older bucket is evicted and the exact aggregate counters
continue to include the full run. `LoadWindowMetrics` separately retains a
bounded, whole-execution time series using uniform power-of-two roll-up. The
terminal result reports the ring capacity, retained coverage, eviction count,
run duration, and coarse-series bin width. See the [LOAD terminal result
contract](load-terminal-result.md).

This provides exact totals and bounded trend data. A long run has less
fine-grained detail in the rolling one-second series, and a whole-run coarse
series has a wider bin interval as the run grows.

## Design questions for #299 follow-up

1. Which run horizons and chart resolutions operators need, including whether
   high-resolution recent data and coarse historical data should coexist.
2. Whether a fixed multi-resolution hierarchy or bounded segmented sidecars
   best preserves useful detail within explicit memory and artifact budgets.
3. How each metric downsamples: additive counters must remain exact, while
   latency distributions need a documented bounded sketch or percentile policy.
4. How the API and Studio request time ranges, page older segments, and show
   gaps, roll-up width, and coverage without loading all history at once.
5. How result-size, archive-entry, and total-artifact limits constrain the
   sidecar format and retention period.

## Constraints for a future implementation

- Preserve execution status, exit code, threshold verdict, and exact aggregate
  counters independently from sampled time-series coverage.
- Keep the #297 compact terminal result under its current size budget; publish
  detailed series through bounded, authorized artifact references.
- Record the sample method, interval, covered start/end, and omitted or evicted
  count so charts never imply full-resolution history where none exists.
- Bound memory, storage, API response size, and per-request work. Pagination or
  range selection must avoid unbounded history reads and telemetry cardinality.
- Validate the chosen resolution and storage budget with a separate soak plan
  tracked by #299. Do not fold the soak into #298.

The design decision remains open until #299 establishes the required chart
horizon and resolution. This note is a starting point for that scoped decision.
