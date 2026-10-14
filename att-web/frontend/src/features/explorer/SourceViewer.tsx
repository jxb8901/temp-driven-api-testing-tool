import { useEffect, useState } from 'react';
import type { ExplorerApi, ResourceIdentity, ResourceSourceReference, SourceProjection } from './types';
import { CodeEditor, detectCodeLanguage } from '../code/CodeEditor';

const MAX_SOURCE_CHARACTERS = 200_000;
const MAX_SOURCE_LINES = 12_000;

interface SourceViewerProps {
  api: ExplorerApi;
  identity: ResourceIdentity;
  available: boolean;
  active: boolean;
  onFileReference?: (path: string, origin?: string, reference?: ResourceSourceReference) => void;
}

function friendlyError(error: unknown): string {
  const status = typeof error === 'object' && error !== null && 'status' in error ? Number((error as { status?: unknown }).status) : 0;
  if (status === 401) return 'Your session has expired. Sign in again, then refresh safe source.';
  if (status === 403) return 'You do not have access to this source projection.';
  if (status === 404) return 'This resource or its source projection is no longer available. Refresh the resource list.';
  if (status === 500) return 'The Server could not project this source. Retry or refresh the resource list.';
  if (status) return `Safe source could not be loaded (HTTP ${status}). Retry.`;
  return 'Safe source could not be loaded. Check the connection and retry.';
}

export function SourceViewer({ api, identity, available, active, onFileReference }: SourceViewerProps) {
  const [projection, setProjection] = useState<SourceProjection | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [refreshKey, setRefreshKey] = useState(0);
  const key = `${identity.packageId}:${identity.type}:${identity.resourceId}`;

  useEffect(() => { setProjection(null); setError(''); }, [key]);
  useEffect(() => {
    if (!active || !available) return;
    const controller = new AbortController();
    setLoading(true); setError('');
    api.getSafeSource(identity, controller.signal).then(data => { setProjection(data); setError(''); })
      .catch(reason => { if (!controller.signal.aborted) setError(friendlyError(reason)); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [active, available, identity.packageId, identity.type, identity.resourceId, refreshKey, api]);

  const rawText = projection?.available ? String(projection.text ?? '') : '';
  const characterCapped = rawText.length > MAX_SOURCE_CHARACTERS;
  const byCharacters = rawText.slice(0, MAX_SOURCE_CHARACTERS);
  const sourceLines = byCharacters.split('\n');
  const lineCapped = sourceLines.length > MAX_SOURCE_LINES;
  const shown = sourceLines.slice(0, MAX_SOURCE_LINES).join('\n');
  const capped = characterCapped || lineCapped;

  return <section className="att-explorer-source" aria-label="Safe source projection">
    <div className="att-source-toolbar">
      <span>{projection?.format ? `${projection.format.toUpperCase()} source` : 'Safe source'}</span>
      <button type="button" onClick={() => setRefreshKey(value => value + 1)} disabled={!available || loading}>{loading ? 'Refreshing…' : projection ? 'Refresh source' : 'Load source'}</button>
    </div>
    <p className="att-source-status" role="status" aria-live="polite">
      {loading ? 'Loading safe source projection…'
        : error || (projection && !projection.available ? `Source unavailable (${projection.reason || 'policy'}).`
          : projection?.available && rawText.length === 0 ? 'Safe source is empty.'
            : capped ? `Source exceeds the viewer limit. Showing ${sourceLines.slice(0, MAX_SOURCE_LINES).length.toLocaleString()} lines within ${MAX_SOURCE_CHARACTERS.toLocaleString()} characters.`
              : projection?.available ? `${sourceLines.length.toLocaleString()} lines` : !available ? 'Source is not available for this resource.' : 'Choose Source to load the safe projection.')}
    </p>
    {error && <button type="button" onClick={() => setRefreshKey(value => value + 1)} disabled={loading}>Retry source</button>}
    {projection?.available && rawText.length > 0 && <CodeEditor
      value={shown}
      language={detectCodeLanguage(projection.logicalPath || identity.resourceId || projection.format || '')}
      label="Safe source; line numbers shown"
      readOnly
      fileReferences={projection.fileReferences || []}
      onFileReference={(_path, origin, referenceId) => {
        const reference = projection.fileReferences?.find(item => item.referenceId === referenceId && item.available !== false);
        if (!reference) return;
        const sourceOrigin = projection.logicalPath || origin;
        if (identity.type === 'template' || identity.type === 'flow')
          onFileReference?.(reference.logicalPath, sourceOrigin, { type: identity.type, resourceId: identity.resourceId, referenceId: reference.referenceId });
        else onFileReference?.(reference.logicalPath, sourceOrigin);
      }}
      sourcePath={projection.logicalPath}
      maxCharacters={MAX_SOURCE_CHARACTERS}
    />}
  </section>;
}

export const sourceViewerLimits = Object.freeze({ maxCharacters: MAX_SOURCE_CHARACTERS, maxLines: MAX_SOURCE_LINES });
