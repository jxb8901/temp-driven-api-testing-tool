import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ApiError, type AsyncState, type JobSummary } from '../../types';
import type { LoadCatalogItem, PackageOption, ServerDiagnostic } from './types';
import type { api } from '../../api';
import { formatRoute, type LoadCatalogRouteState } from '../../router';
import './load.css';

type ScheduleIntent = { packageId: string; environment?: string; draftId: string; previewYaml?: string; expiresAt?: string; executionType: 'load' };
type CatalogApi = Pick<typeof api, 'listLoadCatalog' | 'validateLoadCatalogScenario' | 'submitLoadScenario' | 'listLoadJobs'>
  & Partial<Pick<typeof api, 'getDeclaredConfiguration'>>;
type CatalogJob = JobSummary & { scenario?: string; environment?: string; model?: string };
type CatalogFailure = { message: string; suggestion?: string; cursor?: string; query: string; retrying?: boolean };

function catalogFailure(error: unknown, cursor: string | undefined, query: string): CatalogFailure {
  const first = error instanceof ApiError && Array.isArray(error.diagnostics) ? error.diagnostics[0] : undefined;
  const suggestion = first && typeof first === 'object' && 'suggestion' in first && typeof first.suggestion === 'string'
    ? first.suggestion : undefined;
  return { message: error instanceof Error ? error.message : 'LOAD catalog could not be loaded.', suggestion, cursor, query };
}

function jobDuration(job: CatalogJob): string | null {
  if (!job.startedAt || !job.finishedAt) return null;
  const milliseconds = Date.parse(job.finishedAt) - Date.parse(job.startedAt);
  if (!Number.isFinite(milliseconds) || milliseconds < 0) return null;
  return milliseconds < 1000 ? `${milliseconds} ms` : `${(milliseconds / 1000).toFixed(1)} s`;
}

function safeDiagnosticField(value: unknown): string | undefined {
  return typeof value === 'string' && value.length <= 1000 && !/[\u0000-\u001f\u007f]/.test(value) ? value.trim() || undefined : undefined;
}
function safeDiagnosticPath(value: unknown): string | undefined {
  const path = safeDiagnosticField(value);
  if (!path || path.startsWith('/') || /^[A-Za-z]:[\\/]/.test(path) || path.startsWith('\\\\')) return undefined;
  return path;
}
function safeCatalogDiagnostics(value: unknown): ServerDiagnostic[] {
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is Record<string, unknown> => Boolean(item && typeof item === 'object' && !Array.isArray(item)))
    .map(item => ({ code: safeDiagnosticField(item.code), reason: safeDiagnosticField(item.reason), field: safeDiagnosticField(item.field),
      path: safeDiagnosticPath(item.path), severity: safeDiagnosticField(item.severity), message: safeDiagnosticField(item.message),
      summary: safeDiagnosticField(item.summary), suggestion: safeDiagnosticField(item.suggestion),
      line: typeof item.line === 'number' ? item.line : undefined, column: typeof item.column === 'number' ? item.column : undefined }));
}

