import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { formatRoute } from '../../router';
import type { JobEvent, JobView, LoadJobsApi } from './types';
import { MAX_EVENTS, useJobEventStream, type JobStreamEvent } from './useJobEventStream';

export type JobConsoleViewMode = 'live' | 'events';

const EVENT_TYPES = ['status', 'progress', 'log', 'diagnostic', 'result'] as const;
const MAX_LINE_LENGTH = 4000;
const CONSOLE_MIN_HEIGHT = 192;
const CONSOLE_DEFAULT_HEIGHT = 384;
const CONSOLE_MAX_HEIGHT = 960;

export interface JobConsoleViewProps {
  api: LoadJobsApi;
  jobId: string;
  job?: JobView | null;
  view?: JobConsoleViewMode;
  onViewChange?: (view: JobConsoleViewMode) => void;
  minimized?: boolean;
  onRestore?: () => void;
  onClose?: () => void;
  onMinimize?: () => void;
  onMaximize?: () => void;
  maximized?: boolean;
  onOpenNewTab?: () => void;
  compact?: boolean;
  showSwitcher?: boolean;
}

function executionType(job?: JobView | null): string {
  return (job?.executionType || (job?.command === 'debug' ? 'run' : job?.command) || 'unknown').toUpperCase();
}

function displayValue(value: unknown): string {
  if (typeof value === 'string') return value;
  try { return JSON.stringify(value, null, 2); } catch { return String(value); }
}

function eventText(event: JobEvent): string {
  const data = event.data;
  if (typeof data === 'string') return data;
  if (data && typeof data === 'object' && !Array.isArray(data)) {
    const record = data as Record<string, unknown>;
    for (const key of ['message', 'summary', 'status', 'text']) if (typeof record[key] === 'string') return record[key] as string;
  }
  return displayValue(data);
}

function consoleText(event: JobEvent): string {
  const data = event.data;
  if (!data || typeof data !== 'object' || Array.isArray(data)) return '';
  const value = (data as Record<string, unknown>).console;
  return typeof value === 'string' ? value
    .replace(/\u001B\[[0-?]*[ -/]*[@-~]/g, '')
    .replace(/\u001B\][^\u0007]*(?:\u0007|\u001B\\)/g, '') : '';
}

function eventPreview(event: JobEvent): string {
  const data = event.data;
  if (!data || typeof data !== 'object' || Array.isArray(data)) return eventText(event);
  const record = data as Record<string, unknown>;
  const parts = ['status', 'phase', 'sequence', 'message', 'error', 'summary'].flatMap(key => {
    const value = record[key];
    return typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean' ? [`${key}=${value}`] : [];
  });
  const metrics = record.metrics;
  if (metrics && typeof metrics === 'object' && !Array.isArray(metrics)) {
    const compact = Object.entries(metrics as Record<string, unknown>)
      .filter((entry): entry is [string, string | number | boolean] => ['string', 'number', 'boolean'].includes(typeof entry[1]))
      .slice(0, 6).map(([key, value]) => `${key}=${value}`).join(', ');
    if (compact) parts.push(`metrics=${compact}`);
  }
  return parts.join(' · ');
}

function eventTimestamp(event: JobEvent): string {
  const data = event.data && typeof event.data === 'object' && !Array.isArray(event.data) ? event.data as Record<string, unknown> : {};
  const value = [data.timestamp, data.createdAt, data.time, data.at, event.receivedAt]
    .find(item => typeof item === 'string' && Number.isFinite(Date.parse(item)));
  return typeof value === 'string' ? value : '';
}

function chronologicalEvents(events: JobStreamEvent[]): JobStreamEvent[] {
  return events.map((event, index) => ({ event, index })).sort((left, right) => {
    const a = Number(left.event.id); const b = Number(right.event.id);
    return Number.isFinite(a) && Number.isFinite(b) ? a - b : left.index - right.index;
  }).map(item => item.event);
}

function dateTime(value: string): string {
  const time = Date.parse(value);
  return Number.isFinite(time) ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' }).format(time) : value;
}

