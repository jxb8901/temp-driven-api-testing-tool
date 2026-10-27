// @vitest-environment jsdom
import { act, StrictMode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToString } from 'react-dom/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { JobEventHandlers, LoadJobsApi } from './types';
import {
  COUNTER_FLUSH_INTERVAL_MS,
  EVENT_FLUSH_INTERVAL_MS,
  hasJobEventStreamEntryForTesting,
  jobEventStreamStateForTesting,
  MAX_EVENTS,
  MAX_CONSOLE_LINES,
  MAX_TRACKED_EVENT_IDS,
  useJobEventStream,
  type JobEventStreamSnapshot,
} from './useJobEventStream';

function makeApi() {
  const subscriptions: Array<{ jobId: string; handlers: JobEventHandlers; active: boolean }> = [];
  const api = {
    subscribeJob: vi.fn((jobId: string, handlers: JobEventHandlers) => {
      const subscription = { jobId, handlers, active: true };
      subscriptions.push(subscription);
      return vi.fn(() => { subscription.active = false; });
    }),
  } as unknown as LoadJobsApi;
  return {
    api,
    active: (jobId?: string) => subscriptions.filter(subscription => subscription.active && (!jobId || subscription.jobId === jobId)),
    latestHandler: (jobId: string) => subscriptions.filter(subscription => subscription.jobId === jobId).at(-1)?.handlers,
  };
}

function Consumer({ api, jobId, name = jobId, onSnapshot }: {
  api: LoadJobsApi;
  jobId: string;
  name?: string;
  onSnapshot?: (snapshot: JobEventStreamSnapshot) => void;
}) {
  const stream = useJobEventStream(api, jobId);
  onSnapshot?.(stream);
  return <output
    data-name={name}
    data-job={jobId}
    data-count={stream.events.length}
    data-connection={stream.connection}
    data-duplicates={stream.duplicatesIgnored}
    data-stale={stream.staleIgnored}
    data-gaps={stream.gaps}
    data-console={stream.events.map(event => event.consoleText).filter(Boolean).join('\n')}
  >{stream.events.map(event => event.id).join(',')}</output>;
}

