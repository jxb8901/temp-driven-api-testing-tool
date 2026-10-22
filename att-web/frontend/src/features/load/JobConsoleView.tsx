import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent, type MouseEvent, type PointerEvent } from 'react';
import { formatRoute } from '../../router';
import type { JobEvent, JobView, LoadJobsApi } from './types';
import { MAX_EVENTS, useJobEventStream, type JobStreamEvent } from './useJobEventStream';
import './JobConsoleView.css';

export type JobConsoleViewMode = 'live' | 'events';

const EVENT_TYPES = ['status', 'progress', 'log', 'diagnostic', 'result'] as const;
const MAX_LINE_LENGTH = 4000;
const CONSOLE_MIN_HEIGHT = 192;
const CONSOLE_DEFAULT_HEIGHT = 384;
const CONSOLE_MAX_HEIGHT = 960;
const EVENT_ESTIMATED_HEIGHT = 112;
const EVENT_OVERSCAN = 5;
const CONSOLE_DATE_TIME_FORMATTER = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' });
const TERMINAL_JOB_STATUSES = new Set(['PASS', 'FAIL', 'ERROR', 'INVALID', 'CANCELLED']);

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

function eventPreview(event: JobEvent): string {
  const data = event.data;
  if (data === null || data === undefined) return '';
  if (typeof data !== 'object' || Array.isArray(data)) return typeof data === 'string' ? data : String(data);
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

function statusFromEvent(event: JobStreamEvent): string | null {
  if (event.type !== 'status' && event.type !== 'result') return null;
  const payload = event.data && typeof event.data === 'object' && !Array.isArray(event.data)
    ? (event.data as Record<string, unknown>).status : event.data;
  return typeof payload === 'string' && payload ? payload : null;
}

function projectJobStatus(events: readonly JobStreamEvent[], retainedTerminalStatus: string | null): { status: string | null; terminalStatus: string | null } {
  let status = retainedTerminalStatus;
  let terminalStatus = retainedTerminalStatus;
  for (const event of events) {
    if (terminalStatus) continue;
    const candidate = statusFromEvent(event);
    if (!candidate || (event.type === 'result' && !TERMINAL_JOB_STATUSES.has(candidate))) continue;
    status = candidate;
    if (TERMINAL_JOB_STATUSES.has(candidate)) terminalStatus = candidate;
  }
  return { status: terminalStatus || status, terminalStatus };
}

function dateTime(value: string): string {
  const time = Date.parse(value);
  return Number.isFinite(time) ? CONSOLE_DATE_TIME_FORMATTER.format(time) : value;
}

function streamDiagnostics(duplicatesIgnored: number, staleIgnored: number, gaps: number): string {
  const parts: string[] = [];
  const ignored: string[] = [];
  if (duplicatesIgnored) ignored.push(`${duplicatesIgnored} duplicate`);
  if (staleIgnored) ignored.push(`${staleIgnored} out-of-order`);
  const ignoredTotal = duplicatesIgnored + staleIgnored;
  if (ignored.length) parts.push(`${ignored.join(' and ')} replayed event${ignoredTotal === 1 ? '' : 's'} ignored`);
  if (gaps) parts.push(`${gaps} ID gap${gaps === 1 ? '' : 's'}`);
  return parts.length ? `Stream diagnostics: ${parts.join('; ')}.` : '';
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

function eventSearchText(event: JobStreamEvent): string {
  let data = '';
  if (typeof event.data === 'string') data = event.data;
  else {
    try { data = JSON.stringify(event.data); }
    catch { data = String(event.data); }
  }
  return `${event.type} ${event.id || ''} ${data}`.toLocaleLowerCase();
}

function VirtualSpacer({ height, className }: { height: number; className: string }) {
  const ref = useRef<HTMLLIElement>(null);
  useLayoutEffect(() => {
    ref.current?.style.setProperty('height', `${Math.max(0, height)}px`);
  }, [height]);
  return <li ref={ref} className={className} aria-hidden="true" />;
}

const EventRow = memo(function EventRow({ event, index, total, expanded, onToggle, onKeyDown, onHeight }: {
  event: JobStreamEvent;
  index: number;
  total: number;
  expanded: boolean;
  onToggle: (event: MouseEvent<HTMLElement>, index: number, key: string, expanded: boolean) => void;
  onKeyDown: (event: KeyboardEvent<HTMLElement>, index: number) => void;
  onHeight: (key: string, height: number) => void;
}) {
  const key = event.id || `${event.seq}-${event.type}`;
  const ref = useRef<HTMLLIElement>(null);
  const preview = useMemo(() => eventPreview(event), [event]);
  const timestamp = eventTimestamp(event);
  useLayoutEffect(() => {
    const height = ref.current?.getBoundingClientRect().height || ref.current?.offsetHeight || 0;
    if (height > 0) onHeight(key, height);
  }, [key, expanded, onHeight]);
  return <li ref={ref} key={key} value={index + 1} aria-setsize={total} aria-posinset={index + 1}>
    <details open={expanded}>
      <summary data-event-index={index} tabIndex={0} onKeyDown={event => onKeyDown(event, index)} onClick={event => onToggle(event, index, key, expanded)}>
        <strong>{event.type}</strong>{event.id && <code>#{event.id}</code>}{timestamp && <time dateTime={timestamp}>{dateTime(timestamp)}</time>}{preview && <span className="att-event-preview">{preview}</span>}
      </summary>
      <EventPayload event={event} expanded={expanded} />
    </details>
  </li>;
});

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
  const eventsAtBottomRef = useRef(true);
  const liveAtBottomRef = useRef(true);
  const [eventsScrollTop, setEventsScrollTop] = useState(0);
  const [eventsViewportHeight, setEventsViewportHeight] = useState(576);
  const [eventHeights, setEventHeights] = useState<Map<string, number>>(() => new Map());
  // The shared store already exposes authoritative Server journal append order.
  // Never timestamp-sort or repair stale IDs in this presentation layer.
  const events = stream.events;
  const terminalStatuses = useRef(new Map<string, string>());
  const statusProjection = useMemo(
    () => projectJobStatus(events, terminalStatuses.current.get(jobId) || null),
    [events, jobId],
  );
  useEffect(() => {
    if (statusProjection.terminalStatus) terminalStatuses.current.set(jobId, statusProjection.terminalStatus);
  }, [jobId, statusProjection.terminalStatus]);
  const displayedStatus = statusProjection.status || job?.status || 'Unknown';
  const liveLines = useMemo(() => {
    const query = liveSearch.trim().toLocaleLowerCase();
    return events.filter(event => event.seq > consoleFloor).map(event => event.consoleText).filter(line => line && (!query || line.toLocaleLowerCase().includes(query)));
  }, [events, liveSearch, consoleFloor]);
  const visibleEvents = useMemo(() => {
    const query = eventSearch.trim().toLocaleLowerCase();
    return events.filter(event => (eventTypeFilter.length === EVENT_TYPES.length || eventTypeFilter.includes(event.type))
      && (!query || eventSearchText(event).includes(query)));
  }, [events, eventSearch, eventTypeFilter]);
  const eventMetrics = useMemo(() => {
    const offsets = [0];
    for (const event of visibleEvents) {
      const key = event.id || `${event.seq}-${event.type}`;
      offsets.push(offsets[offsets.length - 1] + (eventHeights.get(key) || EVENT_ESTIMATED_HEIGHT));
    }
    const totalHeight = offsets[offsets.length - 1] || 0;
    let start = 0;
    while (start < visibleEvents.length && offsets[start + 1] < eventsScrollTop) start += 1;
    let end = start;
    const bottom = eventsScrollTop + eventsViewportHeight;
    while (end < visibleEvents.length && offsets[end] < bottom) end += 1;
    return {
      offsets,
      totalHeight,
      start: Math.max(0, start - EVENT_OVERSCAN),
      end: Math.min(visibleEvents.length, end + EVENT_OVERSCAN),
    };
  }, [visibleEvents, eventHeights, eventsScrollTop, eventsViewportHeight]);
  const virtualEvents = visibleEvents.slice(eventMetrics.start, eventMetrics.end);
  const historyTruncated = stream.journalTruncated || events.some(eventJournalTruncated);
  const browserTruncated = stream.events.length >= MAX_EVENTS;
  const journalEntryTruncated = events.some(event => Boolean(event.data && typeof event.data === 'object' && !Array.isArray(event.data) && 'journalLimit' in event.data));
  const protocolEntryTruncated = events.some(event => Boolean(event.data && typeof event.data === 'object' && !Array.isArray(event.data) && 'protocolLimit' in event.data));
  const diagnostics = streamDiagnostics(stream.duplicatesIgnored, stream.staleIgnored, stream.gaps);

  useEffect(() => {
    if (view !== 'live' || !liveAutoScroll || liveScrollPaused) return;
    const console = consoleRef.current;
    if (!console) return;
    const distance = console.scrollHeight - console.scrollTop - console.clientHeight;
    if (!liveAtBottomRef.current && distance > 32) {
      setLiveScrollPaused(true);
      return;
    }
    console.scrollTop = console.scrollHeight;
    liveAtBottomRef.current = true;
  }, [view, liveAutoScroll, liveScrollPaused, liveLines.length, maximized, localMaximized, consoleHeight]);
  useEffect(() => {
    if (view !== 'events' || !eventsAutoScroll || eventsScrollPaused) return;
    const list = eventsRef.current;
    if (!list) return;
    const distance = list.scrollHeight - list.scrollTop - list.clientHeight;
    if (!eventsAtBottomRef.current && distance > 32) {
      setEventsScrollPaused(true);
      return;
    }
    list.scrollTop = list.scrollHeight;
    eventsAtBottomRef.current = true;
  }, [view, eventsAutoScroll, eventsScrollPaused, visibleEvents.length]);
  useEffect(() => {
    if (view !== 'events') return;
    const list = eventsRef.current;
    if (list) setEventsViewportHeight(list.clientHeight || 576);
  }, [view, visibleEvents.length]);
  useEffect(() => {
    setEventsScrollTop(0);
    eventsAtBottomRef.current = true;
    if (eventsRef.current) eventsRef.current.scrollTop = 0;
  }, [eventSearch, eventTypeFilter]);

  const onEventHeight = useCallback((key: string, height: number) => {
    setEventHeights(current => {
      if (current.get(key) === height) return current;
      const next = new Map(current).set(key, height);
      while (next.size > MAX_EVENTS) next.delete(next.keys().next().value!);
      return next;
    });
  }, []);
  const onEventsScroll = useCallback(() => {
    const list = eventsRef.current;
    if (!list) return;
    const distance = list.scrollHeight - list.scrollTop - list.clientHeight;
    eventsAtBottomRef.current = distance <= 32;
    setEventsScrollTop(list.scrollTop);
    if (eventsAutoScroll && distance > 32) setEventsScrollPaused(true);
  }, [eventsAutoScroll]);
  const onLiveScroll = useCallback(() => {
    const console = consoleRef.current;
    if (!console) return;
    const distance = console.scrollHeight - console.scrollTop - console.clientHeight;
    liveAtBottomRef.current = distance <= 32;
    if (liveAutoScroll && distance > 32) setLiveScrollPaused(true);
  }, [liveAutoScroll]);
  function resumeEventsAutoScroll() {
    const list = eventsRef.current;
    if (list) list.scrollTop = list.scrollHeight;
    eventsAtBottomRef.current = true;
    setEventsScrollPaused(false);
  }

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

  const onEventToggle = useCallback((event: MouseEvent<HTMLElement>, index: number, key: string, expanded: boolean) => {
    event.preventDefault();
    setEventOpen(current => ({ ...current, [key]: !expanded }));
    if (index !== visibleEvents.length - 1) setEventsScrollPaused(true);
  }, [visibleEvents.length]);

  const onEventKeyDown = useCallback((event: KeyboardEvent<HTMLElement>, index: number) => {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    event.preventDefault();
    const nextIndex = Math.max(0, Math.min(visibleEvents.length - 1, index + (event.key === 'ArrowDown' ? 1 : -1)));
    const target = eventsRef.current?.querySelector<HTMLElement>(`[data-event-index="${nextIndex}"]`);
    if (target) { target.focus(); return; }
    const list = eventsRef.current;
    if (!list) return;
    const offset = eventMetrics.offsets[nextIndex] || 0;
    list.scrollTop = offset;
    setEventsScrollTop(offset);
    window.requestAnimationFrame(() => eventsRef.current?.querySelector<HTMLElement>(`[data-event-index="${nextIndex}"]`)?.focus());
  }, [eventMetrics.offsets, visibleEvents.length]);

  if (minimized) return <button type="button" className="att-console-chip" aria-label={`Restore console for Job ${jobId}`} onClick={onRestore}>
    <strong>Console · {jobId}</strong><span>{displayedStatus} · SSE {stream.connection}</span>
  </button>;

  return <section className={'att-job-console-view' + (compact ? ' att-job-console-compact' : '')} aria-label={`Job ${jobId} console`}>
    <header className="att-console-view-heading">
      <div><p className="att-eyebrow">{executionType(job)} console</p><h2 title={`Job ${jobId}`}>Job {jobId}</h2>
        <p className="att-console-meta"><span>{displayedStatus}</span><span role="status">SSE {stream.connection}</span></p></div>
      <div className="att-console-view-actions">
        <a href={formatRoute({ kind: 'jobs', jobId })} aria-label="Open full Job details" title="Open full Job details">Details</a>
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
      <button type="button" aria-pressed={view === 'live' ? liveScrollPaused : eventsScrollPaused} onClick={() => view === 'live' ? setLiveScrollPaused(value => !value) : eventsScrollPaused ? resumeEventsAutoScroll() : setEventsScrollPaused(true)}>{(view === 'live' ? liveScrollPaused : eventsScrollPaused) ? 'Resume auto-scroll' : 'Pause auto-scroll'}</button>
      <button type="button" onClick={() => void copyVisible()} disabled={view === 'live' ? !liveLines.length : !visibleEvents.length}>Copy {view === 'live' ? 'Output' : 'Events'}</button>
      {view === 'live' && <button type="button" onClick={() => setConsoleFloor(events.reduce((floor, event) => Math.max(floor, event.seq), consoleFloor))}>Clear view</button>}
      {view === 'events' && <><label><input type="checkbox" aria-label="All Events" checked={eventTypeFilter.length === EVENT_TYPES.length} onChange={event => setEventTypeFilter(event.currentTarget.checked ? [...EVENT_TYPES] : [])} /> All Events</label><button type="button" onClick={() => { setEventsExpanded(true); setEventOpen({}); }}>Expand all</button><button type="button" onClick={() => { setEventsExpanded(false); setEventOpen({}); setEventsScrollPaused(true); }}>Collapse all</button></>}
    </div>
    {copyStatus && <p role="status">{copyStatus}</p>}
    {journalEntryTruncated && <p role="note">Some event payload fields were truncated by the journal byte budget. A preserved console preview can still be complete; [truncated by journal] marks a shortened console entry.</p>}
    {protocolEntryTruncated && <p role="note">Some event payload fields were truncated by the Worker protocol limit. Inspect artifacts for retained full details.</p>}
    {(historyTruncated || browserTruncated) && <p role="note">{historyTruncated && <span>Earlier journal events are no longer retained.</span>}{historyTruncated && browserTruncated && ' '}{browserTruncated && <span>This browser keeps the latest {MAX_EVENTS} events.</span>}</p>}
    {diagnostics && <p className="att-help">{diagnostics}</p>}
    {view === 'live' ? <>
      <p className="att-help">Live Output shows Server console lines. ANSI control sequences are removed safely for display.</p>
      {liveLines.length ? <div className="att-console-shell" data-maximized={(onMaximize ? maximized : localMaximized) ? 'true' : 'false'} style={!onMaximize && localMaximized ? { position: 'fixed', top: 12, left: 12, right: 12, bottom: 12, height: 'auto', maxHeight: 'none', zIndex: 50 } : { height: consoleHeight }}>
        {(!onMaximize && localMaximized) && <div className="att-console-maximized-bar"><button type="button" onClick={() => setLocalMaximized(false)}>Restore</button></div>}
        <pre ref={consoleRef} className="att-console" aria-label="Live output" data-appearance="terminal" onScroll={onLiveScroll} style={{ background: '#0d1117', color: '#f5f7fa', whiteSpace: 'pre', overflow: 'auto' }}>{liveLines.join('\n')}</pre>
        {!(onMaximize ? maximized : localMaximized) && <div role="separator" aria-orientation="horizontal" aria-label="Resize live output" aria-valuemin={CONSOLE_MIN_HEIGHT} aria-valuemax={consoleMaxHeight()} aria-valuenow={consoleHeight} tabIndex={0} className="att-console-resize" onPointerDown={resizeConsole} onKeyDown={resizeConsoleByKeyboard} />}
      </div> : <p role="status">{events.length ? (liveSearch.trim() ? 'No console lines match the current search.' : 'This Job has no console lines. Structured events remain on the Events tab.') : stream.connection === 'completed' ? 'The Job has finished and no console lines were recorded.' : stream.connection === 'reconnecting' ? 'Reconnecting to live output; no recent console lines are available.' : 'Connected; no console lines have been emitted yet.'}</p>}
    </> : visibleEvents.length ? <ol ref={eventsRef} className="att-job-events" aria-label="Structured job events" onScroll={onEventsScroll}>
      <VirtualSpacer height={eventMetrics.offsets[eventMetrics.start]} className="att-event-virtual-spacer" />
      {virtualEvents.map((event, offset) => {
        const index = eventMetrics.start + offset;
        const key = event.id || `${event.seq}-${event.type}`;
        return <EventRow key={key} event={event} index={index} total={visibleEvents.length} expanded={eventOpen[key] ?? eventsExpanded}
          onToggle={onEventToggle} onKeyDown={onEventKeyDown} onHeight={onEventHeight} />;
      })}
      <VirtualSpacer height={eventMetrics.totalHeight - eventMetrics.offsets[eventMetrics.end]} className="att-event-virtual-spacer" />
    </ol> : <p role="status">{events.length ? 'No events match the current filters.' : 'No SSE events have been returned.'}</p>}
  </section>;
}