function eventJournalTruncated(event: JobEvent): boolean {
  return Boolean(event.data && typeof event.data === 'object' && !Array.isArray(event.data)
    && (event.data as Record<string, unknown>).historyTruncated === true);
}

function EventPayload({ event, expanded }: { event: JobStreamEvent; expanded: boolean }) {
  const [retainedExpanded, setRetainedExpanded] = useState(false);
  if (!expanded) return null;
  const payload = displayValue(event.data);
  return <>
    <pre className="att-event-payload">{payload.slice(0, MAX_LINE_LENGTH)}</pre>
    {payload.length > MAX_LINE_LENGTH && <details open={retainedExpanded} onToggle={event => setRetainedExpanded(event.currentTarget.open)}><summary onClick={event => { event.preventDefault(); setRetainedExpanded(value => !value); }}>Events preview clipped — expand retained payload</summary>{retainedExpanded && <pre>{payload.slice(0, 65536)}</pre>}</details>}
    {retainedExpanded && payload.length > 65536 && <p>Retained payload display limited to 65,536 characters.</p>}
  </>;
}

export function consoleRouteUrl(jobId: string, view: JobConsoleViewMode = 'live'): string {
  const url = new URL('./', document.baseURI);
  url.hash = formatRoute({ kind: 'job-console', jobId, view });
  return url.toString();
}