describe('useJobEventStream', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    vi.useFakeTimers();
    host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  });

  afterEach(() => {
    act(() => root.unmount());
    host.remove();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  async function flush(): Promise<void> {
    await act(async () => { await vi.advanceTimersByTimeAsync(EVENT_FLUSH_INTERVAL_MS); });
  }

  it('keeps a render without subscribing read-only and does not create a store entry', () => {
    const { api } = makeApi();
    expect(renderToString(<Consumer api={api} jobId="J1" />)).toContain('data-job="J1"');
    expect(api.subscribeJob).not.toHaveBeenCalled();
    expect(hasJobEventStreamEntryForTesting(api, 'J1')).toBe(false);
  });

  it('keeps a 10k-event burst bounded and publishes the correct last 500 in ID order', async () => {
    const { api, latestHandler } = makeApi();
    await act(async () => root.render(<><Consumer api={api} jobId="J1" name="first" /><Consumer api={api} jobId="J1" name="second" /></>));
    expect(api.subscribeJob).toHaveBeenCalledTimes(1);

    act(() => {
      for (let id = 1; id <= 10_000; id += 1) latestHandler('J1')?.onEvent({ id: String(id), type: 'log', data: { console: String(id) } });
    });
    expect(host.querySelector('output')?.getAttribute('data-count')).toBe('0');
    expect(jobEventStreamStateForTesting(api, 'J1')?.idIndexSize).toBe(MAX_TRACKED_EVENT_IDS);
    expect(jobEventStreamStateForTesting(api, 'J1')?.timerPending).toBe(true);

    await flush();
    const ids = host.querySelector('output')!.textContent!.split(',');
    expect(ids).toHaveLength(MAX_EVENTS);
    expect(ids[0]).toBe('9501');
    expect(ids.at(-1)).toBe('10000');
    expect(host.querySelector('output')?.getAttribute('data-count')).toBe(String(MAX_EVENTS));

    act(() => latestHandler('J1')?.onEvent({ id: '1', type: 'log', data: 'evicted-index replay' }));
    await act(async () => { await vi.advanceTimersByTimeAsync(COUNTER_FLUSH_INTERVAL_MS); });
    expect(host.querySelector('output')?.getAttribute('data-stale')).toBe('1');
    expect(host.querySelector('output')?.textContent?.split(',')[0]).toBe('9501');
  });

  it('retains console text in its own bounded ring when the event ring evicts those events', async () => {
    const { api, latestHandler } = makeApi();
    await act(async () => root.render(<Consumer api={api} jobId="J1" />));
    act(() => {
      latestHandler('J1')?.onEvent({ id: '1', type: 'log', data: { console: 'early console line' } });
      for (let id = 2; id <= MAX_EVENTS + 1; id += 1) latestHandler('J1')?.onEvent({ id: String(id), type: 'progress', data: { phase: 'RUNNING' } });
    });
    await flush();
    const snapshot = jobEventStreamStateForTesting(api, 'J1')?.snapshot;
    expect(snapshot?.events).toHaveLength(MAX_EVENTS);
    expect(snapshot?.events.some(event => event.id === '1')).toBe(false);
    expect(snapshot?.consoleLines.map(line => line.text)).toContain('early console line');
    expect(snapshot?.consoleTruncated).toBe(false);
  });

  it('bounds console retention independently and reports evicted console text', async () => {
    const { api, latestHandler } = makeApi();
    await act(async () => root.render(<Consumer api={api} jobId="J1" />));
    act(() => {
      for (let id = 1; id <= MAX_CONSOLE_LINES + 1; id += 1) latestHandler('J1')?.onEvent({ id: String(id), type: 'log', data: { console: `line-${id}` } });
    });
    await flush();
    const lines = jobEventStreamStateForTesting(api, 'J1')?.snapshot.consoleLines;
    expect(lines).toHaveLength(MAX_CONSOLE_LINES);
    expect(lines?.[0]?.text).toBe('line-2');
    expect(lines?.at(-1)?.text).toBe(`line-${MAX_CONSOLE_LINES + 1}`);
    expect(jobEventStreamStateForTesting(api, 'J1')?.snapshot.consoleTruncated).toBe(true);
  });

  it('ignores duplicates within and older than the visible window, including non-numeric IDs', async () => {
    const { api, latestHandler } = makeApi();
    await act(async () => root.render(<Consumer api={api} jobId="J1" />));
    act(() => {
      for (let id = 1; id <= 600; id += 1) latestHandler('J1')?.onEvent({ id: String(id), type: 'log', data: id });
      latestHandler('J1')?.onEvent({ id: '1', type: 'log', data: 'older than the visible window' });
      latestHandler('J1')?.onEvent({ id: '550', type: 'log', data: 'within the visible window' });
      latestHandler('J1')?.onEvent({ id: 'opaque-id', type: 'log', data: 'accepted once' });
      latestHandler('J1')?.onEvent({ id: 'opaque-id', type: 'log', data: 'opaque duplicate' });
    });
    await flush();

    const output = host.querySelector('output')!;
    expect(output.getAttribute('data-count')).toBe(String(MAX_EVENTS));
    expect(output.getAttribute('data-duplicates')).toBe('3');
    expect(output.textContent?.endsWith('opaque-id')).toBe(true);
    expect(output.textContent?.split(',').filter(id => id === '550')).toHaveLength(1);
  });

  it('deduplicates reconnect replay overlap without changing append order', async () => {
    const { api, latestHandler } = makeApi();
    await act(async () => root.render(<Consumer api={api} jobId="J1" />));
    act(() => {
      latestHandler('J1')?.onEvent({ id: '1', type: 'log', data: 'one' });
      latestHandler('J1')?.onEvent({ id: '2', type: 'log', data: 'two' });
      latestHandler('J1')?.onEvent({ id: '3', type: 'log', data: 'three' });
    });
    await flush();
    act(() => { latestHandler('J1')?.onError(); latestHandler('J1')?.onOpen(); });
    act(() => {
      latestHandler('J1')?.onEvent({ id: '2', type: 'log', data: 'replay two' });
      latestHandler('J1')?.onEvent({ id: '3', type: 'log', data: 'replay three' });
      latestHandler('J1')?.onEvent({ id: '4', type: 'log', data: 'four' });
    });
    await flush();

    const output = host.querySelector('output')!;
    expect(output.textContent).toBe('1,2,3,4');
    expect(output.getAttribute('data-duplicates')).toBe('2');
    expect(output.getAttribute('data-connection')).toBe('connected');
  });

  it('ignores an unseen stale numeric ID and counts numeric gaps without treating them as faults', async () => {
    const { api, latestHandler } = makeApi();
    await act(async () => root.render(<Consumer api={api} jobId="J1" />));
    act(() => {
      latestHandler('J1')?.onEvent({ id: '100', type: 'log', data: 'first' });
      latestHandler('J1')?.onEvent({ id: '102', type: 'log', data: 'gap is allowed' });
      latestHandler('J1')?.onEvent({ id: '101', type: 'log', data: 'stale and unseen' });
    });
    await flush();

    const output = host.querySelector('output')!;
    expect(output.textContent).toBe('100,102');
    expect(output.getAttribute('data-stale')).toBe('1');
    expect(output.getAttribute('data-gaps')).toBe('1');
  });

  it('out-of-order IDs: later-arriving lower IDs are counted stale and not inserted', async () => {
    const { api, latestHandler } = makeApi();
    await act(async () => root.render(<Consumer api={api} jobId="J1" />));
    act(() => {
      latestHandler('J1')?.onEvent({ id: '1', type: 'log', data: 'one' });
      latestHandler('J1')?.onEvent({ id: '3', type: 'log', data: 'three' });
      latestHandler('J1')?.onEvent({ id: '2', type: 'log', data: 'late two' });
      latestHandler('J1')?.onEvent({ id: '4', type: 'log', data: 'four' });
      latestHandler('J1')?.onEvent({ id: '3', type: 'log', data: 'duplicate three' });
    });
    await flush();

    const output = host.querySelector('output')!;
    expect(output.textContent).toBe('1,3,4');
    expect(output.getAttribute('data-stale')).toBe('1');
    expect(output.getAttribute('data-duplicates')).toBe('1');
    expect(output.getAttribute('data-gaps')).toBe('1');
  });

  it('publishes terminal status immediately without waiting for the ordinary flush', async () => {
    const { api, latestHandler } = makeApi();
    await act(async () => root.render(<Consumer api={api} jobId="J1" />));
    await act(async () => latestHandler('J1')?.onEvent({ id: '1', type: 'status', data: { status: 'PASS' } }));
    expect(host.querySelector('output')?.textContent).toBe('1');
    expect(jobEventStreamStateForTesting(api, 'J1')?.timerPending).toBe(false);
  });

  it('publishes cancellation-request feedback immediately', async () => {
    const { api, latestHandler } = makeApi();
    await act(async () => root.render(<Consumer api={api} jobId="J1" />));
    await act(async () => latestHandler('J1')?.onEvent({ id: '1', type: 'status', data: { status: 'CANCEL_REQUESTED' } }));
    expect(host.querySelector('output')?.textContent).toBe('1');
    expect(jobEventStreamStateForTesting(api, 'J1')?.timerPending).toBe(false);
  });

  it('coalesces many ordinary events into one presentation publish', async () => {
    const { api, latestHandler } = makeApi();
    const renders = vi.fn();
    await act(async () => root.render(<Consumer api={api} jobId="J1" onSnapshot={renders} />));
    renders.mockClear();

    act(() => {
      for (let id = 1; id <= 200; id += 1) latestHandler('J1')?.onEvent({ id: String(id), type: 'progress', data: id });
    });
    expect(renders).not.toHaveBeenCalled();
    await flush();
    expect(renders).toHaveBeenCalledTimes(1);
  });

  it('coalesces 50 counter-only duplicate replays into one trailing publish', async () => {
    const { api, latestHandler } = makeApi();
    const renders = vi.fn();
    await act(async () => root.render(<Consumer api={api} jobId="J1" onSnapshot={renders} />));
    act(() => latestHandler('J1')?.onEvent({ id: '1', type: 'log', data: 'original' }));
    await flush();
    renders.mockClear();

    await act(async () => {
      for (let replay = 0; replay < 50; replay += 1) {
        latestHandler('J1')?.onEvent({ id: '1', type: 'log', data: `replay-${replay}` });
        await vi.advanceTimersByTimeAsync(18);
      }
    });
    expect(renders).not.toHaveBeenCalled();
    expect(host.querySelector('output')?.getAttribute('data-duplicates')).toBe('0');

    await act(async () => { await vi.advanceTimersByTimeAsync(COUNTER_FLUSH_INTERVAL_MS - 900); });
    expect(renders).toHaveBeenCalledTimes(1);
    expect(host.querySelector('output')?.getAttribute('data-duplicates')).toBe('50');
  });

  it('suppresses ordinary hidden-document publishes and publishes once when visible', async () => {
    let hidden = true;
    vi.spyOn(document, 'hidden', 'get').mockImplementation(() => hidden);
    const { api, latestHandler } = makeApi();
    const renders = vi.fn();
    await act(async () => root.render(<Consumer api={api} jobId="J1" onSnapshot={renders} />));
    renders.mockClear();

    act(() => {
      latestHandler('J1')?.onOpen();
      latestHandler('J1')?.onEvent({ id: '1', type: 'log', data: 'one' });
      latestHandler('J1')?.onEvent({ id: '2', type: 'log', data: 'two' });
    });
    expect(jobEventStreamStateForTesting(api, 'J1')?.timerPending).toBe(false);
    await flush();
    expect(renders).not.toHaveBeenCalled();

    hidden = false;
    await act(async () => document.dispatchEvent(new Event('visibilitychange')));
    expect(renders).toHaveBeenCalledTimes(1);
    expect(host.querySelector('output')?.textContent).toBe('1,2');
    expect(host.querySelector('output')?.getAttribute('data-connection')).toBe('connected');
  });

  it('publishes terminal events immediately even while the document is hidden', async () => {
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(true);
    const { api, latestHandler } = makeApi();
    await act(async () => root.render(<Consumer api={api} jobId="J1" />));
    await act(async () => latestHandler('J1')?.onEvent({ id: '1', type: 'result', data: { status: 'PASS' } }));
    expect(host.querySelector('output')?.textContent).toBe('1');
    expect(host.querySelector('output')?.getAttribute('data-connection')).toBe('completed');
  });

  it('strips and stores console text once during ingest', async () => {
    const consoleValue = vi.fn(() => '\u001b[31mred\u001b[0m');
    const data = Object.defineProperty({}, 'console', { enumerable: true, get: consoleValue });
    const { api, latestHandler } = makeApi();
    await act(async () => root.render(<Consumer api={api} jobId="J1" />));
    act(() => latestHandler('J1')?.onEvent({ id: '1', type: 'log', data }));
    await flush();

    expect(consoleValue).toHaveBeenCalledTimes(1);
    expect(host.querySelector('output')?.getAttribute('data-console')).toBe('red');
  });

  it('does not replace an immutable snapshot when an immediate state transition is unchanged', async () => {
    const { api, latestHandler } = makeApi();
    await act(async () => root.render(<Consumer api={api} jobId="J1" />));
    act(() => latestHandler('J1')?.onOpen());
    const first = jobEventStreamStateForTesting(api, 'J1')!.snapshot;
    expect(Object.isFrozen(first)).toBe(true);
    expect(Object.isFrozen(first.events)).toBe(true);
    act(() => latestHandler('J1')?.onOpen());
    await flush();
    expect(jobEventStreamStateForTesting(api, 'J1')!.snapshot).toBe(first);
  });

  it('shares one subscription, clears its timer, and closes EventSource after the last release', async () => {
    const { api, active, latestHandler } = makeApi();
    await act(async () => root.render(<><Consumer api={api} jobId="J1" name="first" /><Consumer api={api} jobId="J1" name="second" /></>));
    expect(api.subscribeJob).toHaveBeenCalledTimes(1);
    act(() => latestHandler('J1')?.onEvent({ id: '1', type: 'log', data: 'pending' }));
    expect(jobEventStreamStateForTesting(api, 'J1')?.timerPending).toBe(true);

    await act(async () => root.render(<Consumer api={api} jobId="J1" name="first" />));
    expect(active('J1')).toHaveLength(1);
    await act(async () => root.render(<></>));
    expect(active('J1')).toHaveLength(0);
    expect(hasJobEventStreamEntryForTesting(api, 'J1')).toBe(false);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('reuses one live entry after release and resubscribe when a second consumer mounts', async () => {
    const { api, active, latestHandler } = makeApi();
    await act(async () => root.render(<StrictMode><Consumer api={api} jobId="J1" name="first" /></StrictMode>));
    expect(api.subscribeJob).toHaveBeenCalledTimes(2);
    expect(vi.mocked(api.subscribeJob).mock.results[0].value).toHaveBeenCalledTimes(1);
    expect(active('J1')).toHaveLength(1);

    await act(async () => root.render(
      <StrictMode>
        <Consumer api={api} jobId="J1" name="first" />
        <Consumer api={api} jobId="J1" name="second" />
      </StrictMode>,
    ));
    expect(api.subscribeJob).toHaveBeenCalledTimes(2);
    expect(active('J1')).toHaveLength(1);

    act(() => latestHandler('J1')?.onEvent({ id: 'opaque', type: 'log', data: { console: 'shared' } }));
    await flush();
    expect(host.querySelector('[data-name="first"]')?.textContent).toBe('opaque');
    expect(host.querySelector('[data-name="second"]')?.textContent).toBe('opaque');
  });

  it('keeps one active subscription for two StrictMode consumers and releases it on unmount', async () => {
    const { api, active } = makeApi();
    await act(async () => root.render(
      <StrictMode>
        <Consumer api={api} jobId="J1" name="first" />
        <Consumer api={api} jobId="J1" name="second" />
      </StrictMode>,
    ));
    expect(active('J1')).toHaveLength(1);

    await act(async () => root.render(<></>));
    expect(active('J1')).toHaveLength(0);
  });

  it('disposes the old Job stream when a consumer switches Job', async () => {
    const { api, active } = makeApi();
    await act(async () => root.render(<Consumer api={api} jobId="J1" />));
    expect(active('J1')).toHaveLength(1);

    await act(async () => root.render(<Consumer api={api} jobId="J2" />));
    expect(active('J1')).toHaveLength(0);
    expect(active('J2')).toHaveLength(1);
    expect(api.subscribeJob).toHaveBeenLastCalledWith('J2', expect.anything());
  });

  it('shows flushed existing events to a consumer that mounts later', async () => {
    const { api, active, latestHandler } = makeApi();
    await act(async () => root.render(<Consumer api={api} jobId="J1" name="first" />));
    act(() => latestHandler('J1')?.onEvent({ id: 'opaque', type: 'log', data: { console: 'earlier' } }));
    await flush();

    await act(async () => root.render(<>
      <Consumer api={api} jobId="J1" name="first" />
      <Consumer api={api} jobId="J1" name="second" />
    </>));
    expect(active('J1')).toHaveLength(1);
    expect(host.querySelector('[data-name="second"]')?.textContent).toBe('opaque');
  });
});
