import { useEffect, useState } from 'react';
import { resourceSourceFilePath } from '../../api';
import { inspectionCache } from '../../inspectionCache';
import { useInspectionRefresh } from '../../useInspectionRefresh';
import { CodeEditor, detectCodeLanguage } from '../code/CodeEditor';
import type { ExplorerApi, ResourceIdentity, ResourceSourceReference, SafeSourceFileReference, SourceProjection } from './types';
import type { UiLocale } from '../../uiLocale';
import { sourceViewerLimits } from './SourceViewer';
import './explorer.css';

function text(locale: UiLocale | undefined, en: string, zh: string): string { return locale === 'zh-Hant' ? zh : en; }

function safeError(error: unknown, locale?: UiLocale): string {
  const status = typeof error === 'object' && error !== null && 'status' in error ? Number((error as { status?: unknown }).status) : 0;
  if (status === 401) return text(locale, 'Your session has expired. Sign in again, then retry safe source.', '工作階段已逾期。請重新登入後再重試安全來源。');
  if (status === 403) return text(locale, 'You do not have access to this safe Package file projection.', '您沒有權限存取此套件安全檔案投影。');
  if (status === 404) return text(locale, 'This Package resource or referenced file is no longer available. Return to the calling source and refresh it.', '此套件資源或參照檔案已無法使用。請返回呼叫來源並重新整理。');
  if (status === 413) return text(locale, 'The Server declined this projection because it exceeds the configured safety limit.', 'Server 因投影超過安全限制而拒絕要求。');
  if (status === 429 || status === 503 || status === 504) return text(locale, 'This inspection request is temporarily unavailable. Retry shortly.', '檢查服務暫時無法使用，請稍後重試。');
  if (status) return text(locale, `Safe Package file could not be loaded (HTTP ${status}). Retry.`, `無法載入套件安全檔案（HTTP ${status}）。請重試。`);
  return text(locale, 'Safe Package file could not be loaded. Check the connection and retry.', '無法載入套件安全檔案。請檢查連線後重試。');
}

type SourceFileError = 'missing-reference' | 'unsupported-client' | { kind: 'request'; reason: unknown };

