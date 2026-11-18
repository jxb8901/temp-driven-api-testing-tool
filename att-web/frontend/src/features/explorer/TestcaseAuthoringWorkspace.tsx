import { useContext, useEffect, useRef, useState } from 'react';
import { ApiError } from '../../types';
import { AppShellContext } from '../../AppShell';
import type { ExplorerApi } from './types';
import type { ManagedAuthoringSource, SourceChangeSet, SourceOperation, SourcePublishResult, SourceSnapshotRequest } from './sourceManagement';
import { ChangedFiles } from './SourceAuthoringWorkspace';
import './source-authoring.css';

function safePath(path: string): boolean {
  return Boolean(path && path.length <= 512 && !path.startsWith('/') && !path.includes('\\')
    && path === path.trim() && !/^[A-Za-z]:/.test(path)
    && path.split('/').every(part => part && part !== '.' && part !== '..' && !/[\u0000-\u001f\u007f:]/.test(part)));
}
function failureText(error: unknown, action: string): string {
  const status = error instanceof ApiError ? error.status
    : typeof error === 'object' && error !== null && 'status' in error ? Number((error as { status?: unknown }).status) : 0;
  if (status === 401) return 'Your session expired. Sign in again and reload the Testcase source.';
  if (status === 403) return `The Server denied ${action}. Testcase source editing permission is unavailable or was revoked.`;
  if (status === 404) return 'The Package revision or Testcase source is unavailable. Return to the resource list and refresh.';
  if (status === 409) return 'The Package source revision changed. Reload current source context, compare the draft, then upload again.';
  if (status === 413) return `The Server rejected ${action} because the upload exceeds its configured size limit.`;
  if (status === 422) return `The Server rejected ${action} during validation. Review the returned diagnostics and correct the candidate.`;
  if (status === 429 || status === 503 || status === 504) return `The Server could not complete ${action} right now. Retry when it is available.`;
  if (error instanceof Error && error.message) return error.message;
  return `Could not ${action}. Check the connection and retry.`;
}
function base64(bytes: Uint8Array): string {
  let result = '';
  for (let offset = 0; offset < bytes.length; offset += 0x8000) result += String.fromCharCode(...bytes.subarray(offset, Math.min(offset + 0x8000, bytes.length)));
  return btoa(result);
}
async function sha256Hex(bytes: Uint8Array): Promise<string> {
  if (!globalThis.crypto?.subtle) throw new Error('Secure SHA-256 is unavailable in this browser context.');
  const input = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
  const digest = await globalThis.crypto.subtle.digest('SHA-256', input);
  return [...new Uint8Array(digest)].map(value => value.toString(16).padStart(2, '0')).join('');
}
function errorDiagnostics(error: unknown): Array<{ code?: string; severity?: string; logicalPath?: string | null; field?: string | null; line?: number | null; message?: string | null; remedy?: string | null }> {
  const raw = error instanceof ApiError ? error.diagnostics : undefined;
  if (!Array.isArray(raw)) return [];
  return raw.filter(item => item && typeof item === 'object').slice(0, 50) as Array<{ code?: string; severity?: string; logicalPath?: string | null; field?: string | null; line?: number | null; message?: string | null; remedy?: string | null }>;
}

