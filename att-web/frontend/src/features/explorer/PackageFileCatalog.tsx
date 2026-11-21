import { useCallback, useEffect, useRef, useState } from 'react';
import type { ExplorerApi, PackageFileIndexItem, PackageFileIndexPage, PackageFileIndexUse, ResourceIdentity, ResourceSourceReference } from './types';
import './package-file-catalog.css';

const PAGE_SIZE = 25;

function errorMessage(reason: unknown): string {
  const status = typeof reason === 'object' && reason !== null && 'status' in reason
    ? Number((reason as { status?: unknown }).status) : 0;
  return status === 403 ? 'This Package file index is not available to the current account.'
    : status === 401 ? 'Your session expired. Sign in again, then retry the File catalog.'
      : status === 409 ? 'The Package changed while this index was being paged. Reload the current index.'
        : status ? `The Package file index could not be loaded (HTTP ${status}). Retry.`
          : 'The revision-fenced Package file index could not be loaded. Check the connection and retry.';
}

function mergeFiles(current: PackageFileIndexItem[], next: PackageFileIndexItem[]): PackageFileIndexItem[] {
  const merged = new Map(current.map(item => [item.path, item]));
  for (const item of next) merged.set(item.path, item);
  return [...merged.values()].sort((left, right) => left.path.localeCompare(right.path));
}

function useLabel(use: PackageFileIndexUse): string {
  const calls = use.callSites.map(site => site.actionId).filter((item): item is string => Boolean(item));
  return `${use.label} (${use.type})${calls.length ? ` · ${calls.join(', ')}` : ''}`;
}

