import { useEffect, useMemo, useState } from 'react';
import type { ExplorerApi, ResourceIdentity, SourceProjection } from './types';

const MAX_SOURCE_CHARACTERS = 200_000;
const MAX_SOURCE_LINES = 12_000;
const SOURCE_LINE_HEIGHT = 20;
const VISIBLE_LINES = 30;
const LINE_OVERSCAN = 12;

interface SourceViewerProps {
  api: ExplorerApi;
  identity: ResourceIdentity;
  available: boolean;
  active: boolean;
}

interface HighlightToken {
  text: string;
  kind?: 'comment' | 'key' | 'string' | 'number' | 'literal';
}

function tokenize(line: string): HighlightToken[] {
  const tokens: HighlightToken[] = [];
  const pattern = /(#.*$)|("(?:\\.|[^"\\])*(?=\s*:)|"(?:\\.|[^"\\])*"|'(?:''|[^'])*')|\b(?:true|false|null|yes|no)\b|\b-?\d+(?:\.\d+)?\b/g;
  let cursor = 0;
  for (const match of line.matchAll(pattern)) {
    const index = match.index ?? 0;
    if (index > cursor) tokens.push({ text: line.slice(cursor, index) });
    const text = match[0];
    const kind = text.startsWith('#') ? 'comment'
      : /^("|')/.test(text) ? /:\s*$/.test(line.slice(index + text.length)) ? 'key' : 'string'
        : /^(true|false|null|yes|no)$/.test(text) ? 'literal' : 'number';
    tokens.push({ text, kind });
    cursor = index + text.length;
  }
  if (cursor < line.length || !tokens.length) tokens.push({ text: line.slice(cursor) });
  return tokens;
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

export function SourceViewer({ api, identity, available, active }: SourceViewerProps) {
  const [projection, setProjection] = useState<SourceProjection | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [refreshKey, setRefreshKey] = useState(0);
  const [scrollTop, setScrollTop] = useState(0);
  const [copyStatus, setCopyStatus] = useState('');
  const key = `${identity.packageId}:${identity.type}:${identity.resourceId}`;

  useEffect(() => {
    setProjection(null);
    setError('');
    setCopyStatus('');
    setScrollTop(0);
  }, [key]);

  useEffect(() => {
    if (!active || !available) return;
    const controller = new AbortController();
    setLoading(true);
    setError('');
    api.getSafeSource(identity, controller.signal).then(data => {
      setProjection(data);
      setError('');
      setScrollTop(0);
    }).catch(reason => {
      if (controller.signal.aborted) return;
      setError(friendlyError(reason));
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [active, available, identity.packageId, identity.type, identity.resourceId, refreshKey, api]);

  const rawText = projection?.available ? String(projection.text ?? '') : '';
  const charCapped = rawText.length > MAX_SOURCE_CHARACTERS;
  const boundedText = rawText.slice(0, MAX_SOURCE_CHARACTERS);
  const sourceLines = useMemo(() => boundedText.split('\n'), [boundedText]);
  const lineCapped = sourceLines.length > MAX_SOURCE_LINES;
  const lines = useMemo(() => sourceLines.slice(0, MAX_SOURCE_LINES), [sourceLines]);
  const capped = charCapped || lineCapped;
  const start = Math.max(0, Math.floor(scrollTop / SOURCE_LINE_HEIGHT) - LINE_OVERSCAN);
  const end = Math.min(lines.length, start + VISIBLE_LINES + LINE_OVERSCAN * 2);
  const visibleLines = lines.slice(start, end);

  const copyShown = async () => {
    try {
      if (!navigator.clipboard?.writeText) throw new Error('Clipboard access is unavailable in this browser context.');
      await navigator.clipboard.writeText(lines.join('\n'));
      setCopyStatus(capped ? 'Copied the displayed safe source (truncated at the viewer limit).' : 'Copied safe source.');
    } catch (reason) {
      setCopyStatus(reason instanceof Error ? reason.message : 'Could not copy source.');
    }
  };

  const view = projection?.available ? rawText : '';
  return <section className="att-explorer-source" aria-label="Safe source projection">
    <div className="att-source-toolbar">
      <span>{projection?.format ? `${projection.format.toUpperCase()} source` : 'Safe source'}</span>
      <button type="button" onClick={() => setRefreshKey(value => value + 1)} disabled={!available || loading}>
        {loading ? 'Refreshing…' : projection ? 'Refresh source' : 'Load source'}
      </button>
      <button type="button" onClick={copyShown} disabled={!projection?.available || !view.length}>Copy displayed source</button>
    </div>
    <p className="att-source-status" role="status" aria-live="polite">
      {loading ? 'Loading safe source projection…'
        : error || (projection && !projection.available ? `Source unavailable (${projection.reason || 'policy'}).`
          : projection?.available && rawText.length === 0 ? 'Safe source is empty.'
            : capped ? `Source exceeds the viewer limit. Showing the first ${lines.length.toLocaleString()} lines within ${MAX_SOURCE_CHARACTERS.toLocaleString()} characters.`
              : projection?.available ? `${lines.length.toLocaleString()} lines` : !available ? 'Source is not available for this resource.' : 'Choose Source to load the safe projection.')}
    </p>
    {copyStatus && <p className="att-source-status" role="status" aria-live="polite">{copyStatus}</p>}
    {error && <button type="button" onClick={() => setRefreshKey(value => value + 1)} disabled={loading}>Retry source</button>}
    {projection?.available && rawText.length > 0 && <div className="att-source-viewport" role="region" tabIndex={0} aria-label="Source code; line numbers shown"
      onScroll={event => setScrollTop(event.currentTarget.scrollTop)}>
      <div aria-hidden="true" style={{ height: start * SOURCE_LINE_HEIGHT }} />
      {visibleLines.map((line, offset) => {
        const lineNumber = start + offset + 1;
        return <div key={lineNumber} className="att-source-line" style={{ height: SOURCE_LINE_HEIGHT }}>
          <span className="att-source-line-number" aria-hidden="true">{lineNumber}</span>
          <code>
            {tokenize(line).map((token, tokenIndex) => token.kind
              ? <span key={tokenIndex} className={`att-source-token-${token.kind}`}>{token.text}</span>
              : token.text)}
          </code>
        </div>;
      })}
      {end < lines.length && <div aria-hidden="true" style={{ height: (lines.length - end) * SOURCE_LINE_HEIGHT }} />}
    </div>}
  </section>;
}

export const sourceViewerLimits = Object.freeze({ maxCharacters: MAX_SOURCE_CHARACTERS, maxLines: MAX_SOURCE_LINES });
