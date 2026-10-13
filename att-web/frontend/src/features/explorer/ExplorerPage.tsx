import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { ConfigurationExplorer } from './ConfigurationExplorer';
import { ResourceTree, resourceKey } from './ResourceTree';
import { SourceViewer } from './SourceViewer';
import { TypedSidecarForm } from './TypedSidecarForm';
import type { DebugDraftRequest, DraftResponse, ExplorerApi, ExplorerPageProps, JsonObject, ResourceIdentity, ResourceSummary } from './types';

const MAX_DEFINITION_CHARACTERS = 100_000;

type DetailTab = 'definition' | 'source' | 'relations';

function statusOf(error: unknown): number {
  return typeof error === 'object' && error !== null && 'status' in error ? Number((error as { status?: unknown }).status) : 0;
}
function userError(error: unknown, action: string): string {
  const status = statusOf(error);
  if (status === 401) return `Your session has expired. Sign in again, then retry ${action}.`;
  if (status === 403) return `You do not have access to ${action}. Check your package permissions or Server configuration.`;
  if (status === 404) return `The selected resource or ${action} draft no longer exists. Refresh the resource list.`;
  if (status === 409) return `The package changed while ${action} was in progress. Refresh the resource and validate again.`;
  if (status === 413) return `The Server declined ${action} because the safe projection exceeded its configured limit.`;
  if (status === 429 || status === 503 || status === 504) return `${action} is temporarily unavailable. Wait briefly and retry.`;
  if (status >= 500) return `The Server could not complete ${action}. Retry or refresh the resource list.`;
  if (status) return `${action} failed (HTTP ${status}). Check the fields and access, then retry.`;
  return `${action} could not be completed. Check the connection and retry.`;
}
function isRunnable(resource?: ResourceSummary | null): boolean {
  return Boolean(resource && resource.state !== 'invalid' && ['template', 'flow', 'tool'].includes(resource.type));
}
function debugGroups(type?: string) {
  return type === 'tool'
    ? [{ key: 'inputs', label: 'Inputs' }, { key: 'arguments', label: 'Tool arguments' }]
    : [{ key: 'inputs', label: 'Inputs' }, { key: 'vars', label: 'Variables' }];
}
function safeProjection(value: unknown): string {
  let result = '';
  try { result = JSON.stringify(value ?? {}, null, 2); } catch { result = '{}'; }
  return result.length > MAX_DEFINITION_CHARACTERS
    ? `${result.slice(0, MAX_DEFINITION_CHARACTERS)}\n\n[Definition truncated at ${MAX_DEFINITION_CHARACTERS.toLocaleString()} characters.]`
    : result;
}
function resourceRouteKey(route: ExplorerPageProps['resourceRoute']): string {
  return route ? `${route.type}:${route.resourceId}` : '';
}

