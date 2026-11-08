import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { inspectionCache } from '../../inspectionCache';
import { useInspectionRefresh } from '../../useInspectionRefresh';
import type { ConfigurationEntry, ExplorerApi, JsonValue, ResourceReference, TestdataRecordsResponse } from './types';
import { CodeEditor } from '../code/CodeEditor';
import { SourceAuthoringWorkspace } from './SourceAuthoringWorkspace';
import './package-testdata.css';

function errorText(error: unknown, action: string): string {
  const status = typeof error === 'object' && error !== null && 'status' in error ? Number((error as { status?: unknown }).status) : 0;
  if (status === 401) return 'Your session has expired. Sign in again, then retry.';
  if (status === 403) return `You do not have permission to ${action}.`;
  if (status === 404) return 'The selected Package or Testdata descriptor is unavailable. Refresh Packages.';
  if (status === 413) return 'The Server declined this projection because it exceeds the configured safety limit.';
  if (status === 429 || status === 503 || status === 504) return 'This inspection request is temporarily unavailable. Retry shortly.';
  if (status) return `${action} failed (HTTP ${status}).`;
  return `Could not ${action}. Check the connection and retry.`;
}
function displayField(entry: ConfigurationEntry, name: string): string {
  const field = entry.fields?.[name];
  if (!field) return '—';
  if (field.state !== 'visible') return field.state;
  return typeof field.value === 'string' ? field.value : JSON.stringify(field.value ?? null);
}
function safeCell(value: unknown): string {
  if (value === null) return 'null';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  try { return JSON.stringify(value, null, 2); } catch { return '[unavailable]'; }
}

function ReferenceLink({ reference, onOpen }: { reference: ResourceReference; onOpen(resource: { type: string; resourceId: string }): void }) {
  if (!reference.type || !['case', 'template', 'flow', 'tool'].includes(reference.type) || !reference.resourceId || reference.resolution === 'unresolved') return <li>{reference.logicalId || reference.name || reference.resourceId || 'Unresolved reference'} <span>{reference.resolution || 'unresolved'}</span></li>;
  return <li><button type="button" onClick={() => onOpen({ type: reference.type!, resourceId: reference.resourceId! })}>{reference.logicalId || reference.name || reference.resourceId}</button> <span>{reference.type}</span></li>;
}


function TestdataCell({ value }: { value: unknown }) {
  const [copied, setCopied] = useState(false);
  const nested = value !== null && typeof value === 'object';
  const copyText = typeof value === 'string' ? value : JSON.stringify(value);
  const display = value === undefined ? 'Not present' : value === null ? 'null'
    : value === '' ? '(empty string)' : nested ? Array.isArray(value) ? 'List (' + value.length + ' items)' : 'Object (' + Object.keys(value as object).length + ' fields)' : String(value);
  async function copy() {
    try { await navigator.clipboard.writeText(copyText ?? 'null'); setCopied(true); window.setTimeout(() => setCopied(false), 1500); }
    catch { setCopied(false); }
  }
  return <div className="att-testdata-cell">
    <span>{display}</span>
    {value !== undefined && <button type="button" onClick={() => void copy()} aria-label="Copy Testdata value">Copy</button>}
    {copied && <span role="status">Copied</span>}
    {nested && <details><summary>Inspect nested value</summary>
      <CodeEditor value={JSON.stringify(value, null, 2)} language="json" label="Nested Testdata value" readOnly />
    </details>}
  </div>;
}

function observedType(value: unknown): string {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'list';
  if (typeof value === 'object') return 'object';
  return typeof value;
}

interface RecordControls {
  identity: string;
  offset: number;
  previewGenerated: boolean;
  copiedRecord: number | null;
}

interface KeyedRecordResponse {
  key: string;
  value: TestdataRecordsResponse;
}

