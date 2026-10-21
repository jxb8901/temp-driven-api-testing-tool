// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { JobEventHandlers, LoadJobsApi } from './types';
import { JobConsoleView, consoleRouteUrl } from './JobConsoleView';

const job = { jobId: 'J/1', command: 'load', executionType: 'load', packageId: 'p', status: 'RUNNING' as const };
function makeApi() {
  let handler!: JobEventHandlers;
  const api = { subscribeJob: vi.fn((_id: string, next: JobEventHandlers) => { handler = next; return vi.fn(); }) } as unknown as LoadJobsApi;
  return { api, handler: () => handler };
}

describe('JobConsoleView', () => {
  let host: HTMLDivElement; let root: Root;
  beforeEach(() => { host = document.createElement('div'); document.body.append(host); root = createRoot(host); (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true; });
  afterEach(() => { act(() => root.unmount()); host.remove(); });

  it('does not render event rows while minimized and keeps the stream attached', async () => {
    const { api, handler } = makeApi();
    await act(async () => root.render(<JobConsoleView api={api} jobId={job.jobId} job={job} minimized onRestore={vi.fn()} />));
    handler().onEvent({ id: '1', type: 'log', data: { console: 'line' } });
    expect(host.querySelector('.att-job-events')).toBeNull();
    expect(host.querySelector('[aria-label="Restore console for Job J/1"]')).not.toBeNull();
  });

  it('builds an encoded same-origin noopener console URL', () => {
    const value = new URL(consoleRouteUrl(job.jobId, 'events'));
    expect(value.origin).toBe(window.location.origin);
    expect(value.hash).toBe('#/jobs/J%2F1/console?view=events');
    expect(value.pathname).toBe(window.location.pathname);
  });
});
