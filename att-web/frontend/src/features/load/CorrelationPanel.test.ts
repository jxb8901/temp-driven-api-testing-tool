// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { meanForLoadMetric, missingIntervals, selectLoadPoints, selectSutPoints, splitSeriesAtGaps } from './CorrelationPanel';

describe('LOAD and SUT correlation series', () => {
  it('selects bins by actual phase overlap and preserves partial boundary bins without filling missing bins', () => {
    const points = selectLoadPoints([
      { windowStartEpochMs: 1500, durationMs: 1000, completedRate: 2 },
      { windowStartEpochMs: 2500, durationMs: 1000, completedRate: 3 },
      { windowStartEpochMs: 4500, durationMs: 1000, completedRate: 5 },
    ], { id: 'completedRate', field: 'completedRate', label: 'Completed TPS', unit: 'requests/s' }, 'STEADY', [
      { phase: 'STEADY', windowStartEpochMs: 2000, windowEndEpochMs: 4000 },
    ]);

    expect(points).toEqual([
      { x: 1500, y: 2, durationMs: 1000, partial: true, cohortSamples: 0 },
      { x: 2500, y: 3, durationMs: 1000, partial: false, cohortSamples: 0 },
    ]);
    expect(points).toHaveLength(2);
  });

  it('uses UTC epoch sample times for phase and range selection', () => {
    const selected = selectSutPoints([
      { timestamp: '1970-01-01T00:00:03.000Z', timestampEpochMs: 3000, value: 40 },
      { timestamp: '1970-01-01T00:00:04.000Z', timestampEpochMs: 4000, value: 50 },
    ], 'STEADY', [{ phase: 'STEADY', windowStartEpochMs: 3500, windowEndEpochMs: 5000 }], 3500, 4500);

    expect(selected).toEqual([{ x: 4000, y: 50 }]);
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
});
