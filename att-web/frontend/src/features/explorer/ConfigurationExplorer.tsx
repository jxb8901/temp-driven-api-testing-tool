import { useEffect, useMemo, useState } from 'react';
import type { ConfigurationField, ConfigurationResponse, ExplorerApi } from './types';

type ConfigMode = 'declared' | 'effective' | 'compare';

interface ConfigurationExplorerProps {
  api: ExplorerApi;
  packageId: string;
  environment: string;
  onEnvironmentChange: (environment: string) => void;
}

function errorText(error: unknown): string {
  const status = typeof error === 'object' && error !== null && 'status' in error ? Number((error as { status?: unknown }).status) : 0;
  if (status === 401) return 'Your session has expired. Sign in again to inspect configuration.';
  if (status === 403) return 'Configuration inspection is not permitted for this package.';
  if (status === 404) return 'The package or selected environment no longer exists. Refresh the package list.';
  if (status === 500) return 'The Server could not inspect this configuration. Retry the request.';
  if (status) return `Configuration inspection failed (HTTP ${status}). Retry the request.`;
  return 'Configuration is unavailable. Check the connection and retry.';
}
function safeValue(field?: ConfigurationField): string {
  if (!field) return 'unavailable';
  if (field.state !== 'visible') return field.state;
  const value = field.value;
  return typeof value === 'object' && value !== null ? JSON.stringify(value) : String(value ?? 'null');
}
function visibleFieldMatches(field: ConfigurationField | undefined, query: string): boolean {
  if (!query) return true;
  if (!field) return false;
  const values = [field.state];
  if (field.state === 'visible') values.push(safeValue(field), field.origin || '');
  return values.some(value => value.toLowerCase().includes(query));
}
function flattenEntries(response: ConfigurationResponse, section: string): Array<{ path: string; field: ConfigurationField }> {
  if (section === 'globals') return Object.entries(response.globals || {}).map(([path, field]) => ({ path, field }));
  const selected = (response.sections || []).find(item => item.id === section);
  const rows: Array<{ path: string; field: ConfigurationField }> = [];
  for (const entry of selected?.entries || []) {
    for (const [name, field] of Object.entries(entry.fields || {})) rows.push({ path: `${entry.id}.${name}`, field });
  }
  return rows;
}

