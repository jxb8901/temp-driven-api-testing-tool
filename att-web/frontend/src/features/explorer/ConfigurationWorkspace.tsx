import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import type { PackageOption } from '../load/types';
import { CodeEditor, detectCodeLanguage } from '../code/CodeEditor';
import { ConfigurationExplorer } from './ConfigurationExplorer';
import type { ConfigurationEntry, ConfigurationFileNode, ExplorerApi, JsonValue, ResourceReference, ResourceSourceReference, SourceProjection, TestdataRecordsResponse } from './types';
import './configuration-workspace.css';

type WorkspaceTab = 'source' | 'declared' | 'effective' | 'compare' | 'testdata';
interface ConfigurationWorkspaceProps {
  api: ExplorerApi;
  packages: PackageOption[];
  packageId: string;
  environment: string;
  filePath?: string;
  fileOrigin?: string;
  sourceReference?: ResourceSourceReference;
  tab: WorkspaceTab;
  onPackageChange(packageId: string): void;
  onEnvironmentChange(environment: string): void;
  onFileChange(filePath?: string, origin?: string, reference?: ResourceSourceReference): void;
  onTabChange(tab: WorkspaceTab): void;
  onOpenResource(resource: { type: string; resourceId: string }): void;
}
interface DirectoryState { items: ConfigurationFileNode[]; loading: boolean; error: string; truncated?: boolean; nextOffset?: number | null }