export function LoadCatalogPage({ api, packages, inlineLoadEnabled, schedulingEnabled, environment, catalogState: initialCatalogState, onEnvironmentChange,
  onNewLoad, onQuickLoad, onJobAccepted, onOpenJob, onSchedule, onOpenDetail }: {
  api: CatalogApi; packages: AsyncState<PackageOption[]>; inlineLoadEnabled: boolean; schedulingEnabled: boolean;
  environment: string; catalogState?: LoadCatalogRouteState; onEnvironmentChange(value: string): void; onNewLoad(packageId?: string): void;
  onQuickLoad(packageId: string, template: LoadCatalogItem): void; onJobAccepted(jobId: string): void;
  onOpenJob(jobId: string): void; onSchedule(intent: ScheduleIntent): void;
  onOpenDetail(item: LoadCatalogItem, environment: string, returnTo: string): void;
}) {
  const [catalog, setCatalog] = useState<LoadCatalogItem[]>([]);
  const [catalogState, setCatalogState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [catalogMoreLoading, setCatalogMoreLoading] = useState(false);
  const [catalogError, setCatalogError] = useState('');
  const [catalogFailures, setCatalogFailures] = useState<Record<string, CatalogFailure>>({});
  const catalogFailuresRef = useRef<Record<string, CatalogFailure>>({});
  const [catalogCursors, setCatalogCursors] = useState<Record<string, string>>({});
  const catalogCursorsRef = useRef<Record<string, string>>({});
  const catalogRequestRef = useRef<AbortController | null>(null);
  const catalogGenerationRef = useRef(0);
  const validationGenerationRef = useRef(0);
  const [refreshKey, setRefreshKey] = useState(0);
  const [query, setQuery] = useState(initialCatalogState?.query || '');
  const queryRef = useRef(query);
  queryRef.current = query;
  const [packageFilter, setPackageFilter] = useState(initialCatalogState?.packageFilter || 'all');
  const [kindFilter, setKindFilter] = useState(initialCatalogState?.kindFilter || 'all');
  const [modelFilter, setModelFilter] = useState(initialCatalogState?.modelFilter || 'all');
  const [validationFilter, setValidationFilter] = useState(initialCatalogState?.validationFilter || 'all');
  const [environmentDraft, setEnvironmentDraft] = useState(initialCatalogState?.environment ?? environment);
  const [environmentProfiles, setEnvironmentProfiles] = useState<string[]>([]);
  const [environmentProfilesState, setEnvironmentProfilesState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [environmentReload, setEnvironmentReload] = useState(0);
  const [actionKey, setActionKey] = useState('');
  const actionSequenceRef = useRef(0);
  const [notice, setNotice] = useState('');
  const [clockNow, setClockNow] = useState(Date.now());
  const [jobs, setJobs] = useState<CatalogJob[]>([]);
  const [jobsState, setJobsState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [jobsError, setJobsError] = useState('');
  const [jobsMore, setJobsMore] = useState<number | null>(null);
  const environmentDraftRef = useRef(environmentDraft);
  environmentDraftRef.current = environmentDraft;
  const selectedPackage = packageFilter === 'all'
    ? packages.status === 'ready' ? packages.data[0]?.packageId : undefined
    : packageFilter;

  useEffect(() => {
    const scrollY = initialCatalogState?.scrollY;
    if (typeof scrollY === 'number') requestAnimationFrame(() => window.scrollTo(0, scrollY));
  }, []);

  useEffect(() => {
    const timer = window.setInterval(() => setClockNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);

  const invalidateEnvironmentValidation = useCallback((value: string) => {
    validationGenerationRef.current += 1;
    environmentDraftRef.current = value;
    setEnvironmentDraft(value);
    if (actionKey.startsWith('validate:')) setActionKey('');
    setCatalog(current => current.map(item => item.kind === 'scenario'
      ? { ...item, validation: 'unverified', validatedEnvironment: undefined, runNow: false, schedule: false,
        diagnostic: undefined, diagnostics: undefined, requestId: undefined, scheduleDraftId: undefined,
        schedulePreviewYaml: undefined, scheduleDraftExpiresAt: undefined, scheduleReason: undefined }
      : item));
  }, [actionKey]);

  useEffect(() => {
    if (environment !== environmentDraftRef.current) invalidateEnvironmentValidation(environment);
  }, [environment, invalidateEnvironmentValidation]);

  useEffect(() => {
    const controller = new AbortController();
    if (!selectedPackage) {
      setEnvironmentProfiles([]);
      setEnvironmentProfilesState('ready');
      return () => controller.abort();
    }
    if (!api.getDeclaredConfiguration) {
      // Minimal component fakes may not model profile loading; the Server API always does.
      setEnvironmentProfiles(environmentDraftRef.current ? [environmentDraftRef.current] : []);
      setEnvironmentProfilesState('ready');
      return () => controller.abort();
    }
    setEnvironmentProfilesState('loading');
    api.getDeclaredConfiguration(selectedPackage, controller.signal).then(configuration => {
      if (controller.signal.aborted) return;
      const names = (configuration.environments || [])
        .filter(profile => profile.name && profile.state === 'active')
        .map(profile => profile.name);
      setEnvironmentProfiles(names);
      setEnvironmentProfilesState('ready');
      if (environmentDraftRef.current && !names.includes(environmentDraftRef.current)) {
        invalidateEnvironmentValidation('');
        onEnvironmentChange('');
      }
    }).catch(() => {
      if (!controller.signal.aborted) setEnvironmentProfilesState('error');
    });
    return () => controller.abort();
  }, [api, selectedPackage, environmentReload, invalidateEnvironmentValidation, onEnvironmentChange]);

  const loadCatalog = useCallback(async (signal: AbortSignal, append = false) => {
    if (packages.status !== 'ready') return;
    const requestGeneration = ++catalogGenerationRef.current;
    if (append) setCatalogMoreLoading(true);
    else {
      setCatalogMoreLoading(false); setCatalogState('loading');
      catalogFailuresRef.current = {}; setCatalogFailures({});
    }
    setCatalogError('');
    try {
    const gathered: LoadCatalogItem[] = [];
    const currentCursors = catalogCursorsRef.current;
    const nextCursors: Record<string, string> = append ? { ...currentCursors } : {};
    const failures: Record<string, CatalogFailure> = append
      ? Object.fromEntries(Object.entries(catalogFailuresRef.current).map(([packageId, failure]) => [packageId, { ...failure, retrying: false }]))
      : {};
    for (const pkg of packages.data) {
      const cursor = append ? currentCursors[pkg.packageId] : undefined;
      if (append && !cursor) continue;
      delete failures[pkg.packageId];
      try {
        const page = await api.listLoadCatalog(pkg.packageId, { query: query.trim() || undefined, cursor, limit: 100 }, signal);
        if (signal.aborted || requestGeneration !== catalogGenerationRef.current) return;
        gathered.push(...page.items);
        if (page.nextCursor) nextCursors[pkg.packageId] = page.nextCursor;
        else delete nextCursors[pkg.packageId];
      } catch (error) {
        if (signal.aborted || requestGeneration !== catalogGenerationRef.current) return;
        failures[pkg.packageId] = catalogFailure(error, cursor, query.trim());
      }
    }
    if (!signal.aborted && requestGeneration === catalogGenerationRef.current) {
      setCatalog(current => append ? [...current, ...gathered] : gathered);
      catalogCursorsRef.current = nextCursors; setCatalogCursors(nextCursors);
      catalogFailuresRef.current = failures; setCatalogFailures(failures); setCatalogState('ready');
    }
    } finally {
      if (append && requestGeneration === catalogGenerationRef.current) setCatalogMoreLoading(false);
    }
  }, [api, packages.status, packages.status === 'ready' ? packages.data : null, query]);

  const retryCatalogPackage = useCallback(async (packageId: string) => {
    const failure = catalogFailuresRef.current[packageId];
    const requestedQuery = queryRef.current.trim();
    if (!failure || failure.query !== requestedQuery || Object.values(catalogFailuresRef.current).some(item => item.retrying)) return;
    catalogRequestRef.current?.abort();
    const controller = new AbortController();
    catalogRequestRef.current = controller;
    const requestGeneration = ++catalogGenerationRef.current;
    const retryingFailures = { ...catalogFailuresRef.current, [packageId]: { ...failure, retrying: true } };
    catalogFailuresRef.current = retryingFailures; setCatalogFailures(retryingFailures);
    try {
      const page = await api.listLoadCatalog(packageId, { query: requestedQuery || undefined, cursor: failure.cursor, limit: 100 }, controller.signal);
      if (controller.signal.aborted || requestGeneration !== catalogGenerationRef.current) return;
      setCatalog(current => failure.cursor
        ? [...current, ...page.items]
        : [...current.filter(item => item.packageId !== packageId), ...page.items]);
      const cursors = { ...catalogCursorsRef.current };
      if (page.nextCursor) cursors[packageId] = page.nextCursor;
      else delete cursors[packageId];
      catalogCursorsRef.current = cursors; setCatalogCursors(cursors);
      const nextFailures = { ...catalogFailuresRef.current }; delete nextFailures[packageId];
      catalogFailuresRef.current = nextFailures; setCatalogFailures(nextFailures);
    } catch (error) {
      if (!controller.signal.aborted && requestGeneration === catalogGenerationRef.current) {
        const nextFailures = { ...catalogFailuresRef.current, [packageId]: { ...catalogFailure(error, failure.cursor, requestedQuery), retrying: false } };
        catalogFailuresRef.current = nextFailures; setCatalogFailures(nextFailures);
      }
    } finally {
      if (requestGeneration === catalogGenerationRef.current) setCatalogMoreLoading(false);
    }
  }, [api]);

  useEffect(() => {
    if (packages.status === 'loading') { setCatalogState('loading'); return; }
    if (packages.status === 'error') { setCatalogState('error'); setCatalogError('Configured packages could not be loaded.'); return; }
    const controller = new AbortController();
    catalogRequestRef.current?.abort();
    catalogRequestRef.current = controller;
    void loadCatalog(controller.signal);
    return () => {
      catalogRequestRef.current?.abort();
      catalogGenerationRef.current += 1;
      catalogRequestRef.current = null;
    };
  }, [packages.status, packages.status === 'ready' ? packages.data : null, loadCatalog, refreshKey]);

  const loadJobs = useCallback(async (offset = 0, append = false, signal?: AbortSignal) => {
    setJobsState(offset === 0 ? 'loading' : 'ready'); setJobsError('');
    try {
      const page = await api.listLoadJobs({ offset, limit: 50, signal });
      if (signal?.aborted) return;
      setJobs(current => append ? [...current, ...page.items] : page.items);
      setJobsMore(page.nextOffset ?? null); setJobsState('ready');
    } catch (error) {
      if (!signal?.aborted) { setJobsError(error instanceof Error ? error.message : 'LOAD job history could not be loaded.'); setJobsState('error'); }
    }
  }, [api]);
  useEffect(() => { const controller = new AbortController(); void loadJobs(0, false, controller.signal); return () => controller.abort(); }, [loadJobs]);

  const visible = useMemo(() => catalog.filter(item =>
    (packageFilter === 'all' || item.packageId === packageFilter)
    && (kindFilter === 'all' || item.kind === kindFilter)
    && (modelFilter === 'all' || item.model === modelFilter)
    && (validationFilter === 'all' || item.validation === validationFilter)), [catalog, packageFilter, kindFilter, modelFilter, validationFilter]);

  async function runNow(item: LoadCatalogItem) {
    if (!item.runNow || actionKey) return;
    const actionToken = ++actionSequenceRef.current;
    const confirmation = `Run this package LOAD scenario now?\n\nPackage: ${item.packageId}\nScenario: ${item.path}\nEnvironment: ${environmentDraft || 'Package default'}\nModel: ${item.model || 'Validated by Server'}\nWorkloads: ${item.workloadCount}`;
    if (!window.confirm(confirmation)) return;
    setActionKey(item.resourceId); setNotice('');
    try {
      const job = await api.submitLoadScenario({ packageId: item.packageId, scenario: item.path, ...(environmentDraft ? { environment: environmentDraft } : {}) });
      onJobAccepted(job.jobId);
    } catch (error) { setNotice(error instanceof Error ? error.message : 'The LOAD scenario could not be started.'); }
    finally { if (actionSequenceRef.current === actionToken) setActionKey(''); }
  }

  async function showSource(item: LoadCatalogItem) {
    const state: LoadCatalogRouteState = { query: query || undefined, packageFilter, kindFilter, modelFilter, validationFilter,
      environment: environmentDraft, scrollY: window.scrollY };
    const returnTo = formatRoute({ kind: 'load', catalog: state });
    window.history.replaceState(window.history.state, '', window.location.pathname + window.location.search + returnTo);
    onOpenDetail(item, environmentDraft, returnTo);
  }

  async function validateItem(item: LoadCatalogItem) {
    if (actionKey) return;
    const actionToken = ++actionSequenceRef.current;
    const key = `validate:${item.resourceId}`; setActionKey(key); setNotice('');
    const requestedEnvironment = environmentDraftRef.current;
    const requestedGeneration = validationGenerationRef.current;
    try {
      const result = await api.validateLoadCatalogScenario(item.packageId, item.resourceId, requestedEnvironment || undefined);
      if (actionSequenceRef.current !== actionToken || environmentDraftRef.current !== requestedEnvironment
        || validationGenerationRef.current !== requestedGeneration) return;
      setCatalog(current => current.map(candidate => candidate.packageId === item.packageId && candidate.resourceId === item.resourceId
        ? { ...candidate, validation: result.validation, validatedEnvironment: requestedEnvironment, model: result.model || candidate.model,
          diagnostic: undefined, diagnostics: undefined, requestId: undefined, runNow: true,
          schedule: schedulingEnabled && result.scheduleAvailable === true && Boolean(result.draftId),
          scheduleDraftId: result.draftId, schedulePreviewYaml: result.previewYaml,
          scheduleDraftExpiresAt: result.expiresAt, scheduleReason: result.scheduleReason }
        : candidate));
      setNotice(`${item.path} validation refreshed for ${requestedEnvironment || 'the package default'}: ${result.validation}.`);
    } catch (error) {
      if (actionSequenceRef.current !== actionToken || environmentDraftRef.current !== requestedEnvironment
        || validationGenerationRef.current !== requestedGeneration) return;
      if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
        setCatalog(current => current.map(candidate => candidate.packageId === item.packageId && candidate.resourceId === item.resourceId
          ? { ...candidate, validation: 'invalid', validatedEnvironment: requestedEnvironment, runNow: false, schedule: false,
            diagnostic: error.message, diagnostics: safeCatalogDiagnostics(error.diagnostics), requestId: error.requestId }
          : candidate));
      }
      setNotice(error instanceof Error ? error.message : 'The YAML resource could not be validated.');
    }
    finally { if (actionSequenceRef.current === actionToken) setActionKey(''); }
  }

  async function validateTemplate(item: LoadCatalogItem) {
    if (actionKey) return;
    const actionToken = ++actionSequenceRef.current;
    setActionKey(`validate:${item.resourceId}`); setNotice('');
    try {
      const page = await api.listLoadCatalog(item.packageId, { query: item.path, limit: 100 });
      const refreshed = page.items.find(candidate => candidate.resourceId === item.resourceId);
      if (actionSequenceRef.current !== actionToken) return;
      if (!refreshed) throw new Error('This Load Template is no longer present in the current package catalog. Refresh the catalog.');
      setCatalog(current => current.map(candidate => candidate.packageId === item.packageId && candidate.resourceId === item.resourceId
        ? refreshed : candidate));
      setNotice(`${item.path} policy validation refreshed: ${refreshed.validation}.`);
    } catch (error) {
      if (actionSequenceRef.current === actionToken) setNotice(error instanceof Error ? error.message : 'The Load Template could not be validated.');
    } finally {
      if (actionSequenceRef.current === actionToken) setActionKey('');
    }
  }

  function changeEnvironment(value: string) {
    invalidateEnvironmentValidation(value);
    onEnvironmentChange(value);
  }

  function disabledReasons(item: LoadCatalogItem): string[] {
    const reasons: string[] = [];
    if (item.kind === 'scenario') {
      if (environmentDraft && environmentProfilesState === 'error') reasons.push('Environment profiles are unavailable. Retry profiles or use Package default.');
      if (environmentDraft && item.packageId !== selectedPackage) reasons.push(`Select ${item.packageId} in Package to use its declared Environment profiles.`);
      if (item.validation === 'invalid') reasons.push('Validation failed. Review the diagnostic details before running or scheduling.');
      else if (item.validation !== 'valid') reasons.push(`Validate for ${environmentDraft || 'Package default'} before running or scheduling.`);
      else if (item.validatedEnvironment !== environmentDraft) reasons.push(`Revalidate for ${environmentDraft || 'Package default'} before running or scheduling.`);
      const currentValidation = item.validation === 'valid' && item.validatedEnvironment === environmentDraft;
      if (currentValidation && !item.runNow) reasons.push('Run Now is unavailable under Server policy.');
      if (!schedulingEnabled) reasons.push('Scheduling disabled by Server policy.');
      else if (currentValidation && item.scheduleDraftId && (!item.scheduleDraftExpiresAt
        || !Number.isFinite(Date.parse(item.scheduleDraftExpiresAt)) || Date.parse(item.scheduleDraftExpiresAt) <= clockNow))
        reasons.push('The validated Schedule draft expired. Validate this scenario again before scheduling.');
      else if (currentValidation && !item.schedule) reasons.push(item.scheduleReason || 'Revalidate to receive a short-lived Schedule draft.');
    } else if (item.kind === 'template' && (!item.createQuickLoad || !inlineLoadEnabled)) {
      reasons.push('Quick LOAD composition is disabled by Server policy.');
    }
    return reasons;
  }

  const environmentNotApplicable = (item: LoadCatalogItem) => Boolean(environmentDraft && item.packageId !== selectedPackage);
  const clearCatalogFilters = () => {
    queryRef.current = ''; catalogFailuresRef.current = {}; setCatalogFailures({}); setQuery('');
    setPackageFilter('all'); setKindFilter('all'); setModelFilter('all'); setValidationFilter('all');
  };
  return <div className="page-content att-load-catalog">
    <header className="att-feature-header"><div><p className="att-eyebrow">Load resources and execution history</p><h1>LOAD Catalog</h1>
      <p>Browse package scenarios and Quick LOAD policies. Validate scenarios for the selected environment before running or scheduling.</p></div>
      <div className="att-load-catalog-toolbar-actions"><button className="button button-primary" type="button" onClick={() => onNewLoad(selectedPackage)}>New LOAD</button></div>
    </header>
    {!inlineLoadEnabled && <p className="callout callout-warning" role="status">Quick LOAD composition is disabled by Server policy. Valid package scenarios may still be run directly when Server execution policy allows.</p>}
    <section className="att-query-workspace" aria-labelledby="load-catalog-heading">
      <section className="att-query-filter-panel" aria-label="LOAD Catalog filters">
      <div className="att-query-filter-grid">
        <label>Search resources<input type="search" value={query} onChange={event => {
          const value = event.currentTarget.value;
          queryRef.current = value; catalogFailuresRef.current = {}; setCatalogFailures({}); setQuery(value);
        }} placeholder="Package-relative YAML path" /></label>
        <label>Package<select value={packageFilter} onChange={event => setPackageFilter(event.currentTarget.value)}><option value="all">All packages</option>{packages.status === 'ready' && packages.data.map(pkg => <option key={pkg.packageId} value={pkg.packageId}>{pkg.displayName || pkg.packageId}</option>)}</select></label>
        <label>Kind<select value={kindFilter} onChange={event => setKindFilter(event.currentTarget.value)}><option value="all">All kinds</option><option value="scenario">Scenario</option><option value="template">Template</option><option value="invalid">Invalid</option></select></label>
        <label>Model<select value={modelFilter} onChange={event => setModelFilter(event.currentTarget.value)}><option value="all">All models</option><option value="virtualUsers">Virtual Users</option><option value="arrivalRate">Arrival Rate</option></select></label>
        <label>Validation<select value={validationFilter} onChange={event => setValidationFilter(event.currentTarget.value)}><option value="all">All validation states</option><option value="unverified">Unverified</option><option value="valid">Valid</option><option value="invalid">Invalid</option></select></label>
        <label>Environment for {selectedPackage || 'selected package'}<select aria-label={`Catalog Environment for ${selectedPackage || 'selected package'}`}
          value={environmentDraft} disabled={environmentProfilesState === 'loading'} onChange={event => changeEnvironment(event.currentTarget.value)}>
          <option value="">Package default</option>{environmentProfiles.map(name => <option key={name} value={name}>{name}</option>)}
        </select></label>
      </div>
      <div className="att-query-filter-actions">
        <button className="button button-quiet" type="button" onClick={clearCatalogFilters}>Clear filters</button>
        <button className="button button-quiet" type="button" onClick={() => setRefreshKey(value => value + 1)} disabled={catalogState === 'loading'}>{catalogState === 'loading' ? 'Refreshing…' : 'Refresh resources'}</button>
      </div>
      <p className="att-load-environment-context" role="status">Environment: {environmentDraft || 'Package default'}{selectedPackage ? ` · Profiles from ${selectedPackage}` : ''}</p>
      {environmentProfilesState === 'loading' && <p role="status" aria-busy="true">Loading declared Environment profiles…</p>}
      {environmentProfilesState === 'error' && <p className="att-inline-error" role="alert">Environment profiles could not be loaded. Package default remains available. <button className="button button-quiet" type="button" onClick={() => setEnvironmentReload(value => value + 1)}>Retry profiles</button></p>}
      </section>
      <section className="att-query-results-panel" aria-label="Available LOAD YAML resources">
      <div className="att-query-results-heading"><div><h2 id="load-catalog-heading">Available Load YAML resources</h2><p>Scenario syntax is discovered in bounded pages; validate a scenario for the selected environment before running or scheduling.</p></div></div>
      {catalogState === 'loading' && <p className="att-query-result-status" role="status" aria-busy="true">Discovering package Load YAML resources…</p>}
      {catalogState === 'error' && <p className="att-inline-error att-query-empty-state" role="alert">{catalogError}</p>}
      {Object.entries(catalogFailures).map(([packageId, failure]) => <div className="att-inline-error" role="alert" key={packageId}>
        {packageId}: {failure.message}{failure.suggestion ? ` ${failure.suggestion}` : ''} <button className="button button-quiet" type="button" disabled={Object.values(catalogFailures).some(item => item.retrying)} onClick={() => void retryCatalogPackage(packageId)}>{failure.retrying ? 'Retrying…' : `Retry ${packageId}`}</button>
      </div>)}
      {notice && <p role="status" className="att-inline-error">{notice}</p>}
      {catalogState === 'ready' && visible.length === 0 && <p className="att-query-empty-state" role="status">No Load YAML resources match these filters.</p>}
      {catalogState === 'ready' && visible.length > 0 && <div className="att-load-catalog-list" aria-label="Available Load YAML resources">
        {visible.map(item => <article className="att-load-catalog-card" key={`${item.packageId}:${item.resourceId}`}>
          <div className="att-load-catalog-card-heading"><div><span className={`att-load-kind kind-${item.kind}`}>{item.kind === 'template' ? 'Load Template' : item.kind === 'scenario' ? 'Executable Scenario' : 'Invalid'}</span>
            <h3>{item.path}</h3><p>{item.packageId} · {item.model === 'virtualUsers' ? 'Virtual Users' : item.model === 'arrivalRate' ? 'Arrival Rate' : 'Model unavailable'} · {item.workloadCount} workload{item.workloadCount === 1 ? '' : 's'}</p></div>
            <span className={`att-load-validation validation-${item.validation}`}>{item.validation}</span></div>
          {item.diagnostic && <div className="att-load-catalog-invalid-summary"><p className="att-inline-error" role="status">{item.diagnostic}{item.requestId ? ` Request ID: ${item.requestId}` : ''}</p>
            <button className="button button-quiet" type="button" onClick={() => showSource(item)}>View details</button></div>}
          {item.diagnostics?.length ? <CatalogDiagnostics items={item.diagnostics} onViewDetails={() => showSource(item)} /> : null}
          <div className="att-load-catalog-actions">
            {item.kind === 'scenario' && <button className="button button-quiet" type="button" aria-label={`Validate ${item.path} for ${environmentDraft || 'Package default'}`}
              disabled={Boolean(actionKey) || environmentNotApplicable(item) || Boolean(environmentDraft && environmentProfilesState !== 'ready')}
              onClick={() => void validateItem(item)}>{actionKey === `validate:${item.resourceId}` ? 'Validating…' : 'Validate'}</button>}
            {item.kind === 'template' && <button className="button button-quiet" type="button" disabled={Boolean(actionKey)} onClick={() => void validateTemplate(item)}>{actionKey === `validate:${item.resourceId}` ? 'Validating…' : 'Validate'}</button>}
            {item.kind === 'scenario' && <><button className="button button-primary" type="button" disabled={!item.runNow || item.validation !== 'valid' || item.validatedEnvironment !== environmentDraft || environmentNotApplicable(item) || Boolean(actionKey)} onClick={() => void runNow(item)}>{actionKey === item.resourceId ? 'Starting…' : 'Run Now'}</button>
              <button className="button button-quiet" type="button" disabled={!item.schedule || !item.scheduleDraftId || !item.scheduleDraftExpiresAt || !Number.isFinite(Date.parse(item.scheduleDraftExpiresAt)) || Date.parse(item.scheduleDraftExpiresAt) <= clockNow || item.validation !== 'valid' || item.validatedEnvironment !== environmentDraft || !schedulingEnabled || environmentNotApplicable(item)} title={item.scheduleDraftExpiresAt && (!Number.isFinite(Date.parse(item.scheduleDraftExpiresAt)) || Date.parse(item.scheduleDraftExpiresAt) <= clockNow) ? 'Validated Schedule draft expired. Validate again.' : !schedulingEnabled ? 'Schedules are disabled by Server policy.' : item.validatedEnvironment !== environmentDraft ? 'Validate for the selected environment first.' : undefined}
                onClick={() => onSchedule({ packageId: item.packageId, environment: environmentDraft || undefined,
                  draftId: item.scheduleDraftId!, previewYaml: item.schedulePreviewYaml, expiresAt: item.scheduleDraftExpiresAt, executionType: 'load' })}>Schedule</button></>}
            {item.scheduleDraftExpiresAt && item.scheduleDraftId && <p className="att-help">Validated Schedule draft expires {new Date(item.scheduleDraftExpiresAt).toLocaleString()}.</p>}
            {item.kind === 'template' && <button className="button button-quiet" type="button" disabled={!item.createQuickLoad || !inlineLoadEnabled}
              onClick={() => onQuickLoad(item.packageId, item)}>Create Quick LOAD</button>}
            <button className="button button-quiet" type="button" onClick={() => showSource(item)}>View</button>
          </div>
          {disabledReasons(item).length > 0 && <ul className="att-load-action-reasons" aria-label={`Unavailable actions for ${item.path}`}>
            {disabledReasons(item).map(reason => <li key={reason}>{reason}</li>)}
          </ul>}
        </article>)}
      </div>}
      {Object.keys(catalogCursors).length > 0 && <button className="button button-quiet" type="button" disabled={catalogMoreLoading || catalogState === 'loading'}
        onClick={() => {
          const controller = new AbortController();
          catalogRequestRef.current?.abort();
          catalogRequestRef.current = controller;
          void loadCatalog(controller.signal, true);
        }}>
        {catalogMoreLoading || catalogState === 'loading' ? 'Loading more…' : 'Load more scenarios'}
      </button>}
    </section>
    </section>
    <section className="att-query-workspace" aria-labelledby="recent-load-jobs-heading">
      <section className="att-query-results-panel" aria-label="Recent LOAD job results">
      <div className="att-query-results-heading"><div><h2 id="recent-load-jobs-heading">Recent LOAD Jobs</h2><p>Execution history returned by the Server's authorized LOAD-only query.</p></div>
        <button className="button button-quiet" type="button" onClick={() => void loadJobs() } disabled={jobsState === 'loading'}>{jobsState === 'loading' ? 'Refreshing…' : 'Refresh'}</button></div>
      {jobsState === 'loading' && <p className="att-query-result-status" role="status" aria-busy="true">Loading recent LOAD jobs…</p>}
      {jobsState === 'error' && <p className="att-inline-error att-query-empty-state" role="alert">{jobsError}</p>}
      {jobsState === 'ready' && jobs.length === 0 && <p className="att-query-empty-state" role="status">No LOAD jobs are available for this account.</p>}
      {jobs.length > 0 && <div className="att-load-job-list">{jobs.map(job => <div className="att-load-job-row" key={job.jobId}>
        <button className="text-link" type="button" onClick={() => onOpenJob(job.jobId)}>{job.jobId}</button>
        <span>{job.packageId}{job.scenario ? ` · ${job.scenario}` : ''}</span>
        <span>{job.model || 'Model unavailable'}{job.environment ? ` · ${job.environment}` : ''}</span>
        <span className={'att-status-badge att-status-' + String(job.status).toLowerCase().replace(/[^a-z0-9]+/g, '-')}>{job.status}</span>
        <span className="att-load-job-times"><time title="Created">Created {job.createdAt ? new Date(job.createdAt).toLocaleString() : 'unavailable'}</time>
          {job.startedAt && <time title="Started">Started {new Date(job.startedAt).toLocaleString()}</time>}
          {job.finishedAt && <time title="Finished">Finished {new Date(job.finishedAt).toLocaleString()}</time>}
          {jobDuration(job) && <span>Duration {jobDuration(job)}</span>}</span>
        <button className="button button-quiet" type="button" onClick={() => onOpenJob(job.jobId)}>View Results</button>
      </div>)}</div>}
      {jobsMore !== null && <button className="button button-quiet" type="button" onClick={() => void loadJobs(jobsMore, true)} disabled={jobsState === 'loading'}>Load more LOAD jobs</button>}
      </section>
    </section>
  </div>;
}

function CatalogDiagnostics({ items, onViewDetails }: { items: ServerDiagnostic[]; onViewDetails(): void }) {
  return <div className="att-load-catalog-diagnostics"><ul>{items.map((item, index) => <li key={`${item.code || item.field || 'diagnostic'}-${index}`}>
    <strong>{[item.severity, item.code].filter(Boolean).join(' · ') || 'Diagnostic'}</strong>
    {item.summary || item.message ? <span>{item.summary || item.message}</span> : null}
    {item.field || item.path ? <span>Field: {item.field || item.path}</span> : null}
    {item.line ? <span>Line {item.line}{item.column ? `, column ${item.column}` : ''}</span> : null}
    {item.suggestion ? <span>Suggestion: {item.suggestion}</span> : null}
  </li>)}</ul><button className="button button-quiet" type="button" onClick={onViewDetails}>View details</button></div>;
}
