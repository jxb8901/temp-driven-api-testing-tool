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
  result: { status: 'PASS', evidence: { policy: { quotaMode: 'independent', maxSuccessSamples: 3, maxFailureSamples: 5 }, retainedSuccessCount: 3, retainedFailureCount: 5, failureQuotaExceeded: true, warning: 'Failure evidence quota reached; aggregate metrics remain complete.' }, metrics: { completedThroughput: 4.25, p95Ms: 81, buckets: {
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
  it.each([
    ['READY', 0, '0 B'], ['READY', 25190, '24.6 KB'], ['READY', 1099511627776, '1 TB'],
    ['READY', -1, null], ['READY', null, null], ['PENDING', 25190, null],
    ['ARCHIVING', 25190, null], ['FAILED', 25190, null],
  ] as const)('shows truthful ZIP size for %s and %s bytes', async (archiveStatus, archiveSizeBytes, expected) => {
    const api=makeApi({getJobArtifactArchive: vi.fn(async () => ({ archiveStatus, archiveSizeBytes, items: [], primaryReports: [] })), artifactArchiveHref: () => '/archive.zip'});
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    await advance();
    const link=host.querySelector('#job-panel-artifacts a');
    if(archiveStatus !== 'READY') expect(link).toBeNull();
    else expect(link?.textContent).toBe('Download Artifacts (.zip)' + (expected ? ` — ${expected}` : ''));
  });

  it('shows independent Server archive metrics for an ERROR job without Load results', async () => {
    const archiveMetrics={version:1, attempts:2, retries:1, outcome:'READY', zipBytes:500, publicationWallMs:12, writerCpuMs:null, writerAllocatedBytes:null};
    const api=makeApi({ getJob:vi.fn(async () => ({...sampleJob,status:'ERROR',command:'run'})),
      getJobResult:vi.fn(async () => { throw new Error('No Worker result'); }),
      getJobArtifactArchive:vi.fn(async () => ({archiveStatus:'READY' as const, items:[], primaryReports:[], archiveMetrics})),
    });
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    await advance();
    const metrics=host.querySelector('[aria-label="Artifact archive metrics"]');
    expect(metrics?.textContent).toContain('Outcome: READY');
    expect(metrics?.textContent).toContain('Unavailable');
    expect(metrics?.textContent).toContain('not retained heap / peak');
    expect(metrics?.textContent).toContain('Retries');
    expect(metrics?.textContent).toContain('500');
  });

  it('discovers reports independently of pagination and offers only an archive download', async () => {
    const report = { path: 'load/run/report/index.html', report: true, size: 900 };
    const api = makeApi({
      getJobArtifactArchive: vi.fn(async (_id: string, offset?: number) => ({ archiveStatus: 'READY' as const, primaryReports: [report],
        items: [{ path: offset ? 'nested/evidence.json' : 'case.log', size: 5 }], nextOffset: offset ? null : 100,
        complete: false, omissions: [{ count: 1, reason: 'unsupported output' }] })),
      artifactArchiveHref: () => '/archive.zip',
      getJobArtifactText: vi.fn(async () => '<html><head><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'sha256-reviewed\'; style-src \'unsafe-inline\'"></head><body><h1>Authenticated report</h1></body></html>'),
    });
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    await advance();
    const panel = host.querySelector('#job-panel-artifacts')!;
    expect(panel.textContent).toContain('View Report: load/run/report/index.html');
    expect([...panel.querySelectorAll('a')].map(link => link.textContent)).toEqual(['Download Artifacts (.zip)']);
    expect(panel.textContent).toContain('Some output was excluded');
    await click([...panel.querySelectorAll('button')].find(button => button.textContent === 'Load more artifacts')!);
    expect(panel.textContent).toContain('nested/evidence.json');
    expect(panel.textContent).toContain('case.log');
    await click([...panel.querySelectorAll('button')].find(button => button.textContent?.startsWith('View Report:'))!);
    await advance();
    const frame = panel.querySelector('iframe')!;
    expect(frame.getAttribute('sandbox')).toBe('allow-scripts');
    expect(frame.hasAttribute('allow-same-origin')).toBe(false);
    expect(frame.hasAttribute('src')).toBe(false); // no anonymous navigation to protected API
    expect(frame.getAttribute('srcdoc')).toContain('Authenticated report');
    expect(api.getJobArtifactText).toHaveBeenCalledWith('JOB-204', 'load/run/report/index.html', expect.any(AbortSignal));
  });

  it('polls archive status separately from the terminal job result and retries failures', async () => {
    const getArchive = vi.fn().mockResolvedValueOnce({ archiveStatus: 'PENDING', items: [], primaryReports: [] })
      .mockResolvedValueOnce({ archiveStatus: 'FAILED', items: [], primaryReports: [], retryable: true })
      .mockResolvedValue({ archiveStatus: 'READY', items: [], primaryReports: [] });
    const retryArchive = vi.fn(async () => ({}));
    const api = makeApi({ getJob: vi.fn(async () => ({ ...sampleJob, status: 'PASS' })), getJobArtifactArchive: getArchive, retryJobArtifactArchive: retryArchive });
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    await advance();
    expect(host.querySelector('#job-panel-artifacts')?.textContent).toContain('PENDING');
    await advance(2100);
    const retryButton = [...host.querySelectorAll('button')].find(button => button.textContent === 'Retry archive')!;
    await click(retryButton);await advance();
    expect(retryArchive).toHaveBeenCalledWith('JOB-204');
    expect(host.querySelector('#job-panel-artifacts')?.textContent).toContain('READY');
    expect(host.querySelector('#job-panel-overview')?.textContent).toContain('PASS');
  });

  it('renders stable job tabs and API-backed content for a loaded job', async () => {
    const api = makeApi();
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} confirmCancel={() => true} />));
    await act(async () => { await Promise.resolve(); });

    const tabs = [
      ['Overview', 'overview'], ['Live events', 'live'], ['Events', 'events'],
      ['Results', 'results'], ['Artifacts', 'artifacts'], ['Metrics', 'metrics'],
    ];
    expect([...host.querySelectorAll('[role="tab"]')].map(tab => tab.textContent)).toEqual(tabs.map(([label]) => label));
    for (const [, id] of tabs) {
      expect(host.querySelector(`#job-tab-${id}`)?.getAttribute('aria-controls')).toBe(`job-panel-${id}`);
      expect(host.querySelector(`#job-panel-${id}`)).not.toBeNull();
    }
    expect(host.querySelector('#job-panel-overview')?.textContent).toContain('RUNNING');
    expect(host.querySelector('#job-panel-results')?.textContent).toContain('Raw result payload');
    expect(host.querySelector('[aria-label="Retained evidence quotas"]')?.textContent).toContain('Failure samples retained5');
    expect(host.querySelector('[aria-label="Retained evidence quotas"] [role="alert"]')?.textContent).toContain('Failure evidence quota reached');
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

  it('shows effective per-workload evidence caps without borrowing fields', async () => {
    const perWorkloadResult: JobResultResponse = {
      ...sampleResult,
      result: {
        ...sampleResult.result,
        evidence: {
          policy: {
            quotaMode: 'perWorkload',
            defaults: { maxSuccessSamples: 10, maxFailureSamples: 20 },
          },
          retainedSuccessCount: 12,
          retainedFailureCount: 23,
          workloads: {
            payment: {
              targetId: 'PAYMENT_TEMPLATE', maxSuccessSamples: 10, maxFailureSamples: 20,
              eligibleSuccessCount: 15, samplingIneligibleSuccessCount: 2, retainedSuccessCount: 10,
              retainedFailureCount: 20, skippedSuccessQuotaCount: 5, skippedFailureQuotaCount: 0, skippedMissingEvidenceCount: 1,
            },
            queue: {
              targetId: 'QUEUE_TEMPLATE', maxSuccessSamples: 5, maxFailureSamples: 30,
              eligibleSuccessCount: 7, samplingIneligibleSuccessCount: 1, retainedSuccessCount: 2, retainedFailureCount: 3,
            },
          },
        },
      },
    };
    const api = makeApi({ getJobResult: vi.fn(async () => perWorkloadResult) });
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} confirmCancel={() => true} />));
    await act(async () => { await Promise.resolve(); });

    const summary = host.querySelector('[aria-label="Retained evidence quotas"]')!;
    expect(summary.textContent).toContain('Independent per-workload success and failure caps');
    expect(summary.textContent).toContain('Default success cap10');
    expect(summary.textContent).toContain('Success cap');
    const paymentRow = [...summary.querySelectorAll('tbody tr')].find(row => row.textContent?.includes('payment'))!;
    expect([...paymentRow.querySelectorAll('td')].map(cell => cell.textContent)).toEqual([
      'PAYMENT_TEMPLATE', '10', '20', '15', '2', '10', '20', 'Success: 5 · Failure: 0', '1',
    ]);
    expect(summary.textContent).not.toContain('borrowed');
    expect(summary.textContent).not.toContain('reclaimed borrow');
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
    expect(host.querySelector('.att-query-filter-panel[aria-label="Job filters"]')).not.toBeNull();
    expect(host.querySelector('.att-query-results-panel[aria-label="Job results"]')).not.toBeNull();

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
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Clear filters')!);
    expect((host.querySelector('.att-jobs-filter-panel select') as HTMLSelectElement).value).toBe('');
    expect(host.textContent).toContain('JOB-NEW');
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

  it('shows all event types in chronological order and filters without reconnecting or dropping new events', async () => {
    const api = makeApi();
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} confirmCancel={() => true} />));
    const handlers = vi.mocked(api.subscribeJob).mock.calls[0]?.[1];
    await act(async () => {
      handlers?.onEvent({ id: '5', type: 'result', data: { status: 'PASS' } });
      handlers?.onEvent({ id: '3', type: 'log', data: { message: 'worker log' } });
      handlers?.onEvent({ id: '1', type: 'status', data: { status: 'RUNNING' } });
      handlers?.onEvent({ id: '4', type: 'diagnostic', data: { code: 'ATT-EXAMPLE', summary: 'safe warning' } });
      handlers?.onEvent({ id: '2', type: 'progress', data: { message: 'half complete' } });
    });
    await click(host.querySelector('#job-tab-live')!);
    const panel = host.querySelector('#job-panel-live')!;
    const labels = [...panel.querySelectorAll('.att-job-events li strong')].map(item => item.textContent);
    expect(labels).toEqual(['status', 'progress', 'log', 'diagnostic', 'result']);
    expect(panel.querySelector('.att-job-events time')?.getAttribute('datetime')).toBeTruthy();

    const eventType = (type: string) => panel.querySelector<HTMLInputElement>(`input[aria-label="${type}"]`)!;
    for (const type of ['log', 'diagnostic', 'result']) await click(eventType(type));
    expect(panel.querySelectorAll('.att-job-events li')).toHaveLength(2);
    await act(async () => handlers?.onEvent({ id: '6', type: 'progress', data: { message: 'still connected' } }));
    expect(panel.querySelectorAll('.att-job-events li')).toHaveLength(3);
    await click(panel.querySelector<HTMLInputElement>('[aria-label="All Events"]')!);
    expect(panel.querySelectorAll('.att-job-events li')).toHaveLength(6);
    expect(api.subscribeJob).toHaveBeenCalledTimes(1);
    expect(panel.querySelector('[aria-label="Search events"]')).not.toBeNull();
    expect([...panel.querySelectorAll('button')].some(button => button.textContent === 'Copy visible events')).toBe(true);
  });

  it('distinguishes an open stream with no events from reconnecting', async () => {
    const api = makeApi();
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} confirmCancel={() => true} />));
    const handlers = vi.mocked(api.subscribeJob).mock.calls[0]?.[1];
    await click(host.querySelector('#job-tab-live')!);
    await act(async () => handlers?.onOpen());
    expect(host.querySelector('#job-panel-live')?.textContent).toContain('Connected; no events have been emitted yet.');
    await act(async () => handlers?.onError());
    expect(host.querySelector('#job-panel-live')?.textContent).toContain('Reconnecting to live events; no recent events are available.');
  });
});
