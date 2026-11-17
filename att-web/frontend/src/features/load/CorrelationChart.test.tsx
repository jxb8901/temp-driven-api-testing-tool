// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { CorrelationChart } from './CorrelationPanel';

const metric = { id: 'completedRate', field: 'completedRate', label: 'Completed TPS', unit: 'requests/s', rate: true };

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  Reflect.deleteProperty(globalThis, 'IS_REACT_ACT_ENVIRONMENT');
});

describe('CorrelationChart time rendering', () => {
  it('draws a range-overlapping LOAD bin within the selected chart range when its native start is earlier', async () => {
    const rangeStart = Date.parse('2026-11-14T00:00:30Z');
    const rangeEnd = Date.parse('2026-11-14T00:00:45Z');
    await act(async () => root.render(<CorrelationChart
      loadPoints={[{ x: rangeStart - 30_000, y: 10, durationMs: 60_000,
        visibleStart: rangeStart, visibleEnd: rangeEnd, partialRange: true }]}
      sutPoints={[{ x: rangeStart + 5_000, y: 20 }]}
      loadMetric={metric} sutUnit="%" loadResolution={60_000} sutResolution={15_000}
      boundaries={[]} domain={[rangeStart, rangeEnd]}
    />));

    const loadPath = host.querySelector<SVGPathElement>('path.att-correlation-load-line');
    expect(loadPath).not.toBeNull();
    expect(loadPath?.getAttribute('d')).toMatch(/^M82,[^H]+H878$/);
    expect(loadPath?.textContent).toContain('full bin');
  });

  it('adds UTC dates to the first tick and to ticks after a day boundary on a 25-hour chart', async () => {
    const start = Date.parse('2026-11-13T12:00:00Z');
    const end = start + 25 * 60 * 60 * 1000;
    const loadPoints = Array.from({ length: 25 * 60 }, (_, index) => {
      const x = start + index * 60_000;
      return { x, y: 10 + index % 5, durationMs: 60_000, visibleStart: x, visibleEnd: x + 60_000 };
    });
    await act(async () => root.render(<CorrelationChart
      loadPoints={loadPoints}
      sutPoints={[{ x: start + 12 * 60 * 60 * 1000, y: 20 }, { x: end - 1, y: 30 }]}
      loadMetric={metric} sutUnit="%" loadResolution={60_000} sutResolution={60_000}
      boundaries={[]} domain={[start, end]}
    />));

    const ticks = Array.from(host.querySelectorAll('.att-correlation-time-tick'), tick => tick.textContent || '');
    expect(ticks[0]).toContain('13 Nov 2026');
    expect(ticks.some(tick => tick.includes('14 Nov 2026'))).toBe(true);
  });

  it('draws downsampled LOAD bins independently without strokes across omitted bins', async () => {
    const start = Date.parse('2026-11-13T12:00:00Z');
    const pointCount = 1501;
    const durationMs = 60_000;
    const end = start + pointCount * durationMs;
    const loadPoints = Array.from({ length: pointCount }, (_, index) => {
      const x = start + index * durationMs;
      return { x, y: 10 + index % 7, durationMs, visibleStart: x, visibleEnd: x + durationMs };
    });
    await act(async () => root.render(<CorrelationChart
      loadPoints={loadPoints}
      sutPoints={[{ x: start + durationMs / 2, y: 20 }, { x: end - 1, y: 30 }]}
      loadMetric={metric} sutUnit="%" loadResolution={durationMs} sutResolution={durationMs}
      boundaries={[]} domain={[start, end]}
    />));

    const strokes = Array.from(host.querySelectorAll<SVGPathElement>('path.att-correlation-load-line'));
    expect(strokes).toHaveLength(1200);
    const spans = strokes.map(stroke => {
      const match = /^M(-?(?:\d+(?:\.\d*)?|\.\d+)),-?(?:\d+(?:\.\d*)?|\.\d+)H(-?(?:\d+(?:\.\d*)?|\.\d+))$/.exec(stroke.getAttribute('d') || '');
      expect(match).not.toBeNull();
      return Number(match?.[2]) - Number(match?.[1]);
    });
    const nativeBinWidth = (960 - 82 - 82) * durationMs / (pointCount * durationMs);
    expect(Math.max(...spans)).toBeCloseTo(nativeBinWidth, 6);
    expect(host.textContent).toContain('1200/1501 LOAD points');
  });
});
