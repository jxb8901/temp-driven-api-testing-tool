import { useEffect, useRef, useState } from 'react';
import { CodeEditor } from '../code/CodeEditor';
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
  const [view, setView] = useState<ManifestView>('effective');
  const [environment, setEnvironment] = useState('');
  const [selection, setSelection] = useState('');
  const [projection, setProjection] = useState<ManifestProjection | null>(null);
  const [source, setSource] = useState('');
  const [baseline, setBaseline] = useState('');
  const [draft, setDraft] = useState<ManifestDraft | null>(null);
  const [history, setHistory] = useState<ManifestHistory[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [revision, refresh] = useState(0);
  const dirty = source !== baseline || !!draft;
  useEffect(() => registerUnsavedChanges?.(dirty, 'unpublished Package Manifest changes'), [dirty, registerUnsavedChanges]);
  useEffect(() => { onDirtyChange?.(dirty);return () => onDirtyChange?.(false); }, [dirty, onDirtyChange]);
  const request = useRef<AbortController | null>(null);
  const epoch = useRef(0);
  useEffect(() => {
    epoch.current += 1;
    setDraft(null);setHistory([]);setMessage('');setSource('');setBaseline('');setEnvironment('');setSelection('');
    return () => { epoch.current += 1; request.current?.abort(); };
  }, [packageId]);
  useEffect(() => {
    const controller = new AbortController();request.current?.abort();request.current = controller;
    setBusy(true);setError('');setProjection(null);
    const load = async () => {
      const declared = await api.getPackageManifest(packageId, 'declared', undefined, controller.signal);
      const result = view === 'source' && !declared.present ? { ...declared, available: true, text: 'schemaVersion: att-package/v1\n', redacted: false }
        : view === 'declared' ? declared : await api.getPackageManifest(packageId, view, environment || undefined, controller.signal);
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
    catch (failure) { if (!controller.signal.aborted && current === epoch.current) setError(readable(failure)); }
    finally { if (!controller.signal.aborted && current === epoch.current) setBusy(false); }
  };
  const canWrite = !!projection?.canEdit;
  const editable = canWrite && view === 'source' && !!projection?.available && !projection?.redacted && !draft && !busy;
  const fields = draft?.validation?.fields || projection?.fields;
  return <section className="att-manifest-workspace" aria-label="Package Configuration">
    <header><h2>Package Configuration · {packageId}</h2>
      <p>Package defaults and Testdata belong to <code>package.yaml</code>. Shared profiles, Helpers and Tools remain in Global Configuration.</p>
      <button type="button" disabled={busy || dirty} onClick={() => refresh(value => value + 1)}>Refresh Package Configuration</button>
    </header>
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
        {projection.redacted && <p>Some source values are masked. This projection is read only to preserve protected values.</p>}
        <CodeEditor value={source} language="yaml" label="Package Manifest source" readOnly={!editable} maxCharacters={262144} onChange={setSource} />
        {source !== baseline && !draft && <p>Unpublished Manifest changes. Create a draft or <button type="button" disabled={busy} onClick={() => {setSource(baseline);setError('');}}>Discard source changes</button> before refreshing or switching views.</p>}
        {!canWrite && <p>Source editing requires the active USER whose login matches this registered Package ID and enabled Server source policy.</p>}
        <button type="button" disabled={!editable} onClick={() => void act(async signal => {
          const staged = await api.stagePackageManifest(packageId, source, projection.manifestDigest, signal);
          if (!signal.aborted) setDraft(staged);
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
        {draft.validation?.declared && <CodeEditor value={shown(draft.validation.declared) || '{}'} language="json" label="Validated Manifest declaration" />}
        <button type="button" disabled={busy || draft.state !== 'draft'} onClick={() => void act(async signal => {
          const next = await api.manifestDraftAction(packageId, draft.draftId, 'validate', signal);if (!signal.aborted) setDraft({ ...draft, ...next });
        })}>Validate Manifest draft</button>
        <button type="button" disabled={busy || draft.state !== 'validated'} onClick={() => void act(async signal => {
          const next = await api.manifestDraftAction(packageId, draft.draftId, 'review', signal);if (!signal.aborted) setDraft({ ...draft, ...next });
        })}>Review validated Manifest</button>
        <button type="button" disabled={busy || draft.state !== 'reviewed'} onClick={() => void act(async signal => {
          const result = await api.publishPackageManifest(packageId, draft.draftId, signal);
          if (!signal.aborted && result.published) {setDraft(null);refresh(value => value + 1);setMessage(result.auditConfirmed === false ? 'Manifest published; the durable audit intent records the revision.' : 'Manifest published. Package views will refresh.');}
        })}>Publish reviewed Manifest</button>
        <button type="button" disabled={busy} onClick={() => void act(async signal => {
          await api.manifestDraftAction(packageId, draft.draftId, 'discard', signal);if (!signal.aborted) {setDraft(null);refresh(value => value + 1);}
        })}>Discard Manifest draft</button>
      </section>}
      {canWrite && <section aria-label="Manifest revision history">
        <button type="button" disabled={busy || dirty} onClick={() => void act(async signal => {
          const result = await api.getPackageManifestHistory(packageId, signal);if (!signal.aborted) setHistory(result.items);
        })}>Load Manifest history</button>
        {history.map(item => <p key={item.manifestDigest}><code>{item.manifestDigest.slice(0, 12)}</code> · {item.modifiedAt} <button type="button" disabled={busy || dirty || item.manifestDigest === projection.manifestDigest} onClick={() => void act(async signal => {
          const staged = await api.rollbackPackageManifest(packageId, item.manifestDigest, projection.manifestDigest, signal);
          if (!signal.aborted) setDraft(staged);
        })}>Stage rollback {item.manifestDigest.slice(0, 12)}</button></p>)}
      </section>}
    </>}
  </section>;
}
