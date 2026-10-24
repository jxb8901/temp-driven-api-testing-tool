import { PrincipalCombobox } from "./PrincipalCombobox";
import { Fragment, useEffect, useRef, useState, type KeyboardEvent } from 'react';
import type { JobsFeatureProps, JobsQueryResult, JobSummary } from './types';
import { JobReplayActions } from './JobReplayActions';
import { JobStateIcons } from './JobStateIcons';
import { StatusBadge } from './StatusBadge';
import { formatRoute } from '../../router';

const terminalStatuses = ['PASS', 'FAIL', 'ERROR', 'INVALID', 'CANCELLED'];
function timestamp(value?: string | null) { return value && Number.isFinite(Date.parse(value)) ? new Date(value).toLocaleString() : '—'; }
function targetLabel(job: JobSummary) { const t = job.targetIdentity; return t?.type && t.id ? `${t.type === 'load' ? 'LOAD' : t.type[0].toUpperCase() + t.type.slice(1)} · ${t.id}` : 'Unknown target'; }
function groupKey(job: Pick<JobSummary, 'packageId' | 'environment' | 'targetGroup'>) { return JSON.stringify([job.packageId, job.environment || '', job.targetGroup || 'legacy']); }
function storedSize() { try { const n = Number(localStorage.getItem('att.jobs.pageSize')); return [25, 50, 100].includes(n) ? n : 100; } catch { return 100; } }
function savedFilters(): Record<string, string> { try { const value = JSON.parse(sessionStorage.getItem('att.jobs.filters') || '{}'); return value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).filter((entry): entry is [string, string] => typeof entry[1] === 'string')) : {}; } catch { return {}; } }
function scheduled(job: JobSummary) { return !!job.scheduled || /^(load|debug)-schedule:/.test(job.principal || ''); }
function elapsed(job: JobSummary, now: number) {
  if (!job.startedAt) return '—';
  const end = job.finishedAt ? Date.parse(job.finishedAt) : !['RUNNING', 'CANCEL_REQUESTED'].includes(job.status) ? NaN : now;
  const ms = end - Date.parse(job.startedAt); if (!Number.isFinite(ms) || ms < 0) return '—';
  const seconds = Math.floor(ms / 1000); return `${Math.floor(seconds / 60)}m ${seconds % 60}s`;
}
function FooterMenu({ label, disabled, actions }: { label: string; disabled: boolean; actions: Array<{label: string; run: () => void}> }) {
  const [open, setOpen] = useState(false); const ref = useRef<HTMLDivElement>(null);
  useEffect(() => { if (open) ref.current?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus(); }, [open]);
  const close = () => { setOpen(false); ref.current?.querySelector<HTMLButtonElement>('button')?.focus(); };
  function keys(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') { event.preventDefault(); close(); }
    if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key) && open) {
      event.preventDefault(); const items = [...ref.current!.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')]; const index = items.indexOf(document.activeElement as HTMLButtonElement);
      items[event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (index + (event.key === 'ArrowUp' ? -1 : 1) + items.length) % items.length]?.focus();
    }
  }
  return <div ref={ref} className="att-footer-menu" onKeyDown={keys} onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false); }}>
    <button type="button" disabled={disabled} aria-haspopup="menu" aria-expanded={open && !disabled} onClick={() => setOpen(value => !value)}>{label} ▾</button>
    {open && !disabled && <div role="menu" aria-label={`${label} on this page`}>{actions.map(action => <button type="button" role="menuitem" key={action.label} onClick={() => { close(); action.run(); }}>{action.label}</button>)}</div>}
  </div>;
}

