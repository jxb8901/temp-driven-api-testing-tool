import { useInspectionRefresh } from '../../useInspectionRefresh';
import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { CodeEditor, detectCodeLanguage } from '../code/CodeEditor';
import { ConfigurationExplorer } from './ConfigurationExplorer';
import type { ConfigurationFileNode, ExplorerApi, ResourceReference, ResourceSourceReference, SourceProjection } from './types';
import './configuration-workspace.css';

type WorkspaceTab = 'source' | 'declared' | 'effective' | 'compare';
interface ConfigurationWorkspaceProps {
  api: ExplorerApi;
  packageId: string;
  environment: string;
  filePath?: string;
  fileOrigin?: string;
  sourceReference?: ResourceSourceReference;
  configurationReferenceId?: string;
  tab: WorkspaceTab;
  onEnvironmentChange(environment: string): void;
  onFileChange(filePath?: string, origin?: string, reference?: ResourceSourceReference, configurationReferenceId?: string): void;
  onTabChange(tab: WorkspaceTab): void;
  onOpenResource(resource: { type: string; resourceId: string }): void;
  onValidate?(): void;
  validationLabel?: string;
  canRevealAdminFields?: boolean;
}
interface DirectoryState { items: ConfigurationFileNode[]; loading: boolean; error: string; truncated?: boolean; nextOffset?: number | null }