export function PackageSafeSourceFileViewer({ api, packageId, filePath, fileOrigin, sourceReference, onBack, onOpenFile, locale }: {
  api: ExplorerApi; packageId: string; filePath?: string; fileOrigin?: string; sourceReference?: ResourceSourceReference;
  onBack(): void; onOpenFile(path: string, origin: string | undefined, sourceReference: ResourceSourceReference): void;
  locale?: UiLocale;
}) {
  const [projection, setProjection] = useState<SourceProjection | null>(null);
  const [error, setError] = useState<SourceFileError | null>(null);
  const [loading, setLoading] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);
  const [pathCopied, setPathCopied] = useState(false);
  const inspectionRefresh = useInspectionRefresh();
  const identity: ResourceIdentity | null = sourceReference
    ? { packageId, type: sourceReference.type, resourceId: sourceReference.resourceId }
    : null;
  const validReference = Boolean(sourceReference && /^[0-9a-f]{64}$/.test(sourceReference.referenceId));
  const canRequestSource = Boolean(identity && validReference && api.getResourceSourceFile);

  useEffect(() => {
    setProjection(null); setError(null);
    const reference = sourceReference;
    setLoading(false);
    if (!reference || !/^[0-9a-f]{64}$/.test(reference.referenceId)) {
      setError('missing-reference');
      return;
    }
    if (!identity || !api.getResourceSourceFile) {
      setError('unsupported-client');
      return;
    }
    const controller = new AbortController(); setLoading(true);
    api.getResourceSourceFile(identity, reference.referenceId, controller.signal, fileOrigin)
      .then(result => { if (!controller.signal.aborted) setProjection(result); })
      .catch(reason => { if (!controller.signal.aborted) setError({ kind: 'request', reason }); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [api, packageId, sourceReference?.type, sourceReference?.resourceId, sourceReference?.referenceId, fileOrigin, refreshKey, inspectionRefresh]);

  const refresh = () => {
    if (!canRequestSource || !identity || !sourceReference) return;
    if (identity && sourceReference) inspectionCache.invalidatePath(resourceSourceFilePath(identity, sourceReference.referenceId, fileOrigin));
    setLoading(true); setRefreshKey(value => value + 1);
  };
  const sourceText = projection?.available ? String(projection.text ?? '') : '';
  const cappedText = sourceText.slice(0, sourceViewerLimits.maxCharacters);
  const lines = cappedText.split('\n');
  const shown = lines.slice(0, sourceViewerLimits.maxLines).join('\n');
  const truncated = sourceText.length > sourceViewerLimits.maxCharacters || lines.length > sourceViewerLimits.maxLines;
  const path = projection?.logicalPath || filePath;
  const projectionKind = projection?.projectionKind || (projection?.redacted ? 'sanitized' : undefined);
  const authenticity = projectionKind === 'original' ? text(locale, 'Original', '原始來源')
    : projectionKind === 'sanitized' ? text(locale, 'Sanitized', '已遮罩') : undefined;
  const toolbarMetadata = projection?.available ? [projection.format?.toUpperCase(), `${lines.length.toLocaleString()} ${text(locale, 'lines', '行')}`, authenticity]
    .filter(Boolean).join(' · ') : '';
  const copyPath = async () => {
    if (!path || !navigator.clipboard?.writeText) return;
    try { await navigator.clipboard.writeText(path); setPathCopied(true); window.setTimeout(() => setPathCopied(false), 2000); }
    catch { setPathCopied(false); }
  };
  const errorMessage = error === 'missing-reference'
    ? text(locale, 'This link is missing a valid Server-issued source reference. Return to the calling resource and open the related file again; Retry cannot recreate a missing reference.',
      '此連結缺少有效的 Server 簽發來源參照。請返回呼叫資源並重新開啟相關檔案；重試無法重新建立遺失的參照。')
    : error === 'unsupported-client'
      ? text(locale, 'This Server client cannot open safe Package-file references. Return to the calling resource.',
        '此 Server 用戶端無法開啟套件安全檔案參照。請返回呼叫資源。')
      : error ? safeError(error.reason, locale) : '';
  const statusMessage = loading ? text(locale, 'Loading safe Package file…', '正在載入套件安全檔案…')
    : errorMessage || (projection && !projection.available ? text(locale, `Source unavailable (${projection.reason || 'Server safe-source policy'}).`, `來源無法使用（${projection.reason || 'Server 安全來源政策'}）。`)
      : projection?.available && sourceText.length === 0 ? text(locale, 'Safe source is empty.', '安全來源沒有內容。')
        : truncated ? text(locale, `Source exceeds the viewer limit. Showing at most ${sourceViewerLimits.maxLines.toLocaleString()} lines within ${sourceViewerLimits.maxCharacters.toLocaleString()} characters.`,
          `來源超過檢視器上限，目前最多顯示 ${sourceViewerLimits.maxLines.toLocaleString()} 行及 ${sourceViewerLimits.maxCharacters.toLocaleString()} 個字元。`)
          : !projection ? text(locale, 'Choose Load source to request a Server-verified projection.', '選擇「載入來源」以要求 Server 驗證的投影。') : '');
  const openReference = (reference: SafeSourceFileReference) => {
    if (!identity || reference.available === false) return;
    onOpenFile(reference.logicalPath, projection?.logicalPath || filePath, {
      type: identity.type, resourceId: identity.resourceId, referenceId: reference.referenceId,
      returnTab: sourceReference?.returnTab || 'source',
      ...(sourceReference?.actionId ? { actionId: sourceReference.actionId } : {}),
    });
  };

  return <main className="page-content att-package-source-file">
    <button type="button" className="button button-quiet" onClick={onBack}>{text(locale, 'Back to calling source', '返回呼叫來源')}</button>
    <header><p className="eyebrow">{text(locale, `Packages / ${packageId} / Explorer / Safe source`, `套件 / ${packageId} / Explorer / 安全來源`)}</p>
      <h1>{text(locale, 'Safe Package file', '套件安全檔案')}</h1></header>
    <div className="att-source-toolbar att-source-toolbar-primary">
      {path && <div className="att-source-path"><code title={path}>{path}</code>
        <button type="button" onClick={() => void copyPath()} disabled={!navigator.clipboard?.writeText}>
          {pathCopied ? text(locale, 'Copied path', '已複製路徑') : text(locale, 'Copy path', '複製路徑')}
        </button>
      </div>}
      {toolbarMetadata && <span className="att-source-toolbar-metadata">{toolbarMetadata}</span>}
      <button type="button" onClick={refresh} disabled={!canRequestSource || loading}>
        {loading ? text(locale, 'Refreshing…', '正在重新整理…') : projection ? text(locale, 'Refresh source', '重新整理來源') : text(locale, 'Load source', '載入來源')}
      </button>
    </div>
    {statusMessage && <p className="att-source-status" role="status" aria-live="polite">{statusMessage}</p>}
    {projection?.available && projectionKind === 'sanitized' && <p className="att-source-sanitized-note" role="note">
      {text(locale, 'This projection was reformatted to apply safety masking. It is not byte-exact source and cannot be used as an authoritative edit or publish draft.',
        '此投影已重新格式化並套用安全遮罩，並非逐位元組相同的來源，也不能作為權威編輯或發佈草稿。')}
    </p>}
    {error && canRequestSource && <button type="button" onClick={refresh} disabled={loading}>{text(locale, 'Retry source', '重試來源')}</button>}
    {projection?.available && sourceText.length > 0 && <CodeEditor value={shown}
      language={detectCodeLanguage(projection.logicalPath || filePath || projection.format || '')}
      label={text(locale, `Safe source ${projection.logicalPath || filePath || ''}`, `安全來源 ${projection.logicalPath || filePath || ''}`)} readOnly sourcePath={projection.logicalPath || filePath}
      fileReferences={projection.fileReferences || []}
      onFileReference={(_path, _origin, referenceId) => {
        const reference = projection.fileReferences?.find(item => item.referenceId === referenceId);
        if (reference) openReference(reference);
      }} maxCharacters={sourceViewerLimits.maxCharacters} />}
    {fileOrigin && <details className="att-source-details"><summary>{text(locale, 'Source details', '來源詳細資料')}</summary>
      <dl><div><dt>{text(locale, 'Referenced from', '參照來源')}</dt><dd><code>{fileOrigin}</code></dd></div>
        <div><dt>{text(locale, 'Projection', '投影')}</dt><dd>{text(locale, 'Server-authorized, read-only Package file', '由 Server 授權的唯讀套件檔案')}</dd></div></dl>
    </details>}
    {projection && ((projection.fileReferences?.length || 0) > 0 || projection.fileReferencesUnavailable) ? <section className="att-source-file-references" aria-label={text(locale, 'Package files referenced in source', '來源中參照的套件檔案')}>
      <h2>{text(locale, 'Package files referenced in source', '來源中參照的套件檔案')}</h2>
      {projection.fileReferencesUnavailable && <p role="status">{text(locale, 'Some file references could not be verified because they are missing, invalid, or blocked by the Server safe-source policy. Their literal expressions remain visible in the source; no file contents were loaded.', '部分檔案參照因檔案不存在、無效或遭 Server 安全來源政策阻擋而無法驗證。來源仍顯示字面運算式，未載入任何檔案內容。')}</p>}
      {projection.fileReferences?.length ? <ul>{projection.fileReferences.map(reference => <li key={reference.referenceId}>
        <code>&amp;{'{'}{reference.logicalPath}{'}'}</code>
        {reference.available === false ? <span role="status">{text(locale, 'Unavailable under the Server safe-source policy', 'Server 安全來源政策不允許存取')}</span>
          : <button type="button" onClick={() => openReference(reference)}>{text(locale, 'View referenced file', '檢視參照檔案')}</button>}
      </li>)}</ul> : null}
      {projection.fileReferencesTruncated && <p role="status">{text(locale, 'Additional references were omitted by the Server safety limit.', 'Server 安全限制已省略其他參照。')}</p>}
    </section> : null}
  </main>;
}