export function TestcaseAuthoringWorkspace({ api, packageId, sourcePath, onBack, registerUnsavedChanges }: {
  api: ExplorerApi; packageId: string; sourcePath?: string; onBack?: () => void;
  registerUnsavedChanges?: (isDirty: boolean, description?: string) => () => void;
}) {
  const workspace = useContext(AppShellContext);
  const [revision, setRevision] = useState<Pick<ManagedAuthoringSource, 'sourceEtag' | 'baseSourceRevision' | 'registryRevision'> | null>(null);
  const [draft, setDraft] = useState<SourceChangeSet | null>(null);
  const [snapshotRequest, setSnapshotRequest] = useState<SourceSnapshotRequest | null>(null);
  const [published, setPublished] = useState<SourcePublishResult | null>(null);
  const [access, setAccess] = useState<'loading' | 'ready' | 'denied' | 'unavailable'>('loading');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');
  const [workbookOperation, setWorkbookOperation] = useState<'create' | 'replace'>(() => sourcePath ? 'replace' : 'create');
  const [sidecarOperation, setSidecarOperation] = useState<'create' | 'replace'>(() => sourcePath ? 'replace' : 'create');
  const [newXmlPath, setNewXmlPath] = useState('');
  const [refreshKey, setRefreshKey] = useState(0);
  const revisionControllerRef = useRef<AbortController | null>(null);
  const actionControllerRef = useRef<AbortController | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const previousPackageRef = useRef(packageId);
  const [suspended, setSuspended] = useState(false);
  const [recoveryPending, setRecoveryPending] = useState(false);
  const recoveryRequiredRef = useRef(false);
  const suspendedRef = useRef(false);
  const isDirty = Boolean(busy || draft || !sourcePath && newXmlPath);
  const isDirtyRef = useRef(isDirty);
  isDirtyRef.current = isDirty;
  const targetXmlPath = sourcePath && /\.xml$/i.test(sourcePath) && safePath(sourcePath)
    ? sourcePath : !sourcePath && /\.xml$/i.test(newXmlPath) && safePath(newXmlPath) ? newXmlPath : '';
  const draftStale = Boolean(draft && revision && (draft.baseSourceRevision !== revision.baseSourceRevision
    || draft.registryRevision !== revision.registryRevision));

  useEffect(() => {
    revisionControllerRef.current?.abort();
    const controller = new AbortController(); revisionControllerRef.current = controller;
    const targetChanged = previousPackageRef.current !== packageId;
    if (targetChanged) {
      actionControllerRef.current?.abort(); actionControllerRef.current = null;
      setPublished(null); previousPackageRef.current = packageId;
      setDraft(null); setSnapshotRequest(null); setNewXmlPath(''); recoveryRequiredRef.current = false; suspendedRef.current = false;
      setBusy(false);
    }
    setRevision(null); setError(''); setStatus('');
    setAccess('loading');
    const request = api.getManagedPackageAuthoringSource?.(packageId, { kind: 'manifest' }, controller.signal);
    if (!request) { setAccess('unavailable'); setError('This Server does not expose the source revision contract needed for Testcase uploads.'); return () => controller.abort(); }
    request.then(value => {
      if (controller.signal.aborted) return;
      if (!value.available || !value.sourceEtag || !value.baseSourceRevision || !value.registryRevision) {
        setAccess('unavailable'); setError('The Server did not provide a complete Package source revision and ETag. Testcase upload is unavailable.'); return;
      }
      // Keep revision metadata only; the complete Manifest bytes returned by this endpoint are not retained or rendered here.
      setRevision({ sourceEtag: value.sourceEtag, baseSourceRevision: value.baseSourceRevision, registryRevision: value.registryRevision });
      setAccess('ready');
      if (recoveryRequiredRef.current) setRecoveryPending(true);
      else if (!suspendedRef.current || document.visibilityState !== 'hidden') {
        suspendedRef.current = false; setSuspended(false);
      }
    }).catch(reason => {
      if (controller.signal.aborted) return;
      const status = reason instanceof ApiError ? reason.status : typeof reason === 'object' && reason !== null && 'status' in reason ? Number((reason as { status?: unknown }).status) : 0;
      setAccess(status === 403 ? 'denied' : 'unavailable');
      if (status === 403) {
        actionControllerRef.current?.abort();
        setDraft(null); setSnapshotRequest(null); setNewXmlPath(''); setRecoveryPending(false); setSuspended(false);
        recoveryRequiredRef.current = false; suspendedRef.current = false;
      }
      if (status !== 403) setError(failureText(reason, 'load Package source revisions'));
    });
    return () => { controller.abort(); if (revisionControllerRef.current === controller) revisionControllerRef.current = null; };
  }, [api, packageId, refreshKey]);

  useEffect(() => {
    if (!registerUnsavedChanges) return;
    return registerUnsavedChanges(isDirty, 'Testcase workbook source draft');
  }, [registerUnsavedChanges, isDirty]);
  useEffect(() => {
    workspace?.setWorkspaceEditorLock?.(access === 'ready' || Boolean(draft || busy));
    return () => workspace?.setWorkspaceEditorLock?.(false);
  }, [workspace?.setWorkspaceEditorLock, access, draft, busy]);

  useEffect(() => {
    const suspendPrivilegedDisplay = () => {
      recoveryRequiredRef.current = isDirtyRef.current;
      setRecoveryPending(false); setSuspended(true);
      suspendedRef.current = true;
      revisionControllerRef.current?.abort(); setRevision(null);
      if (!recoveryRequiredRef.current) { setDraft(null); setSnapshotRequest(null); setNewXmlPath(''); }
      setAccess('loading'); setStatus('Testcase workspace is hidden while the page is in the background. Reauthorize before resuming.');
    };
    const onVisible = () => {
      if (document.visibilityState === 'hidden') suspendPrivilegedDisplay();
      else setRefreshKey(value => value + 1);
    };
    window.addEventListener('pagehide', suspendPrivilegedDisplay);
    window.addEventListener('pageshow', onVisible);
    document.addEventListener('visibilitychange', onVisible);
    return () => { window.removeEventListener('pagehide', suspendPrivilegedDisplay); window.removeEventListener('pageshow', onVisible); document.removeEventListener('visibilitychange', onVisible); };
  }, []);

  useEffect(() => () => { revisionControllerRef.current?.abort(); actionControllerRef.current?.abort(); }, []);

  async function withBusy(action: string, run: (signal: AbortSignal) => Promise<void>) {
    const controller = new AbortController(); actionControllerRef.current?.abort(); actionControllerRef.current = controller;
    setBusy(true); setError(''); setStatus('');
    try { await run(controller.signal); }
    catch (reason) {
      if (controller.signal.aborted) return;
      setError(failureText(reason, action));
      if (reason instanceof ApiError && reason.status === 403) {
        setRevision(null); setDraft(null); setSnapshotRequest(null); setNewXmlPath(''); setAccess('denied');
        setRecoveryPending(false); setSuspended(false); recoveryRequiredRef.current = false; suspendedRef.current = false;
      }
      if (reason instanceof ApiError && reason.status === 422) setDiagnostics(errorDiagnostics(reason));
    } finally { if (actionControllerRef.current === controller) { actionControllerRef.current = null; setBusy(false); } }
  }

  const [diagnostics, setDiagnostics] = useState<ReturnType<typeof errorDiagnostics>>([]);
  async function stage(operations: SourceOperation[], snapshot: SourceSnapshotRequest) {
    if (!revision || !api.stageSourceChangeSet) { setError('The Package source revision or Change Set API is unavailable. Reload before uploading.'); return; }
    await withBusy('save the Testcase upload draft', async signal => {
      const result = await api.stageSourceChangeSet!('PACKAGE_SOURCE', packageId, revision.baseSourceRevision, revision.registryRevision, operations, signal);
      if (signal.aborted) return;
      setDraft(result); setSnapshotRequest(snapshot); setPublished(null); setDiagnostics([]);
      setStatus(`Draft ${result.changeSetId} saved. The Testcase snapshot is not generated and published source is unchanged.`);
    });
  }

  async function uploadPair(event: React.ChangeEvent<HTMLInputElement>) {
    const files = [...(event.currentTarget.files || [])]; event.currentTarget.value = '';
    if (files.length !== 2) { setError('Select exactly one .xlsx workbook and its matching .yaml or .yml sidecar.'); return; }
    if (!targetXmlPath) { setError('A verified Testcase XML source path is required before uploading its workbook.'); return; }
    const workbook = files.find(file => /\.xlsx$/i.test(file.name));
    const sidecar = files.find(file => /\.(yaml|yml)$/i.test(file.name));
    if (!workbook || !sidecar || files.some(file => file !== workbook && file !== sidecar)) { setError('Choose one .xlsx workbook and one .yaml or .yml sidecar.'); return; }
    const stem = workbook.name.replace(/\.xlsx$/i, '');
    const sidecarStem = sidecar.name.replace(/\.(yaml|yml)$/i, '');
    const testcaseStem = targetXmlPath.replace(/\.xml$/i, '').split('/').pop();
    if (!stem || stem !== sidecarStem || stem !== testcaseStem) { setError(`Workbook and sidecar names must match the Testcase source (${testcaseStem || 'unknown'}).`); return; }
    if (files.some(file => file.size > 4 * 1024 * 1024) || files.reduce((sum, file) => sum + file.size, 0) > 8 * 1024 * 1024) {
      setError('Each file is limited to 4 MiB and the paired upload is limited to 8 MiB.'); return;
    }
    const directory = targetXmlPath.includes('/') ? targetXmlPath.slice(0, targetXmlPath.lastIndexOf('/') + 1) : '';
    const workbookPath = directory + workbook.name;
    const sidecarPath = directory + sidecar.name;
    if (!safePath(workbookPath) || !safePath(sidecarPath)) { setError('The uploaded filenames do not form valid Package-relative paths.'); return; }
    const uploadController = new AbortController();
    actionControllerRef.current?.abort(); actionControllerRef.current = uploadController;
    setBusy(true); setError(''); setStatus('Reading and hashing the paired Testcase upload…');
    try {
      const workbookBytes = new Uint8Array(await workbook.arrayBuffer());
      if (uploadController.signal.aborted) return;
      const sidecarBytes = new Uint8Array(await sidecar.arrayBuffer());
      if (uploadController.signal.aborted) return;
      const sidecarText = new TextDecoder('utf-8', { fatal: true }).decode(sidecarBytes);
      const stagedSidecarBytes = new TextEncoder().encode(sidecarText);
      const [expectedWorkbookHash, expectedSidecarHash] = await Promise.all([sha256Hex(workbookBytes), sha256Hex(stagedSidecarBytes)]);
      if (uploadController.signal.aborted) return;
      const snapshot: SourceSnapshotRequest = { expectedSourceRevision: revision?.baseSourceRevision || '', workbookPath, expectedWorkbookHash, expectedSidecarHash };
      if (!snapshot.expectedSourceRevision) { setError('The Package source revision is unavailable. Reload before uploading.'); return; }
      if (uploadController.signal.aborted) return;
      if (!window.confirm(`Stage ${workbookPath} and ${sidecarPath} together? This does not generate or publish the Testcase snapshot.`)) return;
      if (uploadController.signal.aborted) return;
      await stage([
        { operation: workbookOperation, path: workbookPath, contentBase64: base64(workbookBytes) },
        { operation: sidecarOperation, path: sidecarPath, content: sidecarText },
      ], snapshot);
    } catch (reason) {
      if (!uploadController.signal.aborted) setError(reason instanceof Error ? reason.message : 'The Testcase upload could not be read as a workbook and UTF-8 sidecar.');
    } finally {
      if (actionControllerRef.current === uploadController) { actionControllerRef.current = null; setBusy(false); }
    }
  }

  async function generateSnapshot() {
    if (!draft || !revision || !snapshotRequest || !api.generateTestcaseSnapshot) { setError('Upload the workbook and matching sidecar together before generating the snapshot.'); return; }
    if (draftStale) { setError('The Testcase draft uses an older Package or registration revision. Discard it and upload against the current revision.'); return; }
    if (!window.confirm(`Generate the Testcase snapshot from ${snapshotRequest.workbookPath}? It remains a draft until validated, reviewed, and published.`)) return;
    await withBusy('generate the Testcase snapshot', async signal => {
      const result = await api.generateTestcaseSnapshot!(packageId, draft.changeSetId, snapshotRequest, signal);
      if (signal.aborted) return;
      setDraft(result); setSnapshotRequest(null); setDiagnostics([]);
      setStatus(`Testcase snapshot ${result.snapshotPath || targetXmlPath} generated. Validate and review before publication.`);
    });
  }

  async function act(action: 'validate' | 'review' | 'publish' | 'discard') {
    if (!draft || !api.sourceChangeSetAction) { setError('The Change Set action API is unavailable.'); return; }
    if (draftStale && action !== 'discard') { setError('The Testcase draft uses an older Package or registration revision. Discard it and upload against the current revision.'); return; }
    if (action === 'validate' && snapshotRequest) { setError('Generate the Testcase snapshot before validating this workbook change.'); return; }
    if (action === 'publish' && !window.confirm(`Publish the reviewed Testcase source draft for ${targetXmlPath}?`)) return;
    if (action === 'discard' && !window.confirm(`Discard draft ${draft.changeSetId}? Its staged files and snapshot will be removed.`)) return;
    await withBusy(`${action} the Testcase source draft`, async signal => {
      const result = await api.sourceChangeSetAction!('PACKAGE_SOURCE', packageId, draft.changeSetId, action, signal);
      if (signal.aborted) return;
      if (action === 'publish' && 'published' in result) {
        const publication = result as SourcePublishResult;
        setPublished(publication); setDraft(null); setSnapshotRequest(null);
        recoveryRequiredRef.current = false; setRecoveryPending(false);
        setStatus(`Published Testcase source revision ${publication.revision}.`);
        setRefreshKey(value => value + 1);
      } else if (action === 'discard') {
        setDraft(null); setSnapshotRequest(null); recoveryRequiredRef.current = false; setRecoveryPending(false); setSuspended(false); suspendedRef.current = false;
        setStatus('Draft discarded. Published Testcase source is unchanged.');
      } else setDraft(result as SourceChangeSet);
    });
  }

  const locked = access !== 'ready' || !targetXmlPath;
  if (suspended) return <section className="att-source-authoring att-testcase-authoring" aria-label="Testcase reauthorization">
    <h2>Testcase workspace hidden while away</h2>
    {access === 'loading' && <p role="status" aria-busy="true">Rechecking Package source permission before showing draft details.</p>}
    {access === 'unavailable' && <><p role="alert">{error || 'Testcase source reauthorization failed. Retry before resuming.'}</p>
      <button type="button" onClick={() => setRefreshKey(value => value + 1)}>Retry permission check</button></>}
    {access === 'ready' && recoveryPending && revision && <>
      <p role="status">Permission was rechecked. Resume the in-memory upload and draft, or discard recovered work.</p>
      <button type="button" onClick={() => { recoveryRequiredRef.current = false; suspendedRef.current = false; setRecoveryPending(false); setSuspended(false); }} disabled={busy}>Resume work</button>
      <button type="button" onClick={() => {
        if (draft) { void act('discard'); return; }
        if (!window.confirm('Discard the recovered Testcase upload path?')) return;
        setNewXmlPath(''); recoveryRequiredRef.current = false; suspendedRef.current = false; setRecoveryPending(false); setSuspended(false);
      }} disabled={busy}>Discard recovered work</button>
    </>}
  </section>;
  return <section className="att-source-authoring att-testcase-authoring" aria-label="Testcase workbook authoring">
    <header className="att-source-authoring-heading"><div><p className="eyebrow">PACKAGE_SOURCE · {packageId} · TESTCASE</p>
      <h2>Testcase workbook and snapshot</h2>
      <p>Upload the workbook and sidecar as one draft, then explicitly generate the XML snapshot before validation.</p></div>
      {onBack && <button type="button" className="button button-quiet" onClick={() => {
        if (!sourcePath && newXmlPath && !window.confirm('Discard this new Testcase path and return to resources?')) return;
        onBack();
      }} disabled={busy || Boolean(draft)}>Back to resources</button>}
      <button type="button" className="button button-quiet" onClick={() => {
        if (isDirty && !window.confirm('Reloading discards this local Testcase draft context. Continue?')) return;
        setDraft(null); setSnapshotRequest(null); setNewXmlPath(''); setRefreshKey(value => value + 1);
      }} disabled={busy || access === 'loading' || Boolean(draft)}>Reload revision</button>
    </header>
    {access === 'loading' && <p role="status" aria-busy="true">Loading owner-only Package source revisions…</p>}
    {access === 'denied' && <p className="att-source-authoring-note" role="status">Testcase editing requires Package owner permission. Safe Testcase inspection remains available above.</p>}
    {access === 'unavailable' && <p className="att-error" role="alert">{error || 'Testcase authoring is unavailable.'}</p>}
    {access === 'ready' && revision && <>
      <div className="att-source-revision-strip" aria-label="Testcase revision details">
        <span>Package source ETag <code>{revision.sourceEtag}</code></span>
        <span>Base source revision <code>{revision.baseSourceRevision}</code></span>
        <span>Package registration revision <code>{revision.registryRevision}</code></span>
        {targetXmlPath && <span>Testcase path <code>{targetXmlPath}</code></span>}
      </div>
      {draftStale && <p className="att-error" role="alert">This draft uses an older Package source or registration revision. It can be discarded, but cannot be generated, validated, reviewed or published.</p>}
      {!sourcePath && <label>New Testcase XML path<input aria-label="New Testcase XML path" value={newXmlPath}
        onChange={event => setNewXmlPath(event.currentTarget.value)} placeholder="cases/new-case.xml" disabled={busy || Boolean(draft)} /></label>}
      {!targetXmlPath && <p className="att-source-authoring-note">{sourcePath ? 'The Server did not return a safe logical .xml Testcase path.' : 'Enter a Package-relative .xml path to create a new Testcase.'} Upload is disabled until the path is valid.</p>}
      {!draft && <div className="att-source-authoring-toolbar" role="group" aria-label="Testcase upload actions">
        <label>Workbook action<select aria-label="Testcase workbook action" value={workbookOperation} onChange={event => setWorkbookOperation(event.currentTarget.value as 'create' | 'replace')} disabled={locked || busy}>
          <option value="replace">Replace existing workbook</option><option value="create">Create new workbook</option></select></label>
        <label>Sidecar action<select aria-label="Testcase sidecar action" value={sidecarOperation} onChange={event => setSidecarOperation(event.currentTarget.value as 'create' | 'replace')} disabled={locked || busy}>
          <option value="replace">Replace existing sidecar</option><option value="create">Create new sidecar</option></select></label>
        <button type="button" onClick={() => fileInputRef.current?.click()} disabled={locked || busy}>Upload workbook + sidecar</button>
        <input ref={fileInputRef} type="file" className="att-source-file-input" aria-label="Upload Testcase workbook and sidecar" accept=".xlsx,.yaml,.yml" multiple onChange={event => void uploadPair(event)} />
      </div>}
      {error && <p className="att-error" role="alert">{error}</p>}
      {diagnostics.length > 0 && <ul className="att-source-diagnostics" aria-label="Validation diagnostics">{diagnostics.map((item, index) => <li key={`${item.code || 'diagnostic'}:${index}`}>
        <strong>{item.severity || 'ERROR'}{item.code ? ` · ${item.code}` : ''}</strong>
        {item.logicalPath && <code>{item.logicalPath}</code>}{item.field && <span>Field: {item.field}</span>}{item.line && <span>Line {item.line}</span>}
        {item.message && <span>{item.message}</span>}{item.remedy && <span>Remedy: {item.remedy}</span>}
      </li>)}</ul>}
      {status && <p role="status" aria-live="polite">{status}</p>}
      {draft && <section className="att-source-review" aria-label="Testcase changed source review">
        <h3>Draft {draft.changeSetId}</h3><p>{draft.state} · expires {draft.expiresAt || 'per Server policy'} · published source is unchanged</p>
        <ChangedFiles files={draft.changedFiles || []} />
        {draft.snapshotGenerated && <p role="status">Snapshot generated: {draft.snapshotPath || targetXmlPath}</p>}
        {snapshotRequest && <button type="button" onClick={() => void generateSnapshot()} disabled={busy || draftStale || !api.generateTestcaseSnapshot}>Generate Testcase snapshot</button>}
        <div className="att-source-authoring-toolbar">
          <button type="button" onClick={() => void act('validate')} disabled={busy || draftStale || Boolean(snapshotRequest) || draft.state === 'reviewed'}>Validate</button>
          <button type="button" onClick={() => void act('review')} disabled={busy || draftStale || draft.state !== 'validated'}>Review changed files</button>
          <button type="button" className="button button-primary" onClick={() => void act('publish')} disabled={busy || draftStale || draft.state !== 'reviewed'}>Publish</button>
          <button type="button" onClick={() => void act('discard')} disabled={busy}>Discard Draft</button>
        </div>
      </section>}
      {published && <p role="status">Last published Testcase revision: <code>{published.revision}</code></p>}
    </>}
  </section>;
}
