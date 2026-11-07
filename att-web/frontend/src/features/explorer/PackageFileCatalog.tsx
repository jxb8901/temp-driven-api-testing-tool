import { useEffect, useMemo, useRef, useState } from 'react';
import type { ExplorerApi, ResourceIdentity, ResourceSourceReference, ResourceSummary } from './types';
import './package-file-catalog.css';

const PAGE_SIZE = 25;
const FILES_PER_PAGE = 25;

interface FileUse {
  type: ResourceSummary['type'];
  resourceId: string;
  label: string;
  referenceId: string;
  available: boolean;
}
interface CatalogFile { path: string; uses: FileUse[] }

function validPath(path: string): boolean {
  return Boolean(path && path.length <= 512 && !path.startsWith('/') && !path.includes('\\')
    && path.split('/').every(part => part && part !== '.' && part !== '..'));
}

async function mapLimited<T, R>(items: T[], limit: number, run: (item: T) => Promise<R>): Promise<R[]> {
  const result: R[] = new Array(items.length);
  let next = 0;
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (next < items.length) {
      const index = next++;
      result[index] = await run(items[index]);
    }
  });
  await Promise.all(workers);
  return result;
}

export function PackageFileCatalog({ api, packageId, onOpenFile }: {
  api: ExplorerApi;
  packageId: string;
  onOpenFile(path: string, origin: string | undefined, reference: ResourceSourceReference): void;
}) {
  const [files, setFiles] = useState<CatalogFile[]>([]);
  const [query, setQuery] = useState('');
  const [cursor, setCursor] = useState<string | null>(null);
  const [pagesLoaded, setPagesLoaded] = useState(0);
  const [resourceCountScanned, setResourceCountScanned] = useState(0);
  const [visibleCount, setVisibleCount] = useState(FILES_PER_PAGE);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [omittedSources, setOmittedSources] = useState(0);
  const controllerRef = useRef<AbortController | null>(null);
  const generationRef = useRef(0);

  useEffect(() => {
    generationRef.current++;
    controllerRef.current?.abort();
    controllerRef.current = null;
    setFiles([]); setCursor(null); setPagesLoaded(0); setResourceCountScanned(0); setVisibleCount(FILES_PER_PAGE);
    setError(''); setOmittedSources(0); setLoading(false);
  }, [packageId, api]);

  async function scanNextPage() {
    if (loading) return;
    const generation = generationRef.current;
    const controller = new AbortController();
    controllerRef.current?.abort();
    controllerRef.current = controller;
    setLoading(true); setError('');
    try {
      const page = await api.listResources(packageId, { type: 'all', limit: PAGE_SIZE, ...(cursor ? { cursor } : {}) }, controller.signal);
      if (controller.signal.aborted || generation !== generationRef.current) return;
      let omitted = 0;
      const supportedSources = (page.items || []).filter(resource => resource.type === 'template' || resource.type === 'flow');
      const found = await mapLimited(supportedSources, 4, async resource => {
        try {
          const identity: ResourceIdentity = { packageId, type: resource.type, resourceId: resource.resourceId };
          const projection = await api.getSafeSource(identity, controller.signal);
          return (projection.fileReferences || []).filter(reference => validPath(reference.logicalPath)
            && /^[0-9a-f]{64}$/i.test(reference.referenceId)).map(reference => ({ path: reference.logicalPath, use: {
              type: resource.type, resourceId: resource.resourceId,
              label: resource.logicalId || resource.name || resource.resourceId,
              referenceId: reference.referenceId, available: reference.available !== false,
            } satisfies FileUse }));
        } catch {
          if (!controller.signal.aborted) omitted++;
          return [];
        }
      });
      if (controller.signal.aborted || generation !== generationRef.current) return;
      const grouped = new Map<string, FileUse[]>();
      for (const entry of found.flat()) grouped.set(entry.path, [...(grouped.get(entry.path) || []), entry.use]);
      setFiles(current => {
        const next = new Map(current.map(item => [item.path, item.uses]));
        for (const [path, uses] of grouped) {
          const merged = new Map<string, FileUse>();
          for (const use of [...(next.get(path) || []), ...uses]) merged.set(`${use.type}:${use.resourceId}:${use.referenceId}`, use);
          next.set(path, [...merged.values()]);
        }
        return [...next].map(([path, uses]) => ({ path, uses })).sort((left, right) => left.path.localeCompare(right.path));
      });
      setOmittedSources(value => value + omitted);
      setResourceCountScanned(value => value + (page.items || []).length);
      setCursor(page.nextCursor || null);
      setPagesLoaded(value => value + 1);
    } catch (reason) {
      if (!controller.signal.aborted && generation === generationRef.current) {
        const status = typeof reason === 'object' && reason !== null && 'status' in reason ? Number((reason as { status?: unknown }).status) : 0;
        setError(status === 403 ? 'This Package source listing is not available to the current account.'
          : status === 401 ? 'Your session expired. Sign in again, then retry the File catalog.'
            : status ? `The File catalog could not load source references (HTTP ${status}). Retry.`
              : 'The File catalog could not load source references. Check the connection and retry.');
      }
    } finally {
      if (!controller.signal.aborted && generation === generationRef.current) setLoading(false);
    }
  }

  const visibleFiles = useMemo(() => files.filter(item => !query.trim()
    || `${item.path} ${item.uses.map(use => `${use.label} ${use.type}`).join(' ')}`.toLocaleLowerCase().includes(query.trim().toLocaleLowerCase())), [files, query]);
  const shownFiles = visibleFiles.slice(0, visibleCount);
  const hasMoreResources = pagesLoaded === 0 || cursor !== null;

  return <section className="att-package-file-catalog" aria-label="Package file catalog">
    <header><div><h2>File catalog</h2><p>Package files discovered from Server-verified safe references. File contents open in the separate source viewer.</p></div>
      <label>Filter files<input type="search" value={query} onChange={event => { setQuery(event.currentTarget.value); setVisibleCount(FILES_PER_PAGE); }} placeholder="Path or referencing resource" /></label>
    </header>
    <p role="status" aria-live="polite">{files.length} referenced files found across {resourceCountScanned} scanned resources.</p>
    {omittedSources > 0 && <p role="status">Safe references were unavailable for {omittedSources} resource{omittedSources === 1 ? '' : 's'}; those resources were skipped.</p>}
    {error && <p role="alert" className="att-error">{error}</p>}
    {shownFiles.length > 0 ? <ul className="att-package-file-list">{shownFiles.map(file => {
      const firstAvailable = file.uses.find(use => use.available);
      return <li key={file.path}><div><code>{file.path}</code><small>{file.uses.length} verified reference{file.uses.length === 1 ? '' : 's'}</small>
        <ul>{file.uses.slice(0, 5).map(use => <li key={`${use.type}:${use.resourceId}:${use.referenceId}`}>
          {use.label} <span>({use.type})</span>{!use.available && <span> · file content unavailable</span>}
        </li>)}</ul>{file.uses.length > 5 && <small>{file.uses.length - 5} additional references</small>}</div>
        {firstAvailable ? <button type="button" onClick={() => onOpenFile(file.path, undefined,
          { type: firstAvailable.type, resourceId: firstAvailable.resourceId, referenceId: firstAvailable.referenceId })}>Open file</button>
          : <span role="status">Unavailable</span>}
      </li>;
    })}</ul> : <p className="att-file-catalog-empty">{files.length ? 'No files match this filter.' : 'No referenced files have been found yet. Scan a resource page to discover verified references.'}</p>}
    {visibleFiles.length > visibleCount && <button type="button" onClick={() => setVisibleCount(value => value + FILES_PER_PAGE)}>Load more files</button>}
    {hasMoreResources && <button type="button" onClick={() => void scanNextPage()} disabled={loading}>
      {loading ? 'Scanning resource sources…' : pagesLoaded ? 'Scan next resource page' : 'Scan resource sources'}
    </button>}
    {!hasMoreResources && pagesLoaded > 0 && <p className="att-file-catalog-empty">All Package resource pages have been scanned.</p>}
  </section>;
}