export function PackageFileCatalog({ api, packageId, onOpenFile, onOpenResource, onOpenNativeTestCase, active = true, locale = 'en' }: {
  api: ExplorerApi;
  packageId: string;
  onOpenFile(path: string, origin: string | undefined, reference: ResourceSourceReference): void;
  onOpenResource?(identity: Omit<ResourceIdentity, 'packageId'>, actionId?: string): void;
  onOpenNativeTestCase?(path: string): void;
  active?: boolean;
  locale?: 'en' | 'zh-Hant';
}) {
  const [files, setFiles] = useState<PackageFileIndexItem[]>([]);
  const [query, setQuery] = useState('');
  const [cursor, setCursor] = useState<string | null>(null);
  const [total, setTotal] = useState(0);
  const [coverage, setCoverage] = useState<PackageFileIndexPage['coverage'] | null>(null);
  const [packageRevision, setPackageRevision] = useState('');
  const [canEdit, setCanEdit] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [reloadToken, setReloadToken] = useState(0);
  const controllerRef = useRef<AbortController | null>(null);
  const generationRef = useRef(0);

  const fetchPage = useCallback(async (requestedCursor: string | null, generation: number, controller: AbortController) => {
    if (!api.listPackageFileIndex) {
      setError('This Server does not provide the revision-fenced Package File index. Resource-page scanning is not used as a substitute.');
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const page = await api.listPackageFileIndex(packageId, {
        query: query.trim() || undefined,
        cursor: requestedCursor || undefined,
        limit: PAGE_SIZE,
      }, controller.signal);
      if (controller.signal.aborted || generation !== generationRef.current) return;
      setFiles(current => requestedCursor ? mergeFiles(current, page.items || []) : page.items || []);
      setCursor(page.nextCursor || null);
      setTotal(page.total || 0);
      setCoverage(page.coverage || null);
      setPackageRevision(page.packageRevision || '');
      setCanEdit(page.canEdit === true);
    } catch (reason) {
      if (!controller.signal.aborted && generation === generationRef.current) setError(errorMessage(reason));
    } finally {
      if (!controller.signal.aborted && generation === generationRef.current) setLoading(false);
    }
  }, [api, packageId, query]);

  useEffect(() => {
    const generation = ++generationRef.current;
    controllerRef.current?.abort();
    controllerRef.current = null;
    if (!active) { setLoading(false); return; }
    setFiles([]); setCursor(null); setTotal(0); setCoverage(null); setPackageRevision(''); setCanEdit(false);
    setError(''); setLoading(true);
    const controller = new AbortController();
    controllerRef.current = controller;
    const timer = window.setTimeout(() => void fetchPage(null, generation, controller), query.trim() ? 180 : 0);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [active, api, packageId, query, reloadToken, fetchPage]);

  const loadMore = () => {
    if (!active || loading || !cursor) return;
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    void fetchPage(cursor, generationRef.current, controller);
  };

  return <section className="att-package-file-catalog" aria-label="Package file catalog">
    <header><div><h2>File catalog</h2><p>Server-indexed Package files with revision-fenced search and inbound resource call sites.</p></div>
      <label>Filter files<input type="search" value={query} onChange={event => setQuery(event.currentTarget.value)} placeholder="Path, resource, or action" /></label>
    </header>
    <p role="status" aria-live="polite">{loading ? 'Loading Package file index…' : locale === 'zh-Hant'
      ? `${files.length}／${total} 個索引檔案 · ${canEdit ? '可編輯來源' : '唯讀存取'}`
      : `${files.length} of ${total} indexed files · ${canEdit ? 'source editing available' : 'read-only access'}`}</p>
    {coverage && <p className="att-file-catalog-coverage" role={coverage.complete ? 'status' : 'alert'}>
      {locale === 'zh-Hant'
        ? `涵蓋範圍：${coverage.state === 'COMPLETE' ? '完整' : coverage.state === 'PARTIAL' ? '部分' : coverage.state}。已索引 ${coverage.resourcesScanned} 個 Template／Flow 來源。`
          + (!coverage.complete ? ` ${coverage.resourcesIncomplete} 個來源無法完整索引；搜尋結果可能缺少參照。` : '')
          + (coverage.referenceSetsTruncated > 0 ? ` ${coverage.referenceSetsTruncated} 組參照達到 Server 安全上限。` : '')
          + (coverage.filesTruncated ? ' Server 檔案索引已達最大容量，可能省略其他路徑。' : '')
        : <>Coverage: {coverage.state}. {coverage.resourcesScanned} Template/Flow sources indexed.
          {!coverage.complete && ` ${coverage.resourcesIncomplete} source${coverage.resourcesIncomplete === 1 ? '' : 's'} could not be fully indexed; search results may omit references.`}
          {coverage.referenceSetsTruncated > 0 && ` ${coverage.referenceSetsTruncated} reference set${coverage.referenceSetsTruncated === 1 ? '' : 's'} reached the Server safety limit.`}
          {coverage.filesTruncated && ' The Server file index reached its maximum size; additional paths may be omitted.'}</>}
    </p>}
    {error && <p role="alert" className="att-error">{error}<button type="button" onClick={() => setReloadToken(value => value + 1)}>Reload index</button></p>}
    {!loading && !error && files.length === 0 && <p className="att-file-catalog-empty">{total
      ? locale === 'zh-Hant' ? '沒有符合此篩選條件的索引檔案。' : 'No indexed files match this filter.'
      : locale === 'zh-Hant' ? '找不到被參照的 Package 檔案。' : 'No referenced Package files were found.'}</p>}
    {files.length > 0 && <ul className="att-package-file-list">{files.map(file => {
      const firstAvailable = file.uses.find(use => use.available && use.referenceId);
      const nativeTestCase = /(?:^|\/)testcase\.ya?ml$/i.test(file.path);
      return <li key={file.path}><div><code>{file.path}</code><small>{file.uses.length} referencing resource{file.uses.length === 1 ? '' : 's'}</small>
        <ul>{file.uses.map(use => <li key={`${use.type}:${use.resourceId}`}>
          {onOpenResource ? <button type="button" className="att-file-catalog-reference" onClick={() => {
            const callSite = use.callSites.find(site => site.actionId);
            onOpenResource({ type: use.type, resourceId: use.resourceId }, callSite?.actionId);
          }}>{useLabel(use)}</button> : <span>{useLabel(use)}</span>}
          {!use.available && <span> · {use.reason || 'file content unavailable'}</span>}
        </li>)}</ul></div>
        {nativeTestCase && onOpenNativeTestCase
          ? <button type="button" onClick={() => onOpenNativeTestCase(file.path)}>{locale === 'zh-Hant' ? '開啟 Native Test Case' : 'Open Native Test Case'}</button>
          : firstAvailable ? <button type="button" onClick={() => onOpenFile(file.path, firstAvailable.originPath,
          { type: firstAvailable.type, resourceId: firstAvailable.resourceId, referenceId: firstAvailable.referenceId! })}>Open file</button>
          : <span role="status">{file.reason || 'Unavailable'}</span>}
      </li>;
    })}</ul>}
    {cursor && <button type="button" onClick={loadMore} disabled={loading}>{loading ? 'Loading…' : 'Load more files'}</button>}
    {packageRevision && <details className="att-file-index-provenance"><summary>Index provenance</summary><p>All pages are bound to one Package revision.</p><code>{packageRevision}</code></details>}
  </section>;
}
