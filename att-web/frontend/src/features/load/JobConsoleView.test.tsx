// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { JobEventHandlers, LoadJobsApi } from './types';
import { JobConsoleView, consoleRouteUrl } from './JobConsoleView';
import { EVENT_FLUSH_INTERVAL_MS, MAX_EVENTS } from './useJobEventStream';

const job = { jobId: 'J/1', command: 'load', executionType: 'load', packageId: 'p', status: 'RUNNING' as const };
function makeApi() {
  let handler!: JobEventHandlers;
  const api = { subscribeJob: vi.fn((_id: string, next: JobEventHandlers) => { handler = next; return vi.fn(); }) } as unknown as LoadJobsApi;
  return { api, handler: () => handler };
}

describe('JobConsoleView', () => {
  let host: HTMLDivElement; let root: Root;
  beforeEach(() => { vi.useFakeTimers(); host = document.createElement('div'); document.body.append(host); root = createRoot(host); (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true; });
  afterEach(() => { act(() => root.unmount()); host.remove(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });

  async function flushEvents(): Promise<void> {
    await act(async () => { await vi.advanceTimersByTimeAsync(EVENT_FLUSH_INTERVAL_MS); });
  }

  function setScrollMetrics(element: HTMLElement, scrollHeight: () => number, clientHeight: number) {
    let scrollTop = 0;
    const writes: number[] = [];
    Object.defineProperties(element, {
      scrollHeight: { configurable: true, get: scrollHeight },
      clientHeight: { configurable: true, value: clientHeight },
      scrollTop: {
        configurable: true,
        get: () => scrollTop,
        set: (value: number) => {
          scrollTop = Math.min(value, Math.max(0, scrollHeight() - clientHeight));
          writes.push(scrollTop);
        },
      },
    });
    return {
      get: () => scrollTop,
      set: (value: number) => { scrollTop = value; },
      writes,
    };
  }

  it('does not render event rows while minimized and keeps the stream attached', async () => {
    const { api, handler } = makeApi();
    await act(async () => root.render(<JobConsoleView api={api} jobId={job.jobId} job={job} minimized onRestore={vi.fn()} />));
    handler().onEvent({ id: '1', type: 'log', data: { console: 'line' } });
    expect(host.querySelector('.att-job-events')).toBeNull();
    expect(host.querySelector('[aria-label="Restore console for Job J/1"]')).not.toBeNull();
  });

  it('keeps standalone controls tied to one Job and renders an empty output surface', async () => {
    const { api } = makeApi(); const onViewChange = vi.fn();
    await act(async () => root.render(<JobConsoleView api={api} jobId={job.jobId} job={job} standalone view="live" onViewChange={onViewChange} onMinimize={vi.fn()} onClose={vi.fn()} />));
    expect(host.querySelector('.att-console-shell')).not.toBeNull();
    expect(host.querySelector('pre.att-console')).not.toBeNull();
    expect(host.querySelector('[role="separator"]')).toBeNull();
    expect(host.textContent).not.toMatch(/Details|New tab|Minimize|Close console/);
    const events = [...host.querySelectorAll('button')].find(button => button.textContent === 'Events')!;
    await act(async () => events.click());
    expect(onViewChange).toHaveBeenCalledWith('events');
    expect(api.subscribeJob).toHaveBeenCalledTimes(1);
    expect(vi.mocked(api.subscribeJob).mock.calls[0][0]).toBe(job.jobId);
  });

  it('builds an encoded same-origin noopener console URL', () => {
    const value = new URL(consoleRouteUrl(job.jobId, 'events'));
    expect(value.origin).toBe(window.location.origin);
    expect(value.hash).toBe('#/jobs/J%2F1/console?view=events');
    expect(value.pathname).toBe(window.location.pathname);
  });

  it.each(['live', 'events'] as const)('keeps %s auto-scroll following new events after the 500-entry ring fills', async view => {
    const { api, handler } = makeApi();
    let latestId = 0;
    await act(async () => root.render(<JobConsoleView api={api} jobId={job.jobId} job={job} view={view} />));
    expect(host.querySelector<HTMLInputElement>(`[aria-label="${view === 'live' ? 'Live output' : 'Events'} auto-scroll"]`)?.checked).toBe(true);
    act(() => {
      for (let id = 1; id <= MAX_EVENTS + 1; id += 1) {
        latestId = id;
        handler().onEvent({ id: String(id), type: 'log', data: { console: `line-${id}${'\n'.repeat(id % 17)}`, message: `entry-${id}` } });
      }
    });
    await flushEvents();

    const scroller = view === 'live'
      ? host.querySelector<HTMLElement>('.att-console')!
      : host.querySelector<HTMLElement>('.att-job-events')!;
    const scrollHeight = view === 'live'
      ? () => (scroller.textContent || '').split('\n').length * 20
      : () => MAX_EVENTS * 112;
    const metrics = setScrollMetrics(scroller, scrollHeight, 224);
    metrics.set(Math.max(0, scrollHeight() - 224));
    await act(async () => { scroller.dispatchEvent(new Event('scroll', { bubbles: true })); });
    metrics.writes.length = 0;

    act(() => {
      for (let id = MAX_EVENTS + 2; id <= MAX_EVENTS + 5; id += 1) {
        latestId = id;
        handler().onEvent({ id: String(id), type: 'log', data: { console: `line-${id}${'\n'.repeat(id % 17)}`, message: `entry-${id}` } });
      }
    });
    await flushEvents();

    expect(metrics.writes.length).toBeGreaterThan(0);
    expect(metrics.get()).toBe(Math.max(0, scrollHeight() - 224));
    if (view === 'live') {
      expect(scroller.textContent).toContain(`line-${latestId}`);
    } else {
      await act(async () => { scroller.dispatchEvent(new Event('scroll', { bubbles: true })); });
      const lastRow = [...scroller.querySelectorAll<HTMLElement>('li[aria-posinset]')].at(-1)!;
      expect(lastRow.getAttribute('aria-posinset')).toBe(String(MAX_EVENTS));
      expect(lastRow.textContent).toContain(`#${latestId}`);
    }
  });

  it.each(['live', 'events'] as const)('does not move a scrolled-up %s view when new events arrive', async view => {
    const { api, handler } = makeApi();
    await act(async () => root.render(<JobConsoleView api={api} jobId={job.jobId} job={job} view={view} />));
    act(() => {
      for (let id = 1; id <= MAX_EVENTS + 1; id += 1) {
        handler().onEvent({ id: String(id), type: 'log', data: { console: `line-${id}`, message: `entry-${id}` } });
      }
    });
    await flushEvents();

    const scroller = view === 'live'
      ? host.querySelector<HTMLElement>('.att-console')!
      : host.querySelector<HTMLElement>('.att-job-events')!;
    const metrics = setScrollMetrics(scroller, () => view === 'live'
      ? (scroller.textContent || '').split('\n').length * 20
      : MAX_EVENTS * 112, 224);
    metrics.set(0);
    await act(async () => { scroller.dispatchEvent(new Event('scroll', { bubbles: true })); });
    metrics.writes.length = 0;

    act(() => {
      handler().onEvent({ id: String(MAX_EVENTS + 2), type: 'log', data: { console: `line-${MAX_EVENTS + 2}`, message: 'later entry' } });
    });
    await flushEvents();

    expect(metrics.writes).toHaveLength(0);
    expect(metrics.get()).toBe(0);
    expect(host.textContent).toContain('Resume auto-scroll');
  });

  it('recomputes event spacers when a ResizeObserver reports a row height change', async () => {
    type TestObserver = {
      targets: Set<Element>;
      observe: (target: Element) => void;
      unobserve: (target: Element) => void;
      disconnect: () => void;
      trigger: (target: Element) => void;
    };
    const observers: TestObserver[] = [];
    class MockResizeObserver {
      private readonly callback: ResizeObserverCallback;
      readonly targets = new Set<Element>();
      constructor(callback: ResizeObserverCallback) {
        this.callback = callback;
        observers.push(this);
      }
      observe(target: Element) { this.targets.add(target); }
      unobserve(target: Element) { this.targets.delete(target); }
      disconnect() { this.targets.clear(); }
      trigger(target: Element) {
        this.callback([{ target, contentRect: { height: 300 } } as ResizeObserverEntry], this as unknown as ResizeObserver);
      }
    }
    vi.stubGlobal('ResizeObserver', MockResizeObserver);

    const { api, handler } = makeApi();
    await act(async () => root.render(<JobConsoleView api={api} jobId={job.jobId} job={job} view="events" />));
    act(() => {
      for (let id = 1; id <= 40; id += 1) {
        handler().onEvent({ id: String(id), type: 'log', data: { console: `line-${id}`, message: `preview-${id}` } });
      }
    });
    await flushEvents();

    const list = host.querySelector<HTMLOListElement>('.att-job-events')!;
    const eventRows = () => [...list.querySelectorAll<HTMLElement>('li[aria-posinset]')];
    const rowsBefore = eventRows();
    const bottomSpacer = list.querySelectorAll<HTMLElement>('.att-event-virtual-spacer')[1]!;
    const spacerBefore = Number.parseFloat(bottomSpacer.style.height);
    const row = list.querySelector<HTMLElement>('li[aria-posinset="1"]')!;
    Object.defineProperty(row, 'getBoundingClientRect', {
      configurable: true,
      value: () => ({ height: 300, width: 400, top: 0, bottom: 300, left: 0, right: 400, x: 0, y: 0, toJSON: () => ({}) } as DOMRect),
    });
    expect(observers).toHaveLength(1);
    expect(observers[0]!.targets.has(row)).toBe(true);
    expect(rowsBefore.every((item, index) => index === 0
      || Number(item.getAttribute('aria-posinset')) === Number(rowsBefore[index - 1]!.getAttribute('aria-posinset')) + 1)).toBe(true);

    act(() => observers[0]!.trigger(row));

    const rowsAfter = eventRows();
    const spacerAfter = Number.parseFloat(list.querySelectorAll<HTMLElement>('.att-event-virtual-spacer')[1]!.style.height);
    expect(rowsAfter.length).toBeLessThan(rowsBefore.length);
    expect(spacerAfter).not.toBe(spacerBefore);
    expect(rowsAfter.every((item, index) => index === 0
      || Number(item.getAttribute('aria-posinset')) === Number(rowsAfter[index - 1]!.getAttribute('aria-posinset')) + 1)).toBe(true);
  });
});