export function TestdataCatalog({ api, packageId, environment, defaultEnvironment, descriptorId, refreshKey, onDescriptorChange, onOpenResource, onRefresh, registerUnsavedChanges }: {
  api: ExplorerApi; packageId: string; environment: string; defaultEnvironment?: string; descriptorId?: string; refreshKey: number;
  onDescriptorChange(descriptorId?: string): void; onOpenResource(resource: { type: string; resourceId: string }): void; onRefresh(): void;
  registerUnsavedChanges?: (isDirty: boolean, description?: string) => () => void;
}) {
  const inspectionRefresh = useInspectionRefresh();
  const [entries, setEntries] = useState<ConfigurationEntry[]>([]);
  const [selectedIdState, setSelectedIdState] = useState(descriptorId || '');
  const selectedId = descriptorId ?? selectedIdState;
  const recordIdentity = JSON.stringify([packageId, environment, selectedId]);
  const [recordControls, setRecordControls] = useState<RecordControls>({ identity: '', offset: 0, previewGenerated: false, copiedRecord: null });
  const activeRecordControls = recordControls.identity === recordIdentity
    ? recordControls : { identity: recordIdentity, offset: 0, previewGenerated: false, copiedRecord: null };
  const { offset, previewGenerated, copiedRecord } = activeRecordControls;
  const [recordResponseState, setRecordResponseState] = useState<KeyedRecordResponse | null>(null);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('Loading resolved Testdata catalog…');
  const [copyStatus, setCopyStatus] = useState('');
  const [recordLoadingKey, setRecordLoadingKey] = useState('');
  const [revision, setRevision] = useState(0);
  const [catalogSearch, setCatalogSearch] = useState('');
  const [routeLookupQuery, setRouteLookupQuery] = useState(descriptorId ?? '');
  const [catalogNextOffset, setCatalogNextOffset] = useState<number | null>(null);
  const [catalogTotal, setCatalogTotal] = useState<number | null>(null);
  const [catalogLoading, setCatalogLoading] = useState(false);
  const [creatingDescriptor, setCreatingDescriptor] = useState(false);
  const [creatorDirty, setCreatorDirty] = useState(false);
  const catalogQuery = routeLookupQuery || catalogSearch.trim();
  const catalogQueryKey = JSON.stringify([packageId, environment, catalogQuery, inspectionRefresh, refreshKey]);
  const catalogQueryKeyRef = useRef(catalogQueryKey);
  const catalogEntriesQueryRef = useRef(catalogQuery);
  const catalogGenerationRef = useRef(0);
  const catalogFirstPageControllerRef = useRef<AbortController | null>(null);
  const catalogLoadMoreControllerRef = useRef<AbortController | null>(null);
  const routeDescriptorRef = useRef(descriptorId);
  const catalogScopeRef = useRef({ api, packageId, environment, inspectionRefresh, refreshKey });
  const recordIdentityRef = useRef(recordIdentity);
  const recordsRequestKey = JSON.stringify([recordIdentity, offset, previewGenerated, revision, inspectionRefresh, refreshKey]);
  const response = recordResponseState?.key === recordsRequestKey ? recordResponseState.value : null;
  const loading = recordLoadingKey === recordsRequestKey;
  const selectedEntry = entries.find(item => item.id === selectedId);
  const visibleEntries = useMemo(() => entries
    .filter(item => !catalogSearch.trim() || item.id.toLocaleLowerCase().includes(catalogSearch.trim().toLocaleLowerCase())
      || (item.source || displayField(item, 'source')).toLocaleLowerCase().includes(catalogSearch.trim().toLocaleLowerCase()))
    .sort((left, right) => left.id.localeCompare(right.id, undefined, { numeric: true, sensitivity: 'base' })),
  [entries, catalogSearch]);
  const isGenerated = selectedEntry?.generated === true || displayField(selectedEntry || { id: '' }, 'generated') === 'true';

  useLayoutEffect(() => {
    recordIdentityRef.current = recordIdentity;
    setRecordControls({ identity: recordIdentity, offset: 0, previewGenerated: false, copiedRecord: null });
    setRecordResponseState(null);
    setRecordLoadingKey('');
    setCopyStatus('');
    setError('');
  }, [recordIdentity]);

  useLayoutEffect(() => {
    catalogQueryKeyRef.current = catalogQueryKey;
    catalogGenerationRef.current += 1;
    catalogFirstPageControllerRef.current?.abort();
    catalogFirstPageControllerRef.current = null;
    catalogLoadMoreControllerRef.current?.abort();
    catalogLoadMoreControllerRef.current = null;
    setCatalogLoading(false);
  }, [api, catalogQueryKey]);

  useLayoutEffect(() => {
    const previousScope = catalogScopeRef.current;
    const scopeChanged = previousScope.api !== api || previousScope.packageId !== packageId
      || previousScope.environment !== environment || previousScope.inspectionRefresh !== inspectionRefresh
      || previousScope.refreshKey !== refreshKey;
    const routeChanged = routeDescriptorRef.current !== descriptorId;
    if (!scopeChanged && !routeChanged) return;
    catalogScopeRef.current = { api, packageId, environment, inspectionRefresh, refreshKey };
    routeDescriptorRef.current = descriptorId;
    const nextQuery = catalogSearch.trim();
    const alreadyInNextQueryResults = !scopeChanged && catalogEntriesQueryRef.current === nextQuery
      && entries.some(item => item.id === descriptorId);
    setRouteLookupQuery(descriptorId && !alreadyInNextQueryResults ? descriptorId : '');
  }, [api, packageId, environment, inspectionRefresh, refreshKey, descriptorId, catalogSearch, entries]);

  useLayoutEffect(() => { setSelectedIdState(descriptorId || ''); }, [packageId, environment, descriptorId]);

  useEffect(() => {
    const generation = catalogGenerationRef.current;
    const queryKey = catalogQueryKey;
    const controller = new AbortController();
    catalogFirstPageControllerRef.current = controller;
    setEntries([]); setRecordResponseState(null); setError(''); setStatus('Loading resolved Testdata catalog…');
    setCatalogNextOffset(null); setCatalogTotal(null); setCatalogLoading(false);
    api.getEffectiveConfiguration(packageId, { environment: environment || undefined, section: 'testdata', query: catalogQuery || undefined, offset: 0, limit: 50 }, controller.signal)
      .then(async result => {
        const data = result.sections?.find(section => section.id === 'testdata')?.entries || [];
        const isCurrent = () => !controller.signal.aborted && generation === catalogGenerationRef.current && queryKey === catalogQueryKeyRef.current;
        if (!isCurrent()) return;
        catalogEntriesQueryRef.current = catalogQuery;
        setEntries(current => generation === catalogGenerationRef.current && queryKey === catalogQueryKeyRef.current ? data : current);
        const firstPageNextOffset = result.nextOffset ?? null;
        const firstPageTotal = result.total ?? data.length;
        let nextOffset = firstPageNextOffset;
        let total = firstPageTotal;
        setCatalogNextOffset(current => generation === catalogGenerationRef.current && queryKey === catalogQueryKeyRef.current ? firstPageNextOffset : current);
        setSelectedIdState(current => {
          if (generation !== catalogGenerationRef.current || queryKey !== catalogQueryKeyRef.current) return current;
          return current && data.some(item => item.id === current) ? current
            : descriptorId && !data.some(item => item.id === descriptorId) ? descriptorId : data[0]?.id || '';
        });
        setCatalogTotal(current => generation === catalogGenerationRef.current && queryKey === catalogQueryKeyRef.current ? firstPageTotal : current);
        setStatus(current => generation === catalogGenerationRef.current && queryKey === catalogQueryKeyRef.current
          ? data.length ? `${firstPageTotal} Testdata descriptors match this search for ${environment || result.environment || 'package default'}.` : 'No Testdata descriptors match this search.'
          : current);

        // A route descriptor ID is exact, but the server query also matches sources.
        // Keep paging only this deep-link lookup until its exact entry is found; manual
        // catalog searches continue to use the existing Load more control.
        if (!descriptorId || routeLookupQuery !== descriptorId || data.some(item => item.id === descriptorId) || nextOffset == null) return;
        setCatalogLoading(true);
        let loaded = data;
        try {
          while (nextOffset != null && !loaded.some(item => item.id === descriptorId)) {
            const pageOffset = nextOffset;
            const page = await api.getEffectiveConfiguration(packageId, {
              environment: environment || undefined, section: 'testdata', query: descriptorId, offset: pageOffset, limit: 50,
            }, controller.signal);
            if (!isCurrent()) return;
            const pageEntries = page.sections?.find(section => section.id === 'testdata')?.entries || [];
            const loadedIds = new Set(loaded.map(item => item.id));
            const nextLoaded = [...loaded, ...pageEntries.filter(item => !loadedIds.has(item.id))];
            const pageNextOffset = page.nextOffset ?? null;
            const pageTotal = page.total ?? total;
            loaded = nextLoaded; nextOffset = pageNextOffset; total = pageTotal;
            setEntries(current => isCurrent() ? nextLoaded : current);
            setCatalogNextOffset(current => isCurrent() ? pageNextOffset : current);
            setCatalogTotal(current => isCurrent() ? pageTotal : current);
          }
        } catch (reason) {
          if (isCurrent()) setError(errorText(reason, 'continue searching for the linked Testdata descriptor'));
        } finally {
          if (isCurrent()) setCatalogLoading(false);
        }
      }).catch(reason => {
        if (!controller.signal.aborted && generation === catalogGenerationRef.current && queryKey === catalogQueryKeyRef.current) {
          setError(errorText(reason, 'load the Testdata catalog')); setStatus('Testdata catalog unavailable.');
        }
      });
    return () => {
      controller.abort();
      if (catalogFirstPageControllerRef.current === controller) catalogFirstPageControllerRef.current = null;
      catalogLoadMoreControllerRef.current?.abort();
      catalogLoadMoreControllerRef.current = null;
    };
  }, [api, packageId, environment, catalogQuery, inspectionRefresh, refreshKey]);

  async function loadMoreCatalog() {
    if (catalogNextOffset == null || catalogLoading) return;
    const generation = catalogGenerationRef.current;
    const queryKey = catalogQueryKey;
    const controller = new AbortController();
    catalogLoadMoreControllerRef.current?.abort();
    catalogLoadMoreControllerRef.current = controller;
    setCatalogLoading(true); setError('');
    try {
      const result = await api.getEffectiveConfiguration(packageId, { environment: environment || undefined, section: 'testdata', query: catalogQuery || undefined, offset: catalogNextOffset, limit: 50 }, controller.signal);
      if (controller.signal.aborted || generation !== catalogGenerationRef.current || queryKey !== catalogQueryKeyRef.current) return;
      const next = result.sections?.find(section => section.id === 'testdata')?.entries || [];
      setEntries(current => generation === catalogGenerationRef.current && queryKey === catalogQueryKeyRef.current ? [...current, ...next] : current);
      setCatalogNextOffset(current => generation === catalogGenerationRef.current && queryKey === catalogQueryKeyRef.current ? result.nextOffset ?? null : current);
      setCatalogTotal(current => generation === catalogGenerationRef.current && queryKey === catalogQueryKeyRef.current ? result.total ?? entries.length + next.length : current);
    } catch (reason) {
      if (!controller.signal.aborted && generation === catalogGenerationRef.current && queryKey === catalogQueryKeyRef.current)
        setError(errorText(reason, 'load more Testdata descriptors'));
    } finally {
      if (catalogLoadMoreControllerRef.current === controller) {
        catalogLoadMoreControllerRef.current = null;
        if (generation === catalogGenerationRef.current && queryKey === catalogQueryKeyRef.current) setCatalogLoading(false);
      }
    }
  }

  useEffect(() => {
    if (selectedId && visibleEntries.length && !visibleEntries.some(item => item.id === selectedId) && selectedId !== descriptorId) {
      const next = visibleEntries[0].id; setSelectedIdState(next); setDescriptorChange(next);
    } else if (!selectedId && visibleEntries.length) setSelectedIdState(visibleEntries[0].id);
  }, [visibleEntries, selectedId]);

  function setDescriptorChange(value?: string) { onDescriptorChange(value); }

  function updateRecordControls(patch: Partial<Pick<RecordControls, 'offset' | 'previewGenerated' | 'copiedRecord'>>) {
    setRecordControls(current => {
      if (recordIdentityRef.current !== recordIdentity) return current;
      const base = current.identity === recordIdentity ? current
        : { identity: recordIdentity, offset: 0, previewGenerated: false, copiedRecord: null };
      return { ...base, ...patch, identity: recordIdentity };
    });
  }

  useEffect(() => {
    const requestKey = recordsRequestKey;
    if (!selectedId || !selectedEntry || !api.getTestdataRecords) { setRecordResponseState(null); setRecordLoadingKey(''); return; }
    const controller = new AbortController(); setRecordLoadingKey(requestKey); setError('');
    api.getTestdataRecords(packageId, selectedId, { environment: environment || undefined, offset, limit: 50, previewGenerated }, controller.signal)
      .then(result => { if (!controller.signal.aborted) setRecordResponseState({ key: requestKey, value: result }); })
      .catch(reason => {
        if (controller.signal.aborted) return;
        const status = typeof reason === 'object' && reason !== null && 'status' in reason
          ? Number((reason as { status?: unknown }).status) : 0;
        setError(status === 404
          ? 'Testdata record preview is not available from this Server version; safe descriptor metadata remains visible.'
          : errorText(reason, 'load Testdata records'));
      })
      .finally(() => { if (!controller.signal.aborted) setRecordLoadingKey(current => current === requestKey ? '' : current); });
    return () => controller.abort();
  }, [api, packageId, selectedId, selectedEntry, environment, offset, previewGenerated, revision, inspectionRefresh, refreshKey, recordsRequestKey]);

  async function copyRecord(index: number, value: JsonValue) {
    const identity = recordIdentity;
    try {
      await navigator.clipboard.writeText(JSON.stringify(value));
      if (recordIdentityRef.current === identity) { updateRecordControls({ copiedRecord: index }); setCopyStatus(`Copied Testdata row ${index + 1}.`); }
    } catch {
      if (recordIdentityRef.current === identity) setCopyStatus('Copy is unavailable in this browser context.');
    }
  }

  const records = response?.records || [];
  const columns = useMemo(() => [...new Set(records.flatMap(record => record.value && typeof record.value === 'object' && !Array.isArray(record.value) ? Object.keys(record.value) : ['value']))], [records]);
  if (error && !selectedId) return <section className="att-testdata-workspace"><h2>Testdata</h2><p className="att-error" role="alert">{error}<button type="button" onClick={onRefresh}>Retry</button></p></section>;
  return <section className="att-testdata-workspace" aria-label="Structured Testdata catalog">
    <header><div><h2>Descriptors</h2><p>Resolved metadata and bounded records for {environment || defaultEnvironment || 'the Package default environment'}.</p></div>
      <button type="button" onClick={() => setCreatingDescriptor(true)}>Create Testdata descriptor</button>
      {(catalogTotal !== 0 || entries.length > 0 || catalogSearch) && <label>Search descriptors<input type="search" aria-label="Search Testdata descriptors" value={catalogSearch} onChange={event => {
        setCatalogSearch(event.currentTarget.value); setSelectedIdState(''); updateRecordControls({ offset: 0, previewGenerated: false, copiedRecord: null });
        setRecordResponseState(null); onDescriptorChange(undefined);
      }} placeholder="Search descriptor IDs and sources" /></label>}
    </header>
    <p role="status" aria-live="polite">{copyStatus || status}{loading ? ' Loading records…' : ''}</p>
    {error && <p className="att-error" role="alert">{error}<button type="button" onClick={onRefresh}>Retry</button></p>}
    <div className="att-testdata-layout">
      <nav className="att-testdata-catalog" aria-label="Testdata descriptors">
        <h3>Catalog</h3><p>{visibleEntries.length} shown of {catalogTotal ?? entries.length} matching descriptors ({entries.length} loaded)</p>
        <ul>{visibleEntries.map(item => <li key={item.id}><button type="button" aria-current={selectedId === item.id ? 'true' : undefined}
          onClick={() => { setSelectedIdState(item.id); setDescriptorChange(item.id); updateRecordControls({ offset: 0, previewGenerated: false, copiedRecord: null }); setRecordResponseState(null); setRevision(value => value + 1); }}>
          <span>{item.id}</span><small>{item.source || displayField(item, 'source')}</small></button></li>)}</ul>
        {catalogNextOffset != null && <button type="button" onClick={() => void loadMoreCatalog()} disabled={catalogLoading}>{catalogLoading ? 'Loading…' : 'Load more descriptors'}</button>}
        {entries.length > 0 && !visibleEntries.length && <p>No descriptors match this search.</p>}
      </nav>
      <div className="att-testdata-detail">
    {creatingDescriptor && <section className="att-testdata-create" aria-label="Create Testdata descriptor">
      <header><div><h3>New Testdata descriptor</h3><p>Update the Package Manifest and create the descriptor file in one candidate before staging.</p></div>
        <button type="button" onClick={() => setCreatingDescriptor(false)} disabled={creatorDirty}
          title={creatorDirty ? 'Stage or discard the local editor work and draft before closing.' : undefined}>Close creator</button></header>
      <SourceAuthoringWorkspace api={api} target={{ domain: 'PACKAGE_SOURCE', packageId,
        query: { kind: 'manifest' }, label: 'Testdata descriptor and Package registration', allowFileOperations: true }}
        registerUnsavedChanges={registerUnsavedChanges} onDirtyChange={setCreatorDirty} onPublished={() => setCreatingDescriptor(false)} />
    </section>}
    {selectedEntry ? <>
      <dl className="att-testdata-meta"><div><dt>Descriptor</dt><dd>{selectedEntry.id}</dd></div><div><dt>Source</dt><dd>{selectedEntry.source ? <span title="Package-local descriptor, not a shared Configuration source">{packageId}: {selectedEntry.source}</span> : 'Not provided'}</dd></div>
        <div><dt>Generated</dt><dd>{isGenerated ? 'Yes' : 'No'}</dd></div><div><dt>Records</dt><dd>{response?.recordCount?.toLocaleString() || displayField(selectedEntry, 'recordCount')}</dd></div>
        <div><dt>Selection</dt><dd>{displayField(selectedEntry, 'selection.strategy')}</dd></div><div><dt>Exhaustion</dt><dd>{displayField(selectedEntry, 'selection.exhaustion')}</dd></div>
        <div><dt>Environment origin</dt><dd>{selectedEntry.fields?.['selection.strategy']?.origin || 'Server projection'}</dd></div></dl>
      {selectedEntry.references?.length ? <><h3>Referenced by</h3><ul>{selectedEntry.references.map((reference, index) =>
        <ReferenceLink key={index} reference={reference} onOpen={onOpenResource} />)}</ul></> : null}
      {isGenerated && <div className="att-testdata-generated"><h3>Generated Testdata</h3><p>This descriptor creates records at runtime. Previewing generated records is bounded and starts only when you request it.</p>
        <dl><div><dt>Sequence start</dt><dd>{String(((((response?.generation as Record<string, JsonValue> | undefined)?.generate as Record<string, JsonValue> | undefined)?.seq as Record<string, JsonValue> | undefined)?.from) ?? 'Not provided')}</dd></div>
          <div><dt>Sequence end</dt><dd>{String(((((response?.generation as Record<string, JsonValue> | undefined)?.generate as Record<string, JsonValue> | undefined)?.seq as Record<string, JsonValue> | undefined)?.to) ?? 'Not provided')}</dd></div>
          <div><dt>Format</dt><dd>{String(((((response?.generation as Record<string, JsonValue> | undefined)?.generate as Record<string, JsonValue> | undefined)?.seq as Record<string, JsonValue> | undefined)?.format) ?? 'Not provided')}</dd></div></dl>
        {response?.generation && <details><summary>Record template</summary><CodeEditor value={JSON.stringify((response.generation as Record<string, JsonValue>).record ?? null, null, 2)} language="json" label="Generated Testdata record template" readOnly /></details>}
        {!previewGenerated && <button type="button" onClick={() => updateRecordControls({ offset: 0, previewGenerated: true, copiedRecord: null })} disabled={loading}>Preview first 50 generated records</button>}
      </div>}
      <div className="att-testdata-record-heading"><h3>{isGenerated ? 'Record preview' : 'Records'}</h3><span>{response ? `${(response.recordCount ?? response.total).toLocaleString()} total` : 'Records withheld or unavailable'}</span></div>
      {response?.previewRequired ? <p className="att-config-empty">Generated records are not loaded automatically. Choose <strong>Preview first 50 generated records</strong> to request a bounded preview.</p>
        : records.length > 0 && <details className="att-testdata-schema"><summary>Fields observed in this page</summary><p>Requiredness and authored descriptions are not provided by this record preview.</p>
          <div className="att-testdata-table-scroll"><table><thead><tr><th scope="col">Field</th><th scope="col">Observed type</th><th scope="col">Example</th></tr></thead><tbody>{columns.map(column => {
            const values = records.map(record => record.value && typeof record.value === 'object' && !Array.isArray(record.value)
              ? (record.value as Record<string, unknown>)[column] : column === 'value' ? record.value : undefined).filter(value => value !== undefined);
            const typeSet = [...new Set(values.map(observedType))];
            return <tr key={column}><th scope="row">{column}</th><td>{typeSet.join(', ') || 'Not observed'}</td><td>{values.length ? safeCell(values[0]) : '—'}</td></tr>;
          })}</tbody></table></div>
        </details>}
      {!response?.previewRequired && (records.length > 0 ? <div className="att-testdata-table-scroll"><table><thead><tr><th scope="col">Index</th>{columns.map(column => <th scope="col" key={column}>{column}</th>)}<th scope="col">Visibility</th><th scope="col">Actions</th></tr></thead>
          <tbody>{records.map(record => <tr key={`${record.index}:${record.sequence ?? ''}`}><th scope="row">{record.index + 1}{record.sequence != null ? <small>seq {record.sequence}</small> : null}</th>
            {columns.map(column => { const value = record.value && typeof record.value === 'object' && !Array.isArray(record.value) ? (record.value as Record<string, unknown>)[column] : column === 'value' ? record.value : undefined; return <td key={column}><TestdataCell value={value} /></td>; })}
            <td>{record.redacted ? 'Some fields redacted' : 'Visible by Server policy'}</td><td><button type="button" onClick={() => void copyRecord(record.index, record.value)}>{copiedRecord === record.index ? 'Copied' : 'Copy row'}</button></td></tr>)}</tbody></table></div>
          : <p className="att-config-empty">{loading ? 'Loading records…' : response ? 'This descriptor contains no records.' : 'No records are available for this descriptor.'}</p>)}
      {response?.nextOffset != null && <button type="button" onClick={() => updateRecordControls({ offset: response.nextOffset || 0, copiedRecord: null })} disabled={loading}>Load next 50 records</button>}
      <details className="att-testdata-source"><summary>Advanced descriptor metadata</summary><CodeEditor value={JSON.stringify(Object.fromEntries(Object.entries(selectedEntry.fields || {}).map(([key, field]) => [key, field.state === 'visible' ? field.value : field.state])), null, 2)} language="json" label="Advanced Testdata descriptor metadata" readOnly /></details>
      {selectedEntry.source && <SourceAuthoringWorkspace api={api} target={{ domain: 'PACKAGE_SOURCE', packageId,
        query: { kind: 'testdata', resourceId: selectedEntry.id, environment: environment || undefined },
        label: `Testdata descriptor · ${selectedEntry.source}`, allowFileOperations: true }} registerUnsavedChanges={registerUnsavedChanges} />}
    </> : <p className="att-config-empty">{descriptorId ? `Testdata descriptor ${descriptorId} is unavailable for this Package or Environment.` : 'No Testdata descriptors were returned for the selected environment.'}</p>}
      </div>
    </div>
  </section>;
}

