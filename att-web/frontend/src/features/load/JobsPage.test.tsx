// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { JobsPage } from './JobsPage';
import { JobArtifacts } from './JobArtifacts';
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
    ['PASS', 'status'], ['FAIL', 'status'], ['ERROR', 'status'], ['INVALID', 'status'], ['CANCELLED', 'status'], ['ERROR', 'result'],
  ])('reconciles final metrics/results once after %s %s SSE', async (status, type) => {
    const running = { ...sampleJob, status: 'RUNNING', performance: undefined };
    const finished = { ...running, status, performance: { worker: { sampleCount: 3, heapPeakUsedBytes: 34660872, processCpuTimeMs: 3521 } } };
    const getJob = vi.fn().mockResolvedValueOnce(running).mockResolvedValue(finished);
    const getJobResult = vi.fn().mockResolvedValueOnce({ job: running, result: null }).mockResolvedValue({
      job: finished, result: { status, diagnostic: { code: 'CONNECTION_ERROR', message: 'org.postgresql.Driver is unavailable' } },
    });
    const api = makeApi({ getJob, getJobResult });
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    expect(host.querySelector('#job-panel-metrics')?.textContent).not.toContain('heapPeakUsedBytes');
    expect(host.querySelector('#job-panel-results')?.textContent).toContain('The Server has not returned a result payload.');
    const handlers = vi.mocked(api.subscribeJob).mock.calls[0][1];
    await act(async () => handlers.onEvent({ id: 'terminal', type, data: { status } }));
    await click(host.querySelector('#job-tab-metrics')!);
    expect(host.querySelector('#job-panel-metrics')?.textContent).toContain('heapPeakUsedBytes');
    expect(host.querySelector('#job-panel-metrics')?.textContent).toContain('processCpuTimeMs');
    await click(host.querySelector('#job-tab-results')!);
    expect(host.querySelector('#job-panel-results')?.textContent).toContain('CONNECTION_ERROR');
    expect(host.querySelector('#job-panel-results')?.textContent).toContain('org.postgresql.Driver');
    expect(host.querySelector('#job-panel-results')?.textContent).not.toContain('The Server has not returned a result payload.');
    await act(async () => {
      handlers.onError(); handlers.onOpen();
      handlers.onEvent({ id: 'terminal', type, data: { status } });
      handlers.onEvent({ id: 'replayed-status', type: 'status', data: { status } });
      handlers.onEvent({ id: 'replayed-result', type: 'result', data: { status } });
    });
    expect(getJob).toHaveBeenCalledTimes(2);
    expect(getJobResult).toHaveBeenCalledTimes(2);
    expect(api.subscribeJob).toHaveBeenCalledTimes(1);
  });

  it('retains the explicit terminal diagnostic when the Worker truly returned no result', async () => {
    const getJobResult = vi.fn().mockResolvedValueOnce({ job: sampleJob, result: null }).mockResolvedValue({
      job: { ...sampleJob, status: 'ERROR' }, result: null,
      diagnostic: { code: 'ATT-SERVER-WORKER-EXITED', summary: 'Worker exited before delivering a terminal result' },
    });
    const api = makeApi({ getJobResult });
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    await act(async () => vi.mocked(api.subscribeJob).mock.calls[0][1].onEvent({ type: 'result', data: {} }));
    expect(host.querySelector('#job-panel-results')?.textContent).toContain('ATT-SERVER-WORKER-EXITED');
    expect(host.querySelector('.att-raw-result')).toBeNull();
    expect(getJobResult).toHaveBeenCalledTimes(2);
  });

  it('aborts initial snapshots and ignores their late responses after terminal SSE', async () => {
    const initialJob = deferred<JobView>();
    const initialResult = deferred<JobResultResponse>();
    const finished = { ...sampleJob, status: 'ERROR', performance: { worker: { sampleCount: 3 } } };
    const getJob = vi.fn().mockReturnValueOnce(initialJob.promise).mockResolvedValue(finished);
    const getJobResult = vi.fn().mockReturnValueOnce(initialResult.promise).mockResolvedValue({ job: finished, result: { status: 'ERROR', code: 'CONNECTION_ERROR' } });
    const api = makeApi({ getJob, getJobResult });
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    await act(async () => vi.mocked(api.subscribeJob).mock.calls[0][1].onEvent({ type: 'status', data: { status: 'ERROR' } }));
    expect(getJob.mock.calls[0][1].aborted).toBe(true);
    expect(getJobResult.mock.calls[0][1].aborted).toBe(true);
    await act(async () => { initialJob.resolve(sampleJob); initialResult.resolve({ job: sampleJob, result: null }); });
    expect(host.querySelector('.att-job-actions')?.textContent).toContain('ERROR');
    expect(host.querySelector('#job-panel-results')?.textContent).toContain('CONNECTION_ERROR');
    expect(host.querySelector('#job-panel-metrics')?.textContent).toContain('sampleCount');
  });

  it('aborts terminal reconciliation on job change and ignores old stream callbacks', async () => {
    const lateJob = deferred<JobView>();
    const lateResult = deferred<JobResultResponse>();
    const getJob = vi.fn().mockResolvedValueOnce(sampleJob).mockReturnValueOnce(lateJob.promise)
      .mockResolvedValue({ ...sampleJob, jobId: 'JOB-NEW', status: 'RUNNING', performance: undefined });
    const getJobResult = vi.fn().mockResolvedValueOnce({ job: sampleJob, result: null }).mockReturnValueOnce(lateResult.promise)
      .mockResolvedValue({ job: { ...sampleJob, jobId: 'JOB-NEW' }, result: null });
    const unsubscribe = vi.fn();
    const api = makeApi({ getJob, getJobResult, subscribeJob: vi.fn(() => unsubscribe) });
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    const old = vi.mocked(api.subscribeJob).mock.calls[0][1];
    await act(async () => old.onEvent({ type: 'status', data: { status: 'ERROR' } }));
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-NEW" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    expect(getJob.mock.calls[1][1].aborted).toBe(true);
    expect(getJobResult.mock.calls[1][1].aborted).toBe(true);
    expect(unsubscribe).toHaveBeenCalledTimes(1);
    await act(async () => {
      lateJob.resolve({ ...sampleJob, status: 'ERROR' });
      lateResult.resolve({ job: sampleJob, result: { code: 'OLD-JOB-RESULT' } });
      old.onEvent({ type: 'result', data: { status: 'ERROR' } }); old.onError();
    });
    expect(host.querySelector('.att-job-actions')?.textContent).toContain('RUNNING');
    expect(host.textContent).not.toContain('OLD-JOB-RESULT');
    expect(getJob).toHaveBeenCalledTimes(3);
    await act(async () => vi.mocked(api.subscribeJob).mock.calls[1][1].onEvent({ type: 'status', data: { status: 'PASS' } }));
    expect(getJob).toHaveBeenCalledTimes(4);
    expect(getJobResult).toHaveBeenCalledTimes(4);
  });

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
    await act(async () => vi.mocked(api.subscribeJob).mock.calls[0][1].onEvent({ type: 'status', data: { status: 'PASS' } }));
    expect(api.getJob).toHaveBeenCalledTimes(2);
    expect(api.getJobResult).toHaveBeenCalledTimes(2);
    expect(api.getJobArtifactArchive).toHaveBeenCalledTimes(2);
    expect(panel.textContent).toContain('nested/evidence.json');
    expect(panel.querySelector('iframe')).toBe(frame);
  });

  it('reconciles 150 visible artifacts without closing an unchanged report on manual refresh', async () => {
    const report = { path: 'load/report/index.html', report: true, size: 900, sha256: 'original' };
    let changed = false;
    let present = true;
    const items = Array.from({ length: 150 }, (_, i) => ({ path: `evidence/${i}.log`, size: 5, sha256: 'same' }));
    const api = makeApi({
      getJobArtifactArchive: vi.fn(async (_id: string, offset = 0) => ({ archiveStatus: 'READY' as const,
        primaryReports: present ? [{ ...report, sha256: changed ? 'replaced' : report.sha256 }] : [],
        items: items.slice(offset, offset + 100), nextOffset: offset ? null : 100 })),
      getJobArtifactText: vi.fn(async () => '<html><head><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'sha256-reviewed\'; style-src \'unsafe-inline\'"></head><body>Preserved report</body></html>'),
    });
    await act(async () => root.render(<JobArtifacts api={api} jobId="JOB-204" refresh={0} />));
    await advance();
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Load more artifacts')!);
    await click([...host.querySelectorAll('button')].find(button => button.textContent?.startsWith('View Report:'))!);
    await advance();
    const frame = host.querySelector('iframe');
    expect(frame).not.toBeNull();
    await act(async () => root.render(<JobArtifacts api={api} jobId="JOB-204" refresh={1} />));
    await advance();
    expect(host.textContent).toContain('evidence/149.log');
    expect(host.querySelector('iframe')).toBe(frame);
    expect(api.getJobArtifactArchive).toHaveBeenCalledTimes(4);
    expect(api.getJobArtifactText).toHaveBeenCalledTimes(1);
    changed = true;
    await act(async () => root.render(<JobArtifacts api={api} jobId="JOB-204" refresh={2} />));
    await advance();
    expect(host.querySelector('[aria-label="Artifact preview"]')).toBeNull();
    await click([...host.querySelectorAll('button')].find(button => button.textContent?.startsWith('View Report:'))!);
    await advance();
    present = false;
    await act(async () => root.render(<JobArtifacts api={api} jobId="JOB-204" refresh={3} />));
    await advance();
    expect(host.querySelector('[aria-label="Artifact preview"]')).toBeNull();
    await act(async () => root.render(<JobArtifacts api={api} jobId="OTHER" refresh={2} />));
    await advance();
    expect(host.textContent).not.toContain('evidence/149.log');
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
      ['Overview', 'overview'], ['Live Output', 'live'], ['Events', 'events'],
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
      subscribe?.onEvent({ id: 'evt-1', type: 'log', data: { message: 'worker ready', console: '[RUN] worker ready' } });
      subscribe?.onEvent({ id: 'evt-2', type: 'status', data: { status: 'CANCELLED' } });
    });
    expect(host.querySelector('#job-panel-overview')?.textContent).toContain('CANCELLED');
    const liveTab = host.querySelector('#job-tab-live');
    expect(liveTab).not.toBeNull();
    await click(liveTab!);
    expect(host.querySelector('#job-panel-live pre.att-console')?.textContent).toContain('[RUN] worker ready');
    expect(host.querySelector('#job-panel-live pre.att-console')?.innerHTML).not.toContain('<');
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
    await click(host.querySelector('#job-tab-events')!);
    const panel = host.querySelector('#job-panel-events')!;
    const labels = [...panel.querySelectorAll('.att-job-events summary strong')].map(item => item.textContent);
    expect(labels).toEqual(['status', 'progress', 'log', 'diagnostic', 'result']);
    expect(panel.querySelector('.att-job-events time')?.getAttribute('datetime')).toBeTruthy();
    expect(panel.textContent).toContain('"message": "worker log"');

    const eventType = (type: string) => panel.querySelector<HTMLInputElement>(`input[aria-label="${type}"]`)!;
    for (const type of ['log', 'diagnostic', 'result']) await click(eventType(type));
    expect(panel.querySelectorAll('.att-job-events li')).toHaveLength(2);
    await act(async () => handlers?.onEvent({ id: '6', type: 'progress', data: { message: 'still connected' } }));
    expect(panel.querySelectorAll('.att-job-events li')).toHaveLength(3);
    await click(panel.querySelector<HTMLInputElement>('[aria-label="All Events"]')!);
    expect(panel.querySelectorAll('.att-job-events li')).toHaveLength(6);
    expect(api.subscribeJob).toHaveBeenCalledTimes(1);
    expect(panel.querySelector('[aria-label="Search events"]')).not.toBeNull();

    await click(host.querySelector('#job-tab-live')!);
    const live = host.querySelector('#job-panel-live')!;
    expect(live.textContent).toContain('This Job has no console lines');
    await act(async () => handlers?.onEvent({ id: '7', type: 'log', data: { message: '<b>secret</b>', console: '[RUN] case=one\n[RUN] case=two' } }));
    expect(live.querySelector('pre.att-console')?.textContent).toBe('[RUN] case=one\n[RUN] case=two');
    expect(live.querySelector('pre.att-console')?.innerHTML).not.toContain('<b>');
    expect([...live.querySelectorAll('button')].some(button => button.textContent === 'Copy Output')).toBe(true);
    await click([...live.querySelectorAll('button')].find(button => button.textContent === 'Clear view')!);
    expect(live.querySelector('pre.att-console')).toBeNull();
    await act(async () => handlers?.onEvent({ id: '8', type: 'log', data: { console: '[RUN] after clear' } }));
    expect(live.querySelector('pre.att-console')?.textContent).toBe('[RUN] after clear');
  });

  it('distinguishes an open stream with no events from reconnecting', async () => {
    const api = makeApi();
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} confirmCancel={() => true} />));
    const handlers = vi.mocked(api.subscribeJob).mock.calls[0]?.[1];
    await click(host.querySelector('#job-tab-live')!);
    await act(async () => handlers?.onOpen());
    expect(host.querySelector('#job-panel-live')?.textContent).toContain('Connected; no console lines have been emitted yet.');
    await act(async () => handlers?.onError());
    expect(host.querySelector('#job-panel-live')?.textContent).toContain('Reconnecting to live output; no recent console lines are available.');
    await act(async () => handlers?.onEvent({ id: '9', type: 'result', data: { status: 'PASS', console: '[RUN] status=PASS jobId=JOB-204 exitCode=0' } }));
    await act(async () => handlers?.onError());
    expect(host.querySelector('#job-panel-live')?.textContent).toContain('completed');
    expect(host.querySelector('#job-panel-live')?.textContent).not.toContain('Reconnecting to live output');
  });
});
