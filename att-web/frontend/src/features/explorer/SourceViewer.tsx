import { useEffect, useState } from 'react';
import { resourceSourcePath } from '../../api';
import { inspectionCache } from '../../inspectionCache';
import { useInspectionRefresh } from '../../useInspectionRefresh';
import type { ExplorerApi, ResourceIdentity, ResourceSourceReference, ResourceSummary, SafeSourceFileReference, SourceProjection } from './types';
import type { UiLocale } from '../../uiLocale';
import { CodeEditor, detectCodeLanguage } from '../code/CodeEditor';

const MAX_SOURCE_CHARACTERS = 200_000;
const MAX_SOURCE_LINES = 12_000;

interface SourceViewerProps {
  api: ExplorerApi;
  identity: ResourceIdentity;
  available: boolean;
  active: boolean;
  resource?: ResourceSummary | null;
  onFileReference?: (path: string, origin?: string, reference?: ResourceSourceReference) => void;
  locale?: UiLocale;
}

function text(locale: UiLocale | undefined, en: string, zh: string): string { return locale === 'zh-Hant' ? zh : en; }

function lineCount(value: string): number {
  let count = 1;
  for (let index = 0; index < value.length; index++) {
    if (value[index] === '\r') {
      count++;
      if (value[index + 1] === '\n') index++;
    } else if (value[index] === '\n') count++;
  }
  return count;
}

/** Caps visible lines without normalizing the authored LF or CRLF separators. */
function capLines(value: string, maxLines: number): string {
  let count = 1;
  for (let index = 0; index < value.length; index++) {
    if (value[index] === '\r') {
      if (count === maxLines) return value.slice(0, index);
      count++;
      if (value[index + 1] === '\n') index++;
    } else if (value[index] === '\n') {
      if (count === maxLines) return value.slice(0, index);
      count++;
    }
  }
  return value;
}

function unavailableToolSource(reason?: string | null, locale?: UiLocale): string {
  if (reason === 'no-standalone-source') return text(locale, 'No standalone source file is defined for this Tool.', '此 Tool 未定義獨立來源檔案。');
  if (['source-disabled', 'visibility-policy', 'not-allowlisted', 'sensitive-path', 'forbidden'].includes(reason || ''))
    return text(locale, `Source exists but access is denied by the inspection policy (${reason}).`, `來源存在，但檢查政策拒絕存取（${reason}）。`);
  if (reason === 'missing') return text(locale, 'The configured Tool source file is missing.', '找不到已設定的 Tool 來源檔案。');
  if (reason === 'binary-file' || reason === 'size-limit' || reason === 'unsupported-file-type')
    return text(locale, `The configured Tool source cannot be displayed (${reason}).`, `無法顯示已設定的 Tool 來源（${reason}）。`);
  return text(locale, `Tool source is unavailable (${reason || 'unknown reason'}).`, `Tool 來源無法使用（${reason || '原因不明'}）。`);
}

function friendlyError(error: unknown, locale?: UiLocale): string {
  const status = typeof error === 'object' && error !== null && 'status' in error ? Number((error as { status?: unknown }).status) : 0;
  if (status === 401) return text(locale, 'Your session has expired. Sign in again, then refresh safe source.', '工作階段已逾期。請重新登入後再重新整理安全來源。');
  if (status === 403) return text(locale, 'You do not have access to this source projection.', '您沒有權限存取此來源投影。');
  if (status === 404) return text(locale, 'This resource or its source projection is no longer available. Refresh the resource list.', '此資源或來源投影已無法使用。請重新整理資源清單。');
  if (status === 500) return text(locale, 'The Server could not project this source. Retry or refresh the resource list.', 'Server 無法產生此來源投影。請重試或重新整理資源清單。');
  if (status) return text(locale, `Safe source could not be loaded (HTTP ${status}). Retry.`, `無法載入安全來源（HTTP ${status}）。請重試。`);
  return text(locale, 'Safe source could not be loaded. Check the connection and retry.', '無法載入安全來源。請檢查連線後重試。');
}

