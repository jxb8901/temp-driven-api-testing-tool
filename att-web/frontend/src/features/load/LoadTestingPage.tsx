import { useEffect, useRef, useState, type ReactNode } from 'react';
import type { api as Api } from '../../api';
import type { PackageOption } from './types';
import type { LoadTemplateItem } from './templateTypes';
import { LoadDialog } from './LoadDialog';
import { CodeEditor } from '../code/CodeEditor';
import { stringify } from 'yaml';
import './load.css';

const icons = ['♙', '◴', '⤨', '▱', '◴', '⌘'];
export function LoadTestingPage({ api, packages, tab, onTab, onCreate, onBlank, onJobs, children, enabled }: {
  api: Pick<typeof Api, 'listLoadTemplates'>; packages: PackageOption[]; tab: 'templates' | 'scenarios'; enabled: boolean;
  onTab(tab: 'templates' | 'scenarios'): void; onCreate(packageId: string, item: LoadTemplateItem): void;
  onBlank(packageId: string): void; onJobs(): void; children: ReactNode;
}) {
  const [packageId, setPackageId] = useState(packages[0]?.packageId || '');
  const [query, setQuery] = useState('');
  const [mode, setMode] = useState('');
  const [items, setItems] = useState<LoadTemplateItem[]>([]);
  const [next, setNext] = useState<number | null>(null);
  const [state, setState] = useState('loading');
  const [message, setMessage] = useState('');
  const [retry, setRetry] = useState(0);
  const [view, setView] = useState<LoadTemplateItem | null>(null);
  const sequence = useRef(0);
  useEffect(() => { if (!packages.some(item => item.packageId === packageId)) setPackageId(packages[0]?.packageId || ''); }, [packages, packageId]);
  useEffect(() => {
    if (tab !== 'templates' || !packageId || !enabled) return;
    const controller = new AbortController(); const version = ++sequence.current;
    setState('loading'); setItems([]); setMessage(''); setNext(null);
    const timer = setTimeout(() => {
      api.listLoadTemplates(packageId, query, 0, controller.signal).then(page => {
        if (version !== sequence.current || controller.signal.aborted) return;
        setItems(page.items); setNext(page.nextOffset ?? null); setState('ready');
        if (page.truncated) setMessage('The package scan limit was reached. Narrow the package template collection.');
      }).catch(error => { if (!controller.signal.aborted) { setMessage(error.message || 'Templates could not be loaded.'); setState('error'); } });
    }, 180);
    return () => { clearTimeout(timer); controller.abort(); ++sequence.current; };
  }, [api, packageId, query, retry, tab, enabled]);
  async function more() {
    if (next == null) return; const version = sequence.current; setState('loading-more');
    try {
      const page = await api.listLoadTemplates(packageId, query, next);
      if (version !== sequence.current) return;
      setItems(current => [...current, ...page.items]); setNext(page.nextOffset ?? null); setState('ready');
    } catch (error) { if (version === sequence.current) { setState('error'); setMessage(error instanceof Error ? error.message : 'Template page failed.'); } }
  }
  const filtered = items.filter(item => !mode || item.mode === mode);
  return <div className="att-load-testing">
    <nav aria-label="Load testing tabs" className="att-load-tabs">
      <button className="button button-quiet" type="button" aria-current={tab === 'templates' ? 'page' : undefined} onClick={() => onTab('templates')}>▦ Templates</button>
      <button className="button button-quiet" type="button" aria-current={tab === 'scenarios' ? 'page' : undefined} onClick={() => onTab('scenarios')}>☷ Scenarios</button>
      <button className="button button-quiet" type="button" onClick={onJobs}>◷ View LOAD Jobs</button>
    </nav>
    {tab === 'scenarios' ? children : <div className="page-content att-query-workspace">
      <header><h1>Load testing</h1><p>Choose an unbound template, then bind Targets and validate before running.</p></header>
      <label>Package <select value={packageId} onChange={event => setPackageId(event.target.value)}>{packages.map(item => <option key={item.packageId} value={item.packageId}>{item.displayName || item.packageId}</option>)}</select></label>
      {!enabled && <p role="status">Browser-created Load drafts are disabled by Server configuration.</p>}
      {!packageId && <p role="status">No permitted packages are available.</p>}
      <div className="att-template-presets">{items.filter(item => item.builtin).map((item, index) => <article key={item.id}>
        <h2><span aria-hidden="true">{icons[index]}</span> {item.name}</h2><p>Built-in · {item.schemaVersion}</p>
        <button className="button button-quiet" type="button" onClick={() => onCreate(packageId, item)} disabled={!enabled}>＋ Create Load</button>
        <button className="button button-quiet" type="button" onClick={() => setView(item)}>▤ View Template</button>
      </article>)}</div>
      <button className="button button-quiet" type="button" onClick={() => onBlank(packageId)} disabled={!packageId || !enabled}>＋ Blank / Advanced Scenario</button>
      <h2>Package templates</h2>
      <label>Search templates <input value={query} onChange={event => setQuery(event.target.value)} /></label>
      <label>Mode <select value={mode} onChange={event => setMode(event.target.value)}><option value="">All modes</option>{['single-vu','single-tps','single-workload-mix','multi-vu','multi-tps','multi-workload-mix'].map(value => <option key={value}>{value}</option>)}</select></label>
      {state.startsWith('loading') && <p role="status" aria-busy="true">Loading templates…</p>}
      {message && <p role={state === 'error' ? 'alert' : 'status'}>{message} <button className="button button-quiet" type="button" onClick={() => setRetry(value => value + 1)}>Refresh / Retry</button></p>}
      <div className="att-table-scroll"><table className="att-data-table"><thead><tr><th>Template</th><th>Mode</th><th>Schema / validation</th><th>Actions</th></tr></thead><tbody>{filtered.filter(item => !item.builtin).map(item => <tr key={item.id}>
        <td>{item.name}<small>{item.id}</small></td><td>{item.mode || 'Unavailable'}</td><td>{item.schemaVersion} · {item.validation}</td><td>
          <button className="button button-quiet" type="button" onClick={() => onCreate(packageId,item)} disabled={!enabled || !item.definition || item.validation !== 'valid'}>＋ Create Load</button>
          <button className="button button-quiet" type="button" onClick={() => setView(item)} disabled={!item.definition}>▤ View Template</button></td>
      </tr>)}</tbody></table></div>
      {state === 'ready' && !filtered.some(item => !item.builtin) && <p>No matching package templates.</p>}
      {next != null && <button className="button button-quiet" type="button" onClick={() => void more()} disabled={state === 'loading-more'}>Load more templates</button>}
      {view && <LoadDialog title={view.name} onCancel={() => setView(null)}><CodeEditor language="yaml" value={stringify(view.definition)} readOnly label="Load template YAML" /></LoadDialog>}
    </div>}
  </div>;
}