export function ExplorerPage({ api, packageId, resourceRoute, environment, principalLabel = '', onResourceRouteChange,
  onEnvironmentChange, onJobAccepted, onPrincipalLabelChange, onOpenQuickLoad, registerUnsavedChanges }: ExplorerPageProps) {
  const [query, setQuery] = useState('');
  const [typeFilter, setTypeFilter] = useState<ResourceSummary['type'] | 'all'>('all');
  const [resources, setResources] = useState<ResourceSummary[]>([]);
  const [totalResources, setTotalResources] = useState(0);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loadingResources, setLoadingResources] = useState(false);
  const [resourceError, setResourceError] = useState('');
  const [resourceStatus, setResourceStatus] = useState('');
  const [detail, setDetail] = useState<ResourceSummary | null>(null);
  const [definition, setDefinition] = useState<unknown>(null);
  const [detailError, setDetailError] = useState('');
  const [detailLoading, setDetailLoading] = useState(false);
  const [tab, setTab] = useState<DetailTab>('definition');
  const [debugOpen, setDebugOpen] = useState(false);
  const [debugForm, setDebugForm] = useState<Awaited<ReturnType<ExplorerApi['getDebugForm']>> | null>(null);
  const [debugInput, setDebugInput] = useState<JsonObject>({});
  const [debugStatus, setDebugStatus] = useState('');
  const [debugError, setDebugError] = useState('');
  const [draft, setDraft] = useState<DraftResponse | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [submitLoading, setSubmitLoading] = useState(false);
  const [formValid, setFormValid] = useState(true);
  const [resetKey, setResetKey] = useState(0);
  const [dirty, setDirty] = useState(false);
  const [principal, setPrincipal] = useState(principalLabel);
  const [resourceRefresh, setResourceRefresh] = useState(0);
  const dirtyRef = useRef(false);
  const selectedRouteKey = resourceRouteKey(resourceRoute);
  const identity = useMemo<ResourceIdentity | null>(() => resourceRoute
    ? { packageId, type: resourceRoute.type, resourceId: resourceRoute.resourceId } : null,
  [packageId, resourceRoute?.type, resourceRoute?.resourceId]);
  const selectedKey = resourceRoute ? `${resourceRoute.type}:${resourceRoute.resourceId}` : '';

  const updateDirty = useCallback((next: boolean) => {
    dirtyRef.current = next;
    setDirty(next);
  }, []);

  useEffect(() => registerUnsavedChanges(dirty, 'Explorer Debug and Test Runner edits'), [dirty, registerUnsavedChanges]);
  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (!dirtyRef.current) return;
      event.preventDefault(); event.returnValue = '';
    };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, []);

  useEffect(() => {
    setPrincipal(principalLabel);
  }, [principalLabel]);

  useEffect(() => {
    setResources([]); setCursor(null); setResourceError(''); setResourceStatus('Loading package resources…');
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      setLoadingResources(true);
      api.listResources(packageId, { query: query.trim() || undefined, type: typeFilter, limit: 50 }, controller.signal)
        .then(page => {
          setResources(page.items || []); setTotalResources(Number(page.total || 0)); setCursor(page.nextCursor || null);
          const warning = page.diagnostics?.map(item => item.summary || item.code).filter(Boolean).join(' ');
          setResourceStatus(`Showing ${(page.items || []).length} of ${Number(page.total || 0)} resources.${warning ? ` ${warning}` : ''}`);
          setResourceError('');
        }).catch(error => {
          if (!controller.signal.aborted) { setResourceError(userError(error, 'resource discovery')); setResourceStatus('Resource discovery failed.'); }
        }).finally(() => { if (!controller.signal.aborted) setLoadingResources(false); });
    }, query ? 220 : 0);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [api, packageId, query, typeFilter, resourceRefresh]);

  const loadMore = async () => {
    if (!cursor || loadingResources) return;
    setLoadingResources(true); setResourceError('');
    try {
      const page = await api.listResources(packageId, { query: query.trim() || undefined, type: typeFilter, cursor, limit: 50 });
      setResources(current => [...current, ...(page.items || [])]); setCursor(page.nextCursor || null);
      setResourceStatus(`Showing ${resources.length + (page.items || []).length} of ${totalResources} resources.`);
    } catch (error) { setResourceError(userError(error, 'loading more resources')); }
    finally { setLoadingResources(false); }
  };

  useEffect(() => {
    if (!identity) { setDetail(null); setDefinition(null); setDetailError(''); setTab('definition'); return; }
    const controller = new AbortController();
    setDetailLoading(true); setDetailError(''); setDetail(null); setDefinition(null); setTab('definition');
    api.getResource(identity, controller.signal).then(response => {
      if (controller.signal.aborted) return;
      setDetail(response.resource); setDefinition(response.definition ?? {}); setDetailError('');
    }).catch(error => {
      if (!controller.signal.aborted) setDetailError(userError(error, 'resource detail'));
    }).finally(() => { if (!controller.signal.aborted) setDetailLoading(false); });
    return () => controller.abort();
  }, [api, identity?.packageId, identity?.type, identity?.resourceId]);

  useEffect(() => {
    setDebugOpen(false); setDebugForm(null); setDebugInput({}); setDraft(null); setDebugError(''); setDebugStatus('');
    setFormValid(true); setResetKey(value => value + 1); updateDirty(false);
  }, [packageId, selectedRouteKey, updateDirty]);

  useEffect(() => {
    if (!debugOpen || !identity || !isRunnable(detail)) return;
    const controller = new AbortController();
    setDebugForm(null); setDraft(null); setDebugError(''); setDebugStatus('Loading safe Debug defaults…');
    api.getDebugForm(identity, environment || undefined, controller.signal).then(response => {
      if (controller.signal.aborted) return;
      setDebugForm(response); setDebugInput(JSON.parse(JSON.stringify(response.input || {})) as JsonObject);
      setResetKey(value => value + 1); setFormValid(true); updateDirty(false);
      setDebugStatus(response.redacted
        ? 'Defaults loaded. Hidden package-authored values are preserved until you choose a typed override.'
        : 'Defaults loaded from the selected resource sidecar.');
    }).catch(error => {
      if (!controller.signal.aborted) { setDebugError(userError(error, 'Debug defaults')); setDebugStatus('Debug defaults are unavailable.'); }
    });
    return () => controller.abort();
  }, [api, debugOpen, identity?.packageId, identity?.type, identity?.resourceId, environment, detail?.resourceId]);

  const setBusinessInput = (value: JsonObject) => {
    setDebugInput(value); setDraft(null); updateDirty(true);
    setDebugStatus('Debug input changed. Validate it before starting.'); setDebugError('');
  };

  const previewDebug = async (): Promise<DraftResponse | null> => {
    if (!debugForm || !identity) { setDebugError('Open Debug for a Template, Flow, or Tool first.'); return null; }
    if (!formValid) { setDebugError('Correct the typed fields or Advanced JSON before validating.'); return null; }
    const body: DebugDraftRequest = { packageId, target: debugForm.target, input: debugInput };
    if (environment) body.environment = environment;
    setPreviewLoading(true); setDebugError(''); setDebugStatus('Validating the effective Debug input…');
    try {
      const nextDraft = await api.previewDebug(body);
      setDraft(nextDraft); setDebugStatus(nextDraft.redacted ? 'Validation passed. Sensitive preview values remain hidden.' : 'Validation passed.');
      return nextDraft;
    } catch (error) { setDebugError(userError(error, 'Debug preview')); setDebugStatus('Debug preview failed.'); return null; }
    finally { setPreviewLoading(false); }
  };

  const startDebug = async () => {
    if (!debugForm || !identity || !formValid) { setDebugError('Correct the typed fields or Advanced JSON before starting.'); return; }
    let currentDraft = draft;
    const expiresAt = currentDraft?.expiresAt ? Date.parse(currentDraft.expiresAt) : Number.NaN;
    if (!currentDraft || !Number.isFinite(expiresAt) || expiresAt <= Date.now() + 15_000) currentDraft = await previewDebug();
    if (!currentDraft) return;
    if (!window.confirm(`Start Debug for ${detail?.logicalId || detail?.name || identity.resourceId}?`)) return;
    setSubmitLoading(true); setDebugError(''); setDebugStatus('Submitting Debug…');
    try {
      const accepted = await api.submitDebug({ packageId, draftId: currentDraft.draftId, principal: principal.trim() || undefined });
      setDraft(null); updateDirty(false); setDebugStatus(`Debug job ${accepted.jobId} was accepted. Opening job status…`);
      onJobAccepted(accepted.jobId);
    } catch (error) {
      const status = statusOf(error);
      if (status === 404 || status === 409) setDraft(null);
      setDebugError(userError(error, 'Debug submission'));
      setDebugStatus(status === 404 || status === 409 ? 'This Debug draft expired or became stale. Validate the current input again.' : 'Debug was not submitted.');
    } finally { setSubmitLoading(false); }
  };

  const resetDebug = () => {
    if (!debugForm) return;
    setDebugInput(JSON.parse(JSON.stringify(debugForm.input || {})) as JsonObject);
    setDraft(null); setDebugError(''); setDebugStatus('Defaults restored. Validate before starting Debug.');
    setFormValid(true); setResetKey(value => value + 1); updateDirty(false);
  };

  const runCase = async () => {
    if (!detail || detail.type !== 'case' || !detail.logicalId || !detail.provenance?.suite) return;
    if (!window.confirm(`Run case ${detail.logicalId}?`)) return;
    setDetailError(''); setDetailLoading(true);
    try {
      const accepted = await api.runCase({ packageId, suite: String(detail.provenance.suite), caseId: detail.logicalId, principal: principal.trim() || undefined });
      updateDirty(false); onJobAccepted(accepted.jobId);
    } catch (error) { setDetailError(userError(error, 'case run')); }
    finally { setDetailLoading(false); }
  };

  const changeEnvironment = (next: string) => {
    if (next === environment) return;
    if (dirtyRef.current && !window.confirm('Changing the environment will discard unsaved Debug edits. Continue?')) return;
    if (dirtyRef.current) { resetDebug(); }
    onEnvironmentChange(next);
  };

  const changePrincipal = (next: string) => {
    setPrincipal(next); onPrincipalLabelChange?.(next);
  };

  const selectedResource = detail || resources.find(item => resourceKey(item) === selectedKey) || null;
  const debugEnabled = isRunnable(selectedResource);
  const selectedType = resourceRoute?.type;
  const groups = debugGroups(debugForm?.target.type || selectedType);
  const previewText = draft?.previewYaml || (draft?.preview ? JSON.stringify(draft.preview, null, 2) : '');
  const detailText = safeProjection(definition);
  const sourceIdentity = identity;
  const onTabsKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const tabs: DetailTab[] = ['definition', 'source', 'relations'];
    const currentIndex = tabs.indexOf(tab);
    const nextIndex = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1
      : (currentIndex + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
    setTab(tabs[nextIndex]);
    event.currentTarget.querySelector<HTMLButtonElement>(`[data-tab="${tabs[nextIndex]}"]`)?.focus();
  };

  return <main className="att-explorer-page">
    <header className="att-explorer-page-heading">
      <div><p className="att-explorer-breadcrumb">Packages / {packageId} / Explorer</p><h1>Package Explorer</h1>
        <p>Browse safe resource projections, inspect configuration, and run a selected case or Debug target.</p></div>
      <label>Run, Debug, and Load environment
        <input value={environment} maxLength={128} onChange={event => changeEnvironment(event.currentTarget.value)} placeholder="Package default" />
      </label>
    </header>

    <div className="att-explorer-workspace">
      <aside className="att-explorer-navigation" aria-label="Resource navigation">
        <h2>Resources</h2>
        <label>Search resources <input type="search" value={query} maxLength={200} onChange={event => setQuery(event.currentTarget.value)} /></label>
        <label>Resource type <select value={typeFilter} onChange={event => setTypeFilter(event.currentTarget.value as typeof typeFilter)}>
          <option value="all">All types</option><option value="case">Cases</option><option value="template">Templates</option><option value="flow">Flows</option><option value="tool">Tools</option>
        </select></label>
        <p className="att-explorer-status" role="status" aria-live="polite">{resourceStatus}</p>
        {resourceError && <p className="att-error" role="alert">{resourceError}<button type="button" onClick={() => setResourceRefresh(value => value + 1)}>Retry</button></p>}
        <ResourceTree items={resources} selectedKey={selectedKey} loading={loadingResources} nextCursor={cursor}
          onSelect={resource => onResourceRouteChange({ type: resource.type, resourceId: resource.resourceId })}
          onLoadMore={() => void loadMore()} />
        <p className="att-explorer-total">{totalResources.toLocaleString()} total indexed resources</p>
      </aside>

      <section className="att-explorer-detail" aria-label="Resource details">
        {!identity && <div className="att-explorer-empty-state"><h2>Select a resource</h2><p>Choose a Case, Template, Flow, or Tool from the resource tree.</p></div>}
        {identity && <>
          <header className="att-resource-title">
            <div><p>{identity.type} · {packageId}</p><h2>{detail?.name || detail?.logicalId || identity.resourceId}</h2>
              <p>{detail?.description || detail?.logicalId || ''}</p></div>
            {detail?.state === 'invalid' && <span className="att-status-badge" data-state="error">Invalid resource</span>}
          </header>
          {detailLoading && <p role="status">Loading resource detail…</p>}
          {detailError && <p className="att-error" role="alert">{detailError}</p>}
          <div className="att-explorer-tabs" role="tablist" aria-label="Resource content" onKeyDown={onTabsKeyDown}>
            {(['definition', 'source', 'relations'] as const).map((name, index) => <button key={name} type="button" role="tab" data-tab={name}
              id={`att-explorer-tab-${name}`} aria-selected={tab === name} aria-controls={`att-explorer-panel-${name}`}
              tabIndex={tab === name ? 0 : -1} onClick={() => setTab(name)}>{name === 'definition' ? 'Definition' : name === 'source' ? 'Source' : 'Dependencies'}</button>)}
          </div>
          <section id="att-explorer-panel-definition" role="tabpanel" aria-labelledby="att-explorer-tab-definition" hidden={tab !== 'definition'}>
            {definition == null && !detailLoading ? <p>No definition projection is available.</p> : <pre className="att-definition-view">{detailText}</pre>}
          </section>
          {sourceIdentity && <section id="att-explorer-panel-source" role="tabpanel" aria-labelledby="att-explorer-tab-source" hidden={tab !== 'source'}>
            <SourceViewer api={api} identity={sourceIdentity} available={detail?.sourceAvailable === true} active={tab === 'source'} />
          </section>}
          <section id="att-explorer-panel-relations" role="tabpanel" aria-labelledby="att-explorer-tab-relations" hidden={tab !== 'relations'}>
            <div className="att-relation-columns">
              <div><h3>References</h3><ul>{(detail?.references || []).map((reference, index) => <li key={`${reference.type}:${reference.resourceId}:${index}`}>
                {reference.logicalId || reference.name || reference.resourceId} <span>{reference.type || reference.resolution || 'unresolved'}</span>
              </li>)}</ul>{!detail?.references?.length && <p>None</p>}</div>
              <div><h3>Referenced by</h3><ul>{(detail?.referencedBy || []).map((reference, index) => <li key={`${reference.type}:${reference.resourceId}:${index}`}>
                {reference.logicalId || reference.name || reference.resourceId} <span>{reference.type || reference.resolution || 'unresolved'}</span>
              </li>)}</ul>{!detail?.referencedBy?.length && <p>None</p>}</div>
            </div>
            {detail?.diagnostics?.map((item, index) => <p className="att-diagnostic" key={`${item.code}:${index}`}>{item.code ? `${item.code}: ` : ''}{item.summary}</p>)}
          </section>
        </>}
      </section>

      <aside className="att-explorer-inspector" aria-label="Contextual actions">
        <h2>Inspector</h2>
        {!selectedResource && <p>Select a resource to view its metadata and actions.</p>}
        {selectedResource && <>
          <dl className="att-resource-meta"><div><dt>Type</dt><dd>{selectedResource.type}</dd></div>
            <div><dt>Logical ID</dt><dd>{selectedResource.logicalId || '—'}</dd></div>
            <div><dt>State</dt><dd>{selectedResource.state || 'ready'}</dd></div>
            <div><dt>Tags</dt><dd>{selectedResource.tags?.join(', ') || '—'}</dd></div></dl>
          <label>Anonymous job label (optional)<input value={principal} maxLength={120} onChange={event => changePrincipal(event.currentTarget.value)} /></label>
          {selectedResource.type === 'case' && <button type="button" disabled={detailLoading || !detail?.provenance?.suite || !detail.logicalId} onClick={() => void runCase()}>Run selected case</button>}
          {debugEnabled && <button type="button" aria-expanded={debugOpen} onClick={() => setDebugOpen(value => !value)}>{debugOpen ? 'Close Debug' : 'Debug this target'}</button>}
          {onOpenQuickLoad && debugEnabled && identity && <button type="button" onClick={() => onOpenQuickLoad(identity)}>Quick Load</button>}
          {debugOpen && <section className="att-debug-panel" aria-labelledby="att-debug-heading">
            <h3 id="att-debug-heading">Debug / Test Runner</h3>
            <p className="att-debug-status" role="status" aria-live="polite">{debugStatus}</p>
            {debugError && <p className="att-error" role="alert">{debugError}</p>}
            {debugForm && <>
              <TypedSidecarForm key={`${selectedRouteKey}:${resetKey}`} value={debugInput} groups={groups} resetKey={resetKey}
                onChange={setBusinessInput} onValidityChange={setFormValid} />
              <div className="att-debug-actions">
                <button type="button" onClick={resetDebug} disabled={previewLoading || submitLoading}>Reset defaults</button>
                <button type="button" onClick={() => void previewDebug()} disabled={previewLoading || submitLoading || !formValid}>{previewLoading ? 'Validating…' : 'Validate and preview'}</button>
                <button type="button" onClick={() => void startDebug()} disabled={submitLoading || previewLoading || !formValid}>{submitLoading ? 'Starting…' : 'Start Debug'}</button>
              </div>
              {previewText && <section className="att-debug-preview"><h4>Safe effective Debug preview</h4><pre>{previewText}</pre></section>}
            </>}
          </section>}
        </>}
      </aside>
    </div>
    <ConfigurationExplorer api={api} packageId={packageId} environment={environment} onEnvironmentChange={changeEnvironment} />
  </main>;
}