function errorText(error: unknown, action: string): string {
  const status = typeof error === 'object' && error !== null && 'status' in error ? Number((error as { status?: unknown }).status) : 0;
  if (status === 401) return 'Your session has expired. Sign in again, then retry.';
  if (status === 403) return `You do not have permission to ${action}.`;
  if (status === 404) return 'The package or selected configuration file no longer exists. Refresh the package.';
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

export function ConfigurationWorkspace({ api, packages, packageId, environment, filePath, fileOrigin, sourceReference, tab, onPackageChange, onEnvironmentChange, onFileChange, onTabChange, onOpenResource }: ConfigurationWorkspaceProps) {
  const [declared, setDeclared] = useState<Awaited<ReturnType<ExplorerApi['getDeclaredConfiguration']>> | null>(null);
  const [directoryMap, setDirectoryMap] = useState<Record<string, DirectoryState>>({});
  const [expanded, setExpanded] = useState<Set<string>>(() => new Set(['']));
  const [searchText, setSearchText] = useState('');
  const [searchResults, setSearchResults] = useState<ConfigurationFileNode[] | null>(null);
  const [searchNextOffset, setSearchNextOffset] = useState<number | null>(null);
  const [searchTruncated, setSearchTruncated] = useState(false);
  const [treeError, setTreeError] = useState('');
  const [source, setSource] = useState<{ text: string; format: string; logicalPath?: string; reason?: string; available: boolean; fileReferences?: SourceProjection['fileReferences'] } | null>(null);
  const [sourceError, setSourceError] = useState('');
  const [sourceLoading, setSourceLoading] = useState(false);
  const [inspectorOpen, setInspectorOpen] = useState(true);
  const requestRefs = useRef(new Map<string, AbortController>());
  const profiles = useMemo(() => (declared?.environments || []).filter(item => item.name && item.state === 'active'), [declared]);
  const selectedFile = useMemo(() => {
    const listed = Object.values(directoryMap).flatMap(item => item.items).find(item => item.path === (source?.logicalPath || filePath));
    if (listed) return listed;
    if (!filePath) return null;
    const resolved = source?.logicalPath || filePath;
    return { name: resolved.split('/').pop() || resolved, path: resolved, kind: 'file' as const,
      format: source?.format, sourceAvailable: source?.available, reason: source?.reason };
  }, [directoryMap, filePath, source]);

  const loadDirectory = useCallback(async (path = '', query?: string, offset = 0) => {
    if (!packageId) return;
    if (!api.listConfigurationFiles) { setTreeError('This Server does not provide the bounded Configuration file tree yet.'); return; }
    const key = (query ? 'search:' + query : path) + ':' + offset;
    requestRefs.current.get(key)?.abort();
    const controller = new AbortController(); requestRefs.current.set(key, controller);
    if (query) setTreeError('');
    else setDirectoryMap(current => ({ ...current, [path]: { items: current[path]?.items || [], loading: true, error: '' } }));
    try {
      const page = await api.listConfigurationFiles(packageId, query ? undefined : path, query, offset, controller.signal);
      if (controller.signal.aborted) return;
      if (query) {
        setSearchResults(current => offset > 0 ? [...(current || []), ...(page.items || [])] : page.items || []);
        setSearchNextOffset(page.nextOffset ?? null);
        setSearchTruncated(current => offset > 0 ? current || Boolean(page.truncated) : Boolean(page.truncated));
      } else setDirectoryMap(current => ({ ...current, [path]: {
        items: offset > 0 ? [...(current[path]?.items || []), ...(page.items || [])] : page.items || [],
        loading: false, error: '', truncated: page.truncated, nextOffset: page.nextOffset ?? null,
      } }));
      if (query) setTreeError('');
    } catch (error) {
      if (controller.signal.aborted) return;
      const message = errorText(error, 'load the Configuration file list');
      if (query) setTreeError(message);
      else setDirectoryMap(current => ({ ...current, [path]: { items: current[path]?.items || [], loading: false, error: message } }));
    } finally { if (requestRefs.current.get(key) === controller) requestRefs.current.delete(key); }
  }, [api, packageId]);

  useEffect(() => {
    requestRefs.current.forEach(controller => controller.abort()); requestRefs.current.clear();
    setDirectoryMap({}); setExpanded(new Set([''])); setSearchResults(null); setTreeError(''); setSource(null); setSourceError(''); setDeclared(null);
    if (!packageId) return;
    void loadDirectory('');
    const controller = new AbortController();
    api.getDeclaredConfiguration(packageId, controller.signal).then(setDeclared).catch(() => { if (!controller.signal.aborted) setDeclared(null); });
    return () => { controller.abort(); requestRefs.current.forEach(item => item.abort()); requestRefs.current.clear(); };
  }, [api, packageId, loadDirectory]);

  useEffect(() => {
    if (tab !== 'source' || !filePath || !packageId) { setSource(null); setSourceError(''); return; }
    const controller = new AbortController(); setSourceLoading(true); setSource(null); setSourceError('');
    const sourceRequest = sourceReference
      ? api.getResourceSourceFile?.({ packageId, type: sourceReference.type, resourceId: sourceReference.resourceId }, sourceReference.referenceId, controller.signal, fileOrigin)
      : api.getConfigurationFileSource?.(packageId, filePath, fileOrigin, controller.signal);
    if (!sourceRequest) { setSource({ text: '', format: '', available: false, reason: 'unsupported' }); setSourceLoading(false); return () => controller.abort(); }
    sourceRequest.then(result => setSource({
      text: result.text || '', format: result.format || filePath.split('.').pop() || 'text', logicalPath: result.logicalPath, available: result.available, reason: result.reason, fileReferences: result.fileReferences,
    })).catch(error => { if (!controller.signal.aborted) setSourceError(errorText(error, 'load safe source')); })
      .finally(() => { if (!controller.signal.aborted) setSourceLoading(false); });
    return () => controller.abort();
  }, [api, packageId, filePath, fileOrigin, sourceReference?.type, sourceReference?.resourceId, sourceReference?.referenceId, tab]);

  useEffect(() => {
    if (!filePath || !filePath.startsWith('config/') || !packageId) return;
    const segments = filePath.split('/').filter(Boolean);
    if (segments.length < 2) return;
    let parent = '';
    for (const segment of segments.slice(0, -1)) {
      parent = parent ? `${parent}/${segment}` : segment;
      setExpanded(current => new Set(current).add(parent));
      if (!directoryMap[parent]) void loadDirectory(parent);
    }
  }, [filePath, packageId, directoryMap, loadDirectory]);

  async function search(event: React.FormEvent) {
    event.preventDefault();
    const query = searchText.trim();
    if (!query) { setSearchResults(null); setSearchNextOffset(null); setSearchTruncated(false); setTreeError(''); return; }
    setSearchResults([]); setSearchNextOffset(null); setSearchTruncated(false); await loadDirectory('', query, 0);
  }
  function toggleDirectory(path: string) {
    setExpanded(current => {
      const next = new Set(current);
      if (next.has(path)) next.delete(path);
      else { next.add(path); if (!directoryMap[path]) void loadDirectory(path); }
      return next;
    });
  }
  async function openDirectoryPath(path: string) {
    const segments = path.split('/').filter(Boolean);
    for (let index = 2; index < segments.length; index++) {
      const parent = segments.slice(0, index).join('/');
      setExpanded(current => new Set(current).add(parent));
      if (!directoryMap[parent]) await loadDirectory(parent);
    }
    setExpanded(current => new Set(current).add(path));
    if (!directoryMap[path]) await loadDirectory(path);
    setSearchResults(null);
  }
  function handleTreeKey(event: KeyboardEvent<HTMLUListElement>) {
    if (!['ArrowDown', 'ArrowUp', 'ArrowRight', 'ArrowLeft'].includes(event.key)) return;
    const current = event.target as HTMLElement;
    if (!current.matches('[role="treeitem"]')) return;
    const items = [...(event.currentTarget.querySelectorAll<HTMLElement>('[role="treeitem"]'))];
    const index = items.indexOf(current);
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault(); items[index + (event.key === 'ArrowDown' ? 1 : -1)]?.focus();
    } else if (event.key === 'ArrowRight' && current.dataset.kind === 'directory') {
      event.preventDefault();
      const path = current.dataset.path || '';
      if (!expanded.has(path)) toggleDirectory(path);
      else (current.nextElementSibling as HTMLElement | null)?.querySelector<HTMLElement>('[role="treeitem"]')?.focus();
    } else if (event.key === 'ArrowLeft') {
      event.preventDefault();
      const path = current.dataset.path || '';
      if (current.dataset.kind === 'directory' && expanded.has(path)) toggleDirectory(path);
      else {
        const parent = current.closest('[role="group"]')?.previousElementSibling as HTMLElement | null;
        if (parent?.matches('[role="treeitem"]')) parent.focus();
        else parent?.querySelector<HTMLElement>('[role="treeitem"]')?.focus();
      }
    }
  }

  function renderNodes(items: ConfigurationFileNode[], depth = 0): React.ReactNode {
    return items.map(node => {
      const isOpen = expanded.has(node.path);
      const children = directoryMap[node.path];
      return <li key={node.path} role="none">
        <button type="button" role="treeitem" data-kind={node.kind} data-path={node.path}
          aria-expanded={node.kind === 'directory' ? isOpen : undefined} aria-current={filePath === node.path ? 'page' : undefined}
          style={{ paddingInlineStart: `${0.6 + depth * 0.9}rem` }}
          disabled={node.kind === 'directory' && node.reason === 'depth-limit'}
          onClick={() => node.kind === 'directory' ? toggleDirectory(node.path) : onFileChange(node.path)}>
          <span aria-hidden="true">{node.kind === 'directory' ? (isOpen ? '▾' : '▸') : '·'}</span> {node.name}
          {node.kind === 'file' && node.sizeBytes != null && <small>{node.sizeBytes.toLocaleString()} B</small>}
          {node.reason === 'depth-limit' && <small>depth limit</small>}
        </button>
        {node.kind === 'directory' && isOpen && <ul role="group">
          {children?.loading && <li role="none"><span className="att-config-tree-message" role="status">Loading…</span></li>}
          {children?.error && <li role="none"><span className="att-config-tree-message att-config-tree-error" role="alert">{children.error}<button type="button" onClick={() => void loadDirectory(node.path)}>Retry</button></span></li>}
          {children?.items.length ? renderNodes(children.items, depth + 1) : !children?.loading && !children?.error && <li role="none"><span className="att-config-tree-message">Empty directory</span></li>}
          {children?.nextOffset != null && <li role="none"><button type="button" onClick={() => void loadDirectory(node.path, undefined, children.nextOffset || 0)}>Load more entries</button></li>}
          {children?.truncated && <li role="none"><span className="att-config-tree-message">This directory reached its listing cap. Search by path to find more.</span></li>}
        </ul>}
      </li>;
    });
  }

  const tabs: Array<{ id: WorkspaceTab; label: string }> = [
    { id: 'source', label: 'Source' }, { id: 'declared', label: 'Declared' }, { id: 'effective', label: 'Effective' },
    { id: 'compare', label: 'Compare' }, { id: 'testdata', label: 'Testdata' },
  ];
  const refs = selectedFile?.references || [];

  return <div className="att-configuration-page">
    <header className="att-configuration-page-heading">
      <div><p className="eyebrow">Package runtime configuration</p><h1>Configuration</h1><p>Inspect safe package source and resolved values. This workspace is read-only.</p></div>
      <div className="att-configuration-context">
        <label>Package<select aria-label="Package" value={packageId} onChange={event => onPackageChange(event.currentTarget.value)}>
          <option value="">Choose a package</option>{packages.map(item => <option key={item.packageId} value={item.packageId}>{item.displayName || item.packageId}</option>)}
        </select></label>
        {packageId && <label>Environment<select aria-label="Configuration environment" value={environment} onChange={event => onEnvironmentChange(event.currentTarget.value)}>
          <option value="">Package default</option>{profiles.map(item => <option key={item.name} value={item.name}>{item.name}{item.default ? ' · default' : ''}</option>)}
        </select></label>}
        <button type="button" aria-expanded={inspectorOpen} onClick={() => setInspectorOpen(value => !value)}>Inspector {inspectorOpen ? 'on' : 'off'}</button>
      </div>
    </header>
    {!packageId ? <section className="att-config-select-package"><h2>Choose a package</h2><p>Configuration is scoped to a registered package.</p>
      {packages.length ? <ul>{packages.map(item => <li key={item.packageId}><button type="button" onClick={() => onPackageChange(item.packageId)}>{item.displayName || item.packageId}</button></li>)}</ul> : <p>No registered packages are available.</p>}
    </section> : <div className={`att-configuration-layout${inspectorOpen ? '' : ' inspector-collapsed'}`}>
      <aside className="att-configuration-tree" aria-label="Package configuration files">
        <h2>Configuration files</h2>
        <form onSubmit={event => void search(event)} className="att-config-search"><label htmlFor="att-config-file-search">Search files</label>
          <div><input id="att-config-file-search" type="search" value={searchText} onChange={event => setSearchText(event.currentTarget.value)} placeholder="Search paths" />
            <button type="submit">Search</button></div>
        </form>
        <div className="att-config-tree-actions">
          <span>{filePath ? <><span>config</span>{filePath.split('/').map(part => <span key={part}> / {part}</span>)}</> : 'config/'}</span>
          <button type="button" onClick={() => { setExpanded(new Set([''])); setSearchResults(null); }}>Collapse all</button>
        </div>
        <p className="att-config-tree-note">Only files and folders under the registered package config root are listed.</p>
        {treeError && !searchResults && <p className="att-error" role="alert">{treeError}</p>}
        {searchResults ? <ul role="tree" aria-label="Configuration file search results">
          {searchResults.map(node => <li role="none" key={node.path}><button type="button" role="treeitem" aria-current={node.kind === 'file' && filePath === node.path ? 'page' : undefined}
            disabled={node.kind === 'directory' && node.reason === 'depth-limit'}
            onClick={() => node.kind === 'directory' ? void openDirectoryPath(node.path) : onFileChange(node.path)}>
            {node.kind === 'directory' ? 'Folder · ' : ''}{node.path}</button></li>)}
          {!searchResults.length && <li role="none"><span className="att-config-tree-message">No matching files.</span></li>}
        </ul> : <ul role="tree" aria-label="Configuration file tree" onKeyDown={handleTreeKey}>
          {directoryMap['']?.loading && <li role="none"><span className="att-config-tree-message" role="status">Loading configuration files…</span></li>}
          {directoryMap['']?.error && <li role="none"><span className="att-config-tree-message att-config-tree-error" role="alert">{directoryMap[''].error}<button type="button" onClick={() => void loadDirectory('')}>Retry</button></span></li>}
          {directoryMap['']?.items?.length ? renderNodes(directoryMap[''].items) : !directoryMap['']?.loading && !directoryMap['']?.error && <li role="none"><span className="att-config-tree-message">No configuration files were returned.</span></li>}
        </ul>}
        {searchResults && searchNextOffset != null && <button type="button" onClick={() => void loadDirectory('', searchText.trim(), searchNextOffset)}>Load more search results</button>}
        {searchResults && searchTruncated && <p className="att-config-tree-message">Search reached the Server traversal cap. Narrow the query to find later paths.</p>}
        {directoryMap['']?.nextOffset != null && <button type="button" onClick={() => void loadDirectory('', undefined, directoryMap[''].nextOffset || 0)}>Load more config entries</button>}
        {directoryMap['']?.truncated && <p className="att-config-tree-message">The Server reached its bounded listing cap. Use search to locate more files.</p>}
      </aside>
      <section className="att-configuration-center" aria-label="Configuration inspection">
        <div className="att-config-tabs" role="tablist" aria-label="Configuration view">
          {tabs.map(item => <button key={item.id} type="button" role="tab" aria-selected={tab === item.id} onClick={() => onTabChange(item.id)}>{item.label}</button>)}
        </div>
        {tab === 'source' && <section className="att-config-source-view" aria-label="Safe configuration source">
          <div className="att-config-source-heading"><div><h2>{source?.logicalPath || filePath || 'Select a configuration file'}</h2><p>{source?.format ? `${source.format.toUpperCase()} · ` : ''}{source?.available ? 'Safe read-only source' : source?.reason ? `Source unavailable (${source.reason})` : 'Select a file from the package tree.'}</p></div></div>
          {sourceLoading && <p role="status">Loading safe configuration source…</p>}
          {sourceError && <p className="att-error" role="alert">{sourceError}<button type="button" onClick={() => filePath && void loadDirectory(filePath.split('/').slice(0, -1).join('/'))}>Refresh file list</button></p>}
          {source && !source.available && <div className="att-config-unavailable"><h3>Source is not available</h3><p>The Server withheld or could not read this file. Its path and safe metadata remain visible.</p></div>}
          {source?.available && source.text.includes('&{') && !source.fileReferences?.some(item => item.referenceId && item.available !== false) && <p className="att-config-tree-message" role="status">{sourceReference ? 'This projection includes no Server-verified file-reference token, so file references cannot be opened.' : 'References in config/ source remain visible but cannot be opened because this endpoint did not return Server-verified reference tokens.'}</p>}
          {source?.available && <CodeEditor value={source.text} language={detectCodeLanguage(source.logicalPath || filePath || source.format || '')} label={`Safe source ${filePath || ''}`} readOnly sourcePath={source.logicalPath || filePath} fileReferences={source.fileReferences || []} onFileReference={(_locator, origin, referenceId) => {
            const reference = source.fileReferences?.find(item => item.referenceId === referenceId && item.available !== false);
            if (!reference) return;
            const nextOrigin = source.logicalPath || origin;
            if (sourceReference) onFileChange(reference.logicalPath, nextOrigin, { ...sourceReference, referenceId: reference.referenceId });
            else onFileChange(reference.logicalPath, nextOrigin);
          }} maxCharacters={200_000} />}
          {!filePath && !sourceLoading && <div className="att-config-empty"><h3>Select a file</h3><p>Choose a configuration file to inspect its safe source.</p></div>}
        </section>}
        {(tab === 'declared' || tab === 'effective' || tab === 'compare') && <ConfigurationExplorer
          api={api} packageId={packageId} environment={environment} onEnvironmentChange={onEnvironmentChange}
          initialMode={tab} showEnvironmentSelector={false} onViewChange={onTabChange}
        />}
        {tab === 'testdata' && <TestdataCatalog api={api} packageId={packageId} environment={environment} onSourceFile={onFileChange} onOpenResource={onOpenResource} />}
      </section>
      {inspectorOpen && <aside className="att-configuration-inspector" aria-label="Configuration inspector">
        <h2>Inspector</h2>
        {!selectedFile ? <p>Select a file to inspect its safe metadata, status, and references.</p> : <>
          <dl><div><dt>Name</dt><dd>{selectedFile.name}</dd></div><div><dt>Path</dt><dd>{selectedFile.path}</dd></div>
            <div><dt>Type</dt><dd>{selectedFile.kind === 'directory' ? 'Directory' : selectedFile.format || selectedFile.path.split('.').pop()?.toUpperCase() || 'File'}</dd></div>
            <div><dt>Size</dt><dd>{selectedFile.sizeBytes == null ? 'Not provided' : `${selectedFile.sizeBytes.toLocaleString()} bytes`}</dd></div>
            <div><dt>Source</dt><dd>{selectedFile.sourceAvailable === false ? selectedFile.reason || 'Unavailable by Server policy' : 'Safe source available'}</dd></div>
            <div><dt>Environment</dt><dd>{environment || 'Package default'}</dd></div></dl>
          {refs.length > 0 && <><h3>Related resources</h3><ul>{refs.map((reference, index) => <ReferenceLink key={`${reference.type}:${reference.resourceId}:${index}`} reference={reference} onOpen={onOpenResource} />)}</ul></>}
          {selectedFile.diagnostics?.map((diagnostic, index) => <p className="att-diagnostic" key={`${diagnostic.code}:${index}`}>{diagnostic.code ? `${diagnostic.code}: ` : ''}{diagnostic.summary}</p>)}
        </>}
      </aside>}
    </div>}
  </div>;
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

function TestdataCatalog({ api, packageId, environment, onSourceFile, onOpenResource }: { api: ExplorerApi; packageId: string; environment: string; onSourceFile(path?: string): void; onOpenResource(resource: { type: string; resourceId: string }): void }) {
  const [entries, setEntries] = useState<ConfigurationEntry[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [response, setResponse] = useState<TestdataRecordsResponse | null>(null);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('Loading resolved Testdata catalog…');
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(false);
  const [revision, setRevision] = useState(0);
  const [catalogSearch, setCatalogSearch] = useState('');
  const [catalogNextOffset, setCatalogNextOffset] = useState<number | null>(null);
  const [catalogLoading, setCatalogLoading] = useState(false);
  const [previewGenerated, setPreviewGenerated] = useState(false);
  const [copiedRecord, setCopiedRecord] = useState<number | null>(null);
  const selectedEntry = entries.find(item => item.id === selectedId);
  const visibleEntries = useMemo(() => entries
    .filter(item => item.id.toLocaleLowerCase().includes(catalogSearch.trim().toLocaleLowerCase()))
    .sort((left, right) => left.id.localeCompare(right.id, undefined, { numeric: true, sensitivity: 'base' })),
  [entries, catalogSearch]);
  const isGenerated = selectedEntry?.generated === true || displayField(selectedEntry || { id: '' }, 'generated') === 'true';

  useEffect(() => {
    const controller = new AbortController(); setEntries([]); setSelectedId(''); setResponse(null); setError(''); setStatus('Loading resolved Testdata catalog…'); setCatalogNextOffset(null); setCatalogSearch(''); setOffset(0); setPreviewGenerated(false);
    api.getEffectiveConfiguration(packageId, { environment: environment || undefined, section: 'testdata', offset: 0, limit: 50 }, controller.signal)
      .then(result => {
        const data = result.sections?.find(section => section.id === 'testdata')?.entries || [];
        if (controller.signal.aborted) return;
        setEntries(data); setSelectedId(data[0]?.id || ''); setCatalogNextOffset(result.nextOffset ?? null);
        setStatus(data.length ? data.length + ' Testdata descriptors loaded for ' + (environment || result.environment || 'package default') + '.' : 'No Testdata descriptors are configured for this environment.');
      }).catch(reason => { if (!controller.signal.aborted) { setError(errorText(reason, 'load the Testdata catalog')); setStatus('Testdata catalog unavailable.'); } });
    return () => controller.abort();
  }, [api, packageId, environment]);

  async function loadMoreCatalog() {
    if (catalogNextOffset == null || catalogLoading) return;
    setCatalogLoading(true); setError('');
    try {
      const result = await api.getEffectiveConfiguration(packageId, { environment: environment || undefined, section: 'testdata', offset: catalogNextOffset, limit: 50 });
      const next = result.sections?.find(section => section.id === 'testdata')?.entries || [];
      setEntries(current => [...current, ...next]); setCatalogNextOffset(result.nextOffset ?? null);
    } catch (reason) { setError(errorText(reason, 'load more Testdata descriptors')); }
    finally { setCatalogLoading(false); }
  }

  useEffect(() => {
    if (visibleEntries.length && !visibleEntries.some(item => item.id === selectedId)) setSelectedId(visibleEntries[0].id);
  }, [visibleEntries, selectedId]);

  useEffect(() => {
    if (!selectedId || !api.getTestdataRecords) { setResponse(null); return; }
    const controller = new AbortController(); setLoading(true); setError('');
    api.getTestdataRecords(packageId, selectedId, { environment: environment || undefined, offset, limit: 50, previewGenerated }, controller.signal)
      .then(result => { if (!controller.signal.aborted) setResponse(result); })
      .catch(reason => {
        if (controller.signal.aborted) return;
        const status = typeof reason === 'object' && reason !== null && 'status' in reason
          ? Number((reason as { status?: unknown }).status) : 0;
        setError(status === 404
          ? 'Testdata record preview is not available from this Server version; safe descriptor metadata remains visible.'
          : errorText(reason, 'load Testdata records'));
      })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [api, packageId, selectedId, environment, offset, isGenerated, previewGenerated, revision]);

  async function copyRecord(index: number, value: JsonValue) {
    try { await navigator.clipboard.writeText(JSON.stringify(value)); setCopiedRecord(index); setStatus(`Copied Testdata row ${index + 1}.`); }
    catch { setStatus('Copy is unavailable in this browser context.'); }
  }

  const records = response?.records || [];
  const columns = useMemo(() => [...new Set(records.flatMap(record => record.value && typeof record.value === 'object' && !Array.isArray(record.value) ? Object.keys(record.value) : ['value']))], [records]);
  if (error && !selectedId) return <section className="att-testdata-workspace"><h2>Testdata</h2><p className="att-error" role="alert">{error}</p></section>;
  return <section className="att-testdata-workspace" aria-label="Structured Testdata catalog">
    <header><div><h2>Testdata</h2><p>Resolved descriptor metadata and bounded records for {environment || 'the package default environment'}.</p></div>
      {entries.length > 0 && <label>Search descriptors<input type="search" aria-label="Search Testdata descriptors" value={catalogSearch} onChange={event => setCatalogSearch(event.currentTarget.value)} placeholder="Search descriptor IDs" /></label>}
    </header>
    <p role="status" aria-live="polite">{status}{loading ? ' Loading records…' : ''}</p>
    {error && <p className="att-error" role="alert">{error}<button type="button" onClick={() => setRevision(value => value + 1)}>Retry</button></p>}
    <div className="att-testdata-layout">
      <nav className="att-testdata-catalog" aria-label="Testdata descriptors">
        <h3>Catalog</h3><p>{visibleEntries.length} shown of {entries.length} loaded</p>
        <ul>{visibleEntries.map(item => <li key={item.id}><button type="button" aria-current={selectedId === item.id ? 'true' : undefined}
          onClick={() => { setSelectedId(item.id); setOffset(0); setPreviewGenerated(false); setRevision(value => value + 1); }}>
          <span>{item.id}</span><small>{item.source || displayField(item, 'source')}</small></button></li>)}</ul>
        {catalogNextOffset != null && <button type="button" onClick={() => void loadMoreCatalog()} disabled={catalogLoading}>{catalogLoading ? 'Loading…' : 'Load more descriptors'}</button>}
        {entries.length > 0 && !visibleEntries.length && <p>No descriptors match this search.</p>}
      </nav>
      <div className="att-testdata-detail">
    {selectedEntry ? <>
      <dl className="att-testdata-meta"><div><dt>Descriptor</dt><dd>{selectedEntry.id}</dd></div><div><dt>Source</dt><dd>{selectedEntry.source ? <button type="button" onClick={() => onSourceFile(selectedEntry.source)}>{selectedEntry.source}</button> : 'Not provided'}</dd></div>
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
        {!previewGenerated && <button type="button" onClick={() => { setOffset(0); setPreviewGenerated(true); }} disabled={loading}>Preview first 50 generated records</button>}
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
      {response?.nextOffset != null && <button type="button" onClick={() => setOffset(response.nextOffset || 0)} disabled={loading}>Load next 50 records</button>}
      <details className="att-testdata-source"><summary>Advanced descriptor metadata</summary><CodeEditor value={JSON.stringify(Object.fromEntries(Object.entries(selectedEntry.fields || {}).map(([key, field]) => [key, field.state === 'visible' ? field.value : field.state])), null, 2)} language="json" label="Advanced Testdata descriptor metadata" readOnly /></details>
    </> : <p className="att-config-empty">No Testdata descriptors were returned for the selected environment.</p>}
      </div>
    </div>
  </section>;
}