function errorText(error: unknown, action: string): string {
  const status = typeof error === 'object' && error !== null && 'status' in error ? Number((error as { status?: unknown }).status) : 0;
  if (status === 401) return 'Your session has expired. Sign in again, then retry.';
  if (status === 403) return `You do not have permission to ${action}.`;
  if (status === 404) return 'The selected configuration file is unavailable. Refresh Configuration.';
  if (status === 413) return 'The Server declined this projection because it exceeds the configured safety limit.';
  if (status === 429 || status === 503 || status === 504) return 'This inspection request is temporarily unavailable. Retry shortly.';
  if (status) return `${action} failed (HTTP ${status}).`;
  return `Could not ${action}. Check the connection and retry.`;
}
export function ConfigurationWorkspace({ api, packageId, environment, filePath, fileOrigin, sourceReference, configurationReferenceId, tab, onEnvironmentChange, onFileChange, onTabChange, onOpenResource, onValidate, validationLabel, canRevealAdminFields = false }: ConfigurationWorkspaceProps) {
  const inspectionRefresh = useInspectionRefresh(true);
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
  const [includeAllFields, setIncludeAllFields] = useState(false);
  const requestRefs = useRef(new Map<string, AbortController>());
  const sourceFlight = useRef<Promise<void>>(Promise.resolve());
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
    if (!api.listConfigurationFiles) { setTreeError('This Server does not provide the bounded Configuration file tree yet.'); return; }
    const key = (query ? 'search:' + query : path) + ':' + offset;
    requestRefs.current.get(key)?.abort();
    const controller = new AbortController(); requestRefs.current.set(key, controller);
    if (query) setTreeError('');
    else setDirectoryMap(current => ({ ...current, [path]: { items: current[path]?.items || [], loading: true, error: '' } }));
    try {
      const page = await api.listConfigurationFiles('', query ? undefined : path, query, offset, controller.signal);
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
  }, [api]);

  useEffect(() => {
    sourceFlight.current = Promise.resolve();
    if (tab !== 'source' || !filePath || (sourceReference && !packageId)) { setSource(null); setSourceError(''); return; }
    const controller = new AbortController(); setSourceLoading(true); setSource(null); setSourceError('');
    const sourceRequest = sourceReference
      ? api.getResourceSourceFile?.({ packageId, type: sourceReference.type, resourceId: sourceReference.resourceId }, sourceReference.referenceId, controller.signal, fileOrigin)
      : api.getConfigurationFileSource?.('', filePath, fileOrigin, controller.signal, configurationReferenceId);
    if (!sourceRequest) { setSource({ text: '', format: '', available: false, reason: 'unsupported' }); setSourceLoading(false); return () => controller.abort(); }
    sourceFlight.current = sourceRequest.then(result => { if (!controller.signal.aborted) setSource({
      text: result.text || '', format: result.format || filePath.split('.').pop() || 'text', logicalPath: result.logicalPath, available: result.available, reason: result.reason, fileReferences: result.fileReferences,
    }); }).catch(error => { if (!controller.signal.aborted) setSourceError(errorText(error, 'load safe source')); })
      .finally(() => { if (!controller.signal.aborted) setSourceLoading(false); });
    return () => controller.abort();
  }, [api, sourceReference ? packageId : '', filePath, fileOrigin, sourceReference?.type, sourceReference?.resourceId, sourceReference?.referenceId, configurationReferenceId, tab, inspectionRefresh]);

  useEffect(() => {
    requestRefs.current.forEach(controller => controller.abort()); requestRefs.current.clear();
    setDirectoryMap({}); setExpanded(new Set([''])); setSearchResults(null); setTreeError(''); setDeclared(null);
    const controller = new AbortController();
    // A deep-linked source gets the bounded inspection slot before background metadata.
    void sourceFlight.current.then(() => {
      if (controller.signal.aborted) return;
      void loadDirectory('');
      void api.getDeclaredConfiguration('', controller.signal).then(result => { if (!controller.signal.aborted) setDeclared(result); }).catch(() => { if (!controller.signal.aborted) setDeclared(null); });
    });
    return () => { controller.abort(); requestRefs.current.forEach(item => item.abort()); requestRefs.current.clear(); };
  }, [api, loadDirectory, inspectionRefresh]);

  useEffect(() => {
    if (!filePath || !filePath.startsWith('config/')) return;
    const segments = filePath.split('/').filter(Boolean);
    if (segments.length < 2) return;
    let cancelled = false;
    void sourceFlight.current.then(() => {
      if (cancelled) return;
      let parent = '';
      for (const segment of segments.slice(0, -1)) {
        parent = parent ? `${parent}/${segment}` : segment;
        setExpanded(current => new Set(current).add(parent));
        if (!directoryMap[parent]) void loadDirectory(parent);
      }
    });
    return () => { cancelled = true; };
  }, [filePath, directoryMap, loadDirectory]);

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
    { id: 'compare', label: 'Compare' },
  ];
  const refs = selectedFile?.references || [];

  return <div className="page-content att-configuration-page">
    <header className="att-configuration-page-heading">
      <div><p className="eyebrow">Shared Runtime Configuration</p><h1>Configuration</h1><p>Inspect centrally managed runtime configuration safely. This view is read-only.</p></div>
      <div className="att-configuration-context">
        {onValidate && <button className="button button-quiet" type="button" onClick={onValidate}>{validationLabel || 'Read-only validate Config'}</button>}
        <label>Environment<select aria-label="Configuration environment" value={environment} onChange={event => onEnvironmentChange(event.currentTarget.value)}>
          <option value="">Runtime default</option>{profiles.map(item => <option key={item.name} value={item.name}>{item.name}{item.default ? ' · default' : ''}</option>)}
        </select></label>
        <button type="button" aria-expanded={inspectorOpen} onClick={() => setInspectorOpen(value => !value)}>Inspector {inspectorOpen ? 'on' : 'off'}</button>
        {canRevealAdminFields && (tab === 'effective' || tab === 'compare') && <label className="att-config-all-fields-toggle"><input type="checkbox" checked={includeAllFields} onChange={event => setIncludeAllFields(event.currentTarget.checked)} /> Show more DB fields (sensitive values stay hidden)</label>}
      </div>
    </header>
    <div className={`att-configuration-layout${inspectorOpen ? '' : ' inspector-collapsed'}`}>
      <aside className="att-configuration-tree" aria-label="Shared configuration files">
        <h2>Configuration files</h2>
        <form onSubmit={event => void search(event)} className="att-config-search"><label htmlFor="att-config-file-search">Search files</label>
          <div><input id="att-config-file-search" type="search" value={searchText} onChange={event => setSearchText(event.currentTarget.value)} placeholder="Search paths" />
            <button type="submit">Search</button></div>
        </form>
        <div className="att-config-tree-actions">
          <span>{filePath ? <><span>config</span>{filePath.split('/').map(part => <span key={part}> / {part}</span>)}</> : 'config/'}</span>
          <button type="button" onClick={() => { setExpanded(new Set([''])); setSearchResults(null); }}>Collapse all</button>
        </div>
        <p className="att-config-tree-note">Only files and folders under the ATT_HOME config directory are listed.</p>
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
          <div className="att-config-source-heading"><div><h2>{source?.logicalPath || filePath || 'Select a configuration file'}</h2><p>{source?.format ? `${source.format.toUpperCase()} · ` : ''}{source?.available ? 'Safe read-only source' : source?.reason ? `Source unavailable (${source.reason})` : 'Select a file from the shared configuration tree.'}</p></div></div>
          {sourceLoading && <p role="status">Loading safe configuration source…</p>}
          {sourceError && <p className="att-error" role="alert">{sourceError}<button type="button" onClick={() => filePath && void loadDirectory(filePath.split('/').slice(0, -1).join('/'))}>Refresh file list</button></p>}
          {source && !source.available && <div className="att-config-unavailable"><h3>Source is not available</h3><p>The Server withheld or could not read this file. Its path and safe metadata remain visible.</p></div>}
          {source?.available && source.text.includes('&{') && !source.fileReferences?.some(item => item.referenceId && item.available !== false) && <p className="att-config-tree-message" role="status">The Server did not authorize any referenced files for navigation from this source.</p>}
          {source?.available && <CodeEditor value={source.text} language={detectCodeLanguage(source.logicalPath || filePath || source.format || '')} label={`Safe source ${filePath || ''}`} readOnly sourcePath={source.logicalPath || filePath} fileReferences={source.fileReferences || []} onFileReference={(_locator, origin, referenceId) => {
            const reference = source.fileReferences?.find(item => item.referenceId === referenceId && item.available !== false);
            if (!reference) return;
            const nextOrigin = source.logicalPath || origin;
            if (sourceReference) onFileChange(reference.logicalPath, nextOrigin, { ...sourceReference, referenceId: reference.referenceId });
            else onFileChange(reference.logicalPath, nextOrigin, undefined, reference.referenceId);
          }} maxCharacters={200_000} />}
          {!filePath && !sourceLoading && <div className="att-config-empty"><h3>Select a file</h3><p>Choose a configuration file to inspect its safe source.</p></div>}
        </section>}
        {(tab === 'declared' || tab === 'effective' || tab === 'compare') && <ConfigurationExplorer
          api={api} packageId="" environment={environment} onEnvironmentChange={onEnvironmentChange}
          initialMode={tab} showEnvironmentSelector={false} onViewChange={onTabChange} includeAllFields={includeAllFields}
        />}
      </section>
      {inspectorOpen && <aside className="att-configuration-inspector" aria-label="Configuration inspector">
        <h2>Inspector</h2>
        {!selectedFile ? <p>Select a file to inspect its safe metadata, status, and references.</p> : <>
          <dl><div><dt>Name</dt><dd>{selectedFile.name}</dd></div><div><dt>Path</dt><dd>{selectedFile.path}</dd></div>
            <div><dt>Type</dt><dd>{selectedFile.kind === 'directory' ? 'Directory' : selectedFile.format || selectedFile.path.split('.').pop()?.toUpperCase() || 'File'}</dd></div>
            <div><dt>Size</dt><dd>{selectedFile.sizeBytes == null ? 'Not provided' : `${selectedFile.sizeBytes.toLocaleString()} bytes`}</dd></div>
            <div><dt>Source</dt><dd>{selectedFile.sourceAvailable === false ? selectedFile.reason || 'Unavailable by Server policy' : 'Safe source available'}</dd></div>
            <div><dt>Environment</dt><dd>{environment || 'Runtime default'}</dd></div></dl>
          {refs.length > 0 && <><h3>Related resources</h3><ul>{refs.map((reference, index) => <ReferenceLink key={`${reference.type}:${reference.resourceId}:${index}`} reference={reference} onOpen={onOpenResource} />)}</ul></>}
          {selectedFile.diagnostics?.map((diagnostic, index) => <p className="att-diagnostic" key={`${diagnostic.code}:${index}`}>{diagnostic.code ? `${diagnostic.code}: ` : ''}{diagnostic.summary}</p>)}
        </>}
      </aside>}
    </div>
  </div>;
}

function ReferenceLink({ reference, onOpen }: { reference: ResourceReference; onOpen(resource: { type: string; resourceId: string }): void }) {
  if (!reference.type || !['case', 'template', 'flow', 'tool'].includes(reference.type) || !reference.resourceId || reference.resolution === 'unresolved') return <li>{reference.logicalId || reference.name || reference.resourceId || 'Unresolved reference'} <span>{reference.resolution || 'unresolved'}</span></li>;
  return <li><button type="button" onClick={() => onOpen({ type: reference.type!, resourceId: reference.resourceId! })}>{reference.logicalId || reference.name || reference.resourceId}</button> <span>{reference.type}</span></li>;
}
