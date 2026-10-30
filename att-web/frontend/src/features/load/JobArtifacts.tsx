import { useEffect, useRef, useState } from 'react';
import { CodeEditor, detectCodeLanguage } from '../code/CodeEditor';
import { ReportPreview } from './ReportPreview';
import { api as serverApi } from '../../api';
import { artifactApi } from './artifactApi';
import type { ArtifactArchiveMetrics, JobArtifact, JobArtifactArchive, LoadJobsApi } from './types';
import { formatRoute } from '../../router';

const categories = [
  ['summaryManifests', 'Summaries and manifests'],
  ['metricsTimeSeries', 'Metrics and time series'],
  ['executionEvidence', 'Execution evidence'],
  ['recoveryCheckpoints', 'Recovery checkpoints'],
  ['other', 'Other artifacts'],
] as const;

function categoryForPath(path: string): typeof categories[number][0] {
  const lower = path.toLowerCase();
  const leaf = lower.slice(lower.lastIndexOf('/') + 1);
  if (lower.includes('/checkpoints/')) return 'recoveryCheckpoints';
  if (lower.includes('/details/') || lower.includes('/timeseries/') || leaf.startsWith('series-') || leaf.includes('metric')) return 'metricsTimeSeries';
  if (leaf.includes('summary') || leaf.includes('manifest') || lower.endsWith('/report/index.html')) return 'summaryManifests';
  if (lower.includes('/evidence/') || lower.includes('/samples/') || lower.includes('/events/') || lower.includes('/failures/')
    || lower.includes('/iterations/') || lower.includes('/executions/') || leaf.includes('iteration')) return 'executionEvidence';
  return 'other';
}

function groupExecutionEvidence(items: JobArtifact[]) {
  const workloads = new Map<string, Map<string, JobArtifact[]>>();
  for (const item of items) {
    const segments = item.path.split('/');
    const evidenceRoot = segments.findIndex(segment => ['samples', 'failures', 'iterations', 'evidence', 'events'].includes(segment.toLowerCase()));
    const workloadIndex = segments.findIndex((segment, index) => index > evidenceRoot && /^workload-/i.test(segment));
    const workload = workloadIndex >= 0 ? segments[workloadIndex] : 'Workload not encoded in path';
    const executionIndex = workloadIndex >= 0 ? workloadIndex + 1 : evidenceRoot + 1;
    const execution = executionIndex >= 0 && executionIndex < segments.length - 1
      ? segments[executionIndex] : 'Run-level evidence';
    const executions = workloads.get(workload) || new Map<string, JobArtifact[]>();
    const entries = executions.get(execution) || [];
    entries.push(item);
    executions.set(execution, entries);
    workloads.set(workload, executions);
  }
  return [...workloads.entries()].map(([workload, executions]) => ({
    workload,
    executions: [...executions.entries()].map(([execution, entries]) => ({ execution, entries })),
  }));
}

export function formatArchiveSize(bytes: number | null | undefined): string | null {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) return null;
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  const index = bytes === 0 ? 0 : Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)));
  return `${(bytes / 1024 ** Math.max(0, index)).toLocaleString(undefined, { maximumFractionDigits: index > 0 ? 1 : 0 })} ${units[Math.max(0, index)]}`;
}

