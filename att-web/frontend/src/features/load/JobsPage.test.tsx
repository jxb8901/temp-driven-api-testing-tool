// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { JobsPage } from './JobsPage';
import type { JobEventHandlers, JobResultResponse, JobStatus, JobSummary, JobView, LoadJobsApi } from './types';

const sampleJob: JobView = {
  jobId: 'JOB-204', command: 'load', packageId: 'payments', principal: 'qa', status: 'RUNNING',
  createdAt: '2026-10-13T09:42:00Z', startedAt: '2026-10-13T09:43:00Z',
  performance: { timings: { apiMs: 12 }, worker: { heapUsedBytes: 512 } },
};

const earlyBucket = Date.parse('2026-10-13T09:42:01.000Z');
const lateBucket = Date.parse('2026-10-13T09:42:02.000Z');
const sampleResult: JobResultResponse = {
  job: sampleJob,
  result: { status: 'PASS', metrics: { completedThroughput: 4.25, p95Ms: 81, buckets: {
    [String(lateBucket)]: { bucketStartEpochMs: lateBucket, bucketStart: new Date(lateBucket).toISOString(), completedTps: 5, p95Ms: 81, p99Ms: 98 },
    [String(earlyBucket)]: { bucketStartEpochMs: earlyBucket, bucketStart: new Date(earlyBucket).toISOString(), completedTps: 4, p95Ms: 72, p99Ms: 91 },
  } } },
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

function makeApi(overrides: Partial<LoadJobsApi> = {}): LoadJobsApi {
  return {
    getLoadPolicy: vi.fn(), getTargetLoadForm: vi.fn(), listTargets: vi.fn(), createLoadDraft: vi.fn(), submitLoadDraft: vi.fn(),
    listJobs: vi.fn(async () => []), getJob: vi.fn(async () => sampleJob),
    getJobResult: vi.fn(async () => sampleResult), getJobArtifacts: vi.fn(async () => [{ path: 'evidence/load-summary.json', size: 120 }]),
    cancelJob: vi.fn(async () => ({ jobId: sampleJob.jobId, status: 'CANCEL_REQUESTED' })),
    subscribeJob: vi.fn((_jobId: string, _handlers: JobEventHandlers) => () => {}),
    artifactHref: vi.fn((_jobId: string, path: string) => `/api/v1/jobs/JOB-204/artifacts/${path}`),
    ...overrides,
  };
}

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  vi.useFakeTimers();
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.useRealTimers();
});

async function advance(delay = 200) {
  await act(async () => { await vi.advanceTimersByTimeAsync(delay); });
}

async function click(element: Element) {
  await act(async () => { element.dispatchEvent(new MouseEvent('click', { bubbles: true })); });
}

