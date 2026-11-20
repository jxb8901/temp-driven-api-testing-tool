import { useContext, useEffect, useRef, useState } from 'react';
import { AppShellContext } from '../../AppShell';
import { CodeEditor } from '../code/CodeEditor';
import { AuthoringWorkflow, type AuthoringStepState } from './AuthoringWorkflow';
import { useAccessibleConfirmation } from './useAccessibleConfirmation';
import type { ManifestDraft, ManifestHistory, ManifestProjection, ManifestView, PackageManifestApi } from './packageManifest';
import './configuration-workspace.css';

function readable(error: unknown): string {
  const status = error && typeof error === 'object' && 'status' in error ? Number(error.status) : 0;
  if (status === 409) return 'Sources changed after validation. Discard this draft and refresh before editing again.';
  if (status === 403) return 'Your current account cannot edit this registered Package, or source editing is disabled.';
  if (status === 401) return 'Sign in again before continuing.';
  if (status === 404) return 'The draft or source is unavailable. Refresh and create a new draft.';
  if (error instanceof Error) return error.message;
  return 'The Manifest request could not be completed. Retry shortly.';
}
const shown = (value: unknown) => typeof value === 'string' ? value : JSON.stringify(value, null, 2);

export function PackageManifestWorkspace({ api, packageId, registerUnsavedChanges, onDirtyChange }: {
  api: PackageManifestApi; packageId: string;
  registerUnsavedChanges?: (dirty: boolean, description?: string) => () => void;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const workspace = useContext(AppShellContext);
  const [view, setView] = useState<ManifestView>('effective');
  const [environment, setEnvironment] = useState('');
  const [selection, setSelection] = useState('');
  const [projection, setProjection] = useState<ManifestProjection | null>(null);
  const [source, setSource] = useState('');
  const [baseline, setBaseline] = useState('');
  const [draft, setDraft] = useState<ManifestDraft | null>(null);
  const [candidateSource, setCandidateSource] = useState<string | null>(null);
  const [draftOutdated, setDraftOutdated] = useState(false);
  const [history, setHistory] = useState<ManifestHistory[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [revision, refresh] = useState(0);
  const [publishedDigest, setPublishedDigest] = useState('');
  const { confirm: confirmAction, dialog: confirmationDialog } = useAccessibleConfirmation();
  const dirty = source !== baseline || !!draft;
  useEffect(() => {
    workspace?.setWorkspaceEditorLock?.(Boolean(projection?.canEdit && dirty));
    return () => workspace?.setWorkspaceEditorLock?.(false);
  }, [workspace?.setWorkspaceEditorLock, projection?.canEdit, dirty]);
  useEffect(() => registerUnsavedChanges?.(dirty, 'unpublished Package Manifest changes'), [dirty, registerUnsavedChanges]);
  useEffect(() => { onDirtyChange?.(dirty);return () => onDirtyChange?.(false); }, [dirty, onDirtyChange]);
  const request = useRef<AbortController | null>(null);
  const epoch = useRef(0);
  useEffect(() => {
    epoch.current += 1;
    setDraft(null);setCandidateSource(null);setDraftOutdated(false);setHistory([]);setMessage('');setSource('');setBaseline('');setEnvironment('');setSelection('');
    return () => { epoch.current += 1; request.current?.abort(); };
  }, [packageId]);
  useEffect(() => {
    const controller = new AbortController();request.current?.abort();request.current = controller;
    setBusy(true);setError('');setProjection(null);
    const load = async () => {
      const declared = await api.getPackageManifest(packageId, 'declared', undefined, controller.signal);
      let result: ManifestProjection = view === 'source' && !declared.present ? { ...declared, available: true, text: 'schemaVersion: att-package/v1\n', redacted: false }
        : view === 'declared' ? declared : await api.getPackageManifest(packageId, view, environment || undefined, controller.signal);
      if (view === 'source' && result.canEdit) {
        try {
          const authoring = await api.getPackageAuthoringSource(packageId, controller.signal);
          if (!authoring.available || !authoring.sourceEtag || !/^[a-f0-9]{64}$/.test(authoring.revision)) throw new Error('Authoring source metadata is unavailable');
          result = { ...result, available: true, present: authoring.present, text: authoring.text,
            manifestDigest: authoring.revision, sourceEtag: authoring.sourceEtag, redacted: false, authoringSourceUnavailable: false };
        } catch (failure) {
          if (controller.signal.aborted) return;
          result = { ...result, canEdit: false, authoringSourceUnavailable: true };
        }
      }
      if (controller.signal.aborted) return;
      setProjection(result);if (view === 'source') {setSource(result.text || '');setBaseline(result.text || '');}
    };
    void load().catch(failure => { if (!controller.signal.aborted) setError(readable(failure)); })
      .finally(() => { if (!controller.signal.aborted) setBusy(false); });
    return () => controller.abort();
  }, [api, packageId, view, environment, revision]);

  const act = async (operation: (signal: AbortSignal) => Promise<void>) => {
    const controller = new AbortController();request.current?.abort();request.current = controller;
    const current = epoch.current;setBusy(true);setError('');setMessage('');
    try { await operation(controller.signal); }
    catch (failure) {
      if (!controller.signal.aborted && current === epoch.current) {
        setError(readable(failure));
        if (draft && failure && typeof failure === 'object' && 'status' in failure && Number(failure.status) === 409) setDraftOutdated(true);
      }
    }
    finally { if (!controller.signal.aborted && current === epoch.current) setBusy(false); }
  };
  const canWrite = !!projection?.canEdit;
  const permissionBlocked = projection !== null && !canWrite;
  const editable = canWrite && view === 'source' && !!projection?.available && !!projection?.sourceEtag && !projection?.redacted && !draft && !busy;
  const fields = draft?.validation?.fields || projection?.fields;
  const workflowState = (done: boolean, current: boolean, blocked = false): AuthoringStepState =>
    done ? 'complete' : blocked ? 'blocked' : current ? 'current' : 'upcoming';
  const permissionReason = workspace?.uiLocale === 'zh-Hant'
    ? projection?.authoringSourceUnavailable
      ? '無法取得完整且已授權的 Manifest source；編輯功能維持唯讀。'
      : '目前帳戶沒有此 Package source 的編輯權限。'
    : projection?.authoringSourceUnavailable
      ? 'The complete authorized Manifest source is unavailable; editing remains read only.'
      : 'This account cannot edit the registered Package source.';
  const validationReason = workspace?.uiLocale === 'zh-Hant'
    ? '先驗證草稿，才能檢閱候選版本。'
    : 'Validate the draft before reviewing the candidate.';
  const publishReason = workspace?.uiLocale === 'zh-Hant'
    ? '檢閱候選版本後才能發布。'
    : 'Review the candidate before publishing.';
  const staleReason = workspace?.uiLocale === 'zh-Hant'
    ? '來源在候選版本建立後已變更；只能捨棄草稿、重新載入並建立新候選版本。'
    : 'The source changed after this candidate was staged. Discard it, refresh, and stage a new candidate.';
  const workflowSteps = [
    { id: 'view', label: 'View published Package configuration', state: 'complete' as AuthoringStepState },
    { id: 'edit', label: 'Edit package.yaml', state: permissionBlocked ? 'blocked' : workflowState(view === 'source' || !!draft, false), reason: permissionBlocked ? permissionReason : undefined },
    { id: 'stage', label: 'Stage draft', state: permissionBlocked ? 'blocked' : workflowState(!!draft, view === 'source' && !draft), reason: permissionBlocked ? permissionReason : undefined },
    { id: 'validate', label: 'Validate draft', state: workflowState(!draftOutdated && (draft?.state === 'validated' || draft?.state === 'reviewed'), !draftOutdated && draft?.state === 'draft', draftOutdated), reason: draftOutdated ? staleReason : undefined },
    { id: 'review', label: 'Review candidate', state: workflowState(!draftOutdated && draft?.state === 'reviewed', !draftOutdated && draft?.state === 'validated', draftOutdated || draft?.state === 'draft'), reason: draftOutdated ? staleReason : draft?.state === 'draft' ? validationReason : undefined },
    { id: 'publish', label: 'Publish', state: workflowState(!!publishedDigest, !draftOutdated && draft?.state === 'reviewed', draftOutdated || (!!draft && draft.state !== 'reviewed')), reason: draftOutdated ? staleReason : draft && draft.state !== 'reviewed' ? publishReason : undefined },
    { id: 'history', label: 'Status and history', state: workflowState(history.length > 0, !!publishedDigest && history.length === 0) },
  ];
  return <section className="att-manifest-workspace" aria-label="Package Configuration">
    <header><h2>Package Configuration · {packageId}</h2>
      <p>Package defaults and Testdata belong to <code>package.yaml</code>. Shared profiles, Helpers and Tools remain in Global Configuration.</p>
      <button type="button" disabled={busy || dirty} onClick={() => refresh(value => value + 1)}>Refresh Package Configuration</button>
    </header>
    <AuthoringWorkflow label="Package Manifest publication workflow" steps={workflowSteps} />
    <p role="status">Package owner: <strong>{packageId}</strong> · {projection === null ? 'Checking Package source permissions…' : canWrite ? 'Package source editing is available for this account.' : 'Package source is read only for this account.'}</p>
    {publishedDigest && <details><summary>Published Manifest revision (advanced)</summary><code>{publishedDigest}</code></details>}
    <nav aria-label="Package Configuration views">{(['source', 'declared', 'effective', 'compare'] as const).map(item =>
      <button key={item} type="button" aria-pressed={view === item} disabled={busy || dirty} onClick={() => setView(item)}>
        {item === 'compare' ? 'Compare with Global' : item[0].toUpperCase() + item.slice(1)}
      </button>)}</nav>
    {(view === 'effective' || view === 'compare') && <form onSubmit={event => { event.preventDefault();setEnvironment(selection.trim()); }}>
      <label>Environment override <input aria-label="Environment override" value={selection} onChange={event => setSelection(event.target.value)} placeholder="Use Package or Global default" maxLength={128} /></label>
      <button type="submit" disabled={busy}>Apply Environment</button>
      {projection?.environment && <p>Resolved Environment: <strong>{projection.environment}</strong> · Origin: {projection.environmentOrigin}</p>}
    </form>}
    {error && <p role="alert">{error}</p>}{message && <p role="status">{message}</p>}
    {busy && <p role="status">Working…</p>}
    {projection && <>
      <p>{projection.present ? 'Manifest present' : 'No Manifest: conventional roots and empty Package Testdata apply.'}</p>
      {projection.diagnostics?.map((text, index) => <p key={index}>{text}</p>)}
      {view === 'source' && <>
        {!projection.available && <p>Source unavailable under the current source browsing policy.</p>}
        {projection.authoringSourceUnavailable && <p>Complete authoring source is unavailable. The safe inspection source remains read only.</p>}
        {projection.redacted && <p>Some source values are masked. This projection is read only to preserve protected values.</p>}
        <details className="att-manifest-raw-editor" open>
          <summary>Advanced raw YAML source</summary>
          <CodeEditor value={source} language="yaml" label="Package Manifest source" readOnly={!editable} maxCharacters={262144} onChange={setSource} />
        </details>
        {source !== baseline && !draft && <p>Unpublished Manifest changes. Create a draft or <button type="button" disabled={busy} onClick={() => void (async () => {
          if (!await confirmAction({ title: 'Discard local Manifest edits?', message: `Remove local edits to package.yaml for Package ${packageId}. Published Package source stays unchanged.`, confirmLabel: 'Discard edits', destructive: true })) return;
          setSource(baseline);setError('');
        })()}>Discard source changes</button> before refreshing or switching views.</p>}
        {!canWrite && <p>Source editing requires the active USER whose login matches this registered Package ID and enabled Server source policy.</p>}
        <button type="button" disabled={!editable} onClick={() => void act(async signal => {
          const staged = await api.stagePackageManifest(packageId, source, projection.manifestDigest, signal);
          if (!signal.aborted) {setDraft(staged);setCandidateSource(source);setDraftOutdated(false);}
        })}>Create Manifest draft</button>
      </>}
      {view === 'declared' && <CodeEditor value={shown(projection.declared) || '{}'} language="json" label="Declared Package Configuration" />}
      {fields && <table><caption>{draft ? 'Candidate effective configuration' : 'Effective values and origins'}</caption>
        <thead><tr><th>Field</th><th>Value</th><th>Origin</th>{view === 'compare' && <th>Global value</th>}</tr></thead>
        <tbody>{Object.entries(fields).map(([name, field]) => <tr key={name}><th scope="row">{name}</th><td><pre>{field.masked ? '[REDACTED]' : shown(field.value)}</pre></td><td>{field.origin} · {field.authoredPath}</td>
          {view === 'compare' && <td><pre>{shown(projection.globalFields?.[name]?.value)}</pre></td>}</tr>)}</tbody>
      </table>}
      {draft && <section aria-label="Manifest publication review">
        <h3>Manifest draft: {draft.state}</h3><p>Expires: {draft.expiresAt}</p>
        {draft.resetToEmptyManifest && <p>This rollback restores pre-Manifest defaults using a canonical empty Manifest.</p>}
        {draft.validation && <p>Candidate Environment: {draft.validation.environment} · Origin: {draft.validation.environmentOrigin}</p>}
        {candidateSource === null ? <div role="status"><p>The staged candidate source is not loaded. Load and review it before validation or publication.</p>
          <button type="button" disabled={busy} onClick={() => void act(async signal => {
            const preview = await api.getPackageManifestDraftSource(packageId, draft.draftId, signal);
            if (preview.draftId !== draft.draftId || preview.expectedManifestDigest !== draft.expectedManifestDigest || !/^[a-f0-9]{64}$/.test(preview.sourceDigest))
              throw new Error('The Server returned a candidate source that does not match this draft.');
            if (!signal.aborted) {setSource(preview.source);setCandidateSource(preview.source);}
          })}>Load candidate source</button>
        </div> : <details open className="att-manifest-source-diff" aria-label="Package Manifest candidate diff">
          <summary>Review published source and candidate</summary>
          <p>Changed files: <code>package.yaml</code> · Base Manifest revision: <code>{draft.expectedManifestDigest.slice(0, 12)}</code></p>
          <div className="att-source-history-content-diff">
            <section><h4>Published source</h4><CodeEditor value={baseline} language="yaml" label="Published Package Manifest source" readOnly maxCharacters={262144} /></section>
            <section><h4>Candidate draft</h4><CodeEditor value={candidateSource} language="yaml" label="Candidate Package Manifest source" readOnly maxCharacters={262144} /></section>
          </div>
        </details>}
        {draft.validation?.declared && <CodeEditor value={shown(draft.validation.declared) || '{}'} language="json" label="Validated Manifest declaration" />}
        <button type="button" disabled={busy || candidateSource === null || draftOutdated || draft.state !== 'draft'} onClick={() => void act(async signal => {
          const next = await api.manifestDraftAction(packageId, draft.draftId, 'validate', signal);if (!signal.aborted) setDraft({ ...draft, ...next });
        })}>Validate Manifest draft</button>
        <button type="button" disabled={busy || candidateSource === null || draftOutdated || draft.state !== 'validated'} onClick={() => void act(async signal => {
          const next = await api.manifestDraftAction(packageId, draft.draftId, 'review', signal);if (!signal.aborted) setDraft({ ...draft, ...next });
        })}>Review validated Manifest</button>
        <button type="button" disabled={busy || candidateSource === null || draftOutdated || draft.state !== 'reviewed'} onClick={() => void act(async signal => {
          if (!await confirmAction({ title: 'Publish reviewed Package Manifest?',
            message: `Publish package.yaml for Package ${packageId}. This changes the registered Package configuration source for future configuration resolution.`,
            confirmLabel: 'Publish Manifest' })) return;
          const result = await api.publishPackageManifest(packageId, draft.draftId, signal);
          if (!signal.aborted && result.published) {setDraft(null);setCandidateSource(null);setDraftOutdated(false);setPublishedDigest(result.manifestDigest);refresh(value => value + 1);setMessage(result.auditConfirmed === false ? 'Manifest published; the durable audit intent records the revision.' : 'Manifest published. Package views will refresh.');}
        })}>Publish reviewed Manifest</button>
        <button type="button" disabled={busy} onClick={() => void act(async signal => {
          if (!await confirmAction({ title: 'Discard Manifest draft?',
            message: `Discard draft ${draft.draftId} and remove its staged Manifest source. Published Package source stays unchanged.`,
            confirmLabel: 'Discard draft', destructive: true })) return;
          await api.manifestDraftAction(packageId, draft.draftId, 'discard', signal);if (!signal.aborted) {setDraft(null);setCandidateSource(null);setDraftOutdated(false);refresh(value => value + 1);}
        })}>Discard Manifest draft</button>
      </section>}
      {canWrite && <section aria-label="Manifest revision history">
        <button type="button" disabled={busy || dirty} onClick={() => void act(async signal => {
          const result = await api.getPackageManifestHistory(packageId, signal);if (!signal.aborted) setHistory(result.items);
        })}>Load Manifest history</button>
        {history.map(item => <p key={item.manifestDigest}><code>{item.manifestDigest.slice(0, 12)}</code> · {item.modifiedAt} <button type="button" disabled={busy || dirty || item.manifestDigest === projection.manifestDigest} onClick={() => void act(async signal => {
          if (!await confirmAction({ title: 'Stage historical Manifest as a new draft?',
            message: `Create a new Package Manifest draft from revision ${item.manifestDigest.slice(0, 12)}. It must be validated, reviewed, and published before becoming current.`,
            confirmLabel: 'Stage restore' })) return;
          const staged = await api.rollbackPackageManifest(packageId, item.manifestDigest, projection.manifestDigest, signal);
          if (signal.aborted) return;
          setDraft(staged);setCandidateSource(null);setDraftOutdated(false);
          const preview = await api.getPackageManifestDraftSource(packageId, staged.draftId, signal);
          if (preview.draftId !== staged.draftId || preview.expectedManifestDigest !== staged.expectedManifestDigest || !/^[a-f0-9]{64}$/.test(preview.sourceDigest))
            throw new Error('The Server returned a candidate source that does not match this draft.');
          if (!signal.aborted) {setSource(preview.source);setCandidateSource(preview.source);}
        })}>Stage rollback {item.manifestDigest.slice(0, 12)}</button></p>)}
      </section>}
    </>}
    {confirmationDialog}
  </section>;
}
