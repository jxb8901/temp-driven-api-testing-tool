// @vitest-environment jsdom
import { act, StrictMode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { renderToString } from 'react-dom/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { JobEventHandlers, LoadJobsApi } from './types';
import { hasJobEventStreamEntryForTesting, MAX_EVENTS, useJobEventStream } from './useJobEventStream';

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

function Consumer({ api, jobId, name = jobId }: { api: LoadJobsApi; jobId: string; name?: string }) {
  const stream = useJobEventStream(api, jobId);
  return <output data-name={name} data-job={jobId} data-count={stream.events.length} data-connection={stream.connection}>{stream.events.map(event => event.id).join(',')}</output>;
}

describe('useJobEventStream', () => {
  let host: HTMLDivElement;
  let root: Root;
  beforeEach(() => {
    host = document.createElement('div'); document.body.append(host); root = createRoot(host);
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  });
  afterEach(() => { act(() => root.unmount()); host.remove(); });

  it('keeps a render without subscribing read-only and does not create a store entry', () => {
    const { api } = makeApi();
    expect(renderToString(<Consumer api={api} jobId="J1" />)).toContain('data-job="J1"');
    expect(api.subscribeJob).not.toHaveBeenCalled();
    expect(hasJobEventStreamEntryForTesting(api, 'J1')).toBe(false);
  });

  it('shares one bounded subscription and releases it after the last consumer', async () => {
    const { api, latestHandler } = makeApi();
    await act(async () => root.render(<><Consumer api={api} jobId="J1" /><Consumer api={api} jobId="J1" /></>));
    expect(api.subscribeJob).toHaveBeenCalledTimes(1);
    latestHandler('J1')?.onOpen();
    for (let index = 0; index < MAX_EVENTS + 25; index++) latestHandler('J1')?.onEvent({ id: String(index), type: 'log', data: { console: String(index) } });
    await act(async () => await Promise.resolve());
    expect(host.querySelector('output')?.getAttribute('data-count')).toBe(String(MAX_EVENTS));
    latestHandler('J1')?.onEvent({ id: String(MAX_EVENTS + 24), type: 'log', data: 'duplicate' });
    expect(host.querySelector('output')?.textContent).not.toContain('duplicate');
    const unsubscribe = vi.mocked(api.subscribeJob).mock.results[0].value as ReturnType<typeof vi.fn>;
    await act(async () => root.render(<Consumer api={api} jobId="J1" />));
    expect(unsubscribe).not.toHaveBeenCalled();
    await act(async () => root.unmount());
    expect(unsubscribe).toHaveBeenCalledTimes(1);
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

    await act(async () => latestHandler('J1')?.onEvent({ id: 'shared', type: 'log', data: { console: 'shared' } }));
    expect(host.querySelector('[data-name="first"]')?.textContent).toBe('shared');
    expect(host.querySelector('[data-name="second"]')?.textContent).toBe('shared');
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

    await act(async () => root.unmount());
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

  it('shows existing events to a consumer that mounts later', async () => {
    const { api, active, latestHandler } = makeApi();
    await act(async () => root.render(<Consumer api={api} jobId="J1" name="first" />));
    await act(async () => latestHandler('J1')?.onEvent({ id: 'earlier', type: 'log', data: { console: 'earlier' } }));

    await act(async () => root.render(<>
      <Consumer api={api} jobId="J1" name="first" />
      <Consumer api={api} jobId="J1" name="second" />
    </>));
    expect(active('J1')).toHaveLength(1);
    expect(host.querySelector('[data-name="second"]')?.textContent).toBe('earlier');
  });
});
