import { useEffect, useState } from 'react';
import { resourceSourcePath } from '../../api';
import { inspectionCache } from '../../inspectionCache';
import { useInspectionRefresh } from '../../useInspectionRefresh';
import type { ExplorerApi, ResourceIdentity, ResourceSourceReference, ResourceSummary, SafeSourceFileReference, SourceProjection } from './types';
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
}

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

function unavailableToolSource(reason?: string | null): string {
  if (reason === 'no-standalone-source') return 'No standalone source file is defined for this Tool.';
  if (['source-disabled', 'visibility-policy', 'not-allowlisted', 'sensitive-path', 'forbidden'].includes(reason || ''))
    return `Source exists but access is denied by the inspection policy (${reason}).`;
  if (reason === 'missing') return 'The configured Tool source file is missing.';
  if (reason === 'binary-file' || reason === 'size-limit' || reason === 'unsupported-file-type')
    return `The configured Tool source cannot be displayed (${reason}).`;
  return `Tool source is unavailable (${reason || 'unknown reason'}).`;
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

export function SourceViewer({ api, identity, available, active, resource, onFileReference }: SourceViewerProps) {
  const [projection, setProjection] = useState<SourceProjection | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [refreshKey, setRefreshKey] = useState(0);
  const inspectionRefresh = useInspectionRefresh();
  const key = `${identity.packageId}:${identity.type}:${identity.resourceId}`;

  useEffect(() => { setProjection(null); setError(''); }, [key]);
  useEffect(() => {
    if (!active || !available) return;
    const controller = new AbortController();
    setLoading(true); setError('');
    api.getSafeSource(identity, controller.signal).then(data => { if (!controller.signal.aborted) { setProjection(data); setError(''); } })
      .catch(reason => { if (!controller.signal.aborted) { setProjection(null); setError(friendlyError(reason)); } })
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
  const sourceIndicator = projectionKind === 'original' ? 'Original source'
    : projectionKind === 'sanitized' ? 'Sanitized projection' : 'Safe source';
  const toolProvenance = resource?.provenance || {};
  const openReference = (reference: SafeSourceFileReference, origin?: string) => {
    if (reference.available === false) return;
    const sourceOrigin = projection?.logicalPath || origin;
    if (identity.type === 'template' || identity.type === 'flow')
      onFileReference?.(reference.logicalPath, sourceOrigin, { type: identity.type, resourceId: identity.resourceId, referenceId: reference.referenceId, returnTab: 'source' });
    else onFileReference?.(reference.logicalPath, sourceOrigin);
  };

  return <section className="att-explorer-source" aria-label="Resource source">
    <div className="att-source-toolbar">
      <span>{projection?.available ? `${sourceIndicator}${projection.format ? ` · ${projection.format.toUpperCase()}` : ''}` : identity.type === 'tool' ? 'Tool source provenance' : 'Safe source'}</span>
      <button type="button" onClick={refreshSource} disabled={!available || loading}>{loading ? 'Refreshing…' : projection ? 'Refresh source' : 'Load source'}</button>
    </div>
    <p className="att-source-status" role="status" aria-live="polite">
      {loading ? 'Loading safe source projection…'
        : error || (projection && !projection.available ? identity.type === 'tool' ? unavailableToolSource(projection.reason || resource?.sourceReason)
          : `Source unavailable (${projection.reason || 'policy'}).`
          : projection?.available && rawText.length === 0 ? `${sourceIndicator} is empty.`
            : capped ? `Source exceeds the viewer limit. Showing ${shownLineCount.toLocaleString()} lines within ${MAX_SOURCE_CHARACTERS.toLocaleString()} characters.`
              : projection?.available ? `${shownLineCount.toLocaleString()} lines · ${sourceIndicator}`
                : identity.type === 'tool' ? unavailableToolSource(resource?.sourceReason)
                  : !available ? 'Source is not available for this resource.' : 'Choose Source to load the safe projection.')}
    </p>
    {projection?.available && projectionKind === 'sanitized' && <p className="att-source-sanitized-note">
      This projection was reformatted to apply safety masking. It is not byte-exact source and cannot be used as an authoritative edit or publish draft.
    </p>}
    {identity.type === 'tool' && <section className="att-tool-source-provenance" aria-label="Tool source provenance">
      <p>Package-local Tool: {String(resource?.logicalId || identity.resourceId)}</p>
      <p>Name: {String(toolProvenance.toolName || resource?.name || resource?.logicalId || identity.resourceId)}</p>
      <p>Source kind: {String(resource?.sourceKind || toolProvenance.toolSourceKind || 'configured Tool')}</p>
      {typeof toolProvenance.toolConfigLocation === 'string' && <p>Tool config: <code>{toolProvenance.toolConfigLocation}</code></p>}
      {typeof toolProvenance.toolGroupManifest === 'string' && <p>Tool Group manifest: <code>{toolProvenance.toolGroupManifest}</code></p>}
      {resource?.sourceKind === 'package-tool-descriptor' && <p>Source is the Package-owned att-tool/v1 descriptor; global Helpers remain centrally configured.</p>}
      {resource?.sourceKind === 'built-in-virtual' && <p>This Tool uses a built-in or virtual provider and has no standalone source file.</p>}
    </section>}
    {error && <button type="button" onClick={refreshSource} disabled={loading}>Retry source</button>}
    {projection?.available && rawText.length > 0 && <CodeEditor
      value={shown}
      language={detectCodeLanguage(projection.logicalPath || identity.resourceId || projection.format || '')}
      label={`${sourceIndicator}; line numbers shown`}
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
    {projection && ((projection.fileReferences?.length || 0) > 0 || projection.fileReferencesUnavailable) ? <section className="att-source-file-references" aria-label="Package files referenced in source">
      <h3>Package files referenced in source</h3>
      {projection.fileReferencesUnavailable && <p role="status">Some file references could not be verified because they are missing, invalid, or blocked by the Server safe-source policy. Their literal expressions remain visible in the source; no file contents were loaded.</p>}
      {projection.fileReferences?.length ? <ul>{projection.fileReferences.map(reference => <li key={reference.referenceId}>
        <code>&amp;{'{'}{reference.logicalPath}{'}'}</code>
        {reference.available === false
          ? <span role="status">Unavailable under the Server safe-source policy</span>
          : <button type="button" onClick={() => openReference(reference)}>View referenced file</button>}
      </li>)}</ul> : null}
      {projection.fileReferencesTruncated && <p role="status">Additional references were omitted by the Server safety limit.</p>}
    </section> : null}
  </section>;
}

export const sourceViewerLimits = Object.freeze({ maxCharacters: MAX_SOURCE_CHARACTERS, maxLines: MAX_SOURCE_LINES });
