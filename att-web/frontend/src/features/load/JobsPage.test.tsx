// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ArtifactsPanel, BucketTable, JobOverview, JobsPage, MetricsPanel, TimeSeriesChart, downsampleMetricPoints, CHART_DISPLAY_POINT_THRESHOLD, type MetricBucket, type MetricSeries } from './JobsPage';
import { JobArtifacts } from './JobArtifacts';
import type { JobEventHandlers, JobResultResponse, JobStatus, JobView, LoadJobsApi } from './types';

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
  it('downsamples only the chart display and preserves exact extrema and coordinates', () => {
    const points = Array.from({ length: CHART_DISPLAY_POINT_THRESHOLD + 240 }, (_, index) => ({
      epochMs: 1_800_000_000_000 + index * 1000,
      value: index === 300 ? -40 : index === 900 ? 900 : index % 17,
    }));
    const sampled = downsampleMetricPoints(points);
    expect(sampled.length).toBeLessThanOrEqual(CHART_DISPLAY_POINT_THRESHOLD);
    expect(sampled[0]).toBe(points[0]);
    expect(sampled.at(-1)).toBe(points.at(-1));
    expect(sampled).toContain(points[300]);
    expect(sampled).toContain(points[900]);
    expect(points).toHaveLength(CHART_DISPLAY_POINT_THRESHOLD + 240);
  });

  it('labels a downsampled chart and keeps the full series available to its table caller', async () => {
    const points = Array.from({ length: CHART_DISPLAY_POINT_THRESHOLD + 1 }, (_, index) => ({ epochMs: 1_800_000_000_000 + index * 1000, value: index }));
    await act(async () => root.render(<TimeSeriesChart series={{ field: 'p95Ms', label: 'p95 latency', unit: 'milliseconds', points }} />));
    expect(host.textContent).toContain(`display sample: ${CHART_DISPLAY_POINT_THRESHOLD} of ${points.length} points`);
    expect(host.querySelector('[role="status"]')?.textContent).toContain('display sample:');
  });

  it('keeps Overview, Metrics, and Artifacts profiler subtrees stable during 50 event flushes', async () => {
    const api = makeApi();
    const profilerCommits: Record<string, number> = { overview: 0, metrics: 0, artifacts: 0 };
    const onMetrics = vi.fn();
    const emptyBuckets: MetricBucket[] = [];
    const emptyCharts: MetricSeries[] = [];
    const onOverviewProfiler = () => { profilerCommits.overview += 1; };
    const onMetricsProfiler = () => { profilerCommits.metrics += 1; };
    const onArtifactsProfiler = () => { profilerCommits.artifacts += 1; };
    function Harness() {
      const [events, setEvents] = useState(0);
      return <>
        <button type="button" onClick={() => setEvents(value => value + 50)}>Ingest events</button><output>{events}</output>
        <JobOverview job={sampleJob} status={sampleJob.status} jobState="ready" hidden onProfilerCommit={onOverviewProfiler} />
        <MetricsPanel job={sampleJob} metrics={null} resultState="ready" archiveMetrics={null} buckets={emptyBuckets} charts={emptyCharts} hidden onProfilerCommit={onMetricsProfiler} />
        <ArtifactsPanel api={api} jobId={sampleJob.jobId} refresh={0} onMetrics={onMetrics} hidden onProfilerCommit={onArtifactsProfiler} />
      </>;
    }
    await act(async () => root.render(<Harness />));
    await act(async () => { await Promise.resolve(); });
    const before = { ...profilerCommits };
    await click(host.querySelector('button')!);
    expect(host.querySelector('output')?.textContent).toBe('50');
    expect(profilerCommits).toEqual(before);
  });

  it('paginates 4096 exact buckets without mounting all rows and keeps keyboard paging accessible', async () => {
    const buckets: MetricBucket[] = Array.from({ length: 4096 }, (_, index) => ({
      key: String(index), epochMs: 1_800_000_000_000 + index * 1000,
      values: { completedTps: index + 0.25, p95Ms: index + 1 },
    }));
    await act(async () => root.render(<BucketTable buckets={buckets} />));
    const table = host.querySelector('table')!;
    expect(table.getAttribute('aria-rowcount')).toBe('4097');
    expect(host.querySelectorAll('tbody tr')).toHaveLength(100);
    expect(host.textContent).toContain('Showing rows 1–100 of 4096');
    expect(host.textContent).toContain('0.25');
    expect(host.textContent).not.toContain('4095.25');
    const next = host.querySelector<HTMLButtonElement>('[aria-label="Next bucket page"]')!;
    await act(async () => { next.focus(); next.click(); });
    expect(host.textContent).toContain('Showing rows 101–200 of 4096');
    expect(host.querySelectorAll('tbody tr')).toHaveLength(100);
    expect(host.textContent).toContain('100.25');
    expect(host.querySelector('tbody tr')?.getAttribute('aria-rowindex')).toBe('102');
  });

  it('keeps expanded published console text and distinguishes journal truncation from preview clipping', async () => {
    const api=makeApi();
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    const handlers=vi.mocked(api.subscribeJob).mock.calls[0][1];
    const text='diagnostic '+ 'x'.repeat(8000) + ' expanded-entry-end';
    await act(async () => handlers.onEvent({id:'1',type:'log',data:{console:text,truncated:true,journalLimit:{limitBytes:65536}}}));
    await advance(100);
    await click(host.querySelector('#job-tab-live')!);
    expect(host.querySelector('[aria-label="Live output"]')?.textContent).toBe(text);
    expect(host.querySelector('#job-panel-live')?.textContent).toContain('Some event payload fields were truncated by the journal');
    await click(host.querySelector('#job-tab-events')!);
    expect(host.querySelector('#job-panel-events')?.textContent).toContain('Events preview clipped');
    expect(host.querySelector('#job-panel-events details details pre')).toBeNull();
    await click(host.querySelector('#job-panel-events details details summary')!);
    expect(host.querySelector('#job-panel-events details details pre')?.textContent).toContain('expanded-entry-end');
  });

  it('serializes an event payload once and only when its event is expanded', async () => {
    const toJson = vi.fn(() => ({ message: 'lazy payload' }));
    const data = { toJSON: toJson };
    const api = makeApi();
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    await click(host.querySelector('#job-tab-events')!);
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Collapse all')!);
    await act(async () => vi.mocked(api.subscribeJob).mock.calls[0][1].onEvent({ id: 'lazy', type: 'diagnostic', data }));
    expect(toJson).not.toHaveBeenCalled();
    await advance(100);
    const event = host.querySelector('#job-panel-events .att-job-events details')!;
    expect(event.querySelector('pre')).toBeNull();
    await click(event.querySelector('summary')!);
    expect(toJson).toHaveBeenCalledTimes(1);
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Pause auto-scroll')!);
    expect(toJson).toHaveBeenCalledTimes(1);
    expect(event.querySelector('pre')?.textContent).toContain('lazy payload');
  });

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
    await click(host.querySelector('#job-tab-artifacts')!);
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
    await click(host.querySelector('#job-tab-artifacts')!);
    await advance();
    await click(host.querySelector('#job-tab-metrics')!);
    const metrics=host.querySelector('[aria-label="Artifact archive metrics"]');
    expect(metrics?.textContent).toContain('Outcome: READY');
    expect(metrics?.textContent).toContain('Unavailable');
    expect(metrics?.textContent).toContain('not retained heap / peak');
    expect(metrics?.textContent).toContain('Retries');
    expect(metrics?.textContent).toContain('500');
  });

  it('discovers reports independently of pagination and offers authenticated standalone artifact links', async () => {
    const report = { path: 'load/run/report/index.html', report: true, size: 900 };
    const api = makeApi({
      getJobArtifactArchive: vi.fn(async (_id: string, offset?: number) => ({ archiveStatus: 'READY' as const, primaryReports: [report],
        items: [{ path: offset ? 'nested/evidence.json' : 'case.log', size: 5 }], nextOffset: offset ? null : 100,
        complete: false, omissions: [{ count: 1, reason: 'unsupported output' }] })),
      artifactArchiveHref: () => '/archive.zip',
      getJobArtifactText: vi.fn(async () => '<html><head><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'sha256-reviewed\'; style-src \'unsafe-inline\'"></head><body><h1>Authenticated report</h1></body></html>'),
    });
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    await click(host.querySelector('#job-tab-artifacts')!);
    await advance();
    const panel = host.querySelector('#job-panel-artifacts')!;
    expect(panel.textContent).toContain('View Report: load/run/report/index.html');
    expect([...panel.querySelectorAll('a')].map(link => link.textContent)).toEqual([
      'Download Artifacts (.zip)', 'Open in new tab', 'Open in new tab',
    ]);
    const reportLink = [...panel.querySelectorAll<HTMLAnchorElement>('a')].find(link => link.href.includes('/artifact?path='));
    expect(reportLink?.getAttribute('href')).toContain('path=load%2Frun%2Freport%2Findex.html');
    expect(reportLink?.target).toBe('_blank');
    expect(reportLink?.rel).toBe('noopener noreferrer');
    expect(panel.textContent).toContain('Some output was excluded');
    await click([...panel.querySelectorAll('button')].find(button => button.textContent === 'Load more artifacts')!);
    expect(panel.textContent).toContain('nested/evidence.json');
    expect(panel.textContent).toContain('case.log');
    await click([...panel.querySelectorAll('button')].find(button => button.textContent?.startsWith('View Report:'))!);
    await advance();
    const previewLink = panel.querySelector<HTMLAnchorElement>('[aria-label="Artifact preview"] a');
    expect(previewLink?.textContent).toBe('Open in new tab');
    expect(previewLink?.target).toBe('_blank');
    expect(previewLink?.rel).toBe('noopener noreferrer');
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
    await click(host.querySelector('#job-tab-artifacts')!);
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
    const api = makeApi({
      getJobArtifactArchive: vi.fn(async () => ({ archiveStatus: 'READY' as const, items: [], primaryReports: [{ path: 'load-summary.json', size: 80 }] })),
      getJobArtifactText: vi.fn(async () => JSON.stringify({ metrics: sampleResult.result?.metrics })),
    });
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
    expect(api.getJobArtifactArchive).not.toHaveBeenCalled();
    expect(api.getJobArtifactText).not.toHaveBeenCalled();
    await click(host.querySelector('#job-tab-artifacts')!);
    await advance();
    expect(host.querySelector('#job-panel-artifacts')?.textContent).toContain('load-summary.json');
    await click(host.querySelector('#job-tab-metrics')!);
    await advance();
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

  it('renders the compact terminal result and primary ERROR cause without requesting an archive on initial detail load', async () => {
    const compact: JobResultResponse = {
      job: { jobId: 'JOB-204', command: 'load', status: 'ERROR', exitCode: 17 },
      result: { status: 'ERROR', exitCode: 17, summary: {
        schemaVersion: 'att-load-terminal-result/v1',
        execution: { executionId: 'run-compact', status: 'ERROR', exitCode: 17, durationMs: 12000 },
        performance: { executionStatus: 'ERROR', verdict: 'NOT_EVALUATED' },
        metrics: { scheduled: 100, started: 98, completed: 90, success: 80, runtimeError: 7, failure: 3, completedThroughput: 7.5 },
        thresholds: { verdict: 'PASS', total: 2 },
        diagnostics: {
          primaryError: { code: 'MQ_POOL_TIMEOUT', summary: 'Connection pool wait timed out.', outcome: 'EXECUTION_ERROR' },
          secondaryNotices: [{ code: 'ATT-LOAD-EVIDENCE-QUOTA', summary: 'Some iteration evidence was not retained.',
            limitName: 'load.evidence.maxFailureSamples', observed: 4, outcome: 'DATA_TRUNCATED' }],
          transportWarnings: [{ code: 'ATT-WORKER-EVENT-TRUNCATED', summary: 'Worker event details were truncated.',
            limitName: 'worker.protocol.maxFrameBytes', observed: 4000000, configuredLimit: 2097152, outcome: 'DATA_TRUNCATED' }],
        },
        metricCoverage: { loadMetricsBucketCapacity: 4096, observedBucketCoverageMs: 5000, windowSeriesBinWidthMs: 2000, windowSeriesCapacity: 1024, windowSeriesCoverage: 'ROLLING_WINDOW' },
        archive: { reportAvailability: 'PENDING', status: 'PENDING' },
      } },
    };
    const getArchive = vi.fn(async () => ({ archiveStatus: 'READY' as const, items: [], primaryReports: [] }));
    const api = makeApi({ getJobResult: vi.fn(async () => compact), getJobArtifactArchive: getArchive });
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });

    const overview = host.querySelector('#job-panel-overview')!;
    expect(overview.querySelector('[aria-label="Compact LOAD result"]')?.textContent).toContain('MQ_POOL_TIMEOUT');
    expect(overview.textContent).toContain('Connection pool wait timed out.');
    const cause = overview.querySelector('[aria-label="LOAD root-cause diagnosis"]');
    const notices = overview.querySelector('[aria-label="Secondary limit and transport notices"]');
    expect(cause?.textContent).toContain('MQ_POOL_TIMEOUT');
    expect(notices?.textContent).toContain('ATT-LOAD-EVIDENCE-QUOTA');
    expect(notices?.textContent).toContain('4 observed');
    expect(notices?.textContent).toContain('ATT-WORKER-EVENT-TRUNCATED');
    expect(notices?.textContent).toContain('4000000 / 2097152');
    expect(cause !== null && notices !== null
      && Boolean(cause.compareDocumentPosition(notices) & Node.DOCUMENT_POSITION_FOLLOWING)).toBe(true);
    expect(overview.textContent).toContain('90 / 80');
    expect(getArchive).not.toHaveBeenCalled();
    const raw = host.querySelector('#job-panel-results details[open]');
    expect(raw).toBeNull();

    await click(host.querySelector('#job-tab-results')!);
    expect(host.querySelector('#job-panel-results [aria-label="LOAD root-cause diagnosis"]')?.textContent).toContain('MQ_POOL_TIMEOUT');
    expect(getArchive).toHaveBeenCalledOnce();
  });

  it('loads detailed LOAD metrics only after Metrics is opened through the authorized archive API', async () => {
    const compact: JobResultResponse = { job: { jobId: 'JOB-204', command: 'load', status: 'PASS' }, result: { status: 'PASS', summary: {
      schemaVersion: 'att-load-terminal-result/v1', execution: { status: 'PASS' }, performance: { verdict: 'PASS' },
      metrics: { scheduled: 60, started: 60, completed: 60, success: 60, runtimeError: 0 },
      metricCoverage: { loadMetricsBucketCapacity: 4096, observedBucketCoverageMs: 3000, windowSeriesBinWidthMs: 1000, windowSeriesCapacity: 1024, windowSeriesCoverage: 'ROLLING_WINDOW' },
      archive: { reportAvailability: 'PENDING', status: 'PENDING' },
    } } };
    const archiveRead = vi.fn(async () => ({ archiveStatus: 'READY' as const, items: [], primaryReports: [{ path: 'load/run/load-summary.json', size: 500 }] }));
    const textRead = vi.fn(async () => JSON.stringify({ metrics: { completedThroughput: 10, buckets: {
      '1800000000000': { bucketStartEpochMs: 1800000000000, completedTps: 9, p95Ms: 80, p99Ms: 120 },
      '1800000001000': { bucketStartEpochMs: 1800000001000, completedTps: 10, p95Ms: 70, p99Ms: 110 },
    } } }));
    const api = makeApi({ getJobResult: vi.fn(async () => compact), getJobArtifactArchive: archiveRead, getJobArtifactText: textRead });
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    expect(archiveRead).not.toHaveBeenCalled();
    expect(textRead).not.toHaveBeenCalled();
    expect(host.querySelector('#job-panel-overview')?.textContent).toContain('60 / 60');

    await click(host.querySelector('#job-tab-metrics')!);
    await advance();
    expect(archiveRead).toHaveBeenCalledWith('JOB-204', 0, expect.any(AbortSignal));
    expect(textRead).toHaveBeenCalledWith('JOB-204', 'load/run/load-summary.json', expect.any(AbortSignal));
    expect(host.querySelector('#job-panel-metrics')?.querySelectorAll('svg[role="img"]')).toHaveLength(3);
    expect(host.querySelector('#job-panel-metrics')?.textContent).toContain('Detailed report');
  });

  it('restores complete archived metric series from authorized sidecars only when Metrics is opened', async () => {
    const compact: JobResultResponse = { job: { jobId: 'JOB-208', command: 'load', status: 'PASS' }, result: { status: 'PASS', summary: {
      schemaVersion: 'att-load-terminal-result/v1', execution: { status: 'PASS' }, performance: { verdict: 'MET' },
      metrics: { scheduled: 40, started: 40, completed: 40, success: 40, runtimeError: 0 },
      archive: { reportAvailability: 'PENDING', status: 'PENDING' },
    } } };
    const buckets = Object.fromEntries(Array.from({ length: 40 }, (_, index) => {
      const epoch = 1_800_000_000_000 + index * 1000;
      return [String(epoch), { bucketStartEpochMs: epoch, completedTps: index + 1, p95Ms: index + 20, p99Ms: index + 30 }];
    }));
    const preview = Object.fromEntries(Object.entries(buckets).slice(0, 32));
    const archiveRead = vi.fn(async () => ({ archiveStatus: 'READY' as const, items: [], primaryReports: [{ path: 'load/run/load-summary.json', size: 1000 }] }));
    const textRead = vi.fn(async (_jobId: string, path: string) => path.endsWith('load-summary.json')
      ? JSON.stringify({ metrics: { scheduled: 40, completed: 40, buckets: preview, detailSeries: {
        schemaVersion: 'att-load-detail-reference/v1', complete: true, series: [{ scope: 'aggregate', target: 'metrics/buckets',
          kind: 'map', count: 40, inlinePreviewCount: 32, complete: true, files: ['details/v1/aggregate/series-0001-0001.json'] },
          { scope: 'aggregate', target: 'metrics/failureDiagnostics', kind: 'list', count: 200,
            complete: true, files: ['details/v1/aggregate/diagnostics-0002-0001.json'] }],
      } } })
      : JSON.stringify({ schemaVersion: 'att-load-detail-series/v1', scope: 'aggregate', target: 'metrics/buckets',
        kind: 'map', part: 1, parts: 1, total: 40, items: buckets }));
    const api = makeApi({ getJobResult: vi.fn(async () => compact), getJobArtifactArchive: archiveRead, getJobArtifactText: textRead });
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-208" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    await advance();
    expect(archiveRead).not.toHaveBeenCalled();
    expect(textRead).not.toHaveBeenCalled();

    await click(host.querySelector('#job-tab-metrics')!);
    await advance();
    expect(textRead).toHaveBeenNthCalledWith(1, 'JOB-208', 'load/run/load-summary.json', expect.any(AbortSignal));
    expect(textRead).toHaveBeenNthCalledWith(2, 'JOB-208', 'load/run/details/v1/aggregate/series-0001-0001.json', expect.any(AbortSignal));
    const panel = host.querySelector('#job-panel-metrics')!;
    expect(panel.textContent).toContain('Load time-series buckets');
    expect(panel.querySelectorAll('[aria-label="Returned Load time-series buckets"] tbody tr')).toHaveLength(40);
    expect(panel.textContent).toContain('40');
    expect(textRead.mock.calls.some(call => call[1].includes('diagnostics-0002')),
      'Metrics must not eagerly download non-chart series').toBe(false);
    await click(host.querySelector('#job-tab-results')!);
    await advance();
    await click(host.querySelector('#job-tab-metrics')!);
    await advance();
    expect(textRead.mock.calls.filter(call => call[1].includes('series-0001')).length,
      'tab switches should reuse the already verified sidecar').toBe(1);
  });

  it('loads archived evidence and workload details only when Results is opened', async () => {
    const compact: JobResultResponse = { job: { jobId: 'JOB-205', command: 'load', status: 'PASS' }, result: { status: 'PASS', summary: {
      schemaVersion: 'att-load-terminal-result/v1', execution: { status: 'PASS' }, performance: { verdict: 'MET' },
      metrics: { scheduled: 12, started: 12, completed: 12, success: 12, runtimeError: 0 },
      archive: { reportAvailability: 'PENDING', status: 'PENDING' },
    } } };
    const archiveRead = vi.fn(async () => ({ archiveStatus: 'READY' as const, items: [], primaryReports: [{ path: 'load/run/load-summary.json', size: 800 }] }));
    const textRead = vi.fn(async () => JSON.stringify({ metrics: { scheduled: 12, completed: 12, buckets: {} },
      evidence: { policy: { quotaMode: 'independent', maxSuccessSamples: 4, maxFailureSamples: 2 }, retainedSuccessCount: 2, retainedFailureCount: 1,
        items: [{ workloadId: 'checkout', execId: 'EXEC-1', iterationId: 'iteration-1', status: 'FAIL', path: 'failures/EXEC-1/evidence.json', caseLog: 'failures/EXEC-1/case.log' }] },
      workloads: { checkout: { status: 'PASS', executionStatus: 'PASS', performanceVerdict: 'MET', model: 'arrivalRate',
        target: { type: 'template', id: 'checkout' }, metrics: { scheduled: 12, started: 12, completed: 12, success: 11, failure: 1, runtimeError: 0, p95Ms: 48 },
        thresholds: { status: 'MET' }, detailArtifact: 'details/v1/workloads/workload-0001/summary.json' } },
    }));
    const api = makeApi({ getJobResult: vi.fn(async () => compact), getJobArtifactArchive: archiveRead, getJobArtifactText: textRead });
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-205" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    await advance();
    expect(archiveRead).not.toHaveBeenCalled();
    expect(textRead).not.toHaveBeenCalled();
    expect(host.querySelector('#job-panel-results [aria-label="Retained evidence quotas"]')).toBeNull();
    expect(host.querySelector('#job-panel-results [aria-label="Archived workload details"]')).toBeNull();

    await click(host.querySelector('#job-tab-results')!);
    await advance();
    const results = host.querySelector('#job-panel-results')!;
    expect(archiveRead).toHaveBeenCalledWith('JOB-205', 0, expect.any(AbortSignal));
    expect(textRead).toHaveBeenCalledWith('JOB-205', 'load/run/load-summary.json', expect.any(AbortSignal));
    expect(results.querySelector('[aria-label="Retained evidence quotas"]')?.textContent).toContain('Success samples retained2');
    expect(results.querySelector('[aria-label="Archived workload details"]')?.textContent).toContain('checkout');
    expect(results.querySelector('[aria-label="Archived workload details"]')?.textContent).toContain('12');
    expect(results.querySelector('[aria-label="Retained evidence quotas"]')?.textContent).toContain('EXEC-1');
    expect(results.querySelector('[aria-label="Retained evidence quotas"] a[href*="failures%2FEXEC-1%2Fevidence.json"]')?.textContent).toBe('Open evidence');
    expect(results.querySelector('[aria-label="Archived workload details"] a[href*="details%2Fv1%2Fworkloads%2Fworkload-0001%2Fsummary.json"]')?.textContent).toBe('Open workload detail');
  });

  it('shows an early INVALID LOAD diagnostic inline in Overview without changing its outcome', async () => {
    const invalid: JobResultResponse = { job: { jobId: 'JOB-206', command: 'load', status: 'INVALID', exitCode: 2 }, result: {
      status: 'INVALID', exitCode: 2, summary: { schemaVersion: 'att-load-terminal-result/v1',
        execution: { status: 'INVALID', exitCode: 2 }, performance: { executionStatus: 'INVALID', verdict: 'NOT_EVALUATED' },
        diagnostics: { primaryError: { code: 'LOAD_INVALID', summary: 'Scenario is missing workloads' } },
        archive: { status: 'OMITTED', reportAvailability: 'OMITTED', paths: {} },
      },
    } };
    const archiveRead = vi.fn();
    const api = makeApi({ getJob: vi.fn(async () => ({ ...sampleJob, status: 'INVALID', exitCode: 2 })), getJobResult: vi.fn(async () => invalid), getJobArtifactArchive: archiveRead });
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-206" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    await advance();
    const overview = host.querySelector('#job-panel-overview')!;
    expect(overview.textContent).toContain('INVALID');
    expect(overview.textContent).toContain('LOAD_INVALID');
    expect(overview.textContent).toContain('Scenario is missing workloads');
    expect(overview.textContent).toContain('OMITTED');
    expect(archiveRead).not.toHaveBeenCalled();
  });

  it('keeps the initial compact Job Details DOM structurally small with no artifact request', async () => {
    const compact: JobResultResponse = { job: { jobId: 'JOB-207', command: 'load', status: 'PASS' }, result: { status: 'PASS', summary: {
      schemaVersion: 'att-load-terminal-result/v1', execution: { status: 'PASS' }, performance: { verdict: 'MET' },
      metrics: { scheduled: 20, started: 20, completed: 20, success: 20, runtimeError: 0 },
      archive: { status: 'PENDING', reportAvailability: 'PENDING' },
    } } };
    const archiveRead = vi.fn();
    const textRead = vi.fn();
    const api = makeApi({ getJobResult: vi.fn(async () => compact), getJobArtifactArchive: archiveRead, getJobArtifactText: textRead });
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-207" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    await advance();
    expect(host.querySelectorAll('*').length).toBeLessThan(400);
    expect(host.querySelectorAll('#job-panel-results tbody tr')).toHaveLength(0);
    expect(archiveRead).not.toHaveBeenCalled();
    expect(textRead).not.toHaveBeenCalled();
  });

  it.each([
    ['PENDING', 'PENDING — initial hint; open Results or Metrics to check the current archive state.'],
    ['ARCHIVING', 'ARCHIVING — the report archive is being published.'],
    ['FAILED', 'FAILED — the inline result remains available.'],
    ['READY', 'OMITTED — the report was not included in the archive.'],
  ] as const)('keeps inline LOAD metrics available while report availability is %s', async (archiveStatus, expected) => {
    const compact: JobResultResponse = { job: { jobId: 'JOB-204', command: 'load', status: 'PASS' }, result: { status: 'PASS', summary: {
      schemaVersion: 'att-load-terminal-result/v1', execution: { status: 'PASS' }, performance: { verdict: 'PASS' },
      metrics: { scheduled: 12, started: 12, completed: 12, success: 12, runtimeError: 0 },
      archive: { reportAvailability: 'PENDING', status: 'PENDING' },
    } } };
    const api = makeApi({ getJobResult: vi.fn(async () => compact), getJobArtifactArchive: vi.fn(async () => ({ archiveStatus, items: [], primaryReports: [] })) });
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} />));
    await click(host.querySelector('#job-tab-metrics')!);
    await advance();
    expect(host.querySelector('#job-panel-metrics')?.textContent).toContain(expected);
    expect(host.querySelector('#job-panel-metrics')?.textContent).toContain('12 / 12');
    expect(api.getJobArtifactText).toBeUndefined();
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

  it('shows all event types in Server append order and filters without reconnecting or dropping new events', async () => {
    const api = makeApi();
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} confirmCancel={() => true} />));
    const handlers = vi.mocked(api.subscribeJob).mock.calls[0]?.[1];
    await act(async () => {
      handlers?.onEvent({ id: '1', type: 'status', data: { status: 'RUNNING' } });
      handlers?.onEvent({ id: '2', type: 'progress', data: { message: 'half complete' } });
      handlers?.onEvent({ id: '3', type: 'log', data: { message: 'worker log' } });
      handlers?.onEvent({ id: '4', type: 'diagnostic', data: { code: 'ATT-EXAMPLE', summary: 'safe warning' } });
      handlers?.onEvent({ id: '5', type: 'result', data: { status: 'PASS' } });
    });
    await click(host.querySelector('#job-tab-events')!);
    const panel = host.querySelector('#job-panel-events')!;
    const labels = [...panel.querySelectorAll('.att-job-events summary strong')].map(item => item.textContent);
    expect(labels).toEqual(['status', 'progress', 'log', 'diagnostic', 'result']);
    expect(panel.querySelector('.att-job-events time')?.getAttribute('datetime')).toBeTruthy();
    expect(panel.textContent).toContain('"message": "worker log"');

    const eventType = (type: string) => panel.querySelector<HTMLInputElement>(`input[aria-label="${type}"]`)!;
    for (const type of ['log', 'diagnostic', 'result']) await click(eventType(type));
    expect(panel.querySelectorAll('.att-job-events li:not([aria-hidden])')).toHaveLength(2);
    await act(async () => handlers?.onEvent({ id: '6', type: 'progress', data: { message: 'still connected' } }));
    await advance(100);
    expect(panel.querySelectorAll('.att-job-events li:not([aria-hidden])')).toHaveLength(3);
    await click(panel.querySelector<HTMLInputElement>('[aria-label="All Events"]')!);
    expect(panel.querySelectorAll('.att-job-events li:not([aria-hidden])')).toHaveLength(6);
    expect(api.subscribeJob).toHaveBeenCalledTimes(1);
    expect(panel.querySelector('[aria-label="Search events"]')).not.toBeNull();

    await click(host.querySelector('#job-tab-live')!);
    const live = host.querySelector('#job-panel-live')!;
    expect(live.textContent).toContain('This Job has no console lines');
    await act(async () => handlers?.onEvent({ id: '7', type: 'log', data: { message: '<b>secret</b>', console: '[RUN] case=one\n[RUN] case=two' } }));
    await advance(100);
    expect(live.querySelector('pre.att-console')?.textContent).toBe('[RUN] case=one\n[RUN] case=two');
    expect(live.querySelector('pre.att-console')?.innerHTML).not.toContain('<b>');
    expect([...live.querySelectorAll('button')].some(button => button.textContent === 'Copy Output')).toBe(true);
    await click([...live.querySelectorAll('button')].find(button => button.textContent === 'Clear view')!);
    expect(live.querySelector('pre.att-console')).toBeNull();
    await act(async () => handlers?.onEvent({ id: '7', type: 'log', data: { console: '[RUN] replay must stay cleared' } }));
    await advance(100);
    expect(live.querySelector('pre.att-console')).toBeNull();
    await act(async () => handlers?.onEvent({ id: '8', type: 'log', data: { console: '[RUN] after clear' } }));
    await advance(100);
    expect(live.querySelector('pre.att-console')?.textContent).toBe('[RUN] after clear');
  });

  it('shows non-live stream diagnostics only when replay or gap counters are non-zero', async () => {
    const api = makeApi();
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} confirmCancel={() => true} />));
    await click(host.querySelector('#job-tab-events')!);
    const panel = host.querySelector('#job-panel-events')!;
    expect([...panel.querySelectorAll('p.att-help')].some(item => item.textContent?.startsWith('Stream diagnostics:'))).toBe(false);

    const handlers = vi.mocked(api.subscribeJob).mock.calls[0][1];
    act(() => {
      handlers.onEvent({ id: '1', type: 'log', data: 'one' });
      handlers.onEvent({ id: '3', type: 'log', data: 'three' });
      handlers.onEvent({ id: '2', type: 'log', data: 'late two' });
      handlers.onEvent({ id: '3', type: 'log', data: 'duplicate three' });
      handlers.onEvent({ id: '3', type: 'log', data: 'duplicate three again' });
    });
    await advance(100);

    const diagnostics = [...panel.querySelectorAll('p.att-help')]
      .find(item => item.textContent?.startsWith('Stream diagnostics:'))!;
    expect(diagnostics.textContent).toBe('Stream diagnostics: 2 duplicate and 1 out-of-order replayed events ignored; 1 ID gap.');
    expect(diagnostics.getAttribute('role')).toBeNull();
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

  it('keeps a resizable terminal console and an independent, informative events view', async () => {
    const api = makeApi();
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} confirmCancel={() => true} />));
    const handlers = vi.mocked(api.subscribeJob).mock.calls[0]?.[1];
    await act(async () => handlers?.onEvent({
      id: '1', type: 'progress',
      data: { status: 'RUNNING', phase: 'STEADY', sequence: 4, message: 'steady progress', metrics: { completed: 4, failed: 0 } },
    }));
    await act(async () => handlers?.onEvent({ id: '2', type: 'log', data: { console: '[LOAD] status=RUNNING phase=STEADY', message: 'console line' } }));
    await advance(100);

    await click(host.querySelector('#job-tab-events')!);
    const events = host.querySelector('#job-panel-events')!;
    const first = events.querySelector('details')!;
    expect(first.open).toBe(true);
    expect(first.querySelector('.att-event-preview')?.textContent).toContain('status=RUNNING');
    expect(first.querySelector('.att-event-preview')?.textContent).toContain('phase=STEADY');
    expect(first.querySelector('.att-event-preview')?.textContent).toContain('sequence=4');
    expect(first.querySelector('.att-event-preview')?.textContent).toContain('message=steady progress');
    expect(first.querySelector('.att-event-preview')?.textContent).toContain('metrics=completed=4, failed=0');
    expect(first.querySelector('pre.att-event-payload')?.textContent).toContain('"phase": "STEADY"');
    await click(first.querySelector('summary')!);
    expect(first.open).toBe(false);
    expect(first.querySelector('.att-event-preview')?.textContent).toContain('status=RUNNING');
    await click([...events.querySelectorAll('button')].find(button => button.textContent === 'Expand all')!);
    expect(events.querySelector('details')?.open).toBe(true);
    await click([...events.querySelectorAll('button')].find(button => button.textContent === 'Collapse all')!);
    expect([...events.querySelectorAll('details')].every(item => !item.open)).toBe(true);

    const list = events.querySelector('.att-job-events') as HTMLElement;
    Object.defineProperty(list, 'scrollHeight', { configurable: true, value: 800 });
    Object.defineProperty(list, 'clientHeight', { configurable: true, value: 200 });
    await click([...events.querySelectorAll('button')].find(button => button.textContent === 'Resume auto-scroll')!);
    expect(list.scrollTop).toBe(800);
    list.scrollTop = 40;
    await act(async () => { list.dispatchEvent(new Event('scroll', { bubbles: true })); });
    expect([...events.querySelectorAll('button')].some(button => button.textContent === 'Resume auto-scroll')).toBe(true);
    await act(async () => handlers?.onEvent({ id: '3', type: 'progress', data: { status: 'RUNNING', phase: 'STEADY', sequence: 5, message: 'still arriving' } }));
    await advance(100);
    expect(list.scrollTop).toBe(40);
    expect(api.subscribeJob).toHaveBeenCalledTimes(1);
    const logFilter = events.querySelector<HTMLInputElement>('input[aria-label="log"]')!;
    await click(logFilter);
    expect(events.textContent).toContain('sequence=5');
    expect(events.textContent).not.toContain('console line');
    expect(events.querySelector<HTMLInputElement>('[aria-label="Events auto-scroll"]')?.checked).toBe(true);
    await click(host.querySelector('#job-tab-live')!);
    const live = host.querySelector('#job-panel-live')!;
    const liveScroll = live.querySelector<HTMLInputElement>('[aria-label="Live output auto-scroll"]')!;
    expect(liveScroll.checked).toBe(true);
    await click(liveScroll);
    expect(liveScroll.checked).toBe(false);
    await click(host.querySelector('#job-tab-events')!);
    expect(host.querySelector<HTMLInputElement>('#job-panel-events [aria-label="Events auto-scroll"]')?.checked).toBe(true);
    expect([...host.querySelector('#job-panel-events')!.querySelectorAll('button')].some(button => button.textContent === 'Resume auto-scroll')).toBe(true);
    await click(host.querySelector('#job-tab-live')!);
    expect(host.querySelector<HTMLInputElement>('#job-panel-live [aria-label="Live output auto-scroll"]')?.checked).toBe(false);

    const search = live.querySelector<HTMLInputElement>('[aria-label="Search output"]')!;
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setter?.call(search, 'STEADY');
      search.dispatchEvent(new Event('input', { bubbles: true }));
    });
    const consoleView = live.querySelector('pre.att-console') as HTMLElement;
    expect(consoleView).not.toBeNull();
    expect(consoleView.style.backgroundColor.replace(/\s/g, '')).toMatch(/rgb\(13,17,23\)|#0d1117/i);
    expect(consoleView.style.color.replace(/\s/g, '')).toMatch(/rgb\(245,247,250\)|#f5f7fa/i);
    expect(consoleView.style.whiteSpace).toBe('pre');
    expect(consoleView.style.overflow).toBe('auto');
    expect(consoleView.textContent).toContain('[LOAD] status=RUNNING phase=STEADY');
    const shell = live.querySelector('.att-console-shell') as HTMLElement;
    const resize = live.querySelector('[aria-label="Resize live output"]') as HTMLElement;
    expect(resize).not.toBeNull();
    expect(live.textContent).toContain('Maximize');
    const before = Number(resize.getAttribute('aria-valuenow'));
    await act(async () => {
      resize.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, clientY: 100, pointerId: 1 }));
      resize.dispatchEvent(new PointerEvent('pointermove', { bubbles: true, clientY: 164, pointerId: 1 }));
      resize.dispatchEvent(new PointerEvent('pointerup', { bubbles: true, clientY: 164, pointerId: 1 }));
    });
    expect(Number(resize.getAttribute('aria-valuenow'))).toBe(before + 64);
    expect(shell.style.height).toBe(`${before + 64}px`);
    const tabs = host.querySelector('[role="tablist"]')!;
    await click([...live.querySelectorAll('button')].find(button => button.textContent === 'Maximize')!);
    expect(shell.getAttribute('data-maximized')).toBe('true');
    expect(shell.style.position).toBe('fixed');
    expect(shell.contains(tabs)).toBe(false);
    expect(host.querySelector('#job-tab-overview')).not.toBeNull();
    expect(search.value).toBe('STEADY');
    expect(consoleView.textContent).toContain('[LOAD] status=RUNNING phase=STEADY');
    expect(api.subscribeJob).toHaveBeenCalledTimes(1);
    await click(shell.querySelector('button')!);
    expect(shell.getAttribute('data-maximized')).toBe('false');
    expect(shell.style.position).toBe('');
    expect(shell.style.height).toBe(`${before + 64}px`);
    expect(live.querySelector('[aria-label="Resize live output"]')).not.toBeNull();
  });

  it('does not treat the browser event window as a truncated Server journal', async () => {
    const api = makeApi();
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} confirmCancel={() => true} />));
    const handlers = vi.mocked(api.subscribeJob).mock.calls[0][1];
    await act(async () => {
      for (let id = 1; id <= 501; id += 1) handlers.onEvent({ id: String(id), type: 'log', data: { message: `event-${id}` } });
    });
    await advance(100);
    const note = host.querySelector('#job-panel-live [role="note"]');
    expect(note?.textContent ?? '').not.toContain('Earlier journal events');
    expect(note?.textContent).toContain('This browser keeps the latest 500 events.');
    const ids = [...host.querySelectorAll('#job-panel-events code')].map(node => node.textContent);
    expect(ids.length).toBeGreaterThan(0);
    expect(ids.length).toBeLessThan(100);
    expect(ids).not.toContain('#1');
    expect(ids[0]).toBe('#2');
    const search = host.querySelector<HTMLInputElement>('#job-panel-events [aria-label="Search events"]')!;
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setter?.call(search, 'event-501');
      search.dispatchEvent(new Event('input', { bubbles: true }));
    });
    expect([...host.querySelectorAll('#job-panel-events code')].map(node => node.textContent)).toContain('#501');
  });

  it('keeps the Server truncation warning after the flagged event leaves the browser window', async () => {
    const api = makeApi();
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-204" onOpenJob={vi.fn()} onShowList={vi.fn()} confirmCancel={() => true} />));
    const handlers = vi.mocked(api.subscribeJob).mock.calls[0][1];
    await act(async () => {
      handlers.onEvent({ id: '1', type: 'log', data: { historyTruncated: true, message: 'oldest retained boundary' } });
      handlers.onEvent({ id: '2', type: 'log', data: { message: 'still in the journal' } });
    });
    await advance(100);
    const early = host.querySelector('#job-panel-live [role="note"]');
    expect(early?.textContent).toContain('Earlier journal events are no longer retained.');
    expect(early?.textContent ?? '').not.toContain('This browser keeps the latest 500 events.');
    await act(async () => {
      for (let id = 3; id <= 501; id += 1) handlers.onEvent({ id: String(id), type: 'log', data: { message: `event-${id}` } });
    });
    await advance(100);
    const note = host.querySelector('#job-panel-live [role="note"]');
    expect(note?.textContent).toContain('Earlier journal events are no longer retained.');
    expect(note?.textContent).toContain('This browser keeps the latest 500 events.');
    const ids = [...host.querySelectorAll('#job-panel-events code')].map(node => node.textContent);
    expect(ids.length).toBeGreaterThan(0);
    expect(ids.length).toBeLessThan(100);
    expect(ids).not.toContain('#1');
    expect(ids[0]).toBe('#2');
    await act(async () => root.render(<JobsPage api={api} jobId="JOB-205" onOpenJob={vi.fn()} onShowList={vi.fn()} confirmCancel={() => true} />));
    expect(host.querySelector('#job-panel-live [role="note"]')).toBeNull();
    expect(api.subscribeJob).toHaveBeenCalledTimes(2);
  });
});