export function ConfigurationExplorer({ api, packageId, environment, onEnvironmentChange }: ConfigurationExplorerProps) {
  const [mode, setMode] = useState<ConfigMode>('declared');
  const [data, setData] = useState<ConfigurationResponse | null>(null);
  const [declaredData, setDeclaredData] = useState<ConfigurationResponse | null>(null);
  const [status, setStatus] = useState('Loading declared configuration…');
  const [error, setError] = useState('');
  const [section, setSection] = useState('globals');
  const [query, setQuery] = useState('');
  const [left, setLeft] = useState('');
  const [right, setRight] = useState('');
  const [nextOffset, setNextOffset] = useState<number | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    setMode('declared'); setData(null); setDeclaredData(null); setError(''); setStatus('Loading declared configuration…');
    api.getDeclaredConfiguration(packageId, controller.signal).then(response => {
      setData(response); setDeclaredData(response); setStatus(`Declared configuration: ${response.state || 'ready'}.`);
      const environments = (response.environments || []).filter(item => item.state === 'active' && item.name);
      const defaultName = environments.find(item => item.default)?.name || environments[0]?.name || '';
      setLeft(defaultName);
      setRight(environments.find(item => item.name !== defaultName)?.name || '');
      if (!environment && defaultName) onEnvironmentChange(defaultName);
    }).catch(reason => {
      if (!controller.signal.aborted) { setError(errorText(reason)); setStatus('Declared configuration could not be loaded.'); }
    });
    return () => controller.abort();
  }, [api, packageId]);

  const profiles = useMemo(() => (declaredData?.environments || []).filter(item => item.state === 'active' && item.name), [declaredData]);

  const loadEffective = async (requestedSection = 'globals', append = false) => {
    const offset = append ? Number(nextOffset || 0) : 0;
    setError(''); setStatus(`Loading effective configuration for ${environment || 'package default'}…`);
    if (append) setLoadingMore(true);
    const controller = new AbortController();
    try {
      const response = await api.getEffectiveConfiguration(packageId,
        { environment: environment || undefined, section: requestedSection === 'globals' ? undefined : requestedSection, offset, limit: 50 }, controller.signal);
      setData(previous => {
        if (!append || !previous) return response;
        if (requestedSection === 'globals') return { ...response, globals: { ...previous.globals, ...response.globals } };
        const current = previous.sections?.find(item => item.id === requestedSection);
        const incoming = response.sections?.find(item => item.id === requestedSection);
        return { ...response, sections: (response.sections || []).map(item => item.id === requestedSection && incoming
          ? { ...item, entries: [...(current?.entries || []), ...(incoming.entries || [])] } : item) };
      });
      setNextOffset(response.nextOffset == null ? null : Number(response.nextOffset));
      setMode('effective'); setSection(response.section || requestedSection);
      setStatus(`Effective configuration for ${response.environment || environment || 'package default'}: ${response.state || 'ready'}.`);
    } catch (reason) { setError(errorText(reason)); setStatus('Effective configuration could not be loaded.'); }
    finally { if (append) setLoadingMore(false); }
  };

  const compare = async () => {
    if (!left || !right || left === right) { setError('Choose two different active environments to compare.'); return; }
    setError(''); setStatus(`Comparing ${left} with ${right}…`);
    const controller = new AbortController();
    try {
      const response = await api.compareConfiguration(packageId, { left, right, offset: 0, limit: 50 }, controller.signal);
      setData(response); setMode('compare'); setNextOffset(response.nextOffset == null ? null : Number(response.nextOffset));
      setStatus(`Configuration comparison: ${response.state || 'ready'}.`);
    } catch (reason) { setError(errorText(reason)); setStatus('Configuration comparison could not be loaded.'); }
  };

  const loadMore = async () => {
    if (nextOffset == null) return;
    if (mode === 'effective') return loadEffective(section, true);
    if (mode !== 'compare') return;
    setLoadingMore(true); setError('');
    try {
      const incoming = await api.compareConfiguration(packageId, { left, right, offset: nextOffset, limit: 50 });
      setData(previous => previous ? { ...incoming, fields: [...(previous.fields || []), ...(incoming.fields || [])], offset: 0 } : incoming);
      setNextOffset(incoming.nextOffset == null ? null : Number(incoming.nextOffset));
      setStatus(`Configuration comparison: ${incoming.state || 'ready'}.`);
    } catch (reason) { setError(errorText(reason)); }
    finally { setLoadingMore(false); }
  };

  const normalizedQuery = query.trim().toLowerCase();
  const effectiveRows = mode === 'effective' && data ? flattenEntries(data, section).filter(row =>
    row.path.toLowerCase().includes(normalizedQuery) || visibleFieldMatches(row.field, normalizedQuery)) : [];
  const compareRows = mode === 'compare' ? (data?.fields || []).filter(row => {
    if (!normalizedQuery) return true;
    return row.path.toLowerCase().includes(normalizedQuery) || row.change.toLowerCase().includes(normalizedQuery)
      || visibleFieldMatches(row.left, normalizedQuery) || visibleFieldMatches(row.right, normalizedQuery);
  }) : [];

  return <section className="att-config-explorer" aria-labelledby="att-config-title">
    <div className="att-config-heading"><div><h2 id="att-config-title">Configuration</h2><p>Values are shown only when the Server marks a field visible.</p></div>
      <label>Run, Debug, and Load environment
        <select value={environment} onChange={event => onEnvironmentChange(event.currentTarget.value)}>
          <option value="">Package default</option>
          {profiles.map(profile => <option key={profile.name} value={profile.name}>{profile.name}{profile.default ? ' · default' : ''}</option>)}
        </select>
      </label>
    </div>
    <div className="att-config-toolbar" role="group" aria-label="Configuration views">
      <button type="button" aria-pressed={mode === 'declared'} onClick={() => {
        setMode('declared'); setData(declaredData); setError(''); setStatus(`Declared configuration: ${declaredData?.state || 'ready'}.`);
      }}>Declared</button>
      <button type="button" aria-pressed={mode === 'effective'} onClick={() => void loadEffective('globals')}>Effective</button>
      <label>Section
        <select value={section} onChange={event => { setSection(event.currentTarget.value); if (mode === 'effective') void loadEffective(event.currentTarget.value); }}>
          <option value="globals">Global fields</option>
          {(declaredData?.sections || []).map(item => <option key={item.id} value={item.id}>{item.id}</option>)}
        </select>
      </label>
      <label>Compare
        <select aria-label="Left environment" value={left} onChange={event => setLeft(event.currentTarget.value)}>
          {profiles.map(profile => <option key={profile.name} value={profile.name}>{profile.name}</option>)}
        </select>
      </label>
      <span aria-hidden="true">with</span>
      <label className="att-visually-hidden" htmlFor="att-config-right">Right environment</label>
      <select id="att-config-right" aria-label="Right environment" value={right} onChange={event => setRight(event.currentTarget.value)}>
        {profiles.map(profile => <option key={profile.name} value={profile.name}>{profile.name}</option>)}
      </select>
      <button type="button" onClick={() => void compare()} disabled={!left || !right || left === right}>Compare</button>
      <label>Filter fields <input type="search" value={query} maxLength={200} onChange={event => setQuery(event.currentTarget.value)} /></label>
    </div>
    <p role="status" aria-live="polite">{status}</p>
    {error && <p className="att-error" role="alert">{error}</p>}
    {mode === 'declared' && data && <div className="att-config-results">
      <h3>Environment profiles</h3>
      {profiles.length ? <ul>{profiles.map(profile => <li key={profile.name}>{profile.name}{profile.default ? ' · default' : ''}</li>)}</ul> : <p>No active environment profiles; package defaults apply.</p>}
      <h3>Declared global fields</h3>
      <div className="att-config-grid" role="table" aria-label="Declared global configuration">
        <div role="row"><span role="columnheader">Field</span><span role="columnheader">Value</span><span role="columnheader">State</span><span role="columnheader">Origin</span></div>
        {Object.entries(data.globals || {}).filter(([name, field]) => name.toLowerCase().includes(normalizedQuery) || visibleFieldMatches(field, normalizedQuery))
          .map(([name, field]) => <div role="row" key={name}><strong role="rowheader">{name}</strong><span role="cell">{safeValue(field)}</span><span role="cell" data-state={field.state}>{field.state}</span><span role="cell">{field.state === 'visible' ? field.origin || 'declared' : '—'}</span></div>)}
        {!Object.keys(data.globals || {}).length && <p>No declared global configuration was returned.</p>}
      </div>
    </div>}
    {mode === 'effective' && <div className="att-config-results">
      <h3>Effective values · {data?.environment || environment || 'package default'}</h3>
      {effectiveRows.length ? <div className="att-config-grid" role="table" aria-label="Effective configuration fields">
        <div role="row"><span role="columnheader">Field</span><span role="columnheader">Value</span><span role="columnheader">State</span><span role="columnheader">Origin</span></div>
        {effectiveRows.map(row => <div role="row" key={row.path}><strong role="rowheader">{row.path}</strong><span role="cell">{safeValue(row.field)}</span><span role="cell" data-state={row.field.state}>{row.field.state}</span><span role="cell">{row.field.state === 'visible' ? row.field.origin || '—' : '—'}</span></div>)}
      </div> : <p>No effective fields match this filter.</p>}
    </div>}
    {mode === 'compare' && <div className="att-config-results">
      <h3>{data?.leftEnvironment || left} compared with {data?.rightEnvironment || right}</h3>
      {compareRows.length ? <div className="att-config-compare" role="table" aria-label="Configuration profile comparison">
        <div className="att-config-compare-row" role="row"><span role="columnheader">Field</span><span role="columnheader">{data?.leftEnvironment || left}</span><span role="columnheader">{data?.rightEnvironment || right}</span><span role="columnheader">Change</span></div>
        {compareRows.map(row => <div className="att-config-compare-row" role="row" key={row.path}>
          <strong role="rowheader">{row.path}</strong>
          <span role="cell" data-label={data?.leftEnvironment || left}><b data-state={row.left.state}>{row.left.state}</b>{row.left.state === 'visible' && <span>{safeValue(row.left)}{row.left.origin ? ` · ${row.left.origin}` : ''}</span>}</span>
          <span role="cell" data-label={data?.rightEnvironment || right}><b data-state={row.right.state}>{row.right.state}</b>{row.right.state === 'visible' && <span>{safeValue(row.right)}{row.right.origin ? ` · ${row.right.origin}` : ''}</span>}</span>
          <span role="cell" data-label="Change">{row.change === 'hidden' ? 'Hidden value; comparison unavailable' : row.change === 'same' ? 'Unchanged' : row.change}</span>
        </div>)}
      </div> : <p>No comparison fields match this filter.</p>}
    </div>}
    {nextOffset != null && <button type="button" onClick={() => void loadMore()} disabled={loadingMore}>{loadingMore ? 'Loading…' : 'Load more configuration'}</button>}
  </section>;
}