export type PackageRegistrationState = 'loading' | 'ready' | 'error';

export function PackageTestdataWorkspace({ api, packageId, packageLabel, registrationState, registered, environment, descriptorId,
  onEnvironmentChange, onDescriptorChange, onBackToResources, onOpenResource, onOpenPackageConfiguration, registerUnsavedChanges }: {
  api: ExplorerApi; packageId: string; packageLabel: string; registrationState: PackageRegistrationState; registered: boolean;
  environment: string; descriptorId?: string; onEnvironmentChange(environment: string): void;
  onDescriptorChange(descriptorId?: string): void; onBackToResources(): void;
  onOpenResource(resource: { type: string; resourceId: string }): void;
  onOpenPackageConfiguration(): void;
  registerUnsavedChanges?: (isDirty: boolean, description?: string) => () => void;
}) {
  const [declared, setDeclared] = useState<Awaited<ReturnType<ExplorerApi['getDeclaredConfiguration']>> | null>(null);
  const [environmentError, setEnvironmentError] = useState('');
  const [environmentNotice, setEnvironmentNotice] = useState('');
  const [refreshKey, setRefreshKey] = useState(0);
  const inspectionRefresh = useInspectionRefresh();
  const profiles = useMemo(() => (declared?.environments || []).filter(item => item.name && item.state === 'active'), [declared]);
  const defaultEnvironment = profiles.find(item => item.default)?.name;
  const refresh = useCallback(() => {
    inspectionCache.invalidatePackage(packageId);
    setRefreshKey(value => value + 1);
  }, [packageId]);

  useEffect(() => {
    if (registrationState !== 'ready' || !registered) { setDeclared(null); setEnvironmentError(''); return; }
    const controller = new AbortController(); setDeclared(null); setEnvironmentError('');
    api.getDeclaredConfiguration(packageId, controller.signal)
      .then(result => { if (!controller.signal.aborted) setDeclared(result); })
      .catch(reason => { if (!controller.signal.aborted) setEnvironmentError(errorText(reason, 'load Package environments')); });
    return () => controller.abort();
  }, [api, packageId, registrationState, registered, inspectionRefresh, refreshKey]);

  useEffect(() => {
    if (!declared || !environment || profiles.some(item => item.name === environment)) return;
    setEnvironmentNotice(`Environment ${environment} is not active for Package ${packageId}; the Package default is selected.`);
    onEnvironmentChange('');
  }, [declared, environment, profiles, packageId, onEnvironmentChange]);

  if (registrationState === 'loading') return <main className="page-content att-testdata-page"><p role="status">Checking the registered Package list…</p></main>;
  if (registrationState === 'error') return <main className="page-content att-testdata-page"><h1>Package Testdata unavailable</h1><p role="alert">The registered Package list could not be confirmed. Return to Packages and retry.</p><button type="button" onClick={onBackToResources}>Open Packages</button></main>;
  if (!registered) return <main className="page-content att-testdata-page"><h1>Package Testdata unavailable</h1><p role="status">Package {packageId} is not registered or is no longer available. Choose a registered Package from Explorer.</p><button type="button" onClick={onBackToResources}>Open Packages</button></main>;

  return <main className="page-content att-testdata-page">
    <header className="att-testdata-page-heading">
      <div><p className="eyebrow">Packages / {packageId} / Explorer</p><h1>Testdata</h1><p>Read-only Package descriptor and record inspection.</p></div>
      <div className="att-testdata-page-actions"><button type="button" onClick={onBackToResources}>Back to resources</button>
        <button type="button" onClick={onOpenPackageConfiguration}>Package Configuration</button>
        <label>Environment<select aria-label="Testdata environment" value={environment} onChange={event => { setEnvironmentNotice(''); onEnvironmentChange(event.currentTarget.value); }}>
          <option value="">Package default{defaultEnvironment ? ` (${defaultEnvironment})` : ''}</option>
          {profiles.map(item => <option key={item.name} value={item.name}>{item.name}{item.default ? ' · Package default' : ''}</option>)}
        </select></label>
      </div>
    </header>
    <p className="att-testdata-package-label">Package: <strong>{packageLabel}</strong> <span>({packageId})</span></p>
    <p role="status">Effective environment: {environment || defaultEnvironment || 'Package default'}{environment ? ' (selected)' : ' (Package default)'}.</p>
    {environmentNotice && <p role="status">{environmentNotice}</p>}
    {environmentError && <p className="att-error" role="alert">{environmentError}<button type="button" onClick={refresh}>Retry Package environments</button></p>}
    <TestdataCatalog api={api} packageId={packageId} environment={environment} defaultEnvironment={defaultEnvironment} descriptorId={descriptorId} refreshKey={refreshKey}
      onDescriptorChange={onDescriptorChange} onOpenResource={onOpenResource} onRefresh={refresh} registerUnsavedChanges={registerUnsavedChanges} />
    <button type="button" onClick={refresh}>Refresh Testdata</button>
  </main>;
}