export function SourceViewer({ api, identity, available, active, resource, onFileReference, locale }: SourceViewerProps) {
  const [projection, setProjection] = useState<SourceProjection | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  const [pathCopied, setPathCopied] = useState(false);
  const inspectionRefresh = useInspectionRefresh();
  const key = `${identity.packageId}:${identity.type}:${identity.resourceId}`;

  useEffect(() => { setProjection(null); setError(''); }, [key]);
  useEffect(() => {
    if (!active || !available) return;
    const controller = new AbortController();
    setLoading(true); setError('');
    api.getSafeSource(identity, controller.signal).then(data => { if (!controller.signal.aborted) { setProjection(data); setError(''); } })
      .catch(reason => { if (!controller.signal.aborted) { setProjection(null); setError(reason); } })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [active, available, identity.packageId, identity.type, identity.resourceId, refreshKey, api, inspectionRefresh]);

  const refreshSource = () => {
    inspectionCache.invalidatePath(resourceSourcePath(identity));
    setLoading(true);
    setRefreshKey(value => value + 1);
  };

  const rawText = projection?.available ? String(projection.text ?? '') : '';
  const characterCapped = rawText.length > MAX_SOURCE_CHARACTERS;
  const byCharacters = rawText.slice(0, MAX_SOURCE_CHARACTERS);
  const lineCapped = lineCount(byCharacters) > MAX_SOURCE_LINES;
  const shown = lineCapped ? capLines(byCharacters, MAX_SOURCE_LINES) : byCharacters;
  const shownLineCount = lineCount(shown);
  const capped = characterCapped || lineCapped;
  const projectionKind = projection?.projectionKind || (projection?.redacted ? 'sanitized' : undefined);
  const sourceIndicator = projectionKind === 'original' ? text(locale, 'Original', '原始來源')
    : projectionKind === 'sanitized' ? text(locale, 'Sanitized projection', '已遮罩投影') : text(locale, 'Safe projection', '安全投影');
  const sourcePath = projection?.available ? projection.logicalPath : undefined;
  const toolbarMetadata = projection?.available ? [
    projection.format?.toUpperCase(),
    `${shownLineCount.toLocaleString()} ${text(locale, 'lines', '行')}`,
    projectionKind ? sourceIndicator : undefined,
  ].filter(Boolean).join(' · ') : '';
  const toolProvenance = resource?.provenance || {};
  const groupManifestPath = typeof toolProvenance.toolGroupManifest === 'string' ? toolProvenance.toolGroupManifest : undefined;
  const groupManifestReference = groupManifestPath
    ? projection?.fileReferences?.find(reference => reference.logicalPath === groupManifestPath) : undefined;
  const openReference = (reference: SafeSourceFileReference, origin?: string) => {
    if (reference.available === false) return;
    const sourceOrigin = projection?.logicalPath || origin;
    if (identity.type === 'tool') {
      onFileReference?.(reference.logicalPath, sourceOrigin, {
        type: identity.type, resourceId: identity.resourceId, referenceId: reference.referenceId, returnTab: 'source',
      });
      return;
    }
    if (identity.type === 'template' || identity.type === 'flow')
      onFileReference?.(reference.logicalPath, sourceOrigin, { type: identity.type, resourceId: identity.resourceId, referenceId: reference.referenceId, returnTab: 'source' });
    else onFileReference?.(reference.logicalPath, sourceOrigin);
  };
  const copyPath = async () => {
    if (!sourcePath || !navigator.clipboard?.writeText) return;
    try {
      await navigator.clipboard.writeText(sourcePath);
      setPathCopied(true);
      window.setTimeout(() => setPathCopied(false), 2000);
    } catch { setPathCopied(false); }
  };
  const errorMessage = error ? friendlyError(error, locale) : '';
  const statusMessage = loading ? text(locale, 'Loading safe source projection…', '正在載入安全來源投影…')
    : errorMessage || (projection && !projection.available ? identity.type === 'tool'
      ? unavailableToolSource(projection.reason || resource?.sourceReason, locale)
      : text(locale, `Source unavailable (${projection.reason || 'policy'}).`, `來源無法使用（${projection.reason || '政策限制'}）。`)
      : projection?.available && rawText.length === 0 ? `${sourceIndicator}${text(locale, ' is empty.', '目前沒有內容。')}`
        : capped ? text(locale, `Source exceeds the viewer limit. Showing ${shownLineCount.toLocaleString()} lines within ${MAX_SOURCE_CHARACTERS.toLocaleString()} characters.`,
          `來源超過檢視器上限，目前顯示 ${shownLineCount.toLocaleString()} 行，最多 ${MAX_SOURCE_CHARACTERS.toLocaleString()} 個字元。`)
          : !available ? identity.type === 'tool' ? unavailableToolSource(resource?.sourceReason, locale)
            : text(locale, 'Source is not available for this resource.', '此資源沒有可用來源。')
            : !projection ? text(locale, 'Choose Source to load the safe projection.', '選擇「來源」以載入安全投影。') : '');

  return <section className="att-explorer-source" aria-label={text(locale, 'Resource source', '資源來源')}>
    <div className="att-source-toolbar att-source-toolbar-primary">
      {sourcePath && <div className="att-source-path"><code title={sourcePath}>{sourcePath}</code>
        <button type="button" onClick={() => void copyPath()} disabled={!navigator.clipboard?.writeText}>
          {pathCopied ? text(locale, 'Copied path', '已複製路徑') : text(locale, 'Copy path', '複製路徑')}
        </button>
      </div>}
      {toolbarMetadata && <span className="att-source-toolbar-metadata">{toolbarMetadata}</span>}
      {identity.type === 'tool' && groupManifestReference && groupManifestReference.available !== false && <button className="att-source-related-file"
        type="button" aria-label={text(locale, `Open Group manifest source ${groupManifestPath}`, `開啟群組資訊清單來源 ${groupManifestPath}`)}
        onClick={() => openReference(groupManifestReference, sourcePath)}>{text(locale, 'Group manifest', '群組資訊清單')} <code>{groupManifestPath}</code></button>}
      <button type="button" onClick={refreshSource} disabled={!available || loading}>
        {loading ? text(locale, 'Refreshing…', '正在重新整理…') : projection ? text(locale, 'Refresh source', '重新整理來源') : text(locale, 'Load source', '載入來源')}
      </button>
    </div>
    {statusMessage && <p className="att-source-status" role="status" aria-live="polite">{statusMessage}</p>}
    {projection?.available && projectionKind === 'sanitized' && <p className="att-source-sanitized-note" role="note">
      {text(locale, 'This projection was reformatted to apply safety masking. It is not byte-exact source and cannot be used as an authoritative edit or publish draft.',
        '此投影已重新格式化並套用安全遮罩，並非逐位元組相同的來源，也不能作為權威編輯或發佈草稿。')}
    </p>}
    {projection?.available && rawText.length > 0 && <CodeEditor
      value={shown}
      language={detectCodeLanguage(projection.logicalPath || identity.resourceId || projection.format || '')}
      label={text(locale, `Source code; ${sourceIndicator.toLowerCase()}, line numbers shown`, `來源程式碼；${sourceIndicator}，顯示行號`)}
      readOnly
      fileReferences={projection.fileReferences || []}
      onFileReference={(_path, origin, referenceId) => {
        const reference = projection.fileReferences?.find(item => item.referenceId === referenceId && item.available !== false);
        if (!reference) return;
        openReference(reference, origin);
      }}
      sourcePath={projection.logicalPath}
      maxCharacters={MAX_SOURCE_CHARACTERS}
    />}
    {identity.type === 'tool' && <details className="att-source-details">
      <summary>{text(locale, 'Source details', '來源詳細資料')}</summary>
      <dl>
        {Boolean(resource?.sourceKind || toolProvenance.toolSourceKind) && <div><dt>{text(locale, 'Source kind', '來源類型')}</dt><dd>{String(resource?.sourceKind || toolProvenance.toolSourceKind)}</dd></div>}
        {typeof toolProvenance.toolGroupId === 'string' && <div><dt>{text(locale, 'Tool Group', 'Tool 群組')}</dt><dd>{toolProvenance.toolGroupId}</dd></div>}
        {typeof toolProvenance.toolConfigLocation === 'string' && <div><dt>{text(locale, 'Tool config', 'Tool 設定')}</dt><dd><code>{toolProvenance.toolConfigLocation}</code></dd></div>}
        {groupManifestPath && <div><dt>{text(locale, 'Group manifest', '群組資訊清單')}</dt><dd><code>{groupManifestPath}</code></dd></div>}
        {resource?.sourceKind === 'package-tool-descriptor' && <div><dt>{text(locale, 'Ownership', '擁有者')}</dt><dd>{text(locale, 'Package-owned att-tool/v1 descriptor; shared Helpers remain centrally configured.', 'Package 所有的 att-tool/v1 描述檔；共用 Helper 仍由中央設定。')}</dd></div>}
        {resource?.sourceKind === 'built-in-virtual' && <div><dt>{text(locale, 'Availability', '可用性')}</dt><dd>{text(locale, 'Built-in or virtual Tool; no standalone source file exists.', '內建或虛擬 Tool，沒有獨立來源檔案。')}</dd></div>}
      </dl>
      {groupManifestPath && !groupManifestReference && <p role="status">{projection?.fileReferencesUnavailable
        ? text(locale, 'Group manifest access is unavailable under the Package source policy.', '套件來源政策未允許存取此群組資訊清單。')
        : text(locale, 'Load the Tool source to check whether the Group manifest is available.', '載入 Tool 來源以檢查群組資訊清單是否可用。')}</p>}
    </details>}
    {projection && ((projection.fileReferences?.length || 0) > 0 || projection.fileReferencesUnavailable) ? <section className="att-source-file-references"
      aria-label={text(locale, 'Package files referenced in source', '來源中參照的套件檔案')}>
      <h3>{text(locale, 'Package files referenced in source', '來源中參照的套件檔案')}</h3>
      {projection.fileReferencesUnavailable && <p role="status">{text(locale, 'Some file references could not be verified because they are missing, invalid, or blocked by the Server safe-source policy. Their literal expressions remain visible in the source; no file contents were loaded.', '部分檔案參照因檔案不存在、無效或遭 Server 安全來源政策阻擋而無法驗證。來源仍顯示字面運算式，未載入任何檔案內容。')}</p>}
      {projection.fileReferences?.length ? <ul>{projection.fileReferences.map(reference => <li key={reference.referenceId}>
        <code>&amp;{'{'}{reference.logicalPath}{'}'}</code>
        {reference.available === false
          ? <span role="status">{text(locale, 'Unavailable under the Server safe-source policy', 'Server 安全來源政策不允許存取')}</span>
          : <button type="button" onClick={() => openReference(reference)}>{text(locale, 'View referenced file', '檢視參照檔案')}</button>}
      </li>)}</ul> : null}
      {projection.fileReferencesTruncated && <p role="status">{text(locale, 'Additional references were omitted by the Server safety limit.', 'Server 安全限制已省略其他參照。')}</p>}
    </section> : null}
  </section>;
}

export const sourceViewerLimits = Object.freeze({ maxCharacters: MAX_SOURCE_CHARACTERS, maxLines: MAX_SOURCE_LINES });