export function JobArtifacts({ api, jobId, refresh, onMetrics }: { api: LoadJobsApi; jobId: string; refresh: number; onMetrics?: (metrics: ArtifactArchiveMetrics | null) => void }) {
  const features = api === serverApi ? artifactApi : api;
  const [archive, setArchive] = useState<JobArtifactArchive | null>(null);
  const [error, setError] = useState('');
  const [version, setVersion] = useState(0);
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState<JobArtifact | null>(null);
  const [text, setText] = useState<string | null>(null);
  const [previewError, setPreviewError] = useState('');
  const [search, setSearch] = useState('');
  const sequence = useRef(0);
  const loadedCount = useRef(0);

  useEffect(() => {
    loadedCount.current = 0;
    setArchive(null); setSelected(null); setBusy(false); setSearch('');
  }, [jobId, api]);

  useEffect(() => {
    const controller = new AbortController();
    const current = ++sequence.current;
    let timer: ReturnType<typeof setTimeout> | undefined;
    setError(''); setBusy(false);
    async function load() {
      try {
        let data: JobArtifactArchive = features.getJobArtifactArchive ? await features.getJobArtifactArchive(jobId, 0, controller.signal)
          : { archiveStatus: 'READY' as const, items: await api.getJobArtifacts(jobId, controller.signal), primaryReports: [] };
        // Reconcile the already-visible prefix instead of replacing it with page one.
        while (data.archiveStatus === 'READY' && data.items.length < loadedCount.current && data.nextOffset != null && features.getJobArtifactArchive) {
          const offset = data.nextOffset;
          const page = await features.getJobArtifactArchive(jobId, offset, controller.signal);
          if (page.archiveStatus !== 'READY') { data = page; break; }
          data = { ...page, items: [...data.items, ...page.items] };
          if (page.nextOffset != null && page.nextOffset <= offset) throw new Error('Invalid artifact pagination.');
        }
        if (controller.signal.aborted || current !== sequence.current) return;
        loadedCount.current = data.items.length;
        setArchive(data);
        setSelected(previous => {
          if (!previous) return null;
          const entry = [...data.items, ...data.primaryReports].find(item => item.path === previous.path);
          return data.archiveStatus === 'READY' && entry && entry.size === previous.size && entry.sha256 === previous.sha256 ? previous : null;
        });
        onMetrics?.(data.archiveMetrics || null);
        if (['PENDING', 'ARCHIVING'].includes(data.archiveStatus) || data.archiveMetrics?.outcome === 'RUNNING') timer = setTimeout(() => void load(), 2000);
      } catch (reason) { if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : 'Artifacts are unavailable.'); }
    }
    void load();
    return () => { controller.abort(); if (timer) clearTimeout(timer); };
  }, [api, features, jobId, refresh, version, onMetrics]);

  useEffect(() => {
    setText(null); setPreviewError('');
    if (!selected || /\.html?$/i.test(selected.path)) return;
    const controller = new AbortController();
    if (!features.getJobArtifactText) { setPreviewError('Text preview is unavailable.'); return; }
    features.getJobArtifactText(jobId, selected.path, controller.signal).then(value => {
      if (!controller.signal.aborted) setText(value);
    }).catch(reason => { if (!controller.signal.aborted) setPreviewError(reason instanceof Error ? reason.message : 'Preview is unavailable.'); });
    return () => controller.abort();
  }, [api, features, jobId, selected]);

  async function more() {
    if (!features.getJobArtifactArchive || archive?.nextOffset == null || busy) return;
    const current = sequence.current;
    setBusy(true);
    try {
      const data = await features.getJobArtifactArchive(jobId, archive.nextOffset);
      if (current !== sequence.current) return;
      if (data.archiveStatus !== 'READY') { setVersion(value => value + 1); return; }
      loadedCount.current += data.items.length;
      setArchive(previous => previous ? { ...data, items: [...previous.items, ...data.items] } : data);
    } catch (reason) { if (current === sequence.current) setError(reason instanceof Error ? reason.message : 'Artifacts could not be loaded.'); }
    finally { if (current === sequence.current) setBusy(false); }
  }

  async function retry() {
    setBusy(true); setError('');
    try { await features.retryJobArtifactArchive?.(jobId); setVersion(value => value + 1); }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Archive retry failed.'); }
    finally { setBusy(false); }
  }

  const query = search.trim().toLocaleLowerCase();
  const matches = (item: JobArtifact) => !query || item.path.toLocaleLowerCase().includes(query);
  const entry = (item: JobArtifact) => <li key={item.path}>
    <button type="button" className="att-link-button" aria-label={`${item.report ? 'View report' : 'Preview'} ${item.path}`}
      title={item.path} onClick={() => setSelected(item)}>{item.report ? 'View Report: ' : ''}{item.path.slice(item.path.lastIndexOf('/') + 1)}</button>
    {item.size != null && <span> ({formatArchiveSize(item.size)})</span>}{' '}
    <a href={formatRoute({ kind: 'job-artifact', jobId, path: item.path })} target="_blank" rel="noopener noreferrer" title={item.path}>Open in new tab</a>
    <details><summary>Full path</summary><code>{item.path}</code></details>
  </li>;
  return <section aria-label="Job artifact archive">
    {!archive && !error && <p role="status">Loading artifacts...</p>}
    {error && <p role="alert">{error} <button type="button" onClick={() => setVersion(value => value + 1)}>Refresh artifacts</button></p>}
    {archive && <>
      <p role="status">Artifact Archive: {archive.archiveStatus}</p>
      {archive.archiveStatus === 'FAILED' && <p>The archive could not be published. {archive.retryable && features.retryJobArtifactArchive && <button type="button" disabled={busy} onClick={() => void retry()}>Retry archive</button>}</p>}
      {archive.archiveStatus === 'READY' && <>
        {features.artifactArchiveHref && <a href={features.artifactArchiveHref(jobId)} title={formatArchiveSize(archive.archiveSizeBytes) ? `${archive.archiveSizeBytes} bytes` : undefined}>Download Artifacts (.zip){formatArchiveSize(archive.archiveSizeBytes) && ` — ${formatArchiveSize(archive.archiveSizeBytes)}`}</a>}
        <p aria-label="Archive contents">{archive.items.length.toLocaleString()} loaded of {(archive.archiveItemsCount ?? archive.items.length).toLocaleString()} approved files
          {archive.archiveItemsBytes != null && ` · ${formatArchiveSize(archive.archiveItemsBytes)} approved payload`}
          {archive.archiveSizeBytes != null && ` · ZIP ${formatArchiveSize(archive.archiveSizeBytes)}`}</p>
        {archive.complete === false && <p role="note">Some output was excluded by the publication policy. Omitted candidates: {archive.omissions?.reduce((sum, item) => sum + item.count, 0).toLocaleString() ?? 'Not available'}. {archive.omissions?.map(item => `${item.count}: ${item.reason === 'unsupported, unsafe, duplicate, binary, oversized, or unapproved output' ? 'reason unavailable in this archive version' : item.reason.replaceAll('_', ' ')}`).join('; ') || 'reason unavailable in this archive version'}.</p>}
        <label>Search path or workload <input type="search" value={search} onChange={event => setSearch(event.target.value)} /></label>
        {query && archive.nextOffset != null && <p role="note">Search covers the loaded files. Load more to search the rest of the archive.</p>}
        {archive.primaryReports.filter(matches).length > 0 && <><h3>Reports</h3><ul>{archive.primaryReports.filter(matches).map(entry)}</ul></>}
        {categories.map(([key, label]) => {
          const tally = archive.categorySummary?.[key];
          const primaryPaths = new Set(archive.primaryReports.map(item => item.path));
          const loaded = archive.items.filter(item => categoryForPath(item.path) === key && !primaryPaths.has(item.path));
          const visible = loaded.filter(matches);
          const primaryReportsInCategory = archive.primaryReports.filter(item => categoryForPath(item.path) === key);
          const matchingPrimaryReportsInCategory = primaryReportsInCategory.filter(matches);
          const primaryReportsListed = query ? matchingPrimaryReportsInCategory.length > 0 : primaryReportsInCategory.length > 0;
          const count = tally?.count ?? loaded.length;
          if (!count) return null;
          return <details key={key}>
            <summary>{label} · {count.toLocaleString()} files{tally?.bytes != null && ` · ${formatArchiveSize(tally.bytes)}`}</summary>
            {key === 'summaryManifests' && primaryReportsListed && <p>Primary reports are listed above.</p>}
            {key === 'executionEvidence' && visible.length ? groupExecutionEvidence(visible).map(workload => <details key={workload.workload}>
              <summary>{workload.workload} · {workload.executions.reduce((count, execution) => count + execution.entries.length, 0)} files</summary>
              {workload.executions.map(execution => <details key={execution.execution}>
                <summary>Execution {execution.execution} · {execution.entries.length} files</summary>
                <ul>{execution.entries.map(entry)}</ul>
              </details>)}
            </details>) : visible.length ? <ul>{visible.map(entry)}</ul> : <p>{query && !matchingPrimaryReportsInCategory.length ? 'No matching files in the loaded pages.' : primaryReportsListed ? 'Primary reports are listed above.' : 'Load more artifacts to view these files.'}</p>}
          </details>;
        })}
        {!archive.items.length && !archive.primaryReports.length && <p>No approved artifacts were produced.</p>}
        {archive.nextOffset != null && <button type="button" disabled={busy} onClick={() => void more()}>Load more artifacts</button>}
      </>}
    </>}
    {selected && <section aria-label="Artifact preview">
      <h3>{selected.path}</h3>
      <div className="att-review-actions">
        <a href={formatRoute({ kind: 'job-artifact', jobId, path: selected.path })} target="_blank" rel="noopener noreferrer">Open in new tab</a>
        <button type="button" onClick={() => setSelected(null)}>Close preview</button>
      </div>
      {/\.html?$/i.test(selected.path) ? (
        features.getJobArtifactText
          ? <ReportPreview key={jobId + ':' + selected.path} jobId={jobId} path={selected.path} readText={features.getJobArtifactText} />
          : <p role="alert">Authenticated report preview is unavailable.</p>
      ) : <>
        {previewError ? <p role="alert">{previewError}</p> : text === null ? <p role="status">Loading preview...</p>
          : text.length === 0 ? <p>Artifact is empty.</p>
            : <><p>Read-only preview{ text.length > 200_000 ? '; showing the first 200,000 characters' : '' }.</p><CodeEditor value={text} readOnly language={detectCodeLanguage(selected.path)} sourcePath={selected.path} label={`${selected.path}; line numbers shown`} maxCharacters={200_000} /></>}
      </>}
    </section>}
  </section>;
}