describe('JobsPage React feature', () => {
  it('renders stable job tabs and API-backed content for a loaded job', async () => {
    const api = makeApi();
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} confirmCancel={() => true} />));
    await act(async () => { await Promise.resolve(); });

    const tabs = [
      ['Overview', 'overview'], ['Live logs', 'live'], ['Events', 'events'],
      ['Results', 'results'], ['Artifacts', 'artifacts'], ['Metrics', 'metrics'],
    ];
    expect([...host.querySelectorAll('[role="tab"]')].map(tab => tab.textContent)).toEqual(tabs.map(([label]) => label));
    for (const [, id] of tabs) {
      expect(host.querySelector(`#job-tab-${id}`)?.getAttribute('aria-controls')).toBe(`job-panel-${id}`);
      expect(host.querySelector(`#job-panel-${id}`)).not.toBeNull();
    }
    expect(host.querySelector('#job-panel-overview')?.textContent).toContain('RUNNING');
    expect(host.querySelector('#job-panel-results')?.textContent).toContain('Raw result payload');
    expect(host.querySelector('#job-panel-artifacts')?.textContent).toContain('load-summary.json');
    const metricsPanel = host.querySelector('#job-panel-metrics')!;
    expect(metricsPanel.textContent).toContain('p95Ms');
    expect(metricsPanel.querySelectorAll('svg[role="img"]')).toHaveLength(3);
    expect(metricsPanel.querySelector('svg[aria-label*="Completed throughput"]')?.textContent).toContain('Completed throughput by bucket start time');
    const bucketTimes = [...metricsPanel.querySelectorAll('[aria-label="Returned Load time-series buckets"] tbody time')]
      .map(time => time.getAttribute('datetime'));
    expect(bucketTimes).toEqual([new Date(earlyBucket).toISOString(), new Date(lateBucket).toISOString()]);

    const subscribe = vi.mocked(api.subscribeJob).mock.calls[0]?.[1];
    expect(subscribe).toBeDefined();
    await act(async () => {
      subscribe?.onOpen();
      subscribe?.onEvent({ id: 'evt-1', type: 'log', data: { message: 'worker ready' } });
      subscribe?.onEvent({ id: 'evt-2', type: 'status', data: { status: 'CANCELLED' } });
    });
    expect(host.querySelector('#job-panel-overview')?.textContent).toContain('CANCELLED');
    const liveTab = host.querySelector('#job-tab-live');
    expect(liveTab).not.toBeNull();
    await click(liveTab!);
    expect(host.querySelector('#job-panel-live')?.textContent).toContain('worker ready');
    expect(host.querySelector('#job-panel-live')?.textContent).toContain('connected');
  });

  it('keeps active list filters through an asynchronous refresh and bounds results to the returned rows', async () => {
    const next = deferred<JobSummary[]>();
    const listJobs = vi.fn<LoadJobsApi['listJobs']>()
      .mockResolvedValueOnce([
        { jobId: 'JOB-RUN', command: 'load', packageId: 'payments', principal: 'qa', status: 'RUNNING', createdAt: '2026-10-13T10:00:00Z' },
        { jobId: 'JOB-PASS', command: 'run', packageId: 'ledger', principal: 'qa', status: 'PASS', createdAt: '2026-10-13T09:00:00Z' },
      ])
      .mockReturnValueOnce(next.promise);
    const api = makeApi({ listJobs });
    const onOpenJob = vi.fn();
    await act(async () => root.render(<JobsPage api={api} onOpenJob={onOpenJob} onShowList={vi.fn()} />));
    await advance();
    expect(host.textContent).toContain('JOB-RUN');

    const status = host.querySelector('.att-jobs-filter-panel select') as HTMLSelectElement;
    await act(async () => {
      status.value = 'RUNNING';
      status.dispatchEvent(new Event('change', { bubbles: true }));
    });
    expect(status.value).toBe('RUNNING');
    expect(host.textContent).toContain('JOB-RUN');
    expect(host.textContent).not.toContain('JOB-PASS');

    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Refresh jobs')!);
    await advance();
    expect(host.textContent).toContain('Refreshing 2 loaded jobs');
    expect((host.querySelector('.att-jobs-filter-panel select') as HTMLSelectElement).value).toBe('RUNNING');

    await act(async () => next.resolve([
      { jobId: 'JOB-NEW', command: 'load', packageId: 'payments', principal: 'qa', status: 'PASS', createdAt: '2026-10-13T11:00:00Z' },
    ]));
    expect((host.querySelector('.att-jobs-filter-panel select') as HTMLSelectElement).value).toBe('RUNNING');
    expect(host.textContent).toContain('No loaded jobs match these filters.');
    expect(host.textContent).not.toContain('JOB-NEW');
    expect(host.textContent).toContain('at most 100 jobs');
    expect(listJobs).toHaveBeenCalledTimes(2);
  });

  it('does not overwrite a terminal SSE event with a delayed cancel response', async () => {
    const cancellation = deferred<{ jobId: string; status: JobStatus }>();
    const cancelJob = vi.fn<LoadJobsApi['cancelJob']>(() => cancellation.promise);
    const api = makeApi({ cancelJob });
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} confirmCancel={() => true} />));
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });

    await click(host.querySelector('.att-job-actions button.att-button-danger')!);
    await act(async () => { await Promise.resolve(); });
    expect(cancelJob).toHaveBeenCalledOnce();

    const handlers = vi.mocked(api.subscribeJob).mock.calls[0]?.[1];
    await act(async () => handlers?.onEvent({ id: 'evt-terminal', type: 'status', data: { status: 'CANCELLED' } }));
    expect(host.querySelector('.att-job-actions')?.textContent).toContain('CANCELLED');

    await act(async () => cancellation.resolve({ jobId: sampleJob.jobId, status: 'CANCEL_REQUESTED' }));
    expect(host.querySelector('.att-job-actions')?.textContent).toContain('CANCELLED');
  });

  it('shows an explicit unavailable state instead of inventing Load metrics or charts', async () => {
    const api = makeApi({ getJobResult: vi.fn(async () => ({ job: sampleJob, result: { status: 'PASS' } })) });
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} confirmCancel={() => true} />));
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    await click(host.querySelector('#job-tab-metrics')!);

    const panel = host.querySelector('#job-panel-metrics')!;
    expect(panel.textContent).toContain('No Load metrics summary was returned. No chart or derived metric is shown.');
    expect(panel.querySelector('[aria-label="Returned Load time-series buckets"]')).toBeNull();
    expect(panel.querySelector('canvas, svg')).toBeNull();
  });
});
