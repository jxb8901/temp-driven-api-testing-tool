// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { JobEventHandlers, LoadJobsApi } from './types';
import { MAX_EVENTS, useJobEventStream } from './useJobEventStream';

function makeApi() {
  let handler: JobEventHandlers | undefined;
  const api = { subscribeJob: vi.fn((_jobId: string, next: JobEventHandlers) => { handler = next; return vi.fn(); }) } as unknown as LoadJobsApi;
  return { api, handler: () => handler! };
}

function Consumer({ api, jobId }: { api: LoadJobsApi; jobId: string }) {
  const stream = useJobEventStream(api, jobId);
  return <output data-job={jobId} data-count={stream.events.length} data-connection={stream.connection}>{stream.events.map(event => event.id).join(',')}</output>;
}

describe('useJobEventStream', () => {
  let host: HTMLDivElement;
  let root: Root;
  beforeEach(() => {
    host = document.createElement('div'); document.body.append(host); root = createRoot(host);
    (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
  });
  afterEach(() => { act(() => root.unmount()); host.remove(); });

  it('shares one bounded subscription and releases it after the last consumer', async () => {
    const { api, handler } = makeApi();
    await act(async () => root.render(<><Consumer api={api} jobId="J1" /><Consumer api={api} jobId="J1" /></>));
    expect(api.subscribeJob).toHaveBeenCalledTimes(1);
    handler().onOpen();
    for (let index = 0; index < MAX_EVENTS + 25; index++) handler().onEvent({ id: String(index), type: 'log', data: { console: String(index) } });
    await act(async () => await Promise.resolve());
    expect(host.querySelector('output')?.getAttribute('data-count')).toBe(String(MAX_EVENTS));
    handler().onEvent({ id: String(MAX_EVENTS + 24), type: 'log', data: 'duplicate' });
    expect(host.querySelector('output')?.textContent).not.toContain('duplicate');
    const unsubscribe = vi.mocked(api.subscribeJob).mock.results[0].value as ReturnType<typeof vi.fn>;
    await act(async () => root.render(<Consumer api={api} jobId="J1" />));
    expect(unsubscribe).not.toHaveBeenCalled();
    await act(async () => root.unmount());
    expect(unsubscribe).toHaveBeenCalledTimes(1);
  });

  it('disposes the old Job stream when a consumer switches Job', async () => {
    const first = makeApi();
    await act(async () => root.render(<Consumer api={first.api} jobId="J1" />));
    const firstUnsubscribe = vi.mocked(first.api.subscribeJob).mock.results[0].value as ReturnType<typeof vi.fn>;
    const second = makeApi();
    await act(async () => root.render(<Consumer api={second.api} jobId="J2" />));
    expect(firstUnsubscribe).toHaveBeenCalledTimes(1);
    expect(second.api.subscribeJob).toHaveBeenCalledWith('J2', expect.anything());
  });
});
