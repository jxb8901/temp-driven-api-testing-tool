import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { parseDocument, stringify } from 'yaml';
import { CodeEditor } from '../code/CodeEditor';
import type { RunDefinition } from '../run/RunPage';
import { AuthoringWorkflow } from './AuthoringWorkflow';
import { SourceAuthoringWorkspace, type SourceAuthoringInitialCandidate } from './SourceAuthoringWorkspace';
import { useAccessibleConfirmation } from './useAccessibleConfirmation';
import type { ExplorerApi, NativeTestCasePreviewResponse, ResourceSummary } from './types';
import type { ManagedAuthoringSource } from './sourceManagement';
import './native-testcase-authoring.css';

const MAX_FLOW_PAGES = 20;
const MAX_FILE_PAGES = 12;
const CASE_ID = /^[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*$/;
const DEFAULT_DEFINITION: Record<string, unknown> = {
  schemaVersion: 'att-testcase/v1', id: 'new-case', name: 'New Test Case', flow: '', inputs: {},
};

function safeRelativePath(path: string): boolean {
  return Boolean(path && path.length <= 512 && path === path.trim() && !path.startsWith('/') && !path.includes('\\')
    && !/^[A-Za-z]:/.test(path) && !/[:\u0000-\u001f\u007f]/.test(path)
    && path.split('/').every(part => part && part !== '.' && part !== '..'));
}

function parsedObject(source: string): Record<string, unknown> | null {
  if (new TextEncoder().encode(source).byteLength > 100 * 1024) return null;
  const document = parseDocument(source, { uniqueKeys: true });
  if (document.errors.length) return null;
  const value: unknown = document.toJS();
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function object(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function jsonText(value: unknown): string {
  try { return JSON.stringify(value ?? {}, null, 2); } catch { return '{}'; }
}

function safeRequestError(reason: unknown, action: string): string {
  const status = typeof reason === 'object' && reason !== null && 'status' in reason
    ? Number((reason as { status?: unknown }).status) : 0;
  if (status === 401) return 'Your session expired. Sign in again, then retry.';
  if (status === 403) return `The Server denied ${action}. Package source permission may be unavailable or revoked.`;
  if (status === 404) return `The source for ${action} is unavailable. Refresh the Package file index.`;
  if (status === 409) return 'The Package changed while this action was in progress. Reload and review the current source.';
  if (status === 422) return `The Server rejected ${action} during validation. Review the source diagnostics in the Change Set workflow.`;
  if (status === 429 || status === 503 || status === 504) return `${action} is temporarily unavailable. Retry when the Server is ready.`;
  return `${action} could not be completed. Check the connection and retry.`;
}

function updateField(source: string, path: string[], value: unknown, remove = false): string | null {
  const document = parseDocument(source, { uniqueKeys: true });
  if (document.errors.length) return null;
  if (path.length === 1) {
    if (remove) document.delete(path[0]); else document.set(path[0], value);
  } else {
    const current = document.getIn(path.slice(0, -1));
    if (!current || typeof current !== 'object') return null;
    if (remove) document.deleteIn(path); else document.setIn(path, value);
  }
  return document.toString({ lineWidth: 0 });
}

function newChangeKey(): string {
  return `native-case-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export function NativeTestCaseAuthoringWorkspace({ api, packageId, sourcePath, onBack, onJobAccepted, registerUnsavedChanges }: {
  api: ExplorerApi;
  packageId: string;
  sourcePath?: string;
  onBack?: () => void;
  onJobAccepted?: (jobId: string) => void;
  registerUnsavedChanges?: (isDirty: boolean, description?: string, onConfirmDiscard?: () => boolean | Promise<boolean>) => () => void;
}) {
  const { confirm, dialog } = useAccessibleConfirmation();
  const [path, setPath] = useState(sourcePath || '');
  const [activeSourcePath, setActiveSourcePath] = useState(sourcePath || '');
  const [sourceReload, setSourceReload] = useState(0);
  const [source, setSource] = useState(() => stringify(DEFAULT_DEFINITION));
  const [loadedSource, setLoadedSource] = useState('');
  const [sourceInfo, setSourceInfo] = useState<ManagedAuthoringSource | null>(null);
  const [sourceState, setSourceState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [testcaseRoot, setTestcaseRoot] = useState('testcase');
  const [testcaseRootState, setTestcaseRootState] = useState<'loading' | 'ready' | 'unavailable'>('loading');
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');
  const [flows, setFlows] = useState<ResourceSummary[]>([]);
  const [flowsLoaded, setFlowsLoaded] = useState(false);
  const [flowError, setFlowError] = useState(false);
  const [preview, setPreview] = useState<NativeTestCasePreviewResponse | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [candidate, setCandidate] = useState<SourceAuthoringInitialCandidate | null>(null);
  const [candidatePath, setCandidatePath] = useState('');
  const [candidateOperation, setCandidateOperation] = useState<'create' | 'replace'>('create');
  const [excelPaths, setExcelPaths] = useState<string[]>([]);
  const [serverDraft, setServerDraft] = useState<{ draftId: string; expiresAt?: string } | null>(null);
  const [runBusy, setRunBusy] = useState(false);
  const [advanced, setAdvanced] = useState(false);
  const requestGeneration = useRef(0);
  const controllerRef = useRef<AbortController | null>(null);
  const actualPath = path || `${testcaseRoot}/${String(parsedObject(source)?.id || 'new-case').replace(/\./g, '/')}/testcase.yaml`;
  const definition = useMemo(() => parsedObject(source), [source]);
  const dataSource = object(definition?.dataSource);
  const inline = dataSource?.type === 'inline';
  const excel = dataSource?.type === 'excel';
  const inputs = object(definition?.inputs) || {};
  const records = Array.isArray(dataSource?.records) ? dataSource.records : [];
  const dirty = Boolean(source !== loadedSource || runBusy);
  const isNewSource = !activeSourcePath;
  const sourceReadOnly = sourceState !== 'ready' || Boolean(activeSourcePath && !sourceInfo?.present);
  const sourceCurrent = Boolean(activeSourcePath && source === loadedSource && !candidate && sourceState === 'ready');
  const previewValid = Boolean(preview?.valid && preview.cases.length && preview.diagnostics.length === 0);
  const selectedCases = preview?.cases.filter(item => selected.includes(item.caseId)) || [];

  useEffect(() => {
    setPath(sourcePath || '');
    setActiveSourcePath(sourcePath || '');
    setCandidate(null); setCandidatePath(''); setPreview(null); setSelected([]); setServerDraft(null);
  }, [sourcePath, packageId]);

  useEffect(() => {
    const controller = new AbortController();
    let cancelled = false;
    const load = async () => {
      setFlowsLoaded(false); setFlowError(false); setFlows([]);
      try {
        const accumulated: ResourceSummary[] = [];
        let cursor: string | undefined;
        for (let pageIndex = 0; pageIndex < MAX_FLOW_PAGES; pageIndex++) {
          const page = await api.listResources(packageId, { type: 'flow', limit: 50, cursor }, controller.signal);
          if (controller.signal.aborted) return;
          accumulated.push(...(page.items || []).filter(item => item.type === 'flow'));
          cursor = page.nextCursor || undefined;
          if (!cursor) break;
        }
        if (!cancelled) setFlows(accumulated);
      } catch {
        if (!cancelled) setFlowError(true);
      } finally {
        if (!cancelled) setFlowsLoaded(true);
      }
    };
    void load();
    return () => { cancelled = true; controller.abort(); };
  }, [api, packageId]);

  useEffect(() => {
    const controller = new AbortController();
    let cancelled = false;
    const load = async () => {
      setTestcaseRootState('loading');
      if (!api.getManagedPackageAuthoringSource) {
        setTestcaseRootState('unavailable'); return;
      }
      try {
        const manifest = await api.getManagedPackageAuthoringSource(packageId, { kind: 'manifest' }, controller.signal);
        if (controller.signal.aborted || cancelled) return;
        if (!manifest.available) { setTestcaseRootState('unavailable'); return; }
        const resources = object(parsedObject(manifest.text)?.resources);
        const configured = manifest.present ? resources?.testcaseRoot : undefined;
        const root = typeof configured === 'string' ? configured : 'testcase';
        if (!safeRelativePath(root)) { setTestcaseRootState('unavailable'); return; }
        setTestcaseRoot(root); setTestcaseRootState('ready');
      } catch {
        if (!cancelled) setTestcaseRootState('unavailable');
      }
    };
    void load();
    return () => { cancelled = true; controller.abort(); };
  }, [api, packageId]);

  useEffect(() => {
    const controller = new AbortController();
    let cancelled = false;
    const load = async () => {
      if (!activeSourcePath) {
        const defaultSource = stringify(DEFAULT_DEFINITION);
        setSourceState('ready'); setSourceInfo(null); setSource(defaultSource); setLoadedSource(defaultSource);
        setError(''); setStatus('Create a Package-relative testcase.yaml under the configured resources.testcaseRoot.');
        return;
      }
      setSourceState('loading'); setSourceInfo(null); setError(''); setStatus('');
      if (!api.getManagedPackageAuthoringSource) {
        setSourceState('error'); setError('This Server does not expose the Package Change Set revision contract.'); return;
      }
      try {
        const managed = await api.getManagedPackageAuthoringSource(packageId, { kind: 'native-testcase', path: activeSourcePath }, controller.signal);
        if (cancelled) return;
        if (!managed.available || !managed.present || !managed.baseSourceRevision || !managed.registryRevision
          || !/(?:^|\/)testcase\.ya?ml$/i.test(managed.logicalPath)) {
          setSourceState('error'); setError('The Server did not return an editable, revision-fenced YAML source.'); return;
        }
        setSourceInfo(managed); setSource(managed.text); setLoadedSource(managed.text); setPath(managed.logicalPath);
        setSourceState('ready');
      } catch (reason) {
        if (!cancelled) { setSourceState('error'); setError(safeRequestError(reason, 'load native Test Case source')); }
      }
    };
    void load();
    return () => { cancelled = true; controller.abort(); };
  }, [api, packageId, activeSourcePath, sourceReload]);

  useEffect(() => {
    const controller = new AbortController();
    let cancelled = false;
    const load = async () => {
      if (!api.listPackageFileIndex) return;
      try {
        const indexed: string[] = [];
        let cursor: string | undefined;
        for (let pageIndex = 0; pageIndex < MAX_FILE_PAGES; pageIndex++) {
          const page = await api.listPackageFileIndex(packageId, { cursor, limit: 50 }, controller.signal);
          if (controller.signal.aborted) return;
          indexed.push(...(page.items || []).map(item => item.path).filter(file => /\.xlsx$/i.test(file)));
          cursor = page.nextCursor || undefined;
          if (!cursor) break;
        }
        if (!cancelled) setExcelPaths([...new Set(indexed)].sort());
      } catch { if (!cancelled) setExcelPaths([]); }
    };
    void load();
    return () => { cancelled = true; controller.abort(); };
  }, [api, packageId]);

  useEffect(() => {
    if (!registerUnsavedChanges) return;
    return registerUnsavedChanges(dirty, 'Native Test Case definition');
  }, [registerUnsavedChanges, dirty]);

  useEffect(() => () => controllerRef.current?.abort(), []);

  const clearPreview = useCallback(() => { setPreview(null); setSelected([]); setServerDraft(null); }, []);

  function editField(field: string, value: unknown, remove = false) {
    const updated = updateField(source, [field], value, remove);
    if (updated == null) { setError('Correct the YAML source before using the guided fields.'); return; }
    setSource(updated); setError(''); setStatus(''); clearPreview();
  }

  function editNested(section: string, field: string, value: unknown, remove = false) {
    const updated = updateField(source, [section, field], value, remove);
    if (updated == null) { setError('Correct the YAML source before using the guided fields.'); return; }
    setSource(updated); setError(''); setStatus(''); clearPreview();
  }

  function switchDataSource(type: 'inline' | 'excel') {
    const document = parseDocument(source, { uniqueKeys: true });
    if (document.errors.length) { setError('Correct the YAML source before changing its Data Source.'); return; }
    document.delete('inputs');
    if (type === 'inline') document.set('dataSource', { type: 'inline', records: [{ recordId: 'case-1', data: {} }] });
    else document.set('dataSource', { type: 'excel', path: excelPaths[0] || 'testcase/cases.xlsx', sheet: 'Cases', headerRows: 1, caseId: 'ID', dataColumns: '' });
    setSource(document.toString({ lineWidth: 0 })); setError(''); clearPreview();
  }

  async function runDesignPreview() {
    if (!api.previewNativeTestCase) {
      setError('This Server does not expose bounded Native Test Case preview.'); setStatus(''); return;
    }
    const targetPath = actualPath.trim();
    if (!safeRelativePath(targetPath) || !targetPath.endsWith('/testcase.yaml')
        || testcaseRootState !== 'ready' || !(targetPath === `${testcaseRoot}/testcase.yaml` || targetPath.startsWith(`${testcaseRoot}/`))) {
      setError(`Enter a safe Package-relative testcase.yaml under the configured resources.testcaseRoot (${testcaseRoot}/).`); setStatus(''); return;
    }
    const generation = ++requestGeneration.current;
    controllerRef.current?.abort();
    const controller = new AbortController(); controllerRef.current = controller;
    setServerDraft(null);
    setPreview(null); setSelected([]); setError(''); setStatus('Resolving the candidate against the current Package source with the bounded Server adapter…');
    try {
      const result = await api.previewNativeTestCase(packageId, { path: targetPath, source }, controller.signal);
      if (controller.signal.aborted || generation !== requestGeneration.current) return;
      setPreview(result);
      setSelected(result.valid && result.diagnostics.length === 0 ? result.cases.map(item => item.caseId) : []);
      setStatus(result.valid
        ? `Server preview resolved ${result.resolvedCount} case(s)${result.truncated ? `; showing the first ${result.previewCount}` : ''}. RUN resolves and freezes the selected IDs again.`
        : 'Server preview found source issues. Correct them, then preview again.');
    } catch (reason) {
      if (!controller.signal.aborted && generation === requestGeneration.current) {
        setPreview(null); setSelected([]); setError(safeRequestError(reason, 'preview Native Test Case')); setStatus('');
      }
    }
  }

  async function stageDefinition() {
    const generation = ++requestGeneration.current;
    controllerRef.current?.abort();
    const controller = new AbortController(); controllerRef.current = controller;
    setError(''); setStatus('Checking the Package source revision before staging…');
    const targetPath = actualPath.trim();
    if (!safeRelativePath(targetPath) || !/\/testcase\.yaml$/i.test('/' + targetPath)) {
      setError('Enter a safe Package-relative path ending in testcase.yaml.'); setStatus(''); return;
    }
    if (!definition || !CASE_ID.test(String(definition.id || ''))) {
      setError('Set a valid Definition ID before staging.'); setStatus(''); return;
    }
    if (testcaseRootState !== 'ready' || !(targetPath === `${testcaseRoot}/testcase.yaml` || targetPath.startsWith(`${testcaseRoot}/`))) {
      setError(`Place testcase.yaml under the Package's configured resources.testcaseRoot (${testcaseRoot}/).`); setStatus(''); return;
    }
    if (activeSourcePath && targetPath !== activeSourcePath) {
      setError('An existing Definition keeps its Package path. Create a new Definition to use another path.'); setStatus(''); return;
    }
    if (!api.getManagedPackageAuthoringSource) {
      setError('This Server does not expose the Package Change Set revision contract.'); setStatus(''); return;
    }
    try {
      const managed = await api.getManagedPackageAuthoringSource(packageId, { kind: 'native-testcase', path: targetPath }, controller.signal);
      if (controller.signal.aborted || generation !== requestGeneration.current) return;
      if (!managed.available || !managed.baseSourceRevision || !managed.registryRevision) {
        setError('The Server did not provide a complete Package source revision and registration revision.'); setStatus(''); return;
      }
      if (activeSourcePath && !managed.present) {
        setError('The selected Test Case source is no longer present. Reload the Package file index.'); setStatus(''); return;
      }
      if (isNewSource && managed.present) {
        setError('A source already exists at this path. Open it from the Package file index or choose a new path.'); setStatus(''); return;
      }
      if (activeSourcePath && sourceInfo && managed.baseSourceRevision !== sourceInfo.baseSourceRevision) {
        setError('The Package changed since this Definition was loaded. Preserve your edits, reload the current source, and reapply them.'); setStatus(''); return;
      }
      const next: SourceAuthoringInitialCandidate = {
        key: newChangeKey(), baseSourceRevision: managed.baseSourceRevision, registryRevision: managed.registryRevision,
        operations: [{ operation: activeSourcePath ? 'replace' : 'create', path: targetPath, content: source }],
      };
      setCandidatePath(targetPath); setCandidateOperation(activeSourcePath ? 'replace' : 'create'); setCandidate(next);
      setStatus('Definition added to the existing Package Change Set workflow. Published source is unchanged.');
    } catch (reason) {
      if (!controller.signal.aborted) { setError(safeRequestError(reason, 'stage the native Test Case')); setStatus(''); }
    }
  }

  async function validateRun() {
    if (!previewValid || !selectedCases.length || !sourceCurrent || !api.previewRun || !api.supportsNativeCaseRun?.()) return;
    const generation = ++requestGeneration.current;
    controllerRef.current?.abort();
    const controller = new AbortController(); controllerRef.current = controller;
    setRunBusy(true); setError(''); setStatus('Validating the selected Cases against the current Package source…'); setServerDraft(null);
    try {
      const request: RunDefinition = {
        schemaVersion: 'att-execution/v2', executionType: 'run', packageId,
        target: { type: 'nativeCase', definitionId: String(definition?.id || ''),
          selection: { resolvedCaseIds: selectedCases.map(item => item.caseId) } },
        options: { debug: false },
      };
      const response = await api.previewRun(request, controller.signal);
      if (controller.signal.aborted || generation !== requestGeneration.current) return;
      setServerDraft({ draftId: response.draftId, expiresAt: response.expiresAt });
      setStatus('Server validation passed. This validated draft binds the selected Case IDs to the current source revision.');
    } catch (reason) {
      if (!controller.signal.aborted) { setError(safeRequestError(reason, 'validate Native Case RUN')); setStatus(''); }
    } finally { if (!controller.signal.aborted) setRunBusy(false); }
  }

  async function submitRun() {
    if (!serverDraft || !api.submitRun || !await confirm({ title: 'Run selected Cases?',
      message: `Submit ${selectedCases.length} selected Case(s) from ${String(definition?.id || 'this Definition')}? The Server validates and freezes the Package source for the Job.`,
      confirmLabel: 'Run now', cancelLabel: 'Cancel' })) return;
    setRunBusy(true); setError(''); setStatus('Submitting the validated Native Case RUN…');
    try {
      const accepted = await api.submitRun({ packageId, draftId: serverDraft.draftId });
      setServerDraft(null); setStatus(`Job ${accepted.jobId} was accepted.`); onJobAccepted?.(accepted.jobId);
    } catch (reason) {
      setServerDraft(null); setError(safeRequestError(reason, 'submit Native Case RUN')); setStatus('The Job was not submitted. Validate the selected Cases again.');
    } finally { setRunBusy(false); }
  }

  const tagsText = Array.isArray(definition?.tags) ? definition.tags.filter((item): item is string => typeof item === 'string').join(', ') : '';
  const recordText = useMemo(() => inline ? jsonText(records) : '[]', [inline, records]);
  const flowId = typeof definition?.flow === 'string' ? definition.flow : '';
  const definitionId = typeof definition?.id === 'string' ? definition.id : '';
  return <section className="att-native-case-authoring" aria-label="Native Test Case authoring">
    {dialog}
    <header className="att-native-case-heading">
      <div><p className="att-explorer-breadcrumb">Packages / {packageId} / Native Test Cases</p>
        <h2>{activeSourcePath ? 'Edit native Test Case' : 'New native Test Case'}</h2>
        <p>Create a Flow-bound Definition and preview logical Cases without a workbook, Stage, or Template editor.</p></div>
      {onBack && <button type="button" className="button button-quiet" onClick={onBack}>Back to Package Explorer</button>}
    </header>

    <AuthoringWorkflow label="Native Test Case workflow" steps={[
      { id: 'edit', label: 'Edit', state: 'current' }, { id: 'preview', label: 'Preview / Resolve', state: preview ? 'complete' : 'upcoming' },
      { id: 'validate', label: 'Validate', state: candidate ? 'current' : 'upcoming' },
      { id: 'review', label: 'Review', state: candidate ? 'upcoming' : 'upcoming' },
      { id: 'publish', label: 'Publish', state: candidate ? 'upcoming' : 'upcoming' },
    ]} />

    {sourceState === 'loading' && <p role="status">Loading authorized Package source…</p>}
    {sourceState === 'error' && <p role="alert" className="att-error">{error}</p>}
    {sourceState !== 'error' && <>
      <section className="att-native-case-editor" aria-label="Native Test Case fields">
        <div className="att-native-case-grid">
          <label>Definition ID<input aria-label="Definition ID" value={definitionId} maxLength={126} disabled={sourceReadOnly || advanced}
            onChange={event => editField('id', event.currentTarget.value)} /></label>
          <label>Name<input aria-label="Definition name" value={typeof definition?.name === 'string' ? definition.name : ''} disabled={sourceReadOnly || advanced}
            onChange={event => editField('name', event.currentTarget.value, !event.currentTarget.value)} /></label>
          <label>Flow<select aria-label="Entry Flow" value={flowId} disabled={sourceReadOnly || advanced || !flowsLoaded}
            onChange={event => editField('flow', event.currentTarget.value)}>
            <option value="">Select a Package Flow</option>
            {flowId && !flows.some(item => item.logicalId === flowId) && <option value={flowId}>{flowId} (current)</option>}
            {flows.map(item => <option key={item.resourceId} value={item.logicalId || ''}>{item.name || item.logicalId}</option>)}
          </select></label>
          <label>Tags, comma separated<input aria-label="Definition tags" value={tagsText} disabled={sourceReadOnly || advanced}
            onChange={event => editField('tags', event.currentTarget.value.split(',').map(tag => tag.trim()).filter(Boolean), !event.currentTarget.value.trim())} /></label>
        </div>
        {flowError && <p role="status">The Flow catalog could not be loaded. Reload Explorer before selecting a Flow.</p>}
        <label className="att-native-source-path">Definition path<input aria-label="Definition path" value={actualPath} disabled={Boolean(activeSourcePath) || sourceReadOnly}
          onChange={event => setPath(event.currentTarget.value)} /><small>{testcaseRootState === 'ready'
            ? `Must remain inside the Package's configured resources.testcaseRoot (${testcaseRoot}/).`
            : 'The Package resources.testcaseRoot could not be verified. Reload the source before staging a new Definition.'}</small></label>

        <label>Data Source<select aria-label="Native Case Data Source" value={inline ? 'inline' : excel ? 'excel' : 'direct'} disabled={sourceReadOnly || advanced}
          onChange={event => {
            if (event.currentTarget.value === 'direct') {
              const document = parseDocument(source, { uniqueKeys: true });
              document.delete('dataSource'); document.delete('caseIdExpression'); document.set('inputs', {});
              setSource(document.toString({ lineWidth: 0 })); clearPreview();
            } else switchDataSource(event.currentTarget.value as 'inline' | 'excel');
          }}>
          <option value="direct">Single Case · typed inputs</option><option value="inline">Inline multi-record Data Source</option>
          <option value="excel">Excel Data Source</option>
        </select></label>

        {!excel && <>
          <label className="att-native-case-json">{inline ? 'Typed input projection (JSON)' : 'Typed inputs (JSON)'}
            <CodeEditor label={inline ? 'Typed input projection' : 'Typed inputs'} language="json" value={jsonText(inputs)} readOnly={sourceReadOnly || advanced}
              onChange={value => {
                try {
                  const parsed: unknown = JSON.parse(value);
                  if (!object(parsed)) { setError('Typed inputs must be a JSON object.'); return; }
                  editField('inputs', parsed);
                } catch { setError('Typed inputs must be a valid JSON object.'); }
              }} maxCharacters={100 * 1024} />
          </label>
        </>}

        {inline && <>
          <label>Case ID expression<input aria-label="Case ID expression" value={typeof definition?.caseIdExpression === 'string' ? definition.caseIdExpression : ''}
            disabled={sourceReadOnly || advanced} placeholder="${DATA.caseId}" onChange={event => editField('caseIdExpression', event.currentTarget.value, !event.currentTarget.value.trim())} />
            <small>Use a direct $&#123;DATA.key&#125; lookup; otherwise each recordId is the local Case ID.</small></label>
          <label className="att-native-case-json">Inline records (JSON)
            <CodeEditor label="Inline multi-record data" language="json" value={recordText} readOnly={sourceReadOnly || advanced}
              onChange={value => {
                try {
                  const parsed: unknown = JSON.parse(value);
                  if (!Array.isArray(parsed)) { setError('Inline records must be a JSON list.'); return; }
                  editNested('dataSource', 'records', parsed);
                } catch { setError('Inline records must be a valid JSON list.'); }
              }} maxCharacters={100 * 1024} />
          </label>
        </>}

        {excel && <section className="att-native-excel-source" aria-label="Excel Data Source mapping">
          <p role="note">The Server opens the Package workbook read only through the bounded S04 adapter. Preview does not write to the workbook.</p>
          <label>Workbook path<input aria-label="Excel workbook path" list="att-native-case-excel-paths" value={String(dataSource?.path || '')}
            disabled={sourceReadOnly || advanced} onChange={event => editNested('dataSource', 'path', event.currentTarget.value)} />
            <datalist id="att-native-case-excel-paths">{excelPaths.map(item => <option key={item} value={item} />)}</datalist></label>
          <div className="att-native-case-grid">
            <label>Sheet<input aria-label="Excel sheet" value={String(dataSource?.sheet || '')} disabled={sourceReadOnly || advanced}
              onChange={event => editNested('dataSource', 'sheet', event.currentTarget.value)} /></label>
            <label>Header rows<input aria-label="Excel header rows" type="number" min={1} max={20} value={String(dataSource?.headerRows ?? 1)} disabled={sourceReadOnly || advanced}
              onChange={event => editNested('dataSource', 'headerRows', Number(event.currentTarget.value))} /></label>
            <label>Case ID column<input aria-label="Excel Case ID column" value={String(dataSource?.caseId || '')} disabled={sourceReadOnly || advanced}
              onChange={event => editNested('dataSource', 'caseId', event.currentTarget.value)} /></label>
            <label>Tags column<input aria-label="Excel tags column" value={String(dataSource?.tags || '')} disabled={sourceReadOnly || advanced}
              onChange={event => editNested('dataSource', 'tags', event.currentTarget.value, !event.currentTarget.value.trim())} /></label>
          </div>
          <label>Data columns mapping<input aria-label="Excel data columns mapping" value={String(dataSource?.dataColumns || '')} disabled={sourceReadOnly || advanced}
            onChange={event => editNested('dataSource', 'dataColumns', event.currentTarget.value, !event.currentTarget.value.trim())} />
            <small>Enter the S04 mapping syntax. Server-side validation remains authoritative.</small></label>
        </section>}

        <button type="button" className="button button-quiet" aria-expanded={advanced} onClick={() => setAdvanced(value => !value)}>
          {advanced ? 'Hide advanced YAML source' : 'Advanced YAML source'}
        </button>
        {advanced && <CodeEditor label="Advanced testcase.yaml source" language="yaml" value={source} readOnly={sourceReadOnly}
          onChange={value => { setSource(value); setError(''); setStatus(''); clearPreview(); }} maxCharacters={100 * 1024} />}

        <div className="att-native-case-actions">
          <button type="button" className="button button-quiet" onClick={runDesignPreview} disabled={sourceState !== 'ready'}>Preview / Resolve</button>
          <button type="button" className="button button-primary" onClick={() => void stageDefinition()}
            disabled={sourceState !== 'ready' || !definition || sourceReadOnly || testcaseRootState !== 'ready'}>
            Add to Change Set
          </button>
        </div>
      </section>

      {error && <p role="alert" className="att-error">{error}</p>}
      {status && <p role="status" aria-live="polite">{status}</p>}

      {preview && <section className="att-native-case-preview" aria-label="Resolved Case design-time preview">
        <h3>Resolved Case preview</h3>
        <p role="note">Read only design-time preview. It does not create a Job or freeze a Job selection; RUN validation resolves the selected Case IDs again.</p>
        <p>Package source revision: <code>{preview.packageSourceRevision || 'unavailable'}</code>
          {preview.definitionRevision && <> · Candidate Definition revision: <code>{preview.definitionRevision}</code></>}
          {preview.workbookRevision && <> · Workbook revision: <code>{preview.workbookRevision}</code></>}</p>
        {preview.diagnostics.length > 0 && <ul role="alert">{preview.diagnostics.map((diagnostic, index) => {
          const location = [diagnostic.sheet && `sheet ${diagnostic.sheet}`, diagnostic.row != null && `row ${diagnostic.row}`,
            diagnostic.column != null && `column ${diagnostic.column}`].filter(Boolean).join(', ');
          return <li key={`${index}:${diagnostic.code || diagnostic.summary || 'diagnostic'}`}>
            <strong>{diagnostic.summary || diagnostic.code || 'Test Case preview issue'}</strong>
            {location && <span> · {location}</span>}{diagnostic.field && <span> · {diagnostic.field}</span>}
            {diagnostic.detail && <p>{diagnostic.detail}</p>}{diagnostic.suggestion && <p>{diagnostic.suggestion}</p>}
          </li>;
        })}</ul>}
        {preview.sourceType === 'excel' && <p role="status">Workbook rows were resolved by the read only S04 adapter.</p>}
        {preview.cases.length > 0 && <>
          <div className="att-native-case-preview-toolbar">
            <strong>{preview.resolvedCount} resolved Case(s){preview.truncated ? ` · showing ${preview.previewCount}` : ''}</strong>
            <button type="button" onClick={() => setSelected(selected.length === preview.cases.length ? [] : preview.cases.map(item => item.caseId))}
              disabled={preview.diagnostics.length > 0}>{selected.length === preview.cases.length ? 'Clear selection' : 'Select all'}</button>
          </div>
          <ul className="att-native-case-preview-list">{preview.cases.map(item => <li key={item.qualifiedCaseId}>
            <label><input type="checkbox" checked={selected.includes(item.caseId)} disabled={preview.diagnostics.length > 0}
              onChange={event => { const checked = event.currentTarget.checked; setSelected(current => checked
                ? [...current, item.caseId] : current.filter(id => id !== item.caseId)); }} />
              <strong>{item.qualifiedCaseId}</strong></label>
            <p>Flow <code>{item.flowId}</code> · {item.provenance.sourceType === 'excel'
              ? `${item.provenance.sheet || 'Workbook'} row ${item.provenance.row ?? 'unknown'}`
              : item.provenance.recordIndex != null ? `Inline record ${item.provenance.recordIndex + 1}${item.provenance.recordId ? ` · ${item.provenance.recordId}` : ''}` : 'Definition inputs'}</p>
            <p>Tags: {item.tags.length ? item.tags.join(', ') : 'none'}</p>
            <details><summary>Typed input values ({item.redacted ? 'some values are redacted by Server policy' : 'Server policy applied'})</summary><pre>{JSON.stringify(item.inputs, null, 2)}</pre></details>
          </li>)}</ul>
          {previewValid && <div className="att-native-case-run-actions">
            <button type="button" className="button button-quiet" onClick={() => void validateRun()}
              disabled={!selectedCases.length || !sourceCurrent || runBusy || !api.previewRun || !api.supportsNativeCaseRun?.()}
              title={!api.supportsNativeCaseRun?.() ? 'This Server has not advertised att-execution/v2 Native Case RUN support.'
                : !sourceCurrent ? 'Publish the current source before running selected Cases.' : undefined}>
              Validate selected Cases for RUN
            </button>
            {!api.supportsNativeCaseRun?.() && <p role="note">Native Case RUN is unavailable until this Server advertises att-execution/v2. Create, preview, validate, and publish remain available.</p>}
            {serverDraft && <button type="button" className="button button-primary" onClick={() => void submitRun()}
              disabled={runBusy || !api.submitRun || !selectedCases.length}>Run selected Cases</button>}
            {!sourceCurrent && <p role="note">Publish this Definition before RUN. Draft edits do not change the active Package source.</p>}
          </div>}
        </>}
      </section>}

      {candidate && <section className="att-native-case-source-workflow" aria-label="Package Change Set workflow">
        <h3>Package Change Set · {candidateOperation}</h3>
        <p>{candidatePath} · Draft → Validate → Review → Publish. The current published source remains unchanged until Publish.</p>
        <SourceAuthoringWorkspace key={candidate.key} api={api} target={{ domain: 'PACKAGE_SOURCE', packageId,
          query: { kind: 'file', path: candidatePath }, label: 'Native Test Case Definition', allowCreate: true, allowFileOperations: true }}
          initialCandidate={candidate} registerUnsavedChanges={registerUnsavedChanges}
          onPublished={() => {
            setCandidate(null); setActiveSourcePath(candidatePath); setPath(candidatePath); setSourceInfo(null); setSourceReload(value => value + 1);
            setStatus('Native Test Case source was published. Re-preview the latest source before RUN.');
            setPreview(null); setSelected([]); setServerDraft(null);
          }} />
      </section>}
    </>}
  </section>;
}
