import { useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { ApiError } from '../../types';
import { AppShellContext } from '../../AppShell';
import { CodeEditor, detectCodeLanguage } from '../code/CodeEditor';
import { SourceViewer } from './SourceViewer';
import type { ExplorerApi, ResourceIdentity, ResourceSourceReference } from './types';
import type {
  ChangedSourceFile, ManagedAuthoringSource, PackageAuthoringSourceQuery, SourceAffectedPackage,
  SourceAffectedSchedule, SourceChangeSet, SourceImpactReport, SourceOperation,
  SourcePublishResult, SourceValidationDiagnostic,
} from './sourceManagement';
import './source-authoring.css';

type PackageSourceTarget = {
  domain: 'PACKAGE_SOURCE';
  packageId: string;
  query: PackageAuthoringSourceQuery;
  label?: string;
  fallbackIdentity?: ResourceIdentity;
  fallbackAvailable?: boolean;
  allowCreate?: boolean;
  allowFileOperations?: boolean;
};
type CentralSourceTarget = {
  domain: 'CENTRAL_RUNTIME_CONFIG';
  path: string;
  label?: string;
};
export type SourceAuthoringTarget = PackageSourceTarget | CentralSourceTarget;

type EditMode = 'view' | 'edit' | 'create';
type AccessState = 'checking' | 'authorized' | 'denied' | 'unavailable';

interface SourceAuthoringWorkspaceProps {
  api: ExplorerApi;
  target: SourceAuthoringTarget;
  canManageCentral?: boolean;
  registerUnsavedChanges?: (isDirty: boolean, description?: string) => () => void;
  onOpenFileReference?: (path: string, origin?: string, reference?: ResourceSourceReference) => void;
  onPublished?: (result: SourcePublishResult) => void;
}

function statusOf(error: unknown): number {
  return error instanceof ApiError ? error.status
    : typeof error === 'object' && error !== null && 'status' in error ? Number((error as { status?: unknown }).status) : 0;
}
function codeOf(error: unknown): string {
  return error instanceof ApiError ? error.code || ''
    : typeof error === 'object' && error !== null && 'code' in error ? String((error as { code?: unknown }).code || '') : '';
}
function diagnosticsOf(error: unknown): SourceValidationDiagnostic[] {
  const raw = error instanceof ApiError ? error.diagnostics : undefined;
  if (!Array.isArray(raw)) return [];
  return raw.filter(item => item && typeof item === 'object').slice(0, 50).map(item => {
    const value = item as Record<string, unknown>;
    return {
      code: typeof value.code === 'string' ? value.code.slice(0, 128) : undefined,
      severity: typeof value.severity === 'string' ? value.severity : undefined,
      logicalPath: typeof value.logicalPath === 'string' && safeLogicalPath(value.logicalPath) ? value.logicalPath : undefined,
      field: typeof value.field === 'string' ? value.field.slice(0, 512) : undefined,
      line: typeof value.line === 'number' && Number.isInteger(value.line) && value.line > 0 ? value.line : undefined,
      message: typeof value.message === 'string' ? value.message.slice(0, 512) : undefined,
      remedy: typeof value.remedy === 'string' ? value.remedy.slice(0, 512) : undefined,
    };
  });
}
function failureText(error: unknown, action: string): string {
  const status = statusOf(error);
  const code = codeOf(error);
  if (status === 401) return 'Your session expired. Sign in again and reload this source.';
  if (status === 403) return `The Server denied ${action}. Source editing permission is unavailable or was revoked.`;
  if (status === 404) return 'The source is protected, unavailable, or no longer registered. Reload the package or choose an approved source path.';
  if (status === 409) return 'The source revision changed. Reload the current source, compare your draft, then stage it again.';
  if (status === 413) return `The Server rejected ${action} because the source or upload exceeds its configured size limit.`;
  if (status === 422) return `The Server rejected ${action} during validation${code ? ` (${code})` : ''}. Review the diagnostics and correct the source.`;
  if (status === 429 || status === 503 || status === 504) return `The Server could not complete ${action} right now. Retry when it is available.`;
  if (error instanceof Error && error.message) return error.message;
  return `Could not ${action}. Check the connection and retry.`;
}
function safeLogicalPath(path: string): boolean {
  return Boolean(path && path.length <= 512 && !path.startsWith('/') && !path.includes('\\')
    && path === path.trim() && !/^[A-Za-z]:/.test(path)
    && path.split('/').every(part => part && part !== '.' && part !== '..' && !/[\u0000-\u001f\u007f:]/.test(part)));
}
function isTextExtension(path: string): boolean {
  return /\.(yaml|yml|xml|json|csv|tsv|txt|sql|properties|md)$/i.test(path);
}
function isBinaryWorkbook(path: string): boolean { return /\.(xlsx|xls)$/i.test(path); }
function pathLabel(target: SourceAuthoringTarget, source?: ManagedAuthoringSource | null): string {
  if (source?.logicalPath) return source.logicalPath;
  if (target.domain === 'CENTRAL_RUNTIME_CONFIG') return target.path;
  return target.query.path || target.query.resourceId || target.label || 'Package source';
}
function summarizeReferences(file: ChangedSourceFile): string[] {
  return (file.inboundReferences || []).map(reference =>
    `${reference.descriptorPath || 'Unknown descriptor'}${reference.referenceCount ? ` · ${reference.referenceCount} reference${reference.referenceCount === 1 ? '' : 's'}` : ''}`);
}

export function SourceAuthoringWorkspace({ api, target, canManageCentral = false, registerUnsavedChanges, onOpenFileReference, onPublished }: SourceAuthoringWorkspaceProps) {
  const workspace = useContext(AppShellContext);
  const [source, setSource] = useState<ManagedAuthoringSource | null>(null);
  const [access, setAccess] = useState<AccessState>(target.domain === 'CENTRAL_RUNTIME_CONFIG' && !canManageCentral ? 'denied' : 'checking');
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [mode, setMode] = useState<EditMode>('view');
  const [content, setContent] = useState('');
  const [createPath, setCreatePath] = useState('');
  const [movePath, setMovePath] = useState('');
  const [draft, setDraft] = useState<SourceChangeSet | null>(null);
  const [published, setPublished] = useState<SourcePublishResult | null>(null);
  const [impact, setImpact] = useState<SourceImpactReport | null>(null);
  const [impactLoading, setImpactLoading] = useState(false);
  const [impactFilter, setImpactFilter] = useState('');
  const [diagnostics, setDiagnostics] = useState<SourceValidationDiagnostic[]>([]);
  const [editorBaseSourceRevision, setEditorBaseSourceRevision] = useState('');
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');
  const [refreshKey, setRefreshKey] = useState(0);
  const targetKey = useMemo(() => JSON.stringify(target), [target]);
  const controllerRef = useRef<AbortController | null>(null);
  const impactControllerRef = useRef<AbortController | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const previousTargetKeyRef = useRef(targetKey);
  const packageTarget = target.domain === 'PACKAGE_SOURCE' ? target : null;
  const centralTarget = target.domain === 'CENTRAL_RUNTIME_CONFIG' ? target : null;
  const isDirty = Boolean(busy || (mode === 'edit' && source && content !== source.text) || mode === 'create' && content.length > 0 || draft);
  const draftStale = Boolean(draft && source && (draft.baseSourceRevision !== source.baseSourceRevision
    || packageTarget && draft.registryRevision !== source.registryRevision));
  const editorStale = Boolean(source && mode !== 'view' && editorBaseSourceRevision
    && editorBaseSourceRevision !== source.baseSourceRevision);
  const displayedPath = pathLabel(target, source);
  const expectedCentralRevision = source?.configRevision || source?.baseSourceRevision || source?.revision;
  const impactMatchesCurrentDraft = Boolean(draft && impact?.freshness?.draftId === draft.changeSetId
    && impact.freshness.draftFingerprint && impact.freshness.baseConfig?.revision
    && impact.freshness.baseConfig.revision === expectedCentralRevision
    && impact.freshness.candidateConfig?.revision);
  const centralReportReady = Boolean(!draftStale && impact?.providerState === 'AVAILABLE'
    && impactMatchesCurrentDraft
    && impact.decision && impact.decision !== 'BLOCKED'
    && !(impact.acknowledgementRequired && impact.decision === 'REQUIRES_REVIEW'));

  useLayoutEffect(() => {
    if (previousTargetKeyRef.current === targetKey) return;
    previousTargetKeyRef.current = targetKey;
    controllerRef.current?.abort();
    impactControllerRef.current?.abort(); impactControllerRef.current = null;
    setSource(null); setDraft(null); setPublished(null); setImpact(null); setDiagnostics([]);
    setContent(''); setMode('view'); setCreatePath(''); setMovePath(''); setEditorBaseSourceRevision(''); setImpactLoading(false);
    setError(''); setStatus(''); setBusy(false); setLoading(false);
    setAccess(centralTarget && !canManageCentral ? 'denied' : 'checking');
  }, [targetKey, centralTarget, canManageCentral]);

  useEffect(() => {
    if (!registerUnsavedChanges) return;
    return registerUnsavedChanges(isDirty, centralTarget ? 'Central Configuration source draft' : 'Package source draft');
  }, [registerUnsavedChanges, isDirty, centralTarget]);

  useEffect(() => {
    const lockWorkspace = centralTarget ? canManageCentral : access === 'authorized';
    workspace?.setWorkspaceEditorLock?.(Boolean(lockWorkspace));
    return () => workspace?.setWorkspaceEditorLock?.(false);
  }, [workspace?.setWorkspaceEditorLock, centralTarget, canManageCentral, access, mode, draft]);

  useEffect(() => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    const targetChanged = previousTargetKeyRef.current !== targetKey;
    if (targetChanged) {
      previousTargetKeyRef.current = targetKey;
      setDraft(null); setPublished(null); setContent(''); setMode('view'); setCreatePath(''); setMovePath(''); setEditorBaseSourceRevision('');
    }
    setSource(null); setImpact(null); setImpactLoading(false); setDiagnostics([]); setError(''); setStatus('');
    if (centralTarget && !canManageCentral) {
      setDraft(null); setContent(''); setMode('view'); setAccess('denied'); setLoading(false);
      return () => controller.abort();
    }
    setAccess('checking'); setLoading(true);
    const request = packageTarget
      ? api.getManagedPackageAuthoringSource?.(packageTarget.packageId, packageTarget.query, controller.signal)
      : centralTarget ? api.getCentralAuthoringSource?.(centralTarget.path, controller.signal) : undefined;
    if (!request) {
      setAccess('unavailable'); setLoading(false);
      setError('This Server does not expose the source authoring contract for this source. Editing remains unavailable.');
      return () => controller.abort();
    }
    request.then(value => {
      if (controller.signal.aborted) return;
      if (!value.available || !value.sourceEtag || !value.baseSourceRevision || typeof value.text !== 'string') {
        setAccess('unavailable'); setSource(null);
        setError('The Server did not provide complete authorized source and revision metadata. Safe or redacted inspection content cannot be edited.');
        return;
      }
      const hasLocalEditorChanges = !targetChanged && mode !== 'view' && (mode === 'create' ? content.length > 0 : Boolean(source && content !== source.text));
      setSource(value);
      if (targetChanged || !hasLocalEditorChanges) setContent(value.text);
      setAccess('authorized'); setError('');
    }).catch(reason => {
      if (controller.signal.aborted) return;
      const status = statusOf(reason);
      setSource(null); setContent(''); setAccess(status === 403 ? 'denied' : 'unavailable');
      if (status === 403) { setDraft(null); setImpact(null); }
      if (status !== 403) setError(failureText(reason, 'load complete source'));
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => {
      controller.abort();
      if (controllerRef.current === controller) controllerRef.current = null;
      impactControllerRef.current?.abort(); impactControllerRef.current = null;
    };
  }, [api, targetKey, refreshKey, canManageCentral, packageTarget?.packageId, centralTarget?.path]);

  useEffect(() => {
    const clearPrivilegedState = () => {
      controllerRef.current?.abort();
      impactControllerRef.current?.abort(); impactControllerRef.current = null;
      setSource(null); setDraft(null); setPublished(null); setImpact(null); setContent(''); setMode('view');
      setCreatePath(''); setMovePath(''); setEditorBaseSourceRevision(''); setBusy(false); setLoading(false);
      setImpactLoading(false);
      setStatus('Privileged source was cleared when the page was hidden. Reload it before continuing.');
      setAccess('checking'); setLoading(false);
    };
    const onPageShow = () => setRefreshKey(value => value + 1);
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') clearPrivilegedState();
      else setRefreshKey(value => value + 1);
    };
    window.addEventListener('pagehide', clearPrivilegedState);
    window.addEventListener('pageshow', onPageShow);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.removeEventListener('pagehide', clearPrivilegedState);
      window.removeEventListener('pageshow', onPageShow);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, []);

  useEffect(() => () => {
    controllerRef.current?.abort();
    impactControllerRef.current?.abort(); impactControllerRef.current = null;
  }, []);

  const clearAfterRevocation = () => {
    controllerRef.current?.abort();
    impactControllerRef.current?.abort(); impactControllerRef.current = null;
    setSource(null); setDraft(null); setImpact(null); setImpactLoading(false); setContent(''); setMode('view'); setAccess('denied');
  };

  async function withBusy(action: string, run: (signal: AbortSignal) => Promise<void>) {
    const controller = new AbortController();
    controllerRef.current?.abort(); controllerRef.current = controller;
    setBusy(true); setError(''); setStatus(''); setDiagnostics([]);
    try { await run(controller.signal); }
    catch (reason) {
      if (controller.signal.aborted) return;
      setError(failureText(reason, action));
      if (statusOf(reason) === 422) setDiagnostics(diagnosticsOf(reason));
      if (statusOf(reason) === 403) clearAfterRevocation();
    } finally {
      if (controllerRef.current === controller) {
        controllerRef.current = null;
        setBusy(false);
      }
    }
  }

  function getScopeId() {
    return target.domain === 'PACKAGE_SOURCE' ? target.packageId : 'central-runtime-config';
  }

  async function stage(operations: SourceOperation[]) {
    if (!source || !api.stageSourceChangeSet) {
      setError('The source revision or Change Set API is unavailable. Reload the complete source before editing.'); return;
    }
    if (!operations.length || operations.length > 32) { setError('A draft must contain between 1 and 32 file changes.'); return; }
    if (operations.some(operation => !safeLogicalPath(operation.path) || (operation.targetPath && !safeLogicalPath(operation.targetPath)))) {
      setError('Use an in-package relative path without empty, dot, parent, or absolute path segments.'); return;
    }
    await withBusy('save the source draft', async signal => {
      const result = await api.stageSourceChangeSet!(target.domain, getScopeId(), source.baseSourceRevision,
        target.domain === 'PACKAGE_SOURCE' ? source.registryRevision : undefined, operations, signal);
      if (signal.aborted) return;
      setDraft(result); setImpact(null); setPublished(null); setMode('view');
      setStatus(`Draft ${result.changeSetId} saved. Published source is unchanged.`);
    });
  }

  async function act(action: 'validate' | 'review' | 'publish' | 'discard') {
    if (!draft || !api.sourceChangeSetAction) { setError('The Change Set action API is unavailable.'); return; }
    if (draftStale && action !== 'discard') { setError('This draft is based on an older Package or Config revision. Compare the current source, discard this draft, then stage a new one.'); return; }
    if (action === 'publish' && centralTarget && !centralReportReady) {
      setError('Central publication is disabled until the Server returns a current, available impact report and any required acknowledgement can be bound.'); return;
    }
    if (action === 'review' && centralTarget) {
      await loadImpact(draft.changeSetId);
      return;
    }
    if (action === 'publish' && !window.confirm(`Publish the reviewed source draft for ${displayedPath}? This changes published source.`)) return;
    if (action === 'discard' && !window.confirm(`Discard draft ${draft.changeSetId}? Its staged source changes will be removed.`)) return;
    await withBusy(`${action} the source draft`, async signal => {
      const result = await api.sourceChangeSetAction!(target.domain, getScopeId(), draft.changeSetId, action, signal);
      if (signal.aborted) return;
      if (action === 'publish' && 'published' in result) {
        const publication = result as SourcePublishResult;
        setPublished(publication); setDraft(null); setImpact(null); setSource(null); setContent(''); setMode('view');
        setStatus(`Published revision ${publication.revision}.${publication.auditConfirmed === false ? ' The Server reports the durable audit record is pending confirmation.' : ''}`);
        onPublished?.(publication);
        setRefreshKey(value => value + 1);
      } else if (action === 'discard') {
        setDraft(null); setImpact(null); setContent(source?.text || ''); setStatus('Draft discarded. Published source is unchanged.');
      } else {
        setDraft(result as SourceChangeSet);
        setStatus(action === 'validate' ? 'Draft validation completed.' : 'Changed-file review completed.');
      }
    });
  }

  async function loadImpact(changeSetId: string, cursor?: string, append = false) {
    if (!api.getCentralConfigImpact) {
      setImpact({ providerState: 'UNAVAILABLE', unavailableCode: 'ATT-CONFIG-IMPACT-UNAVAILABLE' });
      setError('The Config Impact Report API is not available on this Server. Central publication is disabled.'); return;
    }
    const controller = new AbortController();
    impactControllerRef.current?.abort(); impactControllerRef.current = controller;
    setImpactLoading(true); setError('');
    try {
      const report = await api.getCentralConfigImpact(changeSetId, cursor, controller.signal);
      if (controller.signal.aborted) return;
      if (!append || !impact) setImpact(report);
      else if (report.freshness?.draftFingerprint !== impact.freshness?.draftFingerprint) {
        setImpact({ ...impact, providerState: 'STALE', decision: null, schedules: {
          schemaVersion: impact.schedules?.schemaVersion, items: impact.schedules?.items || [],
          totalEstimate: impact.schedules?.totalEstimate, truncated: impact.schedules?.truncated, nextCursor: null,
        } });
        setError('The impact report changed while loading details. Validate and review this draft again.');
      } else setImpact({ ...impact, ...report,
        affectedPackages: [...(impact.affectedPackages || []), ...(report.affectedPackages || [])],
        schedules: { ...report.schedules, items: [...(impact.schedules?.items || []), ...(report.schedules?.items || [])] },
      });
      if (report.providerState !== 'AVAILABLE') {
        setError(`Config Impact Report is ${String(report.providerState).toLowerCase()}${report.unavailableCode ? ` (${report.unavailableCode})` : ''}. Central publication is disabled.`);
        return;
      }
      const expectedRevision = source?.configRevision || source?.baseSourceRevision || source?.revision;
      if (report.freshness?.draftId !== changeSetId || !report.freshness.draftFingerprint
        || !report.freshness.candidateConfig?.revision || !report.freshness.baseConfig?.revision
        || report.freshness.baseConfig.revision !== expectedRevision) {
        setImpact({ ...report, providerState: 'STALE', decision: null });
        setError('The Impact Report is missing or does not match the current draft and source revision. Refresh the source, validate, and review again. Central publication is disabled.');
        return;
      }
      if (report.decision === 'BLOCKED') {
        setError('The Server blocked this central Config candidate. Review the impact result and diagnostics; publication is disabled.'); return;
      }
      if (report.acknowledgementRequired && report.decision === 'REQUIRES_REVIEW') {
        setError('This change requires explicit impact acknowledgement. The acknowledgment binding endpoint is unavailable, so publication remains disabled.'); return;
      }
      if (!cursor) {
        await withBusy('review the central Config draft', async signal => {
          const result = await api.sourceChangeSetAction!(target.domain, getScopeId(), changeSetId, 'review', signal);
          if (!signal.aborted) { setDraft(result as SourceChangeSet); setStatus('Changed-file review and current impact report are ready.'); }
        });
      }
    } catch (reason) {
      if (controller.signal.aborted) return;
      setImpact({ providerState: statusOf(reason) === 409 ? 'STALE' : 'UNAVAILABLE', unavailableCode: codeOf(reason) || undefined });
      setError(`${failureText(reason, 'load Config Impact Report')} Central publication is disabled.`);
    } finally {
      if (impactControllerRef.current === controller) {
        impactControllerRef.current = null;
        setImpactLoading(false);
      }
    }
  }

  async function handleUpload(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.currentTarget.files?.[0];
    event.currentTarget.value = '';
    if (!file) return;
    const path = mode === 'create' ? createPath.trim() || file.name : source?.logicalPath || '';
    if (!safeLogicalPath(path)) { setError('Choose a valid relative source path before uploading.'); return; }
    if (file.size > 4 * 1024 * 1024) { setError('Files larger than 4 MiB cannot be uploaded in one source change.'); return; }
    if (!isTextExtension(path)) { setError('Only UTF-8 text files can be uploaded here. Testcase workbooks use the paired upload flow.'); return; }
    const uploadController = new AbortController();
    controllerRef.current?.abort();
    controllerRef.current = uploadController;
    setBusy(true); setError(''); setStatus('Reading and validating the UTF-8 source upload…');
    try {
      const bytes = new Uint8Array(await file.arrayBuffer());
      if (uploadController.signal.aborted) return;
      const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
      if (uploadController.signal.aborted) return;
      setContent(text);
      const operation: SourceOperation = { operation: source?.present && mode !== 'create' ? 'replace' : 'create', path, content: text };
      await stage([operation]);
    } catch {
      if (!uploadController.signal.aborted) setError('The selected source file is not valid UTF-8 text. Testcase workbooks must use the paired upload flow.');
    } finally {
      if (controllerRef.current === uploadController) setBusy(false);
    }
  }

  function saveTextDraft() {
    if (editorStale) { setError('Review the latest source and rebase your local changes before saving.'); return; }
    if (mode === 'create') {
      const path = createPath.trim();
      if (!safeLogicalPath(path)) { setError('Enter a valid relative path, such as templates/new-template/template.yaml.'); return; }
      if (!content.trim()) { setError('Add source content before creating a draft.'); return; }
      void stage([{ operation: 'create', path, content }]);
      return;
    }
    if (!source || content === source.text) { setStatus('There are no source changes to save.'); return; }
    const operation = source.present ? 'replace' : 'create';
    if (!window.confirm(`${operation === 'create' ? 'Create' : 'Save a draft for'} ${source.logicalPath}? Published source will not change until you publish.`)) return;
    void stage([{ operation, path: source.logicalPath, content }]);
  }

  function beginDelete() {
    if (!source || !source.present) return;
    if (!window.confirm(`Stage deletion of ${source.logicalPath}? The review will list known references before publication.`)) return;
    void stage([{ operation: 'delete', path: source.logicalPath }]);
  }
  function beginMove() {
    if (!source || !source.present) return;
    if (!safeLogicalPath(movePath)) { setError('Enter a valid new relative path.'); return; }
    if (!window.confirm(`Stage move of ${source.logicalPath} to ${movePath}? Known inbound references will be reviewed.`)) return;
    void stage([{ operation: 'move', path: source.logicalPath, targetPath: movePath }]);
  }

  const sourceEditor = source && !draft && (mode === 'edit' || mode === 'view')
    ? <CodeEditor value={content} language={detectCodeLanguage(source.logicalPath || source.format)}
        label={`${mode === 'edit' ? 'Edit' : 'View'} authorized source ${source.logicalPath}`}
        readOnly={mode !== 'edit' || busy} onChange={setContent} maxCharacters={200_000} /> : null;

  function reloadSource() {
    if (draft) { setRefreshKey(value => value + 1); return; }
    const hasLocalChanges = Boolean(mode === 'edit' && source && content !== source.text || mode === 'create' && content.length);
    if (hasLocalChanges && !window.confirm('Reloading will discard these unsaved local editor changes. Continue?')) return;
    if (hasLocalChanges) { setMode('view'); setContent(''); setCreatePath(''); setEditorBaseSourceRevision(''); }
    setRefreshKey(value => value + 1);
  }

  function rebaseLocalEditor() {
    if (!source || !editorStale) return;
    if (!window.confirm('Rebase the editor content on the current source revision? Compare the latest source above and merge any changes before saving.')) return;
    setEditorBaseSourceRevision(source.baseSourceRevision);
    setStatus('Editor rebased on the current source revision. Compare and merge the latest source before saving.');
  }

  if (centralTarget && !canManageCentral) return null;

  return <section className={`att-source-authoring ${target.domain === 'CENTRAL_RUNTIME_CONFIG' ? 'is-central' : ''}`} aria-label={target.label || 'Source authoring'}>
    <header className="att-source-authoring-heading">
      <div><p className="eyebrow">{centralTarget ? 'CENTRAL_RUNTIME_CONFIG · ADMIN' : `PACKAGE_SOURCE · ${packageTarget?.packageId}`}</p>
        <h2>{target.label || displayedPath}</h2>
        {centralTarget && <p className="att-source-authority-banner">Global Runtime Configuration · Changes apply across Packages. ADMIN source permission is checked by the Server.</p>}
        {packageTarget && <p>Editing Package {packageTarget.packageId}. Source authority is bound to this Package and rechecked by the Server.</p>}
      </div>
      <div className="att-source-authoring-actions">
        <button type="button" className="button button-quiet" onClick={reloadSource} disabled={loading || busy}>
          {loading ? 'Loading…' : 'Reload source'}
        </button>
        <button type="button" className="button button-quiet" disabled title="Source history and restore are provided by the #256 history service when available.">History / Restore unavailable</button>
      </div>
    </header>

    {loading && <p role="status" aria-busy="true">Loading complete authorized source…</p>}
    {access === 'denied' && packageTarget && <>
      <p className="att-source-authoring-note">Owner editing is unavailable for this identity. The safe, redacted inspection projection remains separate and cannot be submitted as source.</p>
      {packageTarget.fallbackIdentity && <SourceViewer api={api} identity={packageTarget.fallbackIdentity}
        available={packageTarget.fallbackAvailable === true} active onFileReference={onOpenFileReference} />}
    </>}
    {access === 'denied' && centralTarget && <p className="att-source-authoring-note">Central source editing requires the Server's ADMIN configuration permission. Shared Configuration inspection remains read only.</p>}
    {access === 'unavailable' && <div className="att-source-restricted"><h3>Authorized source unavailable</h3>
      <p>The Server did not return complete unredacted bytes and a source ETag. Masked, truncated, protected, or safe inspection content is never treated as editable source.</p>
      {packageTarget?.fallbackIdentity && <SourceViewer api={api} identity={packageTarget.fallbackIdentity}
        available={packageTarget.fallbackAvailable === true} active onFileReference={onOpenFileReference} />}
    </div>}
    {error && <p className="att-error" role="alert">{error}</p>}
    {diagnostics.length > 0 && <ul className="att-source-diagnostics" aria-label="Source validation diagnostics">{diagnostics.map((item, index) => <li key={`${item.code || 'diagnostic'}:${index}`}>
      <strong>{item.severity || 'ERROR'}{item.code ? ` · ${item.code}` : ''}</strong>
      {item.logicalPath && <code>{item.logicalPath}</code>}{item.field && <span>Field: {item.field}</span>}{item.line && <span>Line {item.line}</span>}
      {item.message && <span>{item.message}</span>}{item.remedy && <span>Remedy: {item.remedy}</span>}
    </li>)}</ul>}
    {status && <p className="att-source-authoring-status" role="status" aria-live="polite">{status}</p>}

    {access === 'authorized' && source && <>
      {draftStale && <p className="att-error" role="alert">This draft uses an older source or Package registration revision. Compare the latest authorized source below, then discard this draft and stage a new one.</p>}
      {editorStale && <p className="att-error" role="alert">The Package source changed after these local edits began. Compare the current source below and rebase before saving.</p>}
      <div className="att-source-revision-strip" aria-label="Source revision details">
        <span><strong>{draft ? 'Draft' : published ? 'Published' : 'Published source'}</strong></span>
        <span>Source ETag <code>{source.sourceEtag}</code></span>
        <span>Base revision <code>{source.baseSourceRevision}</code></span>
        {source.registryRevision && <span>Package registration revision <code>{source.registryRevision}</code></span>}
        {draft && <span>Draft revision <code>{draft.validationRevision || draft.baseSourceRevision}</code></span>}
        {published && <span>Published revision <code>{published.revision}</code></span>}
        {published && centralTarget && <span>Active revision <code>{published.activeRevision || impact?.activationStatus?.activeRevision || 'Unavailable'}</code></span>}
      </div>

      {!draft && <>
        {source.present === false && <p className="att-source-authoring-note">This approved source path does not exist yet. Create publishes only after the draft is validated and reviewed.</p>}
        {isBinaryWorkbook(source.logicalPath) && <p className="att-source-authoring-note">Binary workbook bytes cannot be opened in CodeEditor. Use the explicit Testcase workbook upload flow for workbook changes.</p>}
        <div className="att-source-authoring-toolbar" role="group" aria-label="Source actions">
          {mode === 'view' && <button type="button" onClick={() => { setMode('edit'); setContent(source.text); setEditorBaseSourceRevision(source.baseSourceRevision); }} disabled={busy || isBinaryWorkbook(source.logicalPath)}>{source.present ? 'Edit' : 'Create source'}</button>}
          {mode === 'edit' && <button type="button" onClick={() => {
            if (content !== source.text && !window.confirm('Discard the local source edits? Published source is unchanged.')) return;
            setMode('view'); setContent(source.text); setEditorBaseSourceRevision(''); setError('');
          }} disabled={busy}>Discard local changes</button>}
          {packageTarget && <>
            <button type="button" onClick={() => fileInputRef.current?.click()} disabled={busy}>Upload / Replace</button>
            {packageTarget.allowFileOperations && <>
              <button type="button" onClick={beginDelete} disabled={busy || !source.present}>Delete</button>
              <label>Move to <input aria-label="Move source to path" value={movePath} onChange={event => setMovePath(event.currentTarget.value)} placeholder="relative/path/file.yaml" disabled={busy} /></label>
              <button type="button" onClick={beginMove} disabled={busy || !movePath || !source.present}>Move</button>
            </>}
            {packageTarget.allowCreate && <button type="button" onClick={() => { setMode('create'); setCreatePath(''); setContent(''); setEditorBaseSourceRevision(source.baseSourceRevision); setError(''); }} disabled={busy}>New source</button>}
          </>}
          {mode === 'edit' && <button type="button" className="button button-primary" onClick={saveTextDraft} disabled={busy || content === source.text || editorStale}>Save Draft</button>}
          <input ref={fileInputRef} type="file" className="att-source-file-input" aria-label="Upload source file"
            accept=".yaml,.yml,.xml,.json,.csv,.tsv,.txt,.sql,.properties,.md" onChange={event => void handleUpload(event)} />
        </div>
        {mode === 'edit' && <p className="att-source-authoring-note">The editor contains the exact authorized source shown in View. Source bytes remain in memory only and are never placed in inspection cache or browser storage.</p>}
        {mode === 'edit' && editorStale && <details className="att-source-current-copy"><summary>Latest authorized source</summary>
          <CodeEditor value={source.text} language={detectCodeLanguage(source.logicalPath || source.format)} label={`Latest authorized source ${source.logicalPath}`} readOnly maxCharacters={200_000} />
          <button type="button" onClick={rebaseLocalEditor} disabled={busy}>Rebase local changes on current revision</button>
        </details>}
        {sourceEditor}
        {mode === 'create' && <div className="att-source-create-form">
          <label>New logical path <input value={createPath} onChange={event => setCreatePath(event.currentTarget.value)} placeholder="templates/new-template/template.yaml" disabled={busy} /></label>
          <CodeEditor value={content} language={detectCodeLanguage(createPath)} label="New source content" readOnly={busy} onChange={setContent} maxCharacters={200_000} />
          {editorStale && <details className="att-source-current-copy"><summary>Latest authorized source</summary>
            <CodeEditor value={source.text} language={detectCodeLanguage(source.logicalPath || source.format)} label={`Latest authorized source ${source.logicalPath}`} readOnly maxCharacters={200_000} />
            <button type="button" onClick={rebaseLocalEditor} disabled={busy}>Rebase local changes on current revision</button>
          </details>}
          <div><button type="button" onClick={() => { setMode('view'); setContent(source.text); setCreatePath(''); setEditorBaseSourceRevision(''); }} disabled={busy}>Cancel</button>
            <button type="button" className="button button-primary" onClick={saveTextDraft} disabled={busy || editorStale || !createPath || !content.trim()}>Save Draft</button>
            <button type="button" onClick={() => fileInputRef.current?.click()} disabled={busy}>Upload new source</button></div>
        </div>}
      </>}

      {draft && <section className="att-source-review" aria-label="Changed source review">
        <div className="att-source-review-heading"><div><h3>Draft {draft.changeSetId}</h3><p>{draft.state} · expires {draft.expiresAt || 'per Server policy'} · published source remains unchanged</p></div></div>
        <ChangedFiles files={draft.changedFiles || []} />
        {draftStale && <details className="att-source-current-copy"><summary>Latest authorized source</summary>
          <CodeEditor value={source.text} language={detectCodeLanguage(source.logicalPath || source.format)} label={`Latest authorized source ${source.logicalPath}`} readOnly maxCharacters={200_000} />
        </details>}
        <div className="att-source-authoring-toolbar">
          <button type="button" onClick={() => void act('validate')} disabled={busy || loading || draftStale || draft.state === 'reviewed'}>Validate</button>
          <button type="button" onClick={() => void act('review')} disabled={busy || loading || draftStale || draft.state !== 'validated'}>Review changed files{centralTarget ? ' / Impact' : ''}</button>
          <button type="button" className="button button-primary" onClick={() => void act('publish')}
            disabled={busy || loading || draftStale || draft.state !== 'reviewed' || Boolean(centralTarget && !centralReportReady)}>Publish</button>
          <button type="button" onClick={() => void act('discard')} disabled={busy}>Discard Draft</button>
        </div>
        {centralTarget && impact && <ImpactReview report={impact} loading={impactLoading} filter={impactFilter} onFilter={setImpactFilter}
          onLoadMore={() => impact.schedules?.nextCursor && void loadImpact(draft.changeSetId, impact.schedules.nextCursor, true)} />}
        {centralTarget && !impact && <p className="att-source-authoring-note">Central publication requires a current #335 Impact Report. Review will load the server report and block publication when its provider is unavailable or stale.</p>}
      </section>}

      {published && <section className="att-source-publication-result" aria-label="Publication result">
        <h3>Published revision {published.publishedRevision || published.revision}</h3>
        {centralTarget && <p>Active revision: {published.activeRevision || impact?.activationStatus?.activeRevision || 'Activation status unavailable from this Server'}.
          {(published.activationState || impact?.activationStatus?.state) && <> State: {published.activationState || impact?.activationStatus?.state}.</>}
          {(published.activationFailureMessage || impact?.activationStatus?.failureMessage) && <span role="alert"> {published.activationFailureMessage || impact?.activationStatus?.failureMessage}</span>}</p>}
      </section>}
    </>}
  </section>;
}

export function ChangedFiles({ files }: { files: ChangedSourceFile[] }) {
  return <div className="att-source-changed-files"><h4>Changed files</h4>
    {!files.length ? <p>The Server did not return changed-file details.</p> : <ul>{files.map((file, index) => <li key={`${file.operation}:${file.path}:${index}`}>
      <div className="att-source-file-change"><strong>{file.operation.toUpperCase()}</strong> <code>{file.path}</code>
        {file.targetPath && <> → <code>{file.targetPath}</code></>}
        <span>{file.oldBytes ?? 0} B → {file.newBytes ?? 0} B</span></div>
      {file.automatic && <p>Generated by the Server as part of this Change Set.</p>}
      {file.inboundReferences?.length ? <div className="att-source-inbound-references"><strong>Referenced by</strong><ul>{summarizeReferences(file).map(reference => <li key={reference}>{reference}</li>)}</ul>
        <p>Review every known reference before publishing this delete or move.</p></div> : null}
      {file.contentSummary && <p>{file.contentSummary}</p>}
      {file.contentDiff && <pre aria-label={`Review diff for ${file.path}`}>{file.contentDiff.join('\n')}</pre>}
    </li>)}</ul>}
  </div>;
}

function ImpactReview({ report, loading, filter, onFilter, onLoadMore }: {
  report: SourceImpactReport; loading: boolean; filter: string; onFilter(value: string): void; onLoadMore(): void;
}) {
  const query = filter.trim().toLocaleLowerCase();
  const packages = (report.affectedPackages || []).filter(item => !query
    || `${item.packageId} ${item.environment} ${item.safeReason || ''}`.toLocaleLowerCase().includes(query));
  const schedules: SourceAffectedSchedule[] = report.schedules?.items || [];
  const confirmed = packages.filter(item => item.certainty === 'CONFIRMED_AFFECTED').length;
  const potential = packages.filter(item => item.certainty === 'POTENTIALLY_AFFECTED').length;
  return <section className="att-source-impact" aria-label="Config Impact Report">
    <header><div><p className="eyebrow">#335 Config Impact Report</p><h4>{report.providerState === 'AVAILABLE' ? report.decision || 'Decision unavailable' : `Report ${report.providerState.toLowerCase()}`}</h4></div>
      {report.coverage && <span className="att-source-impact-coverage">Coverage: {report.coverage}</span>}</header>
    {report.providerState !== 'AVAILABLE' && <p role="alert">The report is not current and available. The client does not infer compatibility or enable Publish. {report.unavailableCode || ''}</p>}
    {report.providerState === 'AVAILABLE' && <>
      <p>{confirmed} confirmed and {potential} potential Package / Environment pairs. {report.schedules?.totalEstimate ?? schedules.length} affected Schedule entries.</p>
      {report.freshness && <details><summary>Impact freshness revisions</summary><dl className="att-impact-revisions">
        <div><dt>Draft</dt><dd><code>{report.freshness.draftId}</code> · <code>{report.freshness.draftFingerprint}</code></dd></div>
        <div><dt>Base Config</dt><dd><code>{report.freshness.baseConfig.revision}</code></dd></div>
        <div><dt>Candidate Config</dt><dd><code>{report.freshness.candidateConfig.revision}</code></dd></div>
        <div><dt>Package registry</dt><dd><code>{report.freshness.packageRegistry.revision}</code></dd></div>
        <div><dt>Package / Manifest / Schema / Schedule snapshots</dt><dd>{report.freshness.packageSources.length} / {report.freshness.manifests.length} / {report.freshness.engineSchema.revision} / {report.freshness.scheduleSnapshots.length}</dd></div>
      </dl></details>}
      {report.acknowledgementRequired && <p className="att-source-impact-ack" role="alert">This impact report requires explicit acknowledgement. Publish stays disabled until the Server exposes a revision-bound acknowledgement action.</p>}
      <label>Filter affected Packages / Environments <input type="search" value={filter} onChange={event => onFilter(event.currentTarget.value)} /></label>
      <div className="att-source-impact-table-wrap"><table><thead><tr><th>Package</th><th>Environment</th><th>Certainty</th><th>Why</th><th>Changed logical IDs</th></tr></thead>
        <tbody>{packages.map((item: SourceAffectedPackage, index: number) => <tr key={`${item.packageId}:${item.environment}:${index}`}>
          <th scope="row">{item.packageId}</th><td>{item.environment}</td><td>{item.certainty}</td><td>{item.safeReason || 'No safe reason provided'}</td><td>{item.changedLogicalIds?.join(', ') || '—'}</td>
        </tr>)}{!packages.length && <tr><td colSpan={5}>No affected Package / Environment rows match the filter.</td></tr>}</tbody></table></div>
      <div className="att-source-impact-table-wrap"><h5>Affected Schedules</h5><table><thead><tr><th>Schedule</th><th>Package</th><th>Environment</th><th>Action</th><th>Why</th></tr></thead>
        <tbody>{schedules.map(item => <tr key={item.scheduleId}><th scope="row">{item.scheduleId}</th><td>{item.packageId || '—'}</td><td>{item.environment || '—'}</td><td>{item.action}</td><td>{item.safeReason || '—'}</td></tr>)}
          {!schedules.length && <tr><td colSpan={5}>No Schedule details were returned for this page.</td></tr>}</tbody></table></div>
      {report.schedules?.truncated && <p>The Server bounded the affected Schedule list. Continue loading pages to inspect the remaining details.</p>}
      {report.schedules?.nextCursor && <button type="button" onClick={onLoadMore} disabled={loading}>{loading ? 'Loading Schedules…' : 'Load more Schedules'}</button>}
      {report.activationStatus && <p>Activation: {report.activationStatus.state || 'Unknown'} · Published {report.activationStatus.publishedRevision || 'unavailable'} · Active {report.activationStatus.activeRevision || 'unavailable'}.
        {report.activationStatus.failureMessage && <span role="alert"> {report.activationStatus.failureMessage}</span>}</p>}
    </>}
  </section>;
}