export function JobsList({ api, onOpenJob, onSchedule, onEditSchedule, executionType, onExecutionTypeChange }: JobsFeatureProps) {
  const [saved] = useState(savedFilters);
  const [moreFilters, setMoreFilters] = useState(false);
  const [result, setResult] = useState<JobsQueryResult>({items: [], total: 0, offset: 0, limit: 100, groups: [], principalSuggestions: [], packageSuggestions: []});
  const [principal, setPrincipal] = useState(saved.principal || ''); const [principalMode,setPrincipalMode]=useState(saved.principalMode || 'all'); const [packageId, setPackage] = useState(saved.packageId || ''); const [type, setType] = useState(executionType || saved.type || '');
  const [status, setStatus] = useState(saved.status || ''); const [from, setFrom] = useState(saved.from || ''); const [to, setTo] = useState(saved.to || '');
  const [pinned, setPinned] = useState(saved.pinned === 'true'); const [deletion, setDeletion] = useState(saved.deletion || 'all'); const [search, setSearch] = useState(saved.search || '');
  const [sort, setSort] = useState(saved.sort || 'newest'); const [grouped, setGrouped] = useState(saved.grouped === 'true'); const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [pageSize, setPageSize] = useState(storedSize); const [page, setPage] = useState(0); const [version, setVersion] = useState(0);
  const [loading, setLoading] = useState(true); const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  const [outcomes, setOutcomes] = useState<Array<{jobId: string; outcome: string}>>([]); const [now, setNow] = useState(Date.now());
  const mutationLock = useRef(false); const sequence = useRef(0); const [copyState, setCopyState] = useState('');
  const previousType = useRef(executionType);
  useEffect(() => { if (previousType.current !== executionType) { previousType.current = executionType; setType(executionType || ''); setPage(0); } }, [executionType]);
  useEffect(() => { try { sessionStorage.setItem('att.jobs.filters', JSON.stringify({principal, principalMode, packageId, type, status, from, to, deletion, search, sort, pinned: String(pinned), grouped: String(grouped)})); } catch { /* Storage can be unavailable. */ } }, [principal, principalMode, packageId, type, status, from, to, deletion, search, sort, pinned, grouped]);
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer); }, []);
  useEffect(() => {
    const controller = new AbortController(); const current = ++sequence.current; setLoading(true); setError('');
    const timer = setTimeout(async () => {
      try {
        const mode = principalMode;
        const query = { offset: page * pageSize, limit: pageSize, principal: mode === 'text' ? principal : undefined, principalMode: mode, packageId: packageId || undefined, executionType: type || undefined, status: status || undefined, from: from ? new Date(`${from}T00:00:00`).toISOString() : undefined, to: to ? new Date(`${to}T23:59:59.999`).toISOString() : undefined, search: search || undefined, sort: grouped ? 'target-asc' : sort, pinnedOnly: pinned, deletion, groupBy: grouped ? 'target' : undefined, signal: controller.signal };
        if (!api.queryJobs) throw new Error('This Server does not support paginated Jobs. Refresh Studio and Server together.');
        const response = await api.queryJobs(query);
        if (controller.signal.aborted || sequence.current !== current) return;
        setResult(response);
        if (page > 0 && page * pageSize >= response.total) setPage(Math.max(0, Math.ceil(response.total / pageSize) - 1));
      } catch (reason) { if (!controller.signal.aborted && sequence.current === current) { setError(reason instanceof Error ? reason.message : 'Jobs could not be loaded.'); setResult({items:[],total:0,offset:0,limit:pageSize,groups:[],principalSuggestions:[],packageSuggestions:[]}); } }
      finally { if (!controller.signal.aborted && sequence.current === current) setLoading(false); }
    }, 180);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [api, page, pageSize, principal, principalMode, packageId, type, status, from, to, pinned, deletion, search, sort, grouped, version]);
  async function mutate(ids: string[], action: 'pin' | 'unpin' | 'mark' | 'unmark') {
    if (mutationLock.current || loading || !ids.length) return;
    mutationLock.current = true; setBusy(true); setOutcomes([]); setError('');
    const snapshot = [...new Set(ids)];
    try {
      let response;
      if (action === 'pin' || action === 'unpin') {
        if (!api.pinJobs) throw new Error('Pin actions are unavailable.');
        response = await api.pinJobs(snapshot, action === 'pin');
      } else {
        if (!api.setDeletionRequested) throw new Error('Deletion actions are unavailable.');
        response = await api.setDeletionRequested(snapshot, action === 'mark');
      }
      setOutcomes(response);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Job state change failed.'); }
    finally { mutationLock.current = false; setBusy(false); setVersion(value => value + 1); }
  }
  const update = (setter: (value: string) => void) => (event: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => { setter(event.currentTarget.value); setPage(0); };
  const extraFilters = [from && `From ${from}`, to && `To ${to}`, deletion !== 'all' && `Deletion: ${deletion}`, pinned && 'Pinned only'].filter(Boolean);
  const rows = result.items, pages = Math.max(1, Math.ceil(result.total / pageSize));
  const groups = new Map<string, JobSummary[]>(); for (const job of rows) { const key = groupKey(job); groups.set(key, [...(groups.get(key) || []), job]); }
  const blocked = busy || loading;
  const counts = { updated: 0, already: 0, skipped: 0, failed: 0 };
  for (const item of outcomes) { if (['MARKED', 'UNMARKED', 'PINNED', 'UNPINNED'].includes(item.outcome)) counts.updated++; else if (['ALREADY_IN_STATE', 'ALREADY_UNAVAILABLE'].includes(item.outcome)) counts.already++; else if (item.outcome === 'FAILED') counts.failed++; else counts.skipped++; }
  function row(job: JobSummary) { return <tr key={job.jobId}>
    <th scope="row" data-label="Target / Package / Environment"><a className="att-job-target" href={formatRoute({kind:'jobs',jobId:job.jobId})} onClick={event => { if (!event.ctrlKey && !event.metaKey && !event.shiftKey && event.button === 0) {event.preventDefault();onOpenJob(job.jobId);} }}>{targetLabel(job)}{!job.targetIdentity?.type && ` · ${job.jobId}`}</a>
      <span className="att-job-secondary">{job.packageId} · {job.environment || 'Package default'}</span>
      {job.scenario && <span className="att-job-secondary">{job.scenario}</span>}
      <span className="att-job-tertiary"><code title={job.jobId}>{job.jobId.length > 12 ? `${job.jobId.slice(0,6)}…${job.jobId.slice(-4)}` : job.jobId}</code> <button type="button" className="att-link-button" aria-label={`Copy full Job ID ${job.jobId}`} title="Copy full Job ID" onClick={() => { void navigator.clipboard.writeText(job.jobId).then(() => setCopyState(`Copied ${job.jobId}`)).catch(() => setCopyState(`Copy unavailable: ${job.jobId}`)); }}>⧉</button></span>
    </th>
    <td data-label="Type / Status"><strong>{job.executionType || (job.command === 'debug' ? 'run' : job.command)}</strong> <StatusBadge status={job.status} /><span className="att-job-secondary">{scheduled(job) ? 'Schedule' : job.principal || 'Unknown principal'}</span>{job.deletionState === 'DELETE_REQUESTED' && <span className="att-job-secondary">Pending deletion</span>}</td>
    <td data-label="Timing" className="att-job-timing">{[['Created',job.createdAt],['Started',job.startedAt],['Finished',job.finishedAt]].map(([label,value]) => <span key={label}>{label} <time dateTime={value || undefined} title={value || undefined}>{timestamp(value)}</time></span>)}<strong>Elapsed {elapsed(job,now)}</strong></td>
    <td data-label="Actions"><div className="att-jobs-row-actions"><JobStateIcons job={job} busy={blocked} onPin={api.pinJobs ? () => void mutate([job.jobId],job.pinned?'unpin':'pin') : undefined} onDelete={api.setDeletionRequested ? () => void mutate([job.jobId],job.deletionState==='DELETE_REQUESTED'?'unmark':'mark') : undefined} /><JobReplayActions api={api} configuration={{executionType:job.executionType || job.command,packageId:job.packageId}} jobId={job.jobId} onOpenJob={onOpenJob} onSchedule={onSchedule} onEditSchedule={onEditSchedule} /></div></td>
  </tr>; }
  return <div className="page-content att-jobs-page att-query-workspace" aria-labelledby="jobs-title">
    <header className="att-feature-header"><div><p className="att-eyebrow">Jobs and results</p><h1 id="jobs-title">Jobs</h1></div></header>
    <section className="att-query-filter-panel att-jobs-filter-panel" aria-label="Job filters"><fieldset disabled={busy} className="att-jobs-filter-grid">
      <label title="Server-filtered · up to 30 matching known principals; type to search">Principal<PrincipalCombobox value={principal} suggestions={result.principalSuggestions} disabled={busy} onChange={(value,mode)=>{setPrincipal(value);setPrincipalMode(mode);setPage(0);}} /></label>
      <label>Package<select aria-label="Package" value={packageId} onChange={update(setPackage)}><option value="">All packages</option>{[...new Set([...result.packageSuggestions,...(packageId?[packageId]:[])])].map(value => <option key={value}>{value}</option>)}</select></label>
      <label>Type<select aria-label="Type" value={type} onChange={event => {setType(event.currentTarget.value);setPage(0);onExecutionTypeChange?.(event.currentTarget.value==='load'?'load':undefined);}}><option value="">All types</option><option value="run">RUN / Debug</option><option value="load">LOAD</option><option value="validate">Validate</option></select></label>
      <label>Status<select aria-label="Status" value={status} onChange={update(setStatus)}><option value="">All statuses</option>{['QUEUED','PREPARING','RUNNING','CANCEL_REQUESTED',...terminalStatuses].map(value => <option key={value}>{value}</option>)}</select></label>
      <label>Target or Job ID<input type="search" value={search} onChange={update(setSearch)} /></label>
    </fieldset>
    <div className="att-jobs-filter-actions">
      <button type="button" disabled={busy} aria-expanded={moreFilters} aria-controls="jobs-more-filters" onClick={() => setMoreFilters(value => !value)}>More filters{extraFilters.length ? ` (${extraFilters.length})` : ''}</button>
      {extraFilters.length > 0 && <span className="att-jobs-active-filters">{extraFilters.join(' · ')}</span>}
      <div className="att-query-filter-actions"><button type="button" disabled={busy} onClick={() => {setPrincipal('');setPrincipalMode('all');setPackage('');setType('');setStatus('');setFrom('');setTo('');setPinned(false);setDeletion('all');setSearch('');setSort('newest');setPage(0);onExecutionTypeChange?.(undefined);}}>Clear filters</button><button type="button" disabled={busy || loading} onClick={() => setVersion(value=>value+1)}>Refresh jobs</button></div>
    </div>
    <fieldset id="jobs-more-filters" disabled={busy} hidden={!moreFilters} className="att-jobs-filter-grid att-jobs-more-filters">
      <label>Created from<input type="date" value={from} onChange={update(setFrom)} /></label><label>Created to<input type="date" value={to} onChange={update(setTo)} /></label>
      <label>Deletion state<select aria-label="Deletion state" value={deletion} onChange={update(setDeletion)}><option value="all">All Jobs</option><option value="pending">Pending deletion</option><option value="unmarked">Unmarked</option></select></label>
      <label>Pin state<select aria-label="Pin state" value={pinned?'pinned':'all'} onChange={event => {setPinned(event.currentTarget.value==='pinned');setPage(0);}}><option value="all">All pin states</option><option value="pinned">Pinned only</option></select></label>
    </fieldset></section>
    <section className="att-query-results-panel" aria-label="Job results"><div className="att-jobs-toolbar">
      <label>View<select aria-label="View" value={grouped?'target':'recent'} disabled={busy} onChange={event=>{setGrouped(event.currentTarget.value==='target');setPage(0);}}><option value="recent">Recent Jobs</option><option value="target">Group by Target</option></select></label>
      <label>Sort<select aria-label="Sort" value={grouped ? 'target-asc' : sort} disabled={busy || grouped} onChange={update(setSort)}><option value="newest">Created newest first</option><option value="oldest">Created oldest first</option><option value="target-asc">Target A–Z</option><option value="target-desc">Target Z–A</option></select></label>
      <span title="Groups A–Z · Jobs newest first · counts across matching history. Group identity includes package, environment and all target bindings." aria-label="About target grouping">ⓘ</span>
    <p role="status" aria-live="polite">{loading?'Loading Jobs…':error && !rows.length?'Jobs unavailable':`Showing ${rows.length?result.offset+1:0}–${result.offset+rows.length} of ${result.total} Jobs`}</p></div>
    {error && <p role="alert">{error}</p>}{copyState && <p role="status">{copyState}</p>}
    {!!outcomes.length && <details className="att-job-outcomes"><summary>{counts.updated} updated · {counts.already} already in desired state / unavailable · {counts.skipped} skipped · {counts.failed} failed</summary><ul>{outcomes.map(item=><li key={item.jobId}>{item.jobId}: {item.outcome.replaceAll('_',' ')}</li>)}</ul><p>Deletion marks wait at least 30 seconds before background cleanup can claim them. Unmark only cancels a pending mark.</p></details>}
    {!error && !loading && !rows.length ? <section className="att-empty-state"><h2>No matching Jobs</h2><p>Adjust the filters to search the full authorized history.</p></section> : <div className="att-table-scroll"><table className="att-jobs-table att-data-table"><caption className="att-visually-hidden">Jobs matching Server filters</caption><thead><tr><th>Target / Package / Environment</th><th>Type / Status</th><th>Timing</th><th>Actions</th></tr></thead><tbody>
      {!grouped ? rows.map(row) : [...groups].map(([key,members]) => {
        const aggregate=result.groups.find(group=>groupKey(group)===key);const closed=collapsed.has(key);
        return <Fragment key={key}><tr className="att-job-group"><th colSpan={4}><button type="button" aria-expanded={!closed} onClick={()=>setCollapsed(current=>{const next=new Set(current);if(next.has(key))next.delete(key);else next.add(key);return next;})}>{closed?'▸':'▾'} {members[0].targetGroup==='legacy'?'Unclassified / legacy':targetLabel(members[0])} · {members[0].packageId} · {members[0].environment || 'Package default'} · {aggregate?.total ?? members.length} matching Jobs ({members.length} on this page)</button>{aggregate && <span className="att-job-secondary">{Object.entries(aggregate.statuses).map(([status,count])=>`${status}: ${count}`).join(' · ')} · Latest {timestamp(aggregate.lastCreatedAt)}</span>}{members[0].targetIdentity?.targets && members[0].targetIdentity.targets.length>1 && <details><summary>Target bindings</summary><ul>{members[0].targetIdentity.targets.map(t=><li key={JSON.stringify(t)}>{t.type} · {t.id}</li>)}</ul>{members[0].targetIdentity.truncated && <p>First 32 bindings shown. Group identity includes the full binding signature.</p>}</details>}</th></tr>{!closed && members.map(row)}</Fragment>;
      })}
    </tbody></table></div>}
    <footer className="att-jobs-footer"><label>Rows per page<select aria-label="Rows per page" value={pageSize} disabled={blocked} onChange={event=>{const n=Number(event.currentTarget.value);setPageSize(n);setPage(0);try{localStorage.setItem('att.jobs.pageSize',String(n));}catch{}}}>{[25,50,100].map(n=><option key={n}>{n}</option>)}</select></label>
      <span>Showing {rows.length?result.offset+1:0}–{result.offset+rows.length} of {result.total} Jobs</span>
      <nav aria-label="Jobs pages"><button type="button" disabled={blocked || page===0} onClick={()=>setPage(value=>value-1)}>Previous</button><span>Page {page+1} of {pages}</span><button type="button" disabled={blocked || page>=pages-1} onClick={()=>setPage(value=>value+1)}>Next</button></nav>
      <span>Apply to this page: {rows.length} Jobs{grouped?' (including collapsed groups)':''}</span>
      <FooterMenu label="Pin all" disabled={blocked || !rows.length || !api.pinJobs} actions={[{label:'Pin all on this page',run:()=>void mutate(rows.map(job=>job.jobId),'pin')},{label:'Unpin all on this page',run:()=>void mutate(rows.map(job=>job.jobId),'unpin')}]} />
      <FooterMenu label="Delete all" disabled={blocked || !rows.length || !api.setDeletionRequested} actions={[{label:'Mark all on this page for deletion',run:()=>void mutate(rows.map(job=>job.jobId),'mark')},{label:'Undelete / Unmark pending deletion on this page',run:()=>void mutate(rows.map(job=>job.jobId),'unmark')}]} />
    </footer></section>
  </div>;
}