export function JobConsoleView({
  api, jobId, job, view: requestedView = 'live', onViewChange, minimized = false, onRestore,
  onClose, onMinimize, onMaximize, maximized = false, onOpenNewTab, compact = false, showSwitcher = true,
}: JobConsoleViewProps) {
  const stream = useJobEventStream(api, jobId);
  const [localView, setLocalView] = useState<JobConsoleViewMode>(requestedView);
  const view = onViewChange ? requestedView : localView;
  const [liveSearch, setLiveSearch] = useState('');
  const [eventSearch, setEventSearch] = useState('');
  const [eventTypeFilter, setEventTypeFilter] = useState<string[]>([...EVENT_TYPES]);
  const [liveAutoScroll, setLiveAutoScroll] = useState(true);
  const [eventsAutoScroll, setEventsAutoScroll] = useState(true);
  const [liveScrollPaused, setLiveScrollPaused] = useState(false);
  const [eventsScrollPaused, setEventsScrollPaused] = useState(false);
  const [consoleFloor, setConsoleFloor] = useState(0);
  const [consoleHeight, setConsoleHeight] = useState(CONSOLE_DEFAULT_HEIGHT);
  const [localMaximized, setLocalMaximized] = useState(false);
  const [eventsExpanded, setEventsExpanded] = useState(true);
  const [eventOpen, setEventOpen] = useState<Record<string, boolean>>({});
  const [copyStatus, setCopyStatus] = useState('');
  const consoleRef = useRef<HTMLPreElement>(null);
  const eventsRef = useRef<HTMLOListElement>(null);
  const events = useMemo(() => chronologicalEvents(stream.events), [stream.events]);
  const liveLines = useMemo(() => {
    const query = liveSearch.trim().toLocaleLowerCase();
    return events.filter(event => event.seq > consoleFloor).map(consoleText).filter(line => line && (!query || line.toLocaleLowerCase().includes(query)));
  }, [events, liveSearch, consoleFloor]);
  const visibleEvents = useMemo(() => {
    const query = eventSearch.trim().toLocaleLowerCase();
    return events.filter(event => (eventTypeFilter.length === EVENT_TYPES.length || eventTypeFilter.includes(event.type))
      && (!query || `${event.type} ${event.id || ''} ${displayValue(event.data)}`.toLocaleLowerCase().includes(query)));
  }, [events, eventSearch, eventTypeFilter]);
  const historyTruncated = stream.journalTruncated || events.some(eventJournalTruncated);
  const browserTruncated = stream.events.length >= MAX_EVENTS;
  const journalEntryTruncated = events.some(event => Boolean(event.data && typeof event.data === 'object' && !Array.isArray(event.data) && 'journalLimit' in event.data));
  const protocolEntryTruncated = events.some(event => Boolean(event.data && typeof event.data === 'object' && !Array.isArray(event.data) && 'protocolLimit' in event.data));

  useEffect(() => {
    if (view !== 'live' || !liveAutoScroll || liveScrollPaused) return;
    if (consoleRef.current) consoleRef.current.scrollTop = consoleRef.current.scrollHeight;
  }, [view, liveAutoScroll, liveScrollPaused, liveLines.length, maximized, localMaximized, consoleHeight]);
  useEffect(() => {
    if (view !== 'events' || !eventsAutoScroll || eventsScrollPaused) return;
    if (eventsRef.current) eventsRef.current.scrollTop = eventsRef.current.scrollHeight;
  }, [view, eventsAutoScroll, eventsScrollPaused, visibleEvents.length]);

  function selectView(next: JobConsoleViewMode) {
    if (onViewChange) onViewChange(next); else setLocalView(next);
  }
  async function copyVisible() {
    const value = view === 'live' ? liveLines.join('\n') : visibleEvents.map(event => displayValue(event.data)).join('\n');
    try { await navigator.clipboard.writeText(value); setCopyStatus(`${view === 'live' ? liveLines.length : visibleEvents.length} ${view === 'live' ? 'lines' : 'events'} copied.`); }
    catch { setCopyStatus('Clipboard access is unavailable.'); }
  }
  function onTabsKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const next = event.key === 'Home' ? 'live' : event.key === 'End' ? 'events' : view === 'live' ? 'events' : 'live';
    selectView(next);
    document.getElementById(`job-console-tab-${next}`)?.focus();
  }
  function toggleType(type: string, checked: boolean) {
    setEventTypeFilter(current => checked ? [...new Set([...current, type])] : current.filter(item => item !== type));
  }
  function consoleMaxHeight() { return typeof window === 'undefined' ? CONSOLE_MAX_HEIGHT : Math.max(CONSOLE_MIN_HEIGHT, Math.min(CONSOLE_MAX_HEIGHT, Math.floor(window.innerHeight * .7))); }
  function resizeConsole(event: PointerEvent<HTMLDivElement>) {
    if (event.button !== 0) return;
    const origin = { y: event.clientY, height: consoleHeight };
    const target = event.currentTarget;
    try { target.setPointerCapture(event.pointerId); } catch { /* Pointer capture is optional. */ }
    const move = (next: globalThis.PointerEvent) => setConsoleHeight(Math.min(consoleMaxHeight(), Math.max(CONSOLE_MIN_HEIGHT, Math.round(origin.height + next.clientY - origin.y))));
    const stop = () => { target.removeEventListener('pointermove', move); target.removeEventListener('pointerup', stop); target.removeEventListener('pointercancel', stop); };
    target.addEventListener('pointermove', move); target.addEventListener('pointerup', stop); target.addEventListener('pointercancel', stop);
  }
  function resizeConsoleByKeyboard(event: KeyboardEvent<HTMLDivElement>) {
    const step = event.shiftKey ? 48 : 24;
    if (event.key === 'ArrowDown') setConsoleHeight(value => Math.min(consoleMaxHeight(), value + step));
    else if (event.key === 'ArrowUp') setConsoleHeight(value => Math.max(CONSOLE_MIN_HEIGHT, value - step));
    else if (event.key === 'Home') setConsoleHeight(CONSOLE_MIN_HEIGHT);
    else if (event.key === 'End') setConsoleHeight(consoleMaxHeight());
    else return;
    event.preventDefault();
  }

  if (minimized) return <button type="button" className="att-console-chip" aria-label={`Restore console for Job ${jobId}`} onClick={onRestore}>
    <strong>Console · {jobId}</strong><span>{job?.status || 'Unknown'} · SSE {stream.connection}</span>
  </button>;

  return <section className={'att-job-console-view' + (compact ? ' att-job-console-compact' : '')} aria-label={`Job ${jobId} console`}>
    <header className="att-console-view-heading">
      <div><p className="att-eyebrow">{executionType(job)} console</p><h2>Job {jobId}</h2>
        <p className="att-console-meta"><span>{job?.status || 'Status unavailable'}</span><span role="status">SSE {stream.connection}</span></p></div>
      <div className="att-console-view-actions">
        <a href={formatRoute({ kind: 'jobs', jobId })}>Open full Job details</a>
        {onMinimize && <button type="button" aria-label="Minimize Job console" title="Minimize" onClick={onMinimize}>−</button>}
        <button type="button" aria-label={(onMaximize ? maximized : localMaximized) ? 'Restore Job console' : 'Maximize Job console'} title={(onMaximize ? maximized : localMaximized) ? 'Restore' : 'Maximize'} onClick={onMaximize || (() => setLocalMaximized(value => !value))}>{(onMaximize ? maximized : localMaximized) ? 'Restore' : 'Maximize'}</button>
        <a href={consoleRouteUrl(jobId, view)} target="_blank" rel="noopener noreferrer" aria-label="Open Job console in a new tab" onClick={onOpenNewTab}>↗ New tab</a>
        {onClose && <button type="button" aria-label="Close Job console" title="Close" onClick={onClose}>×</button>}
      </div>
    </header>
    {stream.connection === 'reconnecting' && <p className="att-help" role="status">The SSE stream is reconnecting. The Server may be at its concurrent observer limit; the browser will retry with its last event ID.</p>}
    {showSwitcher && <div className="att-console-switcher" role="tablist" aria-label="Console view" onKeyDown={onTabsKeyDown}>
      {(['live', 'events'] as const).map(item => <button key={item} id={`job-console-tab-${item}`} type="button" role="tab" aria-selected={view === item} tabIndex={view === item ? 0 : -1} onClick={() => selectView(item)}>{item === 'live' ? 'Live Output' : 'Events'}</button>)}
    </div>}
    <div className="att-event-controls" aria-label={`${view === 'live' ? 'Live output' : 'Event'} controls`}>
      {view === 'events' && <fieldset className="att-event-type-filters"><legend>Filter event type</legend>{EVENT_TYPES.map(type => <label key={type}><input type="checkbox" aria-label={type} checked={eventTypeFilter.includes(type)} onChange={event => toggleType(type, event.currentTarget.checked)} /> {type}</label>)}</fieldset>}
      <label>{view === 'live' ? 'Search output' : 'Search events'}<input aria-label={view === 'live' ? 'Search output' : 'Search events'} type="search" value={view === 'live' ? liveSearch : eventSearch} onChange={event => view === 'live' ? setLiveSearch(event.currentTarget.value) : setEventSearch(event.currentTarget.value)} placeholder={view === 'live' ? 'Search console lines' : 'Search event JSON'} /></label>
      <label><input type="checkbox" aria-label={`${view === 'live' ? 'Live output' : 'Events'} auto-scroll`} checked={view === 'live' ? liveAutoScroll : eventsAutoScroll} onChange={event => view === 'live' ? setLiveAutoScroll(event.currentTarget.checked) : setEventsAutoScroll(event.currentTarget.checked)} /> Auto-scroll</label>
      <button type="button" aria-pressed={view === 'live' ? liveScrollPaused : eventsScrollPaused} onClick={() => view === 'live' ? setLiveScrollPaused(value => !value) : setEventsScrollPaused(value => !value)}>{(view === 'live' ? liveScrollPaused : eventsScrollPaused) ? 'Resume auto-scroll' : 'Pause auto-scroll'}</button>
      <button type="button" onClick={() => void copyVisible()} disabled={view === 'live' ? !liveLines.length : !visibleEvents.length}>Copy {view === 'live' ? 'Output' : 'Events'}</button>
      {view === 'live' && <button type="button" onClick={() => setConsoleFloor(events.reduce((floor, event) => Math.max(floor, event.seq), consoleFloor))}>Clear view</button>}
      {view === 'events' && <><label><input type="checkbox" aria-label="All Events" checked={eventTypeFilter.length === EVENT_TYPES.length} onChange={event => setEventTypeFilter(event.currentTarget.checked ? [...EVENT_TYPES] : [])} /> All Events</label><button type="button" onClick={() => { setEventsExpanded(true); setEventOpen({}); }}>Expand all</button><button type="button" onClick={() => { setEventsExpanded(false); setEventOpen({}); setEventsScrollPaused(true); }}>Collapse all</button></>}
    </div>
    {copyStatus && <p role="status">{copyStatus}</p>}
    {journalEntryTruncated && <p role="note">Some event payload fields were truncated by the journal byte budget. A preserved console preview can still be complete; [truncated by journal] marks a shortened console entry.</p>}
    {protocolEntryTruncated && <p role="note">Some event payload fields were truncated by the Worker protocol limit. Inspect artifacts for retained full details.</p>}
    {(historyTruncated || browserTruncated) && <p role="note">{historyTruncated && <span>Earlier journal events are no longer retained.</span>}{historyTruncated && browserTruncated && ' '}{browserTruncated && <span>This browser keeps the latest {MAX_EVENTS} events.</span>}</p>}
    {view === 'live' ? <>
      <p className="att-help">Live Output shows Server console lines. ANSI control sequences are removed safely for display.</p>
      {liveLines.length ? <div className="att-console-shell" data-maximized={(onMaximize ? maximized : localMaximized) ? 'true' : 'false'} style={(onMaximize ? maximized : localMaximized) ? { position: 'fixed', top: 12, left: 12, right: 12, bottom: 12, height: 'auto', maxHeight: 'none', zIndex: 50 } : { height: consoleHeight }}>
        {(!onMaximize && localMaximized) && <div className="att-console-maximized-bar"><button type="button" onClick={() => setLocalMaximized(false)}>Restore</button></div>}
        <pre ref={consoleRef} className="att-console" aria-label="Live output" data-appearance="terminal" style={{ background: '#0d1117', color: '#f5f7fa', whiteSpace: 'pre', overflow: 'auto' }}>{liveLines.join('\n')}</pre>
        {!(onMaximize ? maximized : localMaximized) && <div role="separator" aria-orientation="horizontal" aria-label="Resize live output" aria-valuemin={CONSOLE_MIN_HEIGHT} aria-valuemax={consoleMaxHeight()} aria-valuenow={consoleHeight} tabIndex={0} className="att-console-resize" onPointerDown={resizeConsole} onKeyDown={resizeConsoleByKeyboard} />}
      </div> : <p role="status">{events.length ? (liveSearch.trim() ? 'No console lines match the current search.' : 'This Job has no console lines. Structured events remain on the Events tab.') : stream.connection === 'completed' ? 'The Job has finished and no console lines were recorded.' : stream.connection === 'reconnecting' ? 'Reconnecting to live output; no recent console lines are available.' : 'Connected; no console lines have been emitted yet.'}</p>}
    </> : visibleEvents.length ? <ol ref={eventsRef} className="att-job-events" aria-label="Structured job events" onScroll={() => { if (!eventsAutoScroll) return; const list = eventsRef.current; if (list && list.scrollHeight - list.scrollTop - list.clientHeight > 32) setEventsScrollPaused(true); }}>{visibleEvents.map((event, index) => {
      const key = event.id || `${event.seq}-${event.type}`;
      const expanded = eventOpen[key] ?? eventsExpanded;
      const timestamp = eventTimestamp(event);
      return <li key={key}><details open={expanded}><summary onClick={click => { click.preventDefault(); setEventOpen(current => ({ ...current, [key]: !expanded })); if (index !== visibleEvents.length - 1) setEventsScrollPaused(true); }}><strong>{event.type}</strong>{event.id && <code>#{event.id}</code>}{timestamp && <time dateTime={timestamp}>{dateTime(timestamp)}</time>}{eventPreview(event) && <span className="att-event-preview">{eventPreview(event)}</span>}</summary><EventPayload event={event} expanded={expanded} /></details></li>;
    })}</ol> : <p role="status">{events.length ? 'No events match the current filters.' : 'No SSE events have been returned.'}</p>}
  </section>;
}
