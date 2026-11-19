import { useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { ApiError } from '../../types';
import { AppShellContext } from '../../AppShell';
import { CodeEditor, detectCodeLanguage } from '../code/CodeEditor';
import { SourceViewer } from './SourceViewer';
import { AuthoringWorkflow, type AuthoringStepState } from './AuthoringWorkflow';
import { useAccessibleConfirmation } from './useAccessibleConfirmation';
import type { ExplorerApi, ResourceIdentity, ResourceSourceReference } from './types';
import type {
  ChangedSourceFile, ManagedAuthoringSource, PackageAuthoringSourceQuery, SourceAffectedPackage,
  SourceAffectedSchedule, SourceChangeSet, SourceHistoryDiffResponse, SourceHistoryEntry, SourceHistoryResponse, SourceImpactReport, SourceOperation,
  SourcePublishResult, SourceValidationDiagnostic,
} from './sourceManagement';
import {
  isPackageSourceRevisionIdentity, isSourceRevisionIdentity,
  resolveExecutionRevisionIdentity, resolvePackageSourceRevisionIdentity,
} from './sourceManagement';
import { createImpactAcknowledgement } from './impactAcknowledgement';
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
export interface SourceAuthoringInitialCandidate {
  key: string;
  baseSourceRevision: string;
  registryRevision?: string;
  operations: SourceOperation[];
}

type EditMode = 'view' | 'edit' | 'create';
type AccessState = 'checking' | 'authorized' | 'denied' | 'unavailable';

interface SourceAuthoringWorkspaceProps {
  api: ExplorerApi;
  target: SourceAuthoringTarget;
  canManageCentral?: boolean;
  registerUnsavedChanges?: (isDirty: boolean, description?: string) => () => void;
  onDirtyChange?: (isDirty: boolean) => void;
  initialCandidate?: SourceAuthoringInitialCandidate;
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
  if (code === 'ATT-CHANGESET-RESTORE-LIMIT') return 'This historical restore exceeds the Server limit of 32 file operations. Stage the required files in smaller drafts; this restore did not publish or partially apply any files.';
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
const MAX_PACKAGE_ZIP_BYTES = 16 * 1024 * 1024;
function isBinaryWorkbook(path: string): boolean { return /\.(xlsx|xls)$/i.test(path); }
function pathLabel(target: SourceAuthoringTarget, source?: ManagedAuthoringSource | null): string {
  if (source?.logicalPath) return source.logicalPath;
  if (target.domain === 'CENTRAL_RUNTIME_CONFIG') return target.path;
  return target.query.path || target.query.resourceId || target.label || 'Package source';
}
function impactFreshnessMatches(report: SourceImpactReport | null, changeSetId: string, expectedConfigRevision?: string): boolean {
  const freshness = report?.freshness;
  return Boolean(freshness?.schemaVersion === 'att-config-impact-freshness/v1'
    && freshness.draftId === changeSetId && /^[a-f0-9]{64}$/.test(freshness.draftFingerprint || '')
    && isSourceRevisionIdentity(freshness.baseConfig, 'CONFIG_SOURCE') && freshness.baseConfig.revision === expectedConfigRevision
    && isSourceRevisionIdentity(freshness.candidateConfig, 'CONFIG_SOURCE')
    && isSourceRevisionIdentity(freshness.packageRegistry, 'PACKAGE_REGISTRATION')
    && isSourceRevisionIdentity(freshness.engineSchema, 'ENGINE_SCHEMA')
    && Array.isArray(freshness.packageSources) && freshness.packageSources.every(isPackageSourceRevisionIdentity)
    && Array.isArray(freshness.manifests) && freshness.manifests.every(item => isSourceRevisionIdentity(item, 'PACKAGE_MANIFEST'))
    && Array.isArray(freshness.scheduleSnapshots) && freshness.scheduleSnapshots.every(item => isSourceRevisionIdentity(item, 'SCHEDULE_SNAPSHOT')));
}
function summarizeReferences(file: ChangedSourceFile): string[] {
  return (file.inboundReferences || []).map(reference => {
    const location = reference.canonicalPath || reference.descriptorPath || reference.logicalId
      || [reference.packageId, reference.environment].filter(Boolean).join(' / ') || 'Location unavailable';
    const details = [
      reference.kind,
      reference.relationship,
      reference.callSite ? `Action: ${reference.callSite}` : undefined,
      reference.certainty === 'UNKNOWN' ? 'location uncertain' : reference.certainty === 'KNOWN' ? 'known location' : undefined,
      reference.referenceCount ? `${reference.referenceCount} reference${reference.referenceCount === 1 ? '' : 's'}` : undefined,
    ].filter(Boolean);
    return `${location}${details.length ? ` · ${details.join(' · ')}` : ''}`;
  });
}

export function SourceAuthoringWorkspace({ api, target, canManageCentral = false, registerUnsavedChanges, onDirtyChange, initialCandidate, onOpenFileReference, onPublished }: SourceAuthoringWorkspaceProps) {
  const workspace = useContext(AppShellContext);
  const { confirm: confirmAction, dialog: confirmationDialog } = useAccessibleConfirmation();
  const [source, setSource] = useState<ManagedAuthoringSource | null>(null);
  const [access, setAccess] = useState<AccessState>(target.domain === 'CENTRAL_RUNTIME_CONFIG' && !canManageCentral ? 'denied' : 'checking');
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [mode, setMode] = useState<EditMode>('view');
  const [content, setContent] = useState('');
  const [createPath, setCreatePath] = useState('');
  const [movePath, setMovePath] = useState('');
  const [pendingOperations, setPendingOperations] = useState<SourceOperation[]>([]);
  const [pendingBaseSourceRevision, setPendingBaseSourceRevision] = useState('');
  const [pendingRegistryRevision, setPendingRegistryRevision] = useState<string | undefined>();
  const [relatedPath, setRelatedPath] = useState('');
  const [relatedOperation, setRelatedOperation] = useState<'create' | 'replace'>('create');
  const [relatedContent, setRelatedContent] = useState('');
  const [suspended, setSuspended] = useState(false);
  const [recoveryPending, setRecoveryPending] = useState(false);
  const [draft, setDraft] = useState<SourceChangeSet | null>(null);
  const [published, setPublished] = useState<SourcePublishResult | null>(null);
  const [impact, setImpact] = useState<SourceImpactReport | null>(null);
  const [impactLoading, setImpactLoading] = useState(false);
  const [impactFilter, setImpactFilter] = useState('');
  const [impactActorEntry, setImpactActorEntry] = useState('');
  const [acknowledgedImpact, setAcknowledgedImpact] = useState<{ key: string; actor: string } | null>(null);
  const [diagnostics, setDiagnostics] = useState<SourceValidationDiagnostic[]>([]);
  const [editorBaseSourceRevision, setEditorBaseSourceRevision] = useState('');
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');
  const [historyOpen, setHistoryOpen] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);
  const targetKey = useMemo(() => JSON.stringify(target), [target]);
  const controllerRef = useRef<AbortController | null>(null);
  const actionControllerRef = useRef<AbortController | null>(null);
  const impactControllerRef = useRef<AbortController | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const packageZipInputRef = useRef<HTMLInputElement | null>(null);
  const previousTargetKeyRef = useRef(targetKey);
  const recoveryRequiredRef = useRef(false);
  const suspendedRef = useRef(false);
  const initialCandidateAppliedRef = useRef('');
  const packageTarget = target.domain === 'PACKAGE_SOURCE' ? target : null;
  const centralTarget = target.domain === 'CENTRAL_RUNTIME_CONFIG' ? target : null;
  const isDirty = Boolean(busy || (mode === 'edit' && source && content !== source.text)
    || mode === 'create' && (content.length > 0 || createPath.trim()) || pendingOperations.length || draft
    || recoveryPending || suspended && recoveryRequiredRef.current);
  const isDirtyRef = useRef(isDirty);
  isDirtyRef.current = isDirty;
  const draftStale = Boolean(draft && source && (draft.baseSourceRevision !== source.baseSourceRevision
    || packageTarget && draft.registryRevision !== source.registryRevision));
  const editorStale = Boolean(source && mode !== 'view' && editorBaseSourceRevision
    && editorBaseSourceRevision !== source.baseSourceRevision);
  const candidateStale = Boolean(pendingOperations.length && source && (pendingBaseSourceRevision !== source.baseSourceRevision
    || packageTarget && pendingRegistryRevision !== source.registryRevision));
  const queuedCurrentOperation = pendingOperations.find(operation => operation.path === source?.logicalPath);
  const currentEditorHasUnqueuedChanges = Boolean(mode === 'edit' && source && content !== source.text
    && (!queuedCurrentOperation || queuedCurrentOperation.content !== content)
    || mode === 'create' && content
      && !pendingOperations.some(operation => operation.path === createPath.trim() && operation.content === content));
  const displayedPath = pathLabel(target, source);
  const expectedCentralRevision = source?.configRevision || source?.baseSourceRevision || source?.revision;
  const revisionSet = draft?.revisions || source?.revisions;
  const packageSourceIdentity = resolvePackageSourceRevisionIdentity(revisionSet);
  const executionRevisionIdentity = resolveExecutionRevisionIdentity(revisionSet);
  const impactMatchesCurrentDraft = Boolean(draft && impactFreshnessMatches(impact, draft.changeSetId, expectedCentralRevision));
  const impactKey = JSON.stringify(impact?.freshness || null);
  const impactActor = (workspace?.currentUser?.loginId || impactActorEntry).trim();
  const impactAcknowledged = Boolean(impact?.freshness && acknowledgedImpact?.key === impactKey
    && acknowledgedImpact.actor === impactActor && impactActor);
  const centralReportReady = Boolean(!draftStale && !impactLoading && impact?.providerState === 'AVAILABLE'
    && impactMatchesCurrentDraft && impact.coverage === 'COMPLETE'
    && impact.decision && impact.decision !== 'BLOCKED'
    && (!impact.acknowledgementRequired || impactAcknowledged));
  const centralPublishedRevision = published?.publishedRevision || published?.revision;
  const centralActiveRevision = published?.activeRevision || impact?.activationStatus?.activeRevision;
  const centralActivationState = published?.activationState || impact?.activationStatus?.state;
  const centralPublicationIsActive = Boolean(centralTarget && published && centralActivationState === 'ACTIVE'
    && centralPublishedRevision && centralActiveRevision === centralPublishedRevision);

  useLayoutEffect(() => {
    if (previousTargetKeyRef.current === targetKey) return;
    previousTargetKeyRef.current = targetKey;
    initialCandidateAppliedRef.current = '';
    controllerRef.current?.abort();
    actionControllerRef.current?.abort();
    impactControllerRef.current?.abort(); impactControllerRef.current = null;
    setSource(null); setDraft(null); setPublished(null); setImpact(null); setAcknowledgedImpact(null); setImpactActorEntry(''); setDiagnostics([]);
    setContent(''); setMode('view'); setCreatePath(''); setMovePath(''); setEditorBaseSourceRevision(''); setImpactLoading(false);
    setPendingOperations([]); setRelatedPath(''); setRelatedContent(''); setRelatedOperation('create');
    setPendingBaseSourceRevision(''); setPendingRegistryRevision(undefined);
    setHistoryOpen(false);
    setSuspended(false); setRecoveryPending(false); recoveryRequiredRef.current = false; suspendedRef.current = false;
    setError(''); setStatus(''); setBusy(false); setLoading(false);
    setAccess(centralTarget && !canManageCentral ? 'denied' : 'checking');
  }, [targetKey, centralTarget, canManageCentral]);

  useEffect(() => {
    if (!initialCandidate || !source || access !== 'authorized' || initialCandidateAppliedRef.current === initialCandidate.key) return;
    initialCandidateAppliedRef.current = initialCandidate.key;
    if (source.baseSourceRevision !== initialCandidate.baseSourceRevision
        || source.registryRevision !== initialCandidate.registryRevision) {
      setError('The Package source changed after the guided candidate was prepared. Reload the Manifest and create the candidate again.');
      return;
    }
    if (draft || pendingOperations.length) {
      setError('Finish or discard the current draft before loading the guided candidate.');
      return;
    }
    setContent(source.text); setMode('view'); setPendingOperations(initialCandidate.operations);
    setPendingBaseSourceRevision(initialCandidate.baseSourceRevision);
    setPendingRegistryRevision(initialCandidate.registryRevision);
    setStatus('The guided Testdata candidate includes the descriptor and Package Manifest as one atomic source draft.');
  }, [initialCandidate, source, access, draft, pendingOperations.length]);

  useEffect(() => {
    if (!registerUnsavedChanges) return;
    return registerUnsavedChanges(isDirty, centralTarget ? 'Central Configuration source draft' : 'Package source draft');
  }, [registerUnsavedChanges, isDirty, centralTarget]);
  useEffect(() => {
    onDirtyChange?.(isDirty);
    return () => onDirtyChange?.(false);
  }, [onDirtyChange, isDirty]);

  useEffect(() => {
    const lockWorkspace = centralTarget ? canManageCentral : access === 'authorized';
    workspace?.setWorkspaceEditorLock?.(Boolean(lockWorkspace && isDirty));
    return () => workspace?.setWorkspaceEditorLock?.(false);
  }, [workspace?.setWorkspaceEditorLock, centralTarget, canManageCentral, access, isDirty]);

  useEffect(() => {
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    const targetChanged = previousTargetKeyRef.current !== targetKey;
    if (targetChanged) {
      previousTargetKeyRef.current = targetKey;
      setDraft(null); setPublished(null); setContent(''); setMode('view'); setCreatePath(''); setMovePath(''); setEditorBaseSourceRevision('');
    }
    setSource(null); setImpact(null); setImpactLoading(false); setDiagnostics([]); setError(''); setHistoryOpen(false);
    if (centralTarget && !canManageCentral) {
      actionControllerRef.current?.abort();
      setDraft(null); setPublished(null); setContent(''); setMode('view'); setPendingOperations([]);
      setRecoveryPending(false); setSuspended(false); recoveryRequiredRef.current = false; suspendedRef.current = false;
      setAccess('denied'); setLoading(false);
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
      if (targetChanged || (!recoveryRequiredRef.current && !hasLocalEditorChanges)) setContent(value.text);
      setAccess('authorized'); setError('');
      if (recoveryRequiredRef.current) setRecoveryPending(true);
      else if (!suspendedRef.current || document.visibilityState !== 'hidden') {
        suspendedRef.current = false; setSuspended(false);
      }
    }).catch(reason => {
      if (controller.signal.aborted) return;
      const status = statusOf(reason);
      setSource(null);
      if (!recoveryRequiredRef.current || status === 403) setContent('');
      setAccess(status === 403 ? 'denied' : 'unavailable');
      if (status === 403) {
        actionControllerRef.current?.abort();
        setDraft(null); setImpact(null); setPublished(null); setPendingOperations([]); setCreatePath(''); setMovePath('');
        setRelatedPath(''); setRelatedContent(''); setPendingBaseSourceRevision(''); setPendingRegistryRevision(undefined);
        setRecoveryPending(false); setSuspended(false);
        recoveryRequiredRef.current = false; suspendedRef.current = false;
      }
      if (status !== 403) setError(failureText(reason, 'load complete source'));
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => {
      controller.abort();
      if (controllerRef.current === controller) controllerRef.current = null;
      impactControllerRef.current?.abort(); impactControllerRef.current = null;
    };
  }, [api, targetKey, refreshKey, canManageCentral, packageTarget?.packageId, centralTarget?.path]);

  useEffect(() => {
    const suspendPrivilegedDisplay = () => {
      recoveryRequiredRef.current = isDirtyRef.current;
      setRecoveryPending(false);
      suspendedRef.current = true;
      setSuspended(true);
      controllerRef.current?.abort();
      impactControllerRef.current?.abort(); impactControllerRef.current = null;
      setSource(null); setImpact(null); setAcknowledgedImpact(null); setLoading(false);
      setHistoryOpen(false);
      if (!recoveryRequiredRef.current) {
        setDraft(null); setPublished(null); setContent(''); setMode('view'); setCreatePath(''); setMovePath('');
        setEditorBaseSourceRevision(''); setPendingOperations([]); setRelatedPath(''); setRelatedContent('');
      }
      setImpactLoading(false);
      setStatus('Source is hidden while the page is in the background. Reauthorize before resuming.');
      setAccess('checking'); setLoading(false);
    };
    const onPageShow = () => setRefreshKey(value => value + 1);
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') suspendPrivilegedDisplay();
      else setRefreshKey(value => value + 1);
    };
    window.addEventListener('pagehide', suspendPrivilegedDisplay);
    window.addEventListener('pageshow', onPageShow);
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      window.removeEventListener('pagehide', suspendPrivilegedDisplay);
      window.removeEventListener('pageshow', onPageShow);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, []);

  useEffect(() => () => {
    controllerRef.current?.abort();
    actionControllerRef.current?.abort();
    impactControllerRef.current?.abort(); impactControllerRef.current = null;
  }, []);

  const clearAfterRevocation = useCallback(() => {
    controllerRef.current?.abort();
    actionControllerRef.current?.abort();
    impactControllerRef.current?.abort(); impactControllerRef.current = null;
    setSource(null); setDraft(null); setPublished(null); setImpact(null); setAcknowledgedImpact(null); setImpactLoading(false);
    setContent(''); setMode('view'); setCreatePath(''); setMovePath(''); setEditorBaseSourceRevision(''); setAccess('denied');
    setHistoryOpen(false);
    setPendingOperations([]); setRelatedPath(''); setRelatedContent(''); setRecoveryPending(false); setSuspended(false);
    setPendingBaseSourceRevision(''); setPendingRegistryRevision(undefined);
    recoveryRequiredRef.current = false; suspendedRef.current = false;
  }, []);

  async function withBusy(action: string, run: (signal: AbortSignal) => Promise<void>) {
    const controller = new AbortController();
    actionControllerRef.current?.abort(); actionControllerRef.current = controller;
    setBusy(true); setError(''); setStatus(''); setDiagnostics([]);
    try { await run(controller.signal); }
    catch (reason) {
      if (controller.signal.aborted) return;
      setError(failureText(reason, action));
      if (statusOf(reason) === 422) setDiagnostics(diagnosticsOf(reason));
      if (statusOf(reason) === 403) clearAfterRevocation();
    } finally {
      if (actionControllerRef.current === controller) {
        actionControllerRef.current = null;
        setBusy(false);
      }
    }
  }

  function getScopeId() {
    return target.domain === 'PACKAGE_SOURCE' ? target.packageId : 'central-runtime-config';
  }

  async function stageRestoreFromHistory(snapshotId: string, currentSourceRevision: string, registryRevision?: string) {
    if (!api.stageSourceRestore) { setError('This Server does not provide source history restore.'); return; }
    if (!source || currentSourceRevision !== source.baseSourceRevision
        || packageTarget && registryRevision !== source.registryRevision) {
      setError('The source changed after this history page loaded. Reload the current source and history before staging a restore.');
      setHistoryOpen(false); setRefreshKey(value => value + 1); return;
    }
    if (draft || pendingOperations.length || mode !== 'view' || currentEditorHasUnqueuedChanges) {
      setError('Finish or discard the current editor and draft before staging a historical restore.'); return;
    }
    const scopeLabel = packageTarget ? `Package ${packageTarget.packageId}` : 'Global Runtime Configuration';
    if (!await confirmAction({ title: 'Stage historical source restore?',
      message: `Stage the selected ${scopeLabel} snapshot as a new draft. This includes every difference in this source scope; published source stays unchanged until validation, review, and publication.`,
      confirmLabel: 'Stage restore draft' })) return;
    await withBusy('stage the historical source restore', async signal => {
      const restored = await api.stageSourceRestore!(target.domain, getScopeId(), snapshotId,
        currentSourceRevision, packageTarget ? registryRevision : undefined, signal);
      if (signal.aborted) return;
      setDraft(restored); setImpact(null); setAcknowledgedImpact(null); setPublished(null);
      setMode('view'); setPendingOperations([]); setPendingBaseSourceRevision(''); setPendingRegistryRevision(undefined);
      setHistoryOpen(false);
      setStatus(`Historical source restore is staged as draft ${restored.changeSetId}. Review every changed file, then Validate, Review, and Publish.`);
    });
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
      setDraft(result); setImpact(null); setAcknowledgedImpact(null); setPublished(null); setMode('view');
      setPendingOperations([]); setRelatedPath(''); setRelatedContent(''); setRelatedOperation('create');
      setPendingBaseSourceRevision(''); setPendingRegistryRevision(undefined);
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
    if (action === 'publish' && !await confirmAction({ title: 'Publish reviewed source?',
      message: `Publish the reviewed draft for ${displayedPath}. This changes the Package source${centralTarget ? ' used across Packages' : ''}.`,
      confirmLabel: 'Publish source' })) return;
    if (action === 'discard' && !await confirmAction({ title: 'Discard source draft?',
      message: `Discard draft ${draft.changeSetId} and remove its staged source changes. Published source remains unchanged.`,
      confirmLabel: 'Discard draft', destructive: true })) return;
    await withBusy(`${action} the source draft`, async signal => {
      const acknowledgement = action === 'publish' && centralTarget && impact?.freshness
        ? await createImpactAcknowledgement(impact.freshness, impactActor, impact.acknowledgementRequired === true) : undefined;
      if (signal.aborted) return;
      const result = await api.sourceChangeSetAction!(target.domain, getScopeId(), draft.changeSetId, action, signal, acknowledgement);
      if (signal.aborted) return;
      if (action === 'publish' && 'published' in result) {
        const publication = result as SourcePublishResult;
        setPublished(publication); setDraft(null); setImpact(null); setAcknowledgedImpact(null); setSource(null); setContent(''); setMode('view');
        setPendingOperations([]); recoveryRequiredRef.current = false; setRecoveryPending(false);
        setPendingBaseSourceRevision(''); setPendingRegistryRevision(undefined);
        const activationStatus = publication.activationState || 'unavailable';
        const auditNote = publication.auditConfirmed === false ? ' The durable audit record is pending confirmation.' : '';
        setStatus(centralTarget
          ? (activationStatus === 'ACTIVE' && (publication.activeRevision || '') === (publication.publishedRevision || publication.revision)
            ? 'Runtime Config published and active.'
            : 'Runtime Config was published but is not confirmed active. Keep new work paused and repair activation before retrying.')
            + auditNote
          : 'Package source published successfully.' + auditNote);
        onPublished?.(publication);
        setRefreshKey(value => value + 1);
      } else if (action === 'discard') {
        setDraft(null); setImpact(null); setAcknowledgedImpact(null); setContent(source?.text || ''); setPendingOperations([]);
        setPendingBaseSourceRevision(''); setPendingRegistryRevision(undefined);
        recoveryRequiredRef.current = false; setRecoveryPending(false); setSuspended(false); suspendedRef.current = false;
        setStatus('Draft discarded. Published source is unchanged.');
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
    if (!cursor) setAcknowledgedImpact(null);
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
      if (!impactFreshnessMatches(report, changeSetId, expectedRevision)) {
        setImpact({ ...report, providerState: 'STALE', decision: null });
        setError('The Impact Report is missing or does not match the current draft and source revision. Refresh the source, validate, and review again. Central publication is disabled.');
        return;
      }
      if (report.coverage !== 'COMPLETE') {
        setError('The Impact Report does not cover every required dependency. Central publication is disabled until the Server returns complete coverage.'); return;
      }
      if (report.decision === 'BLOCKED') {
        setError('The Server blocked this central Config candidate. Review the impact result and diagnostics; publication is disabled.'); return;
      }
      if (report.acknowledgementRequired && report.decision === 'REQUIRES_REVIEW') {
        setStatus('Review and explicitly acknowledge this current Config impact report before publishing.');
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
    actionControllerRef.current?.abort();
    actionControllerRef.current = uploadController;
    setBusy(true); setError(''); setStatus('Reading and validating the UTF-8 source upload…');
    try {
      const bytes = new Uint8Array(await file.arrayBuffer());
      if (uploadController.signal.aborted) return;
      const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
      if (uploadController.signal.aborted) return;
      setContent(text);
      const operation: SourceOperation = { operation: source?.present && mode !== 'create' ? 'replace' : 'create', path, content: text };
      if (packageTarget?.allowFileOperations) {
        if (addPendingOperation(operation) && source) { setMode('view'); setContent(source.text); setCreatePath(''); setEditorBaseSourceRevision(''); }
      }
      else await stage([operation]);
    } catch {
      if (!uploadController.signal.aborted) setError('The selected source file is not valid UTF-8 text. Testcase workbooks must use the paired upload flow.');
    } finally {
      if (actionControllerRef.current === uploadController) { actionControllerRef.current = null; setBusy(false); }
    }
  }

  async function handlePackageZipImport(event: React.ChangeEvent<HTMLInputElement>) {
    const archive = event.currentTarget.files?.[0];
    event.currentTarget.value = '';
    if (!archive || !packageTarget) return;
    if (archive.size > MAX_PACKAGE_ZIP_BYTES) {
      setError('Package ZIP exceeds the 16 MiB compressed upload limit.'); return;
    }
    if (!api.stagePackageSourceArchive) {
      setError('This Server does not provide Package ZIP import.'); return;
    }
    if (draft || pendingOperations.length || currentEditorHasUnqueuedChanges) {
      setError('Publish, discard, or stage the current draft before importing another Package ZIP.'); return;
    }
    if (!await confirmAction({ title: 'Import Package ZIP?',
      message: `Stage ${archive.name} as a private Package draft. It will not change published files until validation, review, and publication.`,
      confirmLabel: 'Import as draft' })) return;
    const controller = new AbortController();
    actionControllerRef.current?.abort();
    actionControllerRef.current = controller;
    setBusy(true); setError(''); setStatus('Checking Package ZIP entries and staging the authorized files…');
    try {
      const staged = await api.stagePackageSourceArchive(packageTarget.packageId, archive, controller.signal);
      if (controller.signal.aborted) return;
      setDraft(staged); setPublished(null); setImpact(null); setDiagnostics([]); setMode('view');
      setStatus(`Imported ${archive.name} as draft ${staged.changeSetId}. Published files are unchanged until Validate, Review, and Publish complete.`);
    } catch (reason) {
      if (!controller.signal.aborted) setError(failureText(reason, 'import Package ZIP'));
    } finally {
      if (actionControllerRef.current === controller) { actionControllerRef.current = null; setBusy(false); }
    }
  }

  function addPendingOperation(operation: SourceOperation): boolean {
    if (!safeLogicalPath(operation.path) || (operation.targetPath && !safeLogicalPath(operation.targetPath))) {
      setError('Use an in-package relative path without empty, dot, parent, or absolute path segments.'); return false;
    }
    if (pendingOperations.length >= 32) { setError('A candidate can contain at most 32 file changes.'); return false; }
    const paths = new Set(pendingOperations.flatMap(item => [item.path, item.targetPath].filter(Boolean)));
    if (paths.has(operation.path) || operation.targetPath && paths.has(operation.targetPath)) {
      setError('Each candidate path can be changed only once. Remove the earlier operation before adding this path.'); return false;
    }
    if (!pendingOperations.length && source) {
      setPendingBaseSourceRevision(source.baseSourceRevision);
      setPendingRegistryRevision(source.registryRevision);
    }
    setPendingOperations(current => [...current, operation]);
    setStatus(`Added ${operation.operation} ${operation.path} to the local candidate. Stage the complete candidate when related files are ready.`);
    setError('');
    return true;
  }

  function addRelatedFile() {
    if (!relatedContent.trim()) { setError('Add file content before adding it to the candidate.'); return; }
    if (addPendingOperation({ operation: relatedOperation, path: relatedPath.trim(), content: relatedContent })) {
      setRelatedPath(''); setRelatedContent(''); setRelatedOperation('create');
    }
  }

  function stageCandidate() {
    if (!pendingOperations.length) { setError('Add at least one file change to the candidate.'); return; }
    if (candidateStale) { setError('The Package source or registration changed while these candidate files were being edited. Discard and re-add them against the current revision.'); return; }
    if (currentEditorHasUnqueuedChanges) { setError('Add or discard the current editor changes before staging this candidate.'); return; }
    void stage(pendingOperations);
  }

  async function saveTextDraft() {
    if (editorStale) { setError('Your editor text is preserved, but the source revision changed. Copy the changes you need, discard this editor, then edit the latest source and reapply them.'); return; }
    if (mode === 'create') {
      const path = createPath.trim();
      if (!safeLogicalPath(path)) { setError('Enter a valid relative path, such as templates/new-template/template.yaml.'); return; }
      if (!content.trim()) { setError('Add source content before creating a draft.'); return; }
      const operation: SourceOperation = { operation: 'create', path, content };
      if (packageTarget?.allowFileOperations) {
        if (addPendingOperation(operation) && source) { setMode('view'); setContent(source.text); setCreatePath(''); setEditorBaseSourceRevision(''); }
      }
      else void stage([operation]);
      return;
    }
    if (!source || content === source.text) { setStatus('There are no source changes to save.'); return; }
    const operation = source.present ? 'replace' : 'create';
    if (!await confirmAction({ title: packageTarget?.allowFileOperations ? 'Add source to candidate?' : 'Stage source draft?',
      message: packageTarget?.allowFileOperations
        ? `Add ${source.logicalPath} to the local candidate. Related Package files can be added before staging.`
        : `Create a draft for ${source.logicalPath}. Published source remains unchanged until you publish.`,
      confirmLabel: packageTarget?.allowFileOperations ? 'Add to candidate' : 'Stage draft' })) return;
    if (packageTarget?.allowFileOperations) {
      const queuedIndex = pendingOperations.findIndex(item => item.path === source.logicalPath);
      if (queuedIndex >= 0) {
        setPendingOperations(current => current.map((item, index) => index === queuedIndex
          ? { operation, path: source.logicalPath, content } : item));
        setStatus(`Updated ${source.logicalPath} in the local candidate. Stage the complete candidate when related files are ready.`);
        setMode('view'); setContent(source.text); setEditorBaseSourceRevision('');
        return;
      }
      if (addPendingOperation({ operation, path: source.logicalPath, content })) {
        setMode('view'); setContent(source.text); setEditorBaseSourceRevision('');
      }
    }
    else void stage([{ operation, path: source.logicalPath, content }]);
  }

  async function beginDelete() {
    if (!source || !source.present) return;
    if (mode === 'edit' && content !== source.text) { setError('Add or discard the local editor changes before deleting this source.'); return; }
    if (!await confirmAction({ title: 'Add source deletion?',
      message: `Add deletion of ${source.logicalPath} to the Package candidate. Review will list known references before publication.`,
      confirmLabel: 'Add deletion', destructive: true })) return;
    if (addPendingOperation({ operation: 'delete', path: source.logicalPath })) { setMode('view'); setContent(source.text); setEditorBaseSourceRevision(''); }
  }
  async function beginMove() {
    if (!source || !source.present) return;
    if (mode === 'edit' && content !== source.text) { setError('Add or discard the local editor changes before moving this source.'); return; }
    if (!safeLogicalPath(movePath)) { setError('Enter a valid new relative path.'); return; }
    if (!await confirmAction({ title: 'Add source move?',
      message: `Move ${source.logicalPath} to ${movePath} in the Package candidate. Known inbound references will be reviewed.`,
      confirmLabel: 'Add move' })) return;
    if (addPendingOperation({ operation: 'move', path: source.logicalPath, targetPath: movePath })) { setMode('view'); setContent(source.text); setEditorBaseSourceRevision(''); }
  }

  const sourceEditor = source && !draft && (mode === 'edit' || mode === 'view')
    ? <CodeEditor value={content} language={detectCodeLanguage(source.logicalPath || source.format)}
        label={`${mode === 'edit' ? 'Edit' : 'View'} authorized source ${source.logicalPath}`}
        readOnly={mode !== 'edit' || busy} onChange={setContent} maxCharacters={Math.max(200_000, source.text.length, content.length)} /> : null;

  const workflowCurrent = published ? 'publish' : draft
    ? draft.state === 'reviewed' ? 'publish' : draft.state === 'validated' ? 'review' : 'validate'
    : pendingOperations.length ? 'stage' : mode !== 'view' ? 'edit' : 'view';
  const workflowLabels = workspace?.uiLocale === 'zh-Hant'
    ? ['檢視', '編輯', '建立草稿', '驗證草稿', '檢閱變更 / 影響', '發布']
    : ['View', 'Edit', 'Stage draft', 'Validate draft', 'Review changes / impact', 'Publish'];
  const workflowIds = ['view', 'edit', 'stage', 'validate', 'review', 'publish'];
  const workflowIndex = workflowIds.indexOf(workflowCurrent);
  const workflowSteps = workflowIds.map((id, index) => {
    let state: AuthoringStepState = published || index < workflowIndex ? 'complete'
      : index === workflowIndex ? 'current' : 'upcoming';
    let reason: string | undefined;
    if (id === 'publish' && centralTarget && !centralReportReady && !published) {
      state = 'blocked';
      reason = workspace?.uiLocale === 'zh-Hant' ? '需要最新且可用的 Config Impact 報告。' : 'A current, available Config Impact report is required.';
    } else if ((draftStale || editorStale || candidateStale) && index >= workflowIndex && !published) {
      state = 'blocked';
      reason = workspace?.uiLocale === 'zh-Hant' ? '來源 revision 已過期；請重新載入並重新建立草稿。' : 'The source revision is stale. Reload and stage a fresh draft.';
    }
    return { id, label: workflowLabels[index], state, reason };
  });

  async function reloadSource() {
    if (draft) { setStatus(''); setRefreshKey(value => value + 1); return; }
    const hasLocalChanges = Boolean(mode === 'edit' && source && content !== source.text || mode === 'create' && (content.length || createPath.trim()) || pendingOperations.length);
    if (hasLocalChanges && !await confirmAction({ title: 'Discard local source changes?',
      message: 'Reloading removes the current local editor text, new path, and queued candidate operations. Server drafts are not affected.',
      confirmLabel: 'Discard and reload', destructive: true })) return;
    if (hasLocalChanges) { setMode('view'); setContent(''); setCreatePath(''); setEditorBaseSourceRevision(''); setPendingOperations([]); setPendingBaseSourceRevision(''); setPendingRegistryRevision(undefined); setRelatedPath(''); setRelatedContent(''); }
    setStatus('');
    setRefreshKey(value => value + 1);
  }

  if (centralTarget && !canManageCentral) return null;

  if (suspended) return <section className="att-source-authoring" aria-label="Source authoring reauthorization">
    {confirmationDialog}
    <h2>Source hidden while away</h2>
    {access === 'checking' && <p role="status" aria-busy="true">Rechecking source permission before showing any source or draft.</p>}
    {access === 'unavailable' && <><p role="alert">{error || 'Source reauthorization failed. Retry before resuming this work.'}</p>
      <button type="button" onClick={() => setRefreshKey(value => value + 1)} disabled={loading}>Retry source permission check</button></>}
    {access === 'authorized' && recoveryPending && source && <>
      <p role="status">Permission was rechecked. Resume the in-memory editor and draft, or discard the recovered work.</p>
      <button type="button" onClick={() => { recoveryRequiredRef.current = false; suspendedRef.current = false; setRecoveryPending(false); setSuspended(false); }} disabled={busy}>Resume work</button>
      <button type="button" onClick={async () => {
        if (draft) { void act('discard'); return; }
        if (!await confirmAction({ title: 'Discard recovered local changes?',
          message: 'Remove the recovered local editor text and queued operations. Published source remains unchanged.',
          confirmLabel: 'Discard recovered work', destructive: true })) return;
        setContent(source.text); setMode('view'); setCreatePath(''); setMovePath(''); setPendingOperations([]);
        setPendingBaseSourceRevision(''); setPendingRegistryRevision(undefined);
        setRelatedPath(''); setRelatedContent(''); setEditorBaseSourceRevision('');
        recoveryRequiredRef.current = false; suspendedRef.current = false; setRecoveryPending(false); setSuspended(false);
      }} disabled={busy}>Discard recovered work</button>
    </>}
  </section>;

  return <section className={`att-source-authoring ${target.domain === 'CENTRAL_RUNTIME_CONFIG' ? 'is-central' : ''}`} aria-label={target.label || 'Source authoring'}>
    {confirmationDialog}
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
        <button type="button" className="button button-quiet" onClick={() => setHistoryOpen(value => !value)}
          disabled={access !== 'authorized' || !source || loading || busy} aria-expanded={historyOpen}>
          {historyOpen ? 'Close History / Restore' : 'History / Restore'}
        </button>
        {packageTarget && access === 'authorized' && api.stagePackageSourceArchive && <>
          <button type="button" className="button button-quiet" onClick={() => packageZipInputRef.current?.click()}
            disabled={busy || loading || Boolean(draft) || pendingOperations.length > 0 || currentEditorHasUnqueuedChanges}>Import Package ZIP</button>
          <input ref={packageZipInputRef} type="file" className="att-source-file-input" aria-label="Import Package ZIP"
            accept=".zip,application/zip" onChange={event => void handlePackageZipImport(event)} />
        </>}
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
    {published && <details aria-label="Advanced publication provenance">
      <summary>Advanced publication provenance</summary>
      <dl>
        <div><dt>Published revision</dt><dd><code>{published.publishedRevision || published.revision}</code></dd></div>
        {centralTarget && <>
          <div><dt>Active revision</dt><dd><code>{published.activeRevision || 'Unavailable'}</code></dd></div>
          <div><dt>Activation state</dt><dd>{published.activationState || 'Unavailable'}</dd></div>
          {published.activationFailureCode && <div><dt>Activation failure</dt><dd>{published.activationFailureCode}</dd></div>}
        </>}
        {published.validationRevision && <div><dt>Validation revision</dt><dd><code>{published.validationRevision}</code></dd></div>}
        {published.configGateRevision && <div><dt>Config gate revision</dt><dd><code>{published.configGateRevision}</code></dd></div>}
      </dl>
    </details>}

    {access === 'authorized' && source && <>
      <AuthoringWorkflow label={workspace?.uiLocale === 'zh-Hant' ? '來源發布工作流程' : 'Source publication workflow'} steps={workflowSteps} />
      {draftStale && <p className="att-error" role="alert">This draft uses an older source or Package registration revision. Compare the latest authorized source below, then discard this draft and stage a new one.</p>}
      {editorStale && <p className="att-error" role="alert">The Package source changed after these local edits began. Your editor text is preserved, but it cannot be rebased safely. Copy the changes you need, discard this editor, then edit the latest source and reapply them.</p>}
      <div className="att-source-revision-strip" aria-label="Source publication status">
        <span><strong>{draft ? 'Draft staged' : published
          ? centralTarget ? centralPublicationIsActive ? 'Published and active' : 'Published · activation needs attention'
            : 'Published successfully'
          : source.present ? 'Published source' : 'Source not published'}</strong></span>
        {draft && <span>Published source is unchanged.</span>}
        {centralTarget && published && !centralPublicationIsActive && <span role="alert">
          Published Runtime Config is not confirmed active. Keep new work paused until an administrator repairs activation.
          {(published.activationFailureMessage || impact?.activationStatus?.failureMessage) && <> {published.activationFailureMessage || impact?.activationStatus?.failureMessage}</>}
        </span>}
        <details aria-label="Advanced source revision provenance">
          <summary>Advanced revision provenance</summary>
          <dl>
            <div><dt>Source ETag</dt><dd><code>{source.sourceEtag}</code></dd></div>
            <div><dt>Base revision</dt><dd><code>{source.baseSourceRevision}</code></dd></div>
            {packageSourceIdentity && <div><dt>Package source revision</dt><dd><code>{packageSourceIdentity.revision}</code></dd></div>}
            {executionRevisionIdentity && <div><dt>Execution revision</dt><dd><code>{executionRevisionIdentity.revision}</code></dd></div>}
            {source.registryRevision && <div><dt>Package registration revision</dt><dd><code>{source.registryRevision}</code></dd></div>}
            {draft && <div><dt>Draft revision</dt><dd><code>{draft.validationRevision || draft.baseSourceRevision}</code></dd></div>}
            {published && <div><dt>Published revision</dt><dd><code>{centralPublishedRevision}</code></dd></div>}
            {published && centralTarget && <div><dt>Active revision</dt><dd><code>{centralActiveRevision || 'Unavailable'}</code></dd></div>}
          </dl>
        </details>
      </div>

      {historyOpen && <SourceHistoryPanel api={api} domain={target.domain} scopeId={getScopeId()} locale={workspace?.uiLocale || 'en'}
        disabled={busy || loading || Boolean(draft) || pendingOperations.length > 0 || mode !== 'view' || currentEditorHasUnqueuedChanges}
        onRestore={stageRestoreFromHistory} onAccessRevoked={clearAfterRevocation} />}

      {!draft && <>
        {source.present === false && <p className="att-source-authoring-note">This approved source path does not exist yet. Create publishes only after the draft is validated and reviewed.</p>}
        {isBinaryWorkbook(source.logicalPath) && <p className="att-source-authoring-note">Binary workbook bytes cannot be opened in CodeEditor. Use the explicit Testcase workbook upload flow for workbook changes.</p>}
        <div className="att-source-authoring-toolbar" role="group" aria-label="Source actions">
          {mode === 'view' && <button type="button" onClick={() => { setMode('edit'); setContent(source.text); setEditorBaseSourceRevision(source.baseSourceRevision); }} disabled={busy || isBinaryWorkbook(source.logicalPath)}>{source.present ? 'Edit' : 'Create source'}</button>}
          {mode === 'edit' && <button type="button" onClick={async () => {
            if (content !== source.text && !await confirmAction({ title: 'Discard local source edits?',
              message: 'Discard this local editor text. Published source is unchanged.',
              confirmLabel: 'Discard edits', destructive: true })) return;
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
          {mode === 'edit' && <button type="button" className="button button-primary" onClick={saveTextDraft} disabled={busy || content === source.text || editorStale}>
            {packageTarget?.allowFileOperations ? 'Add current file to candidate' : 'Save Draft'}
          </button>}
          <input ref={fileInputRef} type="file" className="att-source-file-input" aria-label="Upload source file"
            accept=".yaml,.yml,.xml,.json,.csv,.tsv,.txt,.sql,.properties,.md" onChange={event => void handleUpload(event)} />
        </div>
        {mode === 'edit' && <p className="att-source-authoring-note">The editor contains the exact authorized source shown in View. Source bytes remain in memory only and are never placed in inspection cache or browser storage.</p>}
        {mode === 'edit' && editorStale && <details className="att-source-current-copy"><summary>Latest authorized source</summary>
          <CodeEditor value={source.text} language={detectCodeLanguage(source.logicalPath || source.format)} label={`Latest authorized source ${source.logicalPath}`} readOnly maxCharacters={Math.max(200_000, source.text.length)} />
        </details>}
        {sourceEditor}
        {mode === 'create' && <div className="att-source-create-form">
          <label>New logical path <input value={createPath} onChange={event => setCreatePath(event.currentTarget.value)} placeholder="templates/new-template/template.yaml" disabled={busy} /></label>
          <CodeEditor value={content} language={detectCodeLanguage(createPath)} label="New source content" readOnly={busy} onChange={setContent} maxCharacters={Math.max(200_000, content.length)} />
          {editorStale && <details className="att-source-current-copy"><summary>Latest authorized source</summary>
            <CodeEditor value={source.text} language={detectCodeLanguage(source.logicalPath || source.format)} label={`Latest authorized source ${source.logicalPath}`} readOnly maxCharacters={Math.max(200_000, source.text.length)} />
          </details>}
          <div><button type="button" onClick={() => { setMode('view'); setContent(source.text); setCreatePath(''); setEditorBaseSourceRevision(''); }} disabled={busy}>Cancel</button>
            <button type="button" className="button button-primary" onClick={saveTextDraft} disabled={busy || editorStale || !createPath || !content.trim()}>
              {packageTarget?.allowFileOperations ? 'Add current file to candidate' : 'Save Draft'}
            </button>
            <button type="button" onClick={() => fileInputRef.current?.click()} disabled={busy}>Upload new source</button></div>
        </div>}
      </>}

      {!draft && packageTarget?.allowFileOperations && <section className="att-source-candidate" aria-label="Package file change candidate">
        <h3>Candidate file changes</h3>
        <p>Add the current file and related Package files here, then stage them together for one validation and review.</p>
        {candidateStale && <p role="alert">These candidate files use an older Package source or registration revision. Discard and add them again against the current source.</p>}
        {pendingOperations.length > 0 && <ol aria-label="Queued candidate files">{pendingOperations.map((operation, index) => <li key={`${operation.path}:${index}`}>
          <strong>{operation.operation.toUpperCase()}</strong> <code>{operation.path}</code>{operation.targetPath && <> → <code>{operation.targetPath}</code></>}
          <button type="button" onClick={() => {
            const remaining = pendingOperations.filter((_, itemIndex) => itemIndex !== index);
            setPendingOperations(remaining);
            if (!remaining.length) { setPendingBaseSourceRevision(''); setPendingRegistryRevision(undefined); }
          }} disabled={busy}>Remove</button>
        </li>)}</ol>}
        {currentEditorHasUnqueuedChanges && <p role="alert">Add or discard the current editor changes before staging this candidate.</p>}
        <fieldset disabled={busy}>
          <legend>Add a related file</legend>
          <label>Operation<select aria-label="Related file operation" value={relatedOperation} onChange={event => setRelatedOperation(event.currentTarget.value as 'create' | 'replace')}>
            <option value="create">Create</option><option value="replace">Replace</option>
          </select></label>
          <label>Package-relative path<input aria-label="Related file path" value={relatedPath} onChange={event => setRelatedPath(event.currentTarget.value)} placeholder="templates/checkout/payload.yaml" /></label>
          <label>File content<textarea aria-label="Related file content" value={relatedContent} onChange={event => setRelatedContent(event.currentTarget.value)} rows={8} /></label>
          <button type="button" onClick={addRelatedFile}>Add related file</button>
        </fieldset>
        <button type="button" className="button button-primary" onClick={stageCandidate}
          disabled={busy || !pendingOperations.length || candidateStale || currentEditorHasUnqueuedChanges}>
          {workspace?.uiLocale === 'zh-Hant'
            ? `建立候選草稿${pendingOperations.length ? `（${pendingOperations.length} 個檔案）` : ''}`
            : `Stage candidate${pendingOperations.length ? ` (${pendingOperations.length} files)` : ''}`}
        </button>
      </section>}

      {draft && <section className="att-source-review" aria-label="Changed source review">
        <div className="att-source-review-heading"><div><h3>Draft {draft.changeSetId}</h3><p>{draft.state} · expires {draft.expiresAt || 'per Server policy'} · published source remains unchanged</p></div></div>
        <ChangedFiles files={draft.changedFiles || []} locale={workspace?.uiLocale || 'en'} />
        {draftStale && <details className="att-source-current-copy"><summary>Latest authorized source</summary>
          <CodeEditor value={source.text} language={detectCodeLanguage(source.logicalPath || source.format)} label={`Latest authorized source ${source.logicalPath}`} readOnly maxCharacters={Math.max(200_000, source.text.length)} />
        </details>}
        <div className="att-source-authoring-toolbar">
          <button type="button" onClick={() => void act('validate')} disabled={busy || loading || draftStale || draft.state === 'reviewed'}>Validate</button>
          <button type="button" onClick={() => void act('review')} disabled={busy || loading || draftStale || draft.state !== 'validated'}>Review changed files{centralTarget ? ' / Impact' : ''}</button>
          <button type="button" className="button button-primary" onClick={() => void act('publish')}
            disabled={busy || loading || draftStale || draft.state !== 'reviewed' || Boolean(centralTarget && !centralReportReady)}>Publish</button>
          <button type="button" onClick={() => void act('discard')} disabled={busy}>Discard Draft</button>
        </div>
        {centralTarget && impact && <ImpactReview report={impact} loading={impactLoading} filter={impactFilter} onFilter={setImpactFilter}
          actor={impactActor} actorEntry={impactActorEntry} onActorEntry={setImpactActorEntry} actorFromSession={Boolean(workspace?.currentUser?.loginId)}
          acknowledged={impactAcknowledged} onAcknowledged={checked => setAcknowledgedImpact(checked && impact?.freshness && impactActor ? { key: impactKey, actor: impactActor } : null)}
          canAcknowledge={impactMatchesCurrentDraft && impact.providerState === 'AVAILABLE' && impact.coverage === 'COMPLETE' && impact.decision !== 'BLOCKED'}
          onLoadMore={() => impact.schedules?.nextCursor && void loadImpact(draft.changeSetId, impact.schedules.nextCursor, true)} />}
        {centralTarget && !impact && <p className="att-source-authoring-note">Central publication requires a current #335 Impact Report. Review will load the server report and block publication when its provider is unavailable or stale.</p>}
      </section>}

    </>}
  </section>;
}

const SOURCE_HISTORY_PAGE_SIZE = 20;
function isSafeSnapshotId(value: unknown): value is string {
  return typeof value === 'string' && /^[a-f0-9]{64}$/i.test(value);
}

function SourceHistoryPanel({ api, domain, scopeId, locale = 'en', disabled, onRestore, onAccessRevoked }: {
  api: ExplorerApi;
  domain: 'PACKAGE_SOURCE' | 'CENTRAL_RUNTIME_CONFIG';
  scopeId: string;
  locale?: 'en' | 'zh-Hant';
  disabled: boolean;
  onRestore: (snapshotId: string, currentSourceRevision: string, registryRevision?: string) => void;
  onAccessRevoked: () => void;
}) {
  const [history, setHistory] = useState<SourceHistoryResponse | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState('');
  const [diff, setDiff] = useState<SourceHistoryDiffResponse | null>(null);
  const [selectedEntry, setSelectedEntry] = useState<string | null>(null);
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);
  const [diffError, setDiffError] = useState('');
  const historyControllerRef = useRef<AbortController | null>(null);
  const diffControllerRef = useRef<AbortController | null>(null);

  useEffect(() => {
    if (!api.getSourceHistory) {
      setHistoryError('This Server does not provide source history.');
      return;
    }
    const controller = new AbortController();
    historyControllerRef.current = controller;
    setHistoryLoading(true);
    setHistoryError('');
    api.getSourceHistory(domain, scopeId, 0, SOURCE_HISTORY_PAGE_SIZE, controller.signal).then(result => {
      if (controller.signal.aborted) return;
      if (!Array.isArray(result.items) || typeof result.currentSourceRevision !== 'string') {
        throw new Error('The Server returned an incomplete source history response.');
      }
      setHistory(result);
    }).catch(reason => {
      if (controller.signal.aborted) return;
      if (statusOf(reason) === 403) onAccessRevoked();
      else setHistoryError(statusOf(reason) === 404
        ? 'Source history is unavailable on this Server or for this source.'
        : failureText(reason, 'load source history'));
    }).finally(() => {
      if (!controller.signal.aborted) setHistoryLoading(false);
    });
    return () => {
      controller.abort();
      if (historyControllerRef.current === controller) historyControllerRef.current = null;
      diffControllerRef.current?.abort(); diffControllerRef.current = null;
    };
  }, [api, domain, scopeId, onAccessRevoked]);

  async function loadMore() {
    if (!api.getSourceHistory || !history?.hasMore || historyLoading) return;
    historyControllerRef.current?.abort();
    const controller = new AbortController();
    historyControllerRef.current = controller;
    setHistoryLoading(true); setHistoryError('');
    try {
      const result = await api.getSourceHistory(domain, scopeId, history.offset + history.items.length,
        SOURCE_HISTORY_PAGE_SIZE, controller.signal);
      if (controller.signal.aborted) return;
      if (result.currentSourceRevision !== history.currentSourceRevision || result.registryRevision !== history.registryRevision) {
        const refreshed = await api.getSourceHistory(domain, scopeId, 0, SOURCE_HISTORY_PAGE_SIZE, controller.signal);
        if (controller.signal.aborted) return;
        setHistory(refreshed); setDiff(null); setSelectedEntry(null); setSelectedPath(null);
        setHistoryError('The source changed while loading history. The list was refreshed; review the current revisions before restoring.');
      } else setHistory({ ...result, items: [...history.items, ...result.items] });
    } catch (reason) {
      if (!controller.signal.aborted) {
        if (statusOf(reason) === 403) onAccessRevoked();
        else setHistoryError(failureText(reason, 'load more source history'));
      }
    } finally {
      if (!controller.signal.aborted) setHistoryLoading(false);
    }
  }

  async function loadDiff(entry: SourceHistoryEntry, path?: string, append = false) {
    if (!api.getSourceHistoryDiff || !isSafeSnapshotId(entry.beforeSnapshotId) || !isSafeSnapshotId(entry.afterSnapshotId)) {
      setDiffError('A safe comparison is unavailable for this history entry.'); return;
    }
    const offset = append && diff ? diff.offset + diff.items.length : 0;
    diffControllerRef.current?.abort();
    const controller = new AbortController();
    diffControllerRef.current = controller;
    setDiffLoading(true); setDiffError(''); setSelectedEntry(entry.transactionId); setSelectedPath(path || null);
    if (!path && !append) setDiff(null);
    try {
      const result = await api.getSourceHistoryDiff(domain, scopeId, entry.beforeSnapshotId, entry.afterSnapshotId,
        path, controller.signal, offset, SOURCE_HISTORY_PAGE_SIZE);
      if (!controller.signal.aborted) setDiff(previous => append && previous
        && previous.beforeSnapshotId === result.beforeSnapshotId && previous.afterSnapshotId === result.afterSnapshotId
        ? { ...result, items: [...previous.items, ...result.items] } : result);
    } catch (reason) {
      if (!controller.signal.aborted) {
        if (statusOf(reason) === 403) onAccessRevoked();
        else { if (!append) setDiff(null); setDiffError(failureText(reason, 'compare historical source')); }
      }
    } finally {
      if (!controller.signal.aborted) setDiffLoading(false);
    }
  }

  const selectedHistoryEntry = history?.items.find(item => item.transactionId === selectedEntry);
  const selectedDiffEntry = diff?.items.find(item => item.path === selectedPath);

  return <section className="att-source-history att-source-review" aria-label="Source history and restore">
    <header className="att-source-review-heading"><div><h3>Source history</h3>
      <p>{domain === 'PACKAGE_SOURCE' ? 'Package source scope' : 'Global Runtime Configuration scope'} · Restore stages every difference as a new draft. Published source stays unchanged until Publish.</p></div>
    </header>
    {historyLoading && !history && <p role="status" aria-busy="true">Loading source history…</p>}
    {historyError && <p role="alert">{historyError}</p>}
    {history && <>
      <p>Current source revision <code>{history.currentSourceRevision}</code>{history.registryRevision && <> · Package registration <code>{history.registryRevision}</code></>}</p>
      {history.items.length === 0 ? <p>No retained source history is available.</p> : <ol className="att-source-history-list">
        {history.items.map(entry => {
          const comparable = isSafeSnapshotId(entry.beforeSnapshotId) && isSafeSnapshotId(entry.afterSnapshotId);
          const restorable = entry.revisionsAvailable === true && isSafeSnapshotId(entry.afterSnapshotId);
          return <li key={entry.transactionId}>
            <div><strong>{entry.action}</strong> · {entry.outcome} · <time dateTime={entry.createdAt}>{entry.createdAt}</time>
              {entry.author && <> · {entry.author}</>}
            </div>
            {(entry.beforeRevision || entry.afterRevision) && <p>Revision {entry.beforeRevision || '—'} → {entry.afterRevision || '—'}</p>}
            {entry.changedResources?.length ? <ul aria-label={`Changed files in ${entry.transactionId}`}>
              {entry.changedResources.map(path => <li key={path}><code>{path}</code></li>)}
            </ul> : <p>{entry.changedResourceCount ?? 0} changed file(s)</p>}
            {entry.restoredFromSnapshotId && <p>Restored from <code>{entry.restoredFromSnapshotId}</code></p>}
            <div className="att-source-authoring-toolbar">
              <button type="button" onClick={() => void loadDiff(entry)} disabled={!comparable || diffLoading}>
                {selectedEntry === entry.transactionId && diff ? 'Refresh changes' : 'View changes'}
              </button>
              <button type="button" className="button button-primary" onClick={() => history.currentSourceRevision && onRestore(
                entry.afterSnapshotId!, history.currentSourceRevision, history.registryRevision)}
                disabled={disabled || !restorable || !history.currentSourceRevision || domain === 'PACKAGE_SOURCE' && !history.registryRevision}
                title={restorable ? 'Stage this complete historical source snapshot as a new draft' : 'This history entry has no retained restorable snapshot'}>
                Stage restore as draft
              </button>
            </div>
          </li>;
        })}
      </ol>}
      {history.hasMore && <button type="button" onClick={() => void loadMore()} disabled={historyLoading}>
        {historyLoading ? 'Loading…' : 'Load older history'}
      </button>}
      {history.retention && <p>{locale === 'zh-Hant'
        ? `保留狀態：共 ${history.retention.retainedTransactions ?? history.total} 筆，政策上限 ${history.retention.transactionLimit ?? '未提供'} 筆 · ${history.retention.state || '狀態無法使用'}。`
        : `Retention: ${history.retention.retainedTransactions ?? history.total} of ${history.retention.transactionLimit ?? 'policy limit'} transactions · ${history.retention.state || 'status unavailable'}.`}</p>}
    </>}
    {selectedHistoryEntry && <section className="att-source-history-diff" aria-label="Historical source comparison">
      <h4>Compare {selectedHistoryEntry.action} · {selectedHistoryEntry.createdAt}</h4>
      {diffLoading && <p role="status" aria-busy="true">Loading comparison…</p>}
      {diffError && <p role="alert">{diffError}</p>}
      {diff && <>
        {!selectedPath && <>
          {!diff.items.length && <p>No source changes between these snapshots.</p>}
          <ul aria-label="Files changed between snapshots">{diff.items.map(item => <li key={item.path}>
            <button type="button" onClick={() => void loadDiff(selectedHistoryEntry, item.path)} disabled={diffLoading}>
              {item.change} <code>{item.path}</code> · {item.beforeBytes ?? 0} → {item.afterBytes ?? 0} bytes
            </button>
          </li>)}</ul>
          <p>Showing {Math.min(diff.offset + diff.items.length, diff.total)} of {diff.total} changed files.</p>
          {diff.hasMore && <button type="button" onClick={() => void loadDiff(selectedHistoryEntry, undefined, true)} disabled={diffLoading}>
            {diffLoading ? 'Loading…' : 'Load more changed files'}
          </button>}
        </>}
        {selectedPath && selectedDiffEntry && <>
          <p><strong>{selectedDiffEntry.change}</strong> <code>{selectedDiffEntry.path}</code></p>
          {selectedDiffEntry.contentAvailable && <>
            <details><summary>Show before and after source</summary><div className="att-source-history-content-diff">
              <section><h5>Before</h5><CodeEditor value={selectedDiffEntry.beforeContent ?? ''}
                language={detectCodeLanguage(selectedDiffEntry.path)} label={`Historical source before ${selectedDiffEntry.path}`}
                readOnly maxCharacters={200_000} /></section>
              <section><h5>After</h5><CodeEditor value={selectedDiffEntry.afterContent ?? ''}
                language={detectCodeLanguage(selectedDiffEntry.path)} label={`Historical source after ${selectedDiffEntry.path}`}
                readOnly maxCharacters={200_000} /></section>
            </div></details>
            {selectedDiffEntry.contentDiff && <details><summary>Show unified diff</summary>
              <DiffLineList lines={selectedDiffEntry.contentDiff} label={`Unified diff for ${selectedDiffEntry.path}`} />
            </details>}
          </>}
          {selectedDiffEntry.contentAvailable === false && <p>{selectedDiffEntry.redacted
            ? 'The Server withheld protected source content. Path and change metadata remain available.'
            : 'Text comparison is unavailable for this file. The Server withheld its contents.'}</p>}
        </>}
      </>}
    </section>}
  </section>;
}

export function ChangedFiles({ files, locale = 'en' }: { files: ChangedSourceFile[]; locale?: 'en' | 'zh-Hant' }) {
  const [filter, setFilter] = useState('');
  const query = filter.trim().toLocaleLowerCase();
  const visibleFiles = files.filter(file => !query || `${file.operation} ${file.path} ${file.targetPath || ''}`.toLocaleLowerCase().includes(query));
  return <div className="att-source-changed-files"><h4>Changed files</h4>
    {!files.length ? <p>The Server did not return changed-file details.</p> : <>
      <label>Filter changed files <input type="search" aria-label="Filter changed files" value={filter} onChange={event => setFilter(event.currentTarget.value)} /></label>
      <p>{locale === 'zh-Hant' ? `顯示 ${visibleFiles.length} 個，共 ${files.length} 個變更檔案。`
        : `Showing ${visibleFiles.length} of ${files.length} changed files.`}</p>
      <ul>{visibleFiles.map((file, index) => <li key={`${file.operation}:${file.path}:${index}`}>
      <div className="att-source-file-change"><strong>{file.operation.toUpperCase()}</strong> <code>{file.path}</code>
        {file.targetPath && <> → <code>{file.targetPath}</code></>}
        <span>{file.oldBytes ?? 0} B → {file.newBytes ?? 0} B</span></div>
      {file.automatic && <p>Generated by the Server as part of this Change Set.</p>}
      {file.inboundReferences?.length ? <div className="att-source-inbound-references"><strong>Referenced by</strong><ul>{summarizeReferences(file).map(reference => <li key={reference}>{reference}</li>)}</ul>
        <p>Review each returned call site and its certainty before publishing this delete or move. The reference list may be incomplete.</p></div> : null}
      {file.contentSummary && <p>{file.contentSummary}</p>}
      {file.contentDiff && <details><summary>Show unified diff for {file.path}</summary>
        <DiffLineList lines={file.contentDiff} label={`Review diff for ${file.path}`} />
      </details>}
    </li>)}</ul></>}
  </div>;
}

function DiffLineList({ lines, label }: { lines: string[]; label: string }) {
  return <ol className="att-source-diff-lines" aria-label={label}>{lines.map((line, index) => {
    const marker = line[0];
    const kind = marker === '+' ? 'added' : marker === '-' ? 'removed' : 'context';
    return <li className={`att-source-diff-line is-${kind}`} key={`${index}:${line}`}>
      <span aria-label={`Diff row ${index + 1}`}>{index + 1}</span><code>{line}</code>
    </li>;
  })}</ol>;
}

function ImpactReview({ report, loading, filter, onFilter, onLoadMore, actor, actorEntry, onActorEntry, actorFromSession, acknowledged, onAcknowledged, canAcknowledge }: {
  report: SourceImpactReport; loading: boolean; filter: string; onFilter(value: string): void; onLoadMore(): void;
  actor: string; actorEntry: string; onActorEntry(value: string): void; actorFromSession: boolean;
  acknowledged: boolean; onAcknowledged(value: boolean): void; canAcknowledge: boolean;
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
      {report.acknowledgementRequired && <p className="att-source-impact-ack" role="status">This report requires administrator acknowledgement of the current impact assessment.</p>}
      {report.acknowledgementRequired && !actorFromSession && <label>Authenticated administrator ID <input aria-label="Authenticated administrator ID" value={actorEntry}
        onChange={event => onActorEntry(event.currentTarget.value)} placeholder="Exact authenticated Server principal" autoComplete="off" /></label>}
      {report.acknowledgementRequired && <label><input type="checkbox" aria-label="Acknowledge current Config impact" checked={acknowledged}
        disabled={!canAcknowledge || !actor || loading} onChange={event => onAcknowledged(event.currentTarget.checked)} />
        I have reviewed affected Packages and Schedules and approve this exact revision for publication.</label>}
      {report.acknowledgementRequired && !actor && <p role="status">Enter your authenticated administrator ID to bind approval to the current Server principal; publication remains disabled.</p>}
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
      {report.activationStatus && <>
        <p>Activation state: {report.activationStatus.state || 'Unknown'}.
          {report.activationStatus.state !== 'ACTIVE' && ' New Job admission must remain paused until activation is verified.'}</p>
        {report.activationStatus.failureMessage && <p role="alert">{report.activationStatus.failureMessage}</p>}
        <details><summary>Advanced activation provenance</summary>
          <dl className="att-impact-revisions">
            <div><dt>Published revision</dt><dd><code>{report.activationStatus.publishedRevision || 'unavailable'}</code></dd></div>
            <div><dt>Active revision</dt><dd><code>{report.activationStatus.activeRevision || 'unavailable'}</code></dd></div>
          </dl>
        </details>
      </>}
    </>}
  </section>;
}
