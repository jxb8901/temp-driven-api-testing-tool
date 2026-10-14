import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent, type UIEvent } from 'react';
import { ResourceTree, resourceKey } from './ResourceTree';
import { SourceViewer } from './SourceViewer';
import { CodeEditor } from '../code/CodeEditor';
import { TypedSidecarForm } from './TypedSidecarForm';
import type { DebugDraftRequest, Diagnostic, DraftResponse, ExplorerApi, ExplorerPageProps, JsonObject, ResourceIdentity, ResourceReference, ResourceSortField, ResourceSummary } from './types';

const MAX_DEFINITION_CHARACTERS = 100_000;
const RESOURCE_PAGE_SIZE = 50;

interface ExplorerListSnapshot {
  query: string;
  typeFilter: ResourceSummary['type'] | 'all';
  sortKey: ResourceSortField;
  sortDirection: 'asc' | 'desc';
  pagesLoaded: number;
  scrollTop: number;
}

const explorerListSnapshots = new Map<string, ExplorerListSnapshot>();

function savedExplorerList(packageId: string): ExplorerListSnapshot {
  return explorerListSnapshots.get(packageId) || {
    query: '', typeFilter: 'all', sortKey: 'name', sortDirection: 'asc', pagesLoaded: 1, scrollTop: 0,
  };
}

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
function isResourceType(type: string): type is ResourceSummary['type'] {
  return ['case', 'template', 'flow', 'tool'].includes(type);
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

function ExplorerReference({ reference, onOpen }: { reference: ResourceReference; onOpen: ExplorerPageProps['onResourceRouteChange'] }) {
  const label = reference.logicalId || reference.name || reference.resourceId || 'Unresolved reference';
  if (reference.targetKind === 'helper' && reference.resolution === 'resolved') {
    const callSite = reference.callSite && typeof reference.callSite.actionId === 'string' ? reference.callSite.actionId : '';
    return <li><details className="att-helper-reference"><summary>{label}<span>Configured Helper method</span></summary>
      <p>This call resolves through the package Helper configuration. It is not a separate Tool resource and cannot be opened as a Tool.</p>
      {callSite && <p>Called from action {callSite}.</p>}
    </details></li>;
  }
  const valid = Boolean(reference.type && ['case', 'template', 'flow', 'tool'].includes(reference.type) && reference.resourceId && reference.resolution !== 'unresolved');
  return <li>{valid ? <button type="button" onClick={() => onOpen({ type: reference.type!, resourceId: reference.resourceId!, tab: 'definition' })}>{label}</button> : <span>{label}</span>}
    <span>{reference.resolution === 'unresolved' ? 'Unresolved reference' : reference.type || reference.resolution || 'unresolved'}</span></li>;
}

export function ExplorerPage({ api, packageId, resourceRoute, environment, principalLabel = '', onResourceRouteChange,
  onEnvironmentChange, onJobAccepted, onPrincipalLabelChange, onOpenQuickLoad, onOpenConfiguration, onOpenConfigurationFile, registerUnsavedChanges }: ExplorerPageProps) {
  const initialListState = useRef(savedExplorerList(packageId)).current;
  const [query, setQuery] = useState(initialListState.query);
  const [typeFilter, setTypeFilter] = useState<ResourceSummary['type'] | 'all'>(initialListState.typeFilter);
  const [sortKey, setSortKey] = useState<ResourceSortField>(initialListState.sortKey);
  const [sortDirection, setSortDirection] = useState<'asc' | 'desc'>(initialListState.sortDirection);
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
  const resourceListRef = useRef<HTMLDivElement | null>(null);
  const pagesToRestore = useRef(Math.max(1, initialListState.pagesLoaded));
  const savedScrollTop = useRef(Math.max(0, initialListState.scrollTop));
  const didRestoreScroll = useRef(false);
  const scrollSaveTimer = useRef<number | null>(null);
  const listStateRef = useRef({ packageId, query, typeFilter, sortKey, sortDirection });
  listStateRef.current = { packageId, query, typeFilter, sortKey, sortDirection };
  const dirtyRef = useRef(false);
  const unregisterDirtyRef = useRef<(() => void) | null>(null);
  const selectedRouteKey = resourceRouteKey(resourceRoute);
  const identity = useMemo<ResourceIdentity | null>(() => resourceRoute && isResourceType(resourceRoute.type)
    ? { packageId, type: resourceRoute.type, resourceId: resourceRoute.resourceId } : null,
  [packageId, resourceRoute?.type, resourceRoute?.resourceId]);
  const selectedKey = resourceRoute ? `${resourceRoute.type}:${resourceRoute.resourceId}` : '';

  const updateDirty = useCallback((next: boolean) => {
    dirtyRef.current = next;
    if (!next) {
      unregisterDirtyRef.current?.();
      unregisterDirtyRef.current = null;
    }
    setDirty(next);
  }, []);

  useEffect(() => {
    const unregister = registerUnsavedChanges(dirty, 'Explorer Debug and Test Runner edits');
    unregisterDirtyRef.current = unregister;
    return () => {
      unregister();
      if (unregisterDirtyRef.current === unregister) unregisterDirtyRef.current = null;
    };
  }, [dirty, registerUnsavedChanges]);
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
    setLoadingResources(true);
    const requestedPages = Math.min(pagesToRestore.current, 1000);
    const timer = window.setTimeout(() => {
      void (async () => {
        const accumulated: ResourceSummary[] = [];
        let nextCursor: string | null = null;
        let total = 0;
        let diagnostics: Diagnostic[] = [];
        let pagesFetched = 0;
        try {
          for (let index = 0; index < requestedPages; index++) {
            const page = await api.listResources(packageId, {
              query: query.trim() || undefined, type: typeFilter, sort: sortKey, direction: sortDirection,
              ...(nextCursor ? { cursor: nextCursor } : {}), limit: RESOURCE_PAGE_SIZE,
            }, controller.signal);
            if (controller.signal.aborted) return;
            accumulated.push(...(page.items || []));
            total = Number(page.total || 0);
            nextCursor = page.nextCursor || null;
            diagnostics = page.diagnostics || [];
            pagesFetched++;
            if (!nextCursor) break;
          }
          if (controller.signal.aborted) return;
          pagesToRestore.current = Math.max(1, pagesFetched);
          setResources(accumulated); setTotalResources(total); setCursor(nextCursor);
          const warning = diagnostics.map(item => item.summary || item.code).filter(Boolean).join(' ');
          setResourceStatus(`Showing ${accumulated.length} of ${total} resources.${warning ? ` ${warning}` : ''}`);
          setResourceError('');
          explorerListSnapshots.set(packageId, {
            ...listStateRef.current, pagesLoaded: pagesToRestore.current, scrollTop: savedScrollTop.current,
          });
        } catch (error) {
          if (!controller.signal.aborted) { setResourceError(userError(error, 'resource discovery')); setResourceStatus('Resource discovery failed.'); }
        } finally { if (!controller.signal.aborted) setLoadingResources(false); }
      })();
    }, query ? 220 : 0);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [api, packageId, query, typeFilter, sortKey, sortDirection, resourceRefresh]);

  useEffect(() => {
    explorerListSnapshots.set(packageId, {
      ...listStateRef.current, pagesLoaded: pagesToRestore.current, scrollTop: savedScrollTop.current,
    });
  }, [packageId, query, typeFilter, sortKey, sortDirection]);

  useEffect(() => {
    if (loadingResources || didRestoreScroll.current) return;
    const container = resourceListRef.current;
    if (!container) return;
    container.scrollTop = savedScrollTop.current;
    didRestoreScroll.current = true;
  }, [loadingResources, resources.length]);

  useEffect(() => () => {
    if (scrollSaveTimer.current !== null) window.clearTimeout(scrollSaveTimer.current);
    explorerListSnapshots.set(packageId, {
      ...listStateRef.current, pagesLoaded: pagesToRestore.current, scrollTop: savedScrollTop.current,
    });
  }, [packageId]);

  const resetListPosition = () => {
    pagesToRestore.current = 1;
    savedScrollTop.current = 0;
    didRestoreScroll.current = true;
    if (resourceListRef.current) resourceListRef.current.scrollTop = 0;
  };

  const handleListScroll = (event: UIEvent<HTMLDivElement>) => {
    savedScrollTop.current = event.currentTarget.scrollTop;
    if (scrollSaveTimer.current !== null) window.clearTimeout(scrollSaveTimer.current);
    scrollSaveTimer.current = window.setTimeout(() => {
      explorerListSnapshots.set(packageId, {
        ...listStateRef.current, pagesLoaded: pagesToRestore.current, scrollTop: savedScrollTop.current,
      });
      scrollSaveTimer.current = null;
    }, 100);
  };

  const changeSort = (field: ResourceSortField) => {
    resetListPosition();
    if (field === sortKey) setSortDirection(value => value === 'asc' ? 'desc' : 'asc');
    else { setSortKey(field); setSortDirection('asc'); }
  };

  const loadMore = async () => {
    if (!cursor || loadingResources) return;
    setLoadingResources(true); setResourceError('');
    try {
      const page = await api.listResources(packageId, { query: query.trim() || undefined, type: typeFilter, sort: sortKey, direction: sortDirection, cursor, limit: 50 });
      setResources(current => [...current, ...(page.items || [])]); setCursor(page.nextCursor || null);
      setResourceStatus(`Showing ${resources.length + (page.items || []).length} of ${totalResources} resources.`);
      pagesToRestore.current++;
      explorerListSnapshots.set(packageId, {
        ...listStateRef.current, pagesLoaded: pagesToRestore.current, scrollTop: savedScrollTop.current,
      });
    } catch (error) { setResourceError(userError(error, 'loading more resources')); }
    finally { setLoadingResources(false); }
  };

  useEffect(() => {
    if (!identity) { setDetail(null); setDefinition(null); setDetailError(''); return; }
    const controller = new AbortController();
    setDetailLoading(true); setDetailError(''); setDetail(null); setDefinition(null);
    api.getResource(identity, controller.signal).then(response => {
      if (controller.signal.aborted) return;
      setDetail(response.resource); setDefinition(response.definition ?? {}); setDetailError('');
    }).catch(error => {
      if (!controller.signal.aborted) setDetailError(userError(error, 'resource detail'));
    }).finally(() => { if (!controller.signal.aborted) setDetailLoading(false); });
    return () => controller.abort();
  }, [api, identity?.packageId, identity?.type, identity?.resourceId]);

  useEffect(() => {
    const requestedTab = resourceRoute?.tab;
    setTab(requestedTab === 'source' || requestedTab === 'relations' || requestedTab === 'definition' ? requestedTab : 'definition');
  }, [selectedRouteKey, resourceRoute?.tab]);

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
    if (identity) onResourceRouteChange({ type: identity.type, resourceId: identity.resourceId, tab: tabs[nextIndex] });
    event.currentTarget.querySelector<HTMLButtonElement>(`[data-tab="${tabs[nextIndex]}"]`)?.focus();
  };
  const selectDetailTab = (next: DetailTab) => {
    setTab(next);
    if (identity) onResourceRouteChange({ type: identity.type, resourceId: identity.resourceId, tab: next });
  };

  return <main className="att-explorer-page">
    <header className="att-explorer-page-heading">
      <div><p className="att-explorer-breadcrumb">Packages / {packageId} / Explorer</p><h1>Package Explorer</h1>
        <p>Package resources and counts are independent of runtime environment. Select an environment only when inspecting or running a resource.</p></div>
      {identity && <label>Run, Debug, and Load environment
        <input value={environment} maxLength={128} onChange={event => changeEnvironment(event.currentTarget.value)} placeholder="Package default" />
      </label>}
    </header>

    <div className={`att-explorer-workspace${identity ? ' has-selected-resource' : ' is-list-first'}`}>
      <aside className="att-explorer-navigation" aria-label="Resource navigation">
        <h2>Resources</h2>
        <label>Search resources <input type="search" value={query} maxLength={200} onChange={event => { resetListPosition(); setQuery(event.currentTarget.value); }} /></label>
        <div className="att-explorer-categories" role="tablist" aria-label="Resource categories">
          {([
            ['all', 'All'], ['case', 'Test Cases'], ['template', 'Templates'], ['flow', 'Flows'], ['tool', 'Tools'],
          ] as Array<[ResourceSummary['type'] | 'all', string]>).map(([value, label]) =>
            <button key={value} type="button" role="tab" aria-selected={typeFilter === value}
              onClick={() => { resetListPosition(); setTypeFilter(value); }}>{label}</button>)}
        </div>
        <p className="att-explorer-status" role="status" aria-live="polite">{resourceStatus}</p>
        {resourceError && <p className="att-error" role="alert">{resourceError}<button type="button" onClick={() => setResourceRefresh(value => value + 1)}>Retry</button></p>}
        <ResourceTree items={resources} selectedKey={selectedKey} loading={loadingResources} nextCursor={cursor} sortKey={sortKey} direction={sortDirection} onSortChange={changeSort}
          onSelect={resource => onResourceRouteChange({ type: resource.type, resourceId: resource.resourceId })}
          onLoadMore={() => void loadMore()} scrollContainerRef={resourceListRef} onListScroll={handleListScroll} />
        <p className="att-explorer-total">{totalResources.toLocaleString()} {typeFilter === 'all' ? 'package resources' : typeFilter + ' resources'}</p>
      </aside>

      <section className="att-explorer-detail" aria-label="Resource details" hidden={!identity}>
        {!identity && <div className="att-explorer-empty-state"><h2>Select a resource</h2><p>Choose a Case, Template, Flow, or Tool from the resource list.</p></div>}
        {identity && <>
          <header className="att-resource-title">
            <div><p>{identity.type} · {packageId}</p><h2>{detail?.name || detail?.logicalId || identity.resourceId}</h2>
              <p>{detail?.description || detail?.logicalId || ''}</p></div>
            {detail?.state === 'invalid' && <span className="att-status-badge" data-state="error">Invalid resource</span>}
          </header>
          {detailLoading && <p role="status">Loading resource detail…</p>}
          {detailError && <p className="att-error" role="alert">{detailError}</p>}
          <div className="att-explorer-tabs" role="tablist" aria-label="Resource content" onKeyDown={onTabsKeyDown}>
            {(['definition', 'source', 'relations'] as const).map(name => <button key={name} type="button" role="tab" data-tab={name}
              id={`att-explorer-tab-${name}`} aria-selected={tab === name} aria-controls={`att-explorer-panel-${name}`}
              tabIndex={tab === name ? 0 : -1} onClick={() => selectDetailTab(name)}>{name === 'definition' ? 'Definition' : name === 'source' ? 'Source' : 'Dependencies'}</button>)}
          </div>
          <section id="att-explorer-panel-definition" role="tabpanel" aria-labelledby="att-explorer-tab-definition" hidden={tab !== 'definition'}>
            {definition == null && !detailLoading ? <p>No definition projection is available.</p> : <CodeEditor value={detailText} language="json" label="Safe resource definition" readOnly sourcePath={detail?.sourcePath} fileReferences={[]} />}
          </section>
          {sourceIdentity && <section id="att-explorer-panel-source" role="tabpanel" aria-labelledby="att-explorer-tab-source" hidden={tab !== 'source'}>
            <SourceViewer api={api} identity={sourceIdentity} available={detail?.sourceAvailable === true} active={tab === 'source'} onFileReference={onOpenConfigurationFile} />
          </section>}
          <section id="att-explorer-panel-relations" role="tabpanel" aria-labelledby="att-explorer-tab-relations" hidden={tab !== 'relations'}>
            <div className="att-relation-columns">
              <div><h3>References</h3><ul>{(detail?.references || []).map((reference, index) => <ExplorerReference key={`${reference.type}:${reference.resourceId}:${index}`} reference={reference} onOpen={onResourceRouteChange} />)}</ul>{!detail?.references?.length && <p>None</p>}</div>
              <div><h3>Referenced by</h3><ul>{(detail?.referencedBy || []).map((reference, index) => <ExplorerReference key={`${reference.type}:${reference.resourceId}:${index}`} reference={reference} onOpen={onResourceRouteChange} />)}</ul>{!detail?.referencedBy?.length && <p>None</p>}</div>
            </div>
            {detail?.diagnostics?.map((item, index) => <p className="att-diagnostic" key={`${item.code}:${index}`}>{item.code ? `${item.code}: ` : ''}{item.summary}</p>)}
          </section>
        </>}
      </section>

      <aside className="att-explorer-inspector" aria-label="Contextual actions" hidden={!identity}>
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
          {onOpenConfiguration && identity && ['template', 'flow', 'tool'].includes(identity.type) && <button type="button" onClick={() => onOpenConfiguration(identity)}>Open in Configuration</button>}
          {onOpenQuickLoad && debugEnabled && identity && <button type="button" onClick={() => onOpenQuickLoad(identity)}>Quick Load</button>}
          {debugOpen && <section className="att-debug-panel" aria-labelledby="att-debug-heading">
            <h3 id="att-debug-heading">Debug / Test Runner</h3>
            <p className="att-debug-status" role="status" aria-live="polite">{debugStatus}</p>
            {debugError && <p className="att-error" role="alert">{debugError}</p>}
            {debugForm && <>
              <TypedSidecarForm key={`${selectedRouteKey}:${resetKey}`} value={debugInput} groups={groups} resetKey={resetKey}
                onChange={setBusinessInput} onValidityChange={setFormValid} />
              <div className="att-debug-actions">
                <button type="button" disabled title="The Server scheduler accepts immutable Load scenarios, not Debug draft overrides.">Schedule…</button>
                <button type="button" onClick={resetDebug} disabled={previewLoading || submitLoading}>Reset defaults</button>
                <button type="button" onClick={() => void previewDebug()} disabled={previewLoading || submitLoading || !formValid}>{previewLoading ? 'Validating…' : 'Validate and preview'}</button>
                <button type="button" onClick={() => void startDebug()} disabled={submitLoading || previewLoading || !formValid}>{submitLoading ? 'Starting…' : 'Start Debug'}</button>
              </div>
              <p className="att-help" role="note">Debug runs are one-shot. Schedule is available only for validated immutable Load scenarios; Debug draft overrides are not converted to recurring Load jobs.</p>
              {previewText && <section className="att-debug-preview"><h4>Safe effective Debug preview</h4><CodeEditor value={previewText} language={draft?.previewYaml ? 'yaml' : 'json'} label="Safe effective Debug preview" readOnly /></section>}
            </>}
          </section>}
        </>}
      </aside>
    </div>
  </main>;
}
