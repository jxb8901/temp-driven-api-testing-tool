import { useEffect, useState } from 'react';
import { ApiError } from '../../types';
import { api as serverApi } from '../../api';
import { contextRelativeReturnTo } from '../../contextPaths';
import { CodeEditor, detectCodeLanguage } from '../code/CodeEditor';
import { formatRoute } from '../../router';
import { artifactApi } from './artifactApi';
import { ReportPreview } from './ReportPreview';
import type { JobArtifactArchive, LoadJobsApi } from './types';
import './StandaloneJobArtifactPage.css';

type ArtifactViewState =
  | { kind: 'loading'; path: string }
  | { kind: 'archive'; path: string; status: JobArtifactArchive['archiveStatus'] }
  | { kind: 'ready'; path: string; text: string | null }
  | { kind: 'invalid'; path: string }
  | { kind: 'error'; path: string; stage: 'job' | 'artifact'; error: unknown };

/** Route input is untrusted. The server still performs the authoritative manifest and ownership checks. */
export function isSafeArtifactRoutePath(path: string): boolean {
  if (!path || path.length > 2048 || path.startsWith('/') || path.includes('\\')
    || path.normalize('NFC') !== path || /[\x00-\x1f\x7f<>"|*%:?#]/.test(path)) return false;
  return path.split('/').every(segment => segment !== '' && segment !== '.' && segment !== '..'
    && !segment.endsWith('.') && !segment.endsWith(' ')
    && !/^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\..*)?$/i.test(segment));
}

function errorMessage(error: unknown, stage: 'job' | 'artifact'): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return 'Your session has expired. Sign in again to open this artifact.';
    if (error.status === 403) return 'Your account is not authorized to open this Job artifact.';
    if (error.status === 404) return stage === 'job'
      ? 'This Job is unavailable or was deleted.'
      : 'This artifact is unavailable or was not approved for preview.';
    if (error.status === 409) return 'The artifact archive is unavailable. Refresh to check its status.';
    if (error.status === 503) return 'Artifact preview capacity is full. Refresh to try again.';
    return error.message;
  }
  return error instanceof Error ? error.message : 'The artifact could not be loaded.';
}

function archiveStatusMessage(status: JobArtifactArchive['archiveStatus']): string {
  if (status === 'PENDING' || status === 'ARCHIVING') return 'The approved artifact archive is still being prepared.';
  if (status === 'EXPIRED') return 'The approved artifact archive has expired.';
  if (status === 'FAILED') return 'The approved artifact archive failed and is unavailable.';
  return 'The approved artifact archive is unavailable.';
}

export function StandaloneJobArtifactPage({ api, jobId, path }: { api: LoadJobsApi; jobId: string; path: string }) {
  const features = api === serverApi ? artifactApi : api;
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<ArtifactViewState>({ kind: 'loading', path });
  const html = /\.html?$/i.test(path);

  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let stage: 'job' | 'artifact' = 'job';
    setState({ kind: 'loading', path });
    if (!isSafeArtifactRoutePath(path)) {
      setState({ kind: 'invalid', path });
      return () => controller.abort();
    }

    async function load() {
      try {
        await api.getJob(jobId, controller.signal);
        const archive = await features.getJobArtifactArchive?.(jobId, 0, controller.signal);
        if (!archive) throw new Error('Artifact archive metadata is unavailable.');
        if (controller.signal.aborted) return;
        if (archive.archiveStatus !== 'READY') {
          setState({ kind: 'archive', path, status: archive.archiveStatus });
          if (archive.archiveStatus === 'PENDING' || archive.archiveStatus === 'ARCHIVING')
            timer = setTimeout(() => setAttempt(value => value + 1), 2000);
          return;
        }
        stage = 'artifact';
        if (html) {
          // ReportPreview retrieves the sanitized archive entry through the same authenticated API.
          setState({ kind: 'ready', path, text: null });
          return;
        }
        const text = await features.getJobArtifactText?.(jobId, path, controller.signal);
        if (text === undefined) throw new Error('Text artifact preview is unavailable.');
        if (!controller.signal.aborted) setState({ kind: 'ready', path, text });
      } catch (error) {
        if (!controller.signal.aborted) setState({ kind: 'error', path, stage, error });
      }
    }
    void load();
    return () => { controller.abort(); if (timer) clearTimeout(timer); };
  }, [api, attempt, features, html, jobId, path]);

  const backToJob = formatRoute({ kind: 'jobs', jobId, tab: 'artifacts' });
  const signInHref = new URL('login.html?returnTo=' + encodeURIComponent(contextRelativeReturnTo(location.pathname, location.search, location.hash)), document.baseURI).toString();
  const currentState = state.path === path ? state : { kind: 'loading' as const, path };

  function navigateToArtifact(nextPath: string) {
    if (!isSafeArtifactRoutePath(nextPath)) return;
    window.location.hash = formatRoute({ kind: 'job-artifact', jobId, path: nextPath });
  }

  return <div className="att-standalone-artifact">
    <header className="att-standalone-artifact-heading">
      <div><p className="att-eyebrow">Job artifact</p><h1>{jobId}</h1><p className="att-standalone-artifact-path">{path || 'No artifact path was provided.'}</p></div>
      <a href={backToJob}>Back to Job Details</a>
    </header>
    <main aria-label="Standalone Job artifact">
      {currentState.kind === 'loading' && <p role="status" aria-busy="true">Loading approved artifact…</p>}
      {currentState.kind === 'invalid' && <p role="alert">This artifact path is invalid. Open an artifact from Job Details.</p>}
      {currentState.kind === 'archive' && <section className="att-standalone-artifact-state" role={currentState.status === 'PENDING' || currentState.status === 'ARCHIVING' ? 'status' : 'alert'}>
        <p>{archiveStatusMessage(currentState.status)}</p>
        <button type="button" onClick={() => setAttempt(value => value + 1)}>Refresh artifact status</button>
      </section>}
      {currentState.kind === 'error' && <section className="att-standalone-artifact-state" role="alert">
        <p>{errorMessage(currentState.error, currentState.stage)}</p>
        {currentState.error instanceof ApiError && currentState.error.status === 401 && <a className="button button-primary" href={signInHref}>Sign in again</a>}
        <button type="button" onClick={() => setAttempt(value => value + 1)}>Retry</button>
      </section>}
      {currentState.kind === 'ready' && (html
        ? features.getJobArtifactText
          ? <ReportPreview key={jobId + ':' + path} jobId={jobId} path={path} readText={features.getJobArtifactText} onNavigate={navigateToArtifact} />
          : <p role="alert">Authenticated report preview is unavailable.</p>
        : currentState.text === ''
          ? <p>This artifact is empty.</p>
          : <><p>Read-only preview{(currentState.text?.length || 0) > 200_000 ? '; showing the first 200,000 characters' : ''}.</p>
            <CodeEditor value={currentState.text || ''} readOnly language={detectCodeLanguage(path)} sourcePath={path}
              label={`${path}; line numbers shown`} maxCharacters={200_000} /></>)}
    </main>
  </div>;
}
