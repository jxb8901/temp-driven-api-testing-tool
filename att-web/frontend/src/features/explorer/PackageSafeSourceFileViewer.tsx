import { useEffect, useState } from 'react';
import { resourceSourceFilePath } from '../../api';
import { inspectionCache } from '../../inspectionCache';
import { useInspectionRefresh } from '../../useInspectionRefresh';
import { CodeEditor, detectCodeLanguage } from '../code/CodeEditor';
import type { ExplorerApi, ResourceIdentity, ResourceSourceReference, SafeSourceFileReference, SourceProjection } from './types';
import { sourceViewerLimits } from './SourceViewer';
import './explorer.css';

function safeError(error: unknown): string {
  const status = typeof error === 'object' && error !== null && 'status' in error ? Number((error as { status?: unknown }).status) : 0;
  if (status === 401) return 'Your session has expired. Sign in again, then retry safe source.';
  if (status === 403) return 'You do not have access to this safe Package file projection.';
  if (status === 404) return 'This Package resource or referenced file is no longer available. Return to the calling source and refresh it.';
  if (status === 413) return 'The Server declined this projection because it exceeds the configured safety limit.';
  if (status === 429 || status === 503 || status === 504) return 'This inspection request is temporarily unavailable. Retry shortly.';
  if (status) return `Safe Package file could not be loaded (HTTP ${status}). Retry.`;
  return 'Safe Package file could not be loaded. Check the connection and retry.';
}

export function PackageSafeSourceFileViewer({ api, packageId, filePath, fileOrigin, sourceReference, onBack, onOpenFile }: {
  api: ExplorerApi; packageId: string; filePath?: string; fileOrigin?: string; sourceReference?: ResourceSourceReference;
  onBack(): void; onOpenFile(path: string, origin: string | undefined, sourceReference: ResourceSourceReference): void;
}) {
  const [projection, setProjection] = useState<SourceProjection | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [refreshKey, setRefreshKey] = useState(0);
  const inspectionRefresh = useInspectionRefresh();
  const identity: ResourceIdentity | null = sourceReference
    ? { packageId, type: sourceReference.type, resourceId: sourceReference.resourceId }
    : null;

  useEffect(() => {
    setProjection(null); setError('');
    const reference = sourceReference;
    setLoading(false);
    if (!identity || !reference || !api.getResourceSourceFile || !/^[0-9a-f]{64}$/.test(reference.referenceId)) {
      setError('The safe Package file reference is invalid or unavailable. Return to the calling source and open a Server-verified reference.');
      return;
    }
    const controller = new AbortController(); setLoading(true);
    api.getResourceSourceFile(identity, reference.referenceId, controller.signal, fileOrigin)
      .then(result => { if (!controller.signal.aborted) setProjection(result); })
      .catch(reason => { if (!controller.signal.aborted) setError(safeError(reason)); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [api, packageId, sourceReference?.type, sourceReference?.resourceId, sourceReference?.referenceId, fileOrigin, refreshKey, inspectionRefresh]);

  const refresh = () => {
    if (identity && sourceReference) inspectionCache.invalidatePath(resourceSourceFilePath(identity, sourceReference.referenceId, fileOrigin));
    setLoading(true); setRefreshKey(value => value + 1);
  };
  const text = projection?.available ? String(projection.text ?? '') : '';
  const cappedText = text.slice(0, sourceViewerLimits.maxCharacters);
  const lines = cappedText.split('\n');
  const shown = lines.slice(0, sourceViewerLimits.maxLines).join('\n');
  const truncated = text.length > sourceViewerLimits.maxCharacters || lines.length > sourceViewerLimits.maxLines;
  const openReference = (reference: SafeSourceFileReference) => {
    if (!identity || reference.available === false) return;
    onOpenFile(reference.logicalPath, projection?.logicalPath || filePath, {
      type: identity.type, resourceId: identity.resourceId, referenceId: reference.referenceId,
    });
  };

  return <main className="page-content att-package-source-file">
    <button type="button" className="button button-quiet" onClick={onBack}>Back to calling source</button>
    <header><p className="eyebrow">Packages / {packageId} / Explorer / Safe source</p>
      <h1>{projection?.logicalPath || filePath || 'Referenced Package file'}</h1>
      <p>Read-only safe Package-relative source projection.</p></header>
    <div className="att-source-toolbar"><span>{projection?.format ? `${projection.format.toUpperCase()} source` : 'Safe source'}</span>
      <button type="button" onClick={refresh} disabled={!sourceReference || loading}>{loading ? 'Refreshing…' : projection ? 'Refresh source' : 'Load source'}</button>
    </div>
    <p className="att-source-status" role="status" aria-live="polite">
      {loading ? 'Loading safe Package file projection…' : error || (projection && !projection.available
        ? `Source unavailable (${projection.reason || 'Server safe-source policy'}).`
        : projection?.available && text.length === 0 ? 'Safe source is empty.'
          : truncated ? `Source exceeds the viewer limit. Showing at most ${sourceViewerLimits.maxLines.toLocaleString()} lines within ${sourceViewerLimits.maxCharacters.toLocaleString()} characters.`
            : projection?.available ? `${lines.length.toLocaleString()} lines · ${text.length.toLocaleString()} characters · safe redaction applied by the Server.`
              : 'Choose Load source to request a Server-verified projection.')}
    </p>
    {error && <button type="button" onClick={refresh} disabled={loading}>Retry source</button>}
    {projection?.available && text.length > 0 && <CodeEditor value={shown}
      language={detectCodeLanguage(projection.logicalPath || filePath || projection.format || '')}
      label={`Safe source ${projection.logicalPath || filePath || ''}`} readOnly sourcePath={projection.logicalPath || filePath}
      fileReferences={projection.fileReferences || []}
      onFileReference={(_path, _origin, referenceId) => {
        const reference = projection.fileReferences?.find(item => item.referenceId === referenceId);
        if (reference) openReference(reference);
      }} maxCharacters={sourceViewerLimits.maxCharacters} />}
    {projection && ((projection.fileReferences?.length || 0) > 0 || projection.fileReferencesUnavailable) ? <section className="att-source-file-references" aria-label="Package files referenced in source">
      <h2>Package files referenced in source</h2>
      {projection.fileReferencesUnavailable && <p role="status">Some file references could not be verified because they are missing, invalid, or blocked by the Server safe-source policy. Their literal expressions remain visible in the source; no file contents were loaded.</p>}
      {projection.fileReferences?.length ? <ul>{projection.fileReferences.map(reference => <li key={reference.referenceId}>
        <code>&amp;{'{'}{reference.logicalPath}{'}'}</code>
        {reference.available === false ? <span role="status">Unavailable under the Server safe-source policy</span>
          : <button type="button" onClick={() => openReference(reference)}>View referenced file</button>}
      </li>)}</ul> : null}
      {projection.fileReferencesTruncated && <p role="status">Additional references were omitted by the Server safety limit.</p>}
    </section> : null}
  </main>;
}
