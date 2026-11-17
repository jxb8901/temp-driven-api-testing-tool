// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { downsampleSegments, exclusiveObservedEnd, meanForLoadMetric, meanForSutMetric, missingIntervals, parseUtcInstant, queriesEligibleForMetricGroup, selectLoadPoints, selectSutPoints, splitSeriesAtGaps } from './CorrelationPanel';

describe('LOAD and SUT correlation series', () => {
  it('selects every query with pinned observations for the chosen label group', () => {
    const queries = [
      { id: 'cpu', metricId: 'cpu', targetId: 'sut-1' },
      { id: 'memory', metricId: 'memory', targetId: 'sut-1' },
      { id: 'sessions', metricId: 'sessions', targetId: 'sut-1' },
    ];
    const items = [
      { queryId: 'cpu', metricId: 'cpu', title: 'CPU', targetId: 'sut-1', unit: 'percent', groupView: { assignments: [
        { observationId: 'cpu-app', groupId: 'app', labels: { role: 'app' } },
        { observationId: 'cpu-db', groupId: 'db', labels: { role: 'db' } },
      ] } },
      { queryId: 'memory', metricId: 'memory', title: 'Memory', targetId: 'sut-1', unit: 'bytes', groupView: { assignments: [
        { observationId: 'memory-app', groupId: 'app', labels: { role: 'app' } },
      ] } },
      { queryId: 'sessions', metricId: 'sessions', title: 'Sessions', targetId: 'sut-1', unit: 'count', groupView: { assignments: [
        { observationId: 'sessions-db', groupId: 'db', labels: { role: 'db' } },
      ] } },
    ];

    expect(queriesEligibleForMetricGroup(queries, items, 'app').map(query => query.id)).toEqual(['cpu', 'memory']);
    expect(queriesEligibleForMetricGroup(queries, items, 'db').map(query => query.id)).toEqual(['cpu', 'sessions']);
  });

  it('selects bins by actual phase overlap and preserves partial boundary bins without filling missing bins', () => {
    const points = selectLoadPoints([
      { windowStartEpochMs: 1500, durationMs: 1000, completedRate: 2 },
      { windowStartEpochMs: 2500, durationMs: 1000, completedRate: 3 },
      { windowStartEpochMs: 4500, durationMs: 1000, completedRate: 5 },
    ], { id: 'completedRate', field: 'completedRate', label: 'Completed TPS', unit: 'requests/s' }, 'STEADY', [
      { phase: 'STEADY', windowStartEpochMs: 2000, windowEndEpochMs: 4000 },
    ]);

    expect(points).toEqual([
      { x: 1500, y: 2, durationMs: 1000, visibleStart: 2000, visibleEnd: 2500, partialPhase: true, partialRange: false, cohortSamples: 0 },
      { x: 2500, y: 3, durationMs: 1000, visibleStart: 2500, visibleEnd: 3500, partialPhase: false, partialRange: false, cohortSamples: 0 },
    ]);
    expect(points).toHaveLength(2);
  });

  it('uses half-open interval overlap for custom ranges and marks bins clipped by either boundary', () => {
    const points = selectLoadPoints([
      { windowStartEpochMs: 0, durationMs: 60_000, completedRate: 2 },
      { windowStartEpochMs: 60_000, durationMs: 60_000, completedRate: 3 },
      { windowStartEpochMs: 120_000, durationMs: 60_000, completedRate: 4 },
    ], { id: 'completedRate', field: 'completedRate', label: 'Completed TPS', unit: 'requests/s' }, 'all', [], 30_000, 90_000);

    expect(points).toEqual([
      { x: 0, y: 2, durationMs: 60_000, visibleStart: 30_000, visibleEnd: 60_000, partialPhase: false, partialRange: true, cohortSamples: 0 },
      { x: 60_000, y: 3, durationMs: 60_000, visibleStart: 60_000, visibleEnd: 90_000, partialPhase: false, partialRange: true, cohortSamples: 0 },
    ]);
    // Retained bin values and durations are unchanged; the display discloses that each bin is partial.
    expect(points.map(point => [point.y, point.durationMs])).toEqual([[2, 60_000], [3, 60_000]]);
  });

  it('labels the arithmetic mean of unequal-duration rates as unweighted and retains clipped bin rates', () => {
    const points = selectLoadPoints([
      { windowStartEpochMs: 0, durationMs: 1000, completedRate: 0 },
      { windowStartEpochMs: 1000, durationMs: 9000, completedRate: 100 },
    ], { id: 'completedRate', field: 'completedRate', label: 'Completed TPS', unit: 'requests/s', rate: true },
    'all', [], 500, 10_000);

    expect(points.map(point => [point.y, point.durationMs, point.partialRange])).toEqual([
      [0, 1000, true], [100, 9000, false],
    ]);
    expect(meanForLoadMetric(points, false)).toBe(50);
  });

  it('keeps the latest SUT sample inside a preset window with an exclusive end', () => {
    const samples = [
      { timestamp: '1970-01-01T00:00:30.000Z', timestampEpochMs: 30_000, value: 10 },
      { timestamp: '1970-01-01T00:00:45.000Z', timestampEpochMs: 45_000, value: 20 },
    ];
    const loadBins = [{ windowStartEpochMs: 0, durationMs: 30_000, completedRate: 2 }];
    const end = exclusiveObservedEnd(loadBins, samples, 15_000, null);
    const latest = selectSutPoints(samples, 'all', [], end - 15 * 60_000, end);

    expect(end).toBe(60_000);
    expect(latest.at(-1)).toEqual({ x: 45_000, y: 20 });
  });

  it('anchors a preset window after the end of the newest native LOAD bin', () => {
    const samples = [{ timestamp: '1970-01-01T00:00:45.000Z', timestampEpochMs: 45_000, value: 20 }];
    const loadBins = [{ windowStartEpochMs: 60_000, durationMs: 60_000, completedRate: 3 }];
    const end = exclusiveObservedEnd(loadBins, samples, 15_000, null);
    const latest = selectLoadPoints(loadBins, { id: 'completedRate', field: 'completedRate', label: 'Completed TPS', unit: 'requests/s' },
      'all', [], end - 15 * 60_000, end);

    expect(end).toBe(120_000);
    expect(latest).toHaveLength(1);
    expect(latest[0]?.visibleEnd).toBe(end);
  });

  it('uses UTC epoch sample times for phase and range selection', () => {
    const selected = selectSutPoints([
      { timestamp: '1970-01-01T00:00:03.000Z', timestampEpochMs: 3000, value: 40 },
      { timestamp: '1970-01-01T00:00:04.000Z', timestampEpochMs: 4000, value: 50 },
      { timestamp: '1970-01-01T00:00:04.500Z', timestampEpochMs: 4500, value: 60 },
    ], 'STEADY', [{ phase: 'STEADY', windowStartEpochMs: 3500, windowEndEpochMs: 5000 }], 3500, 4500);

    expect(selected).toEqual([{ x: 4000, y: 50 }]);
  });

  it('returns no samples when the selected phase has no archived boundary', () => {
    const samples = [
      { timestamp: '1970-01-01T00:00:03.000Z', timestampEpochMs: 3000, value: 40 },
      { timestamp: '1970-01-01T00:00:04.000Z', timestampEpochMs: 4000, value: 50 },
    ];
    expect(selectSutPoints(samples, 'STEADY', [], 0, 5000)).toEqual([]);
    expect(selectLoadPoints([
      { windowStartEpochMs: 2000, durationMs: 1000, completedRate: 2 },
    ], { id: 'completedRate', field: 'completedRate', label: 'Completed TPS', unit: 'requests/s' }, 'STEADY', [], 0, 5000)).toEqual([]);
  });

  it('reports missing intervals and breaks the line at those gaps', () => {
    const points = [{ x: 1000, y: 1 }, { x: 2000, y: 2 }, { x: 5000, y: 5 }];
    expect(missingIntervals(points, 1000)).toBe(2);
    expect(splitSeriesAtGaps(points, 1000)).toEqual([[points[0], points[1]], [points[2]]]);
  });

  it('does not calculate a mean across scheduled-cohort p95 bins', () => {
    const p95Bins = [{ x: 1000, y: 80 }, { x: 2000, y: 120 }];
    const rateBins = [{ x: 1000, y: 2 }, { x: 2000, y: 4 }];
    expect(meanForLoadMetric(p95Bins, true)).toBeNull();
    expect(meanForLoadMetric(rateBins, false)).toBe(3);
  });

  it('documents SUT sample means for gauges and rates but does not average counters or histograms', () => {
    const points = [{ x: 1000, y: 20 }, { x: 2000, y: 40 }];
    expect(meanForSutMetric(points, 'gauge')).toBe(30);
    expect(meanForSutMetric(points, 'rate')).toBe(30);
    expect(meanForSutMetric(points, 'counter')).toBeNull();
    expect(meanForSutMetric(points, 'histogram')).toBeNull();
    expect(meanForSutMetric(points)).toBeNull();
  });

  it('accepts strict UTC custom-range instants and rejects local or impossible timestamps', () => {
    expect(parseUtcInstant('2026-11-14T02:10:00Z')).toBe(Date.parse('2026-11-14T02:10:00Z'));
    expect(parseUtcInstant('2026-11-14T02:10:00.125Z')).toBe(Date.parse('2026-11-14T02:10:00.125Z'));
    expect(parseUtcInstant('2026-11-14T02:10:00')).toBeNull();
    expect(parseUtcInstant('2026-02-31T02:10:00Z')).toBeNull();
  });

  it('keeps a 25-hour native series under the display cap and preserves an isolated spike', () => {
    const points = Array.from({ length: 25 * 60 + 1 }, (_, index) => ({ x: index * 60_000, y: 20 + index % 7 }));
    points[745] = { x: 745 * 60_000, y: 99_999 };
    const displayed = downsampleSegments(points, 60_000);
    expect(displayed.total).toBeLessThanOrEqual(1200);
    expect(displayed.segments).toHaveLength(1);
    expect(displayed.segments[0]?.[0]).toEqual(points[0]);
    expect(displayed.segments[0]?.at(-1)).toEqual(points.at(-1));
    expect(displayed.segments.flat()).toContain(points[745]);
  });
});
