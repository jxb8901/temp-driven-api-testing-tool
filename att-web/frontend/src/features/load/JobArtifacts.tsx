import { useEffect, useRef, useState } from 'react';
import { CodeEditor, detectCodeLanguage } from '../code/CodeEditor';
import { ReportPreview } from './ReportPreview';
import { api as serverApi } from '../../api';
import { artifactApi } from './artifactApi';
import type { ArtifactArchiveMetrics, JobArtifact, JobArtifactArchive, LoadJobsApi } from './types';

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
  const sequence = useRef(0);

  useEffect(() => {
    const controller = new AbortController();
    const current = ++sequence.current;
    let timer: ReturnType<typeof setTimeout> | undefined;
    setArchive(null); setError(''); setSelected(null); setBusy(false);
    async function load() {
      try {
        const data: JobArtifactArchive = features.getJobArtifactArchive ? await features.getJobArtifactArchive(jobId, 0, controller.signal)
          : { archiveStatus: 'READY' as const, items: await api.getJobArtifacts(jobId, controller.signal), primaryReports: [] };
        if (controller.signal.aborted || current !== sequence.current) return;
        setArchive(data);
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

  const entry = (item: JobArtifact) => <li key={item.path}><button type="button" className="att-link-button" onClick={() => setSelected(item)}>{item.report ? 'View Report: ' : 'Preview: '}{item.path}</button>{item.size != null && <span> ({item.size.toLocaleString()} bytes)</span>}</li>;
  return <section aria-label="Job artifact archive">
    {!archive && !error && <p role="status">Loading artifacts...</p>}
    {error && <p role="alert">{error} <button type="button" onClick={() => setVersion(value => value + 1)}>Refresh artifacts</button></p>}
    {archive && <>
      <p role="status">Artifact Archive: {archive.archiveStatus}</p>
      {archive.archiveStatus === 'FAILED' && <p>The archive could not be published. {archive.retryable && features.retryJobArtifactArchive && <button type="button" disabled={busy} onClick={() => void retry()}>Retry archive</button>}</p>}
      {archive.archiveStatus === 'READY' && <>
        {features.artifactArchiveHref && <a href={features.artifactArchiveHref(jobId)} title={formatArchiveSize(archive.archiveSizeBytes) ? `${archive.archiveSizeBytes} bytes` : undefined}>Download Artifacts (.zip){formatArchiveSize(archive.archiveSizeBytes) && ` — ${formatArchiveSize(archive.archiveSizeBytes)}`}</a>}
        {archive.complete === false && <p role="note">Some output was excluded by the publication policy. {archive.omissions?.map(item => `${item.count}: ${item.reason}`).join('; ')}</p>}
        {archive.primaryReports.length > 0 && <><h3>Reports</h3><ul>{archive.primaryReports.map(entry)}</ul></>}
        <ul>{archive.items.filter(item => !item.report).map(entry)}</ul>
        {!archive.items.length && !archive.primaryReports.length && <p>No approved artifacts were produced.</p>}
        {archive.nextOffset != null && <button type="button" disabled={busy} onClick={() => void more()}>Load more artifacts</button>}
      </>}
    </>}
    {selected && <section aria-label="Artifact preview">
      <h3>{selected.path}</h3><button type="button" onClick={() => setSelected(null)}>Close preview</button>
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
