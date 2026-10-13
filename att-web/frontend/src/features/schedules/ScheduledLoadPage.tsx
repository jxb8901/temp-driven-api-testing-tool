import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { ApiError, type AsyncState, type PackageSummary } from '../../types';
import { useNavigationGuard } from '../../router';
import type { ScheduledLoadApi, LoadSchedule, ScheduleOccurrence } from './types';
import {
  createScheduleDraft, cronForDraft, draftFromSchedule, explainOccurrence,
  formatScheduleTime, isIanaTimeZone, localTimeZone, scheduleInputFromDraft, WEEKDAYS,
  type ScheduleDraft, type ScheduleRecurrence,
} from './schedule';
import './schedules.css';

type LoadState = 'loading' | 'ready' | 'error' | 'disabled';
type PreviewState = { status: 'idle' | 'loading' | 'ready' | 'error'; signature: string; nextRunAt?: string; error?: string };
type Confirmation = { action: 'run' | 'delete'; schedule: LoadSchedule };
type HistoryState = { status: 'idle' | 'loading' | 'ready' | 'error'; items: ScheduleOccurrence[]; error?: string };

export interface ScheduledLoadPageProps {
  api: ScheduledLoadApi;
  packages: AsyncState<PackageSummary[]>;
  enabled: boolean;
  onOpenJob(jobId: string): void;
}

function errorStatus(error: unknown): number {
  return error instanceof ApiError || (typeof error === 'object' && error !== null && 'status' in error)
    ? Number((error as { status?: unknown }).status || 0) : 0;
}

function errorMessage(error: unknown, operation: string): string {
  const status = errorStatus(error);
  if (status === 401) return 'Your Tomcat session is not authenticated. Sign in and retry.';
  if (status === 403) return 'Your account does not have the configured Scheduled Load administrator role.';
  if (status === 404) return 'Scheduled Load is disabled by the Server or this schedule no longer exists.';
  if (status === 409) return 'This schedule changed on the Server. Reload the schedule list before editing it again.';
  if (status === 429) return 'The Server is at its scheduled-load capacity. Review the occurrence history and retry later.';
  return error instanceof Error ? error.message : `${operation} failed. Check the connection and retry.`;
}

function recurrenceLabel(schedule: LoadSchedule): string {
  const minute = /^(\d{1,2}) (\d{1,2}) \* \* \*$/.exec(schedule.cron.trim());
  if (minute) return `Daily at ${String(Number(minute[2])).padStart(2, '0')}:${String(Number(minute[1])).padStart(2, '0')}`;
  const weekly = /^(\d{1,2}) (\d{1,2}) \* \* (1-5|0,6|[0-6])$/.exec(schedule.cron.trim());
  if (weekly) {
    const days = WEEKDAYS.find(day => day.value === weekly[3])?.label || `day ${weekly[3]}`;
    return `Weekly · ${days} at ${String(Number(weekly[2])).padStart(2, '0')}:${String(Number(weekly[1])).padStart(2, '0')}`;
  }
  return `Cron · ${schedule.cron}`;
}

function timeZoneChoices(): string[] {
  const intl = Intl as typeof Intl & { supportedValuesOf?: (key: 'timeZone') => string[] };
  const zones = intl.supportedValuesOf?.('timeZone') || [];
  return [...new Set(['UTC', localTimeZone(), ...zones])].sort((left, right) => left.localeCompare(right));
}

function statusLabel(status: string): string {
  return status.replaceAll('-', ' ').replaceAll('_', ' ');
}

export function ScheduledLoadPage({ api, packages, enabled, onOpenJob }: ScheduledLoadPageProps): React.JSX.Element {
  const navigation = useNavigationGuard();
  const [schedules, setSchedules] = useState<LoadSchedule[]>([]);
  const [loadState, setLoadState] = useState<LoadState>(enabled ? 'loading' : 'disabled');
  const [loadError, setLoadError] = useState('');
  const [notice, setNotice] = useState('');
  const [mutationError, setMutationError] = useState('');
  const [filter, setFilter] = useState('');
  const [stateFilter, setStateFilter] = useState<'all' | 'enabled' | 'paused'>('all');
  const [packageFilter, setPackageFilter] = useState('');
  const [draft, setDraft] = useState<ScheduleDraft>(() => createScheduleDraft());
  const [editingId, setEditingId] = useState('');
  const [baseline, setBaseline] = useState(() => JSON.stringify(createScheduleDraft()));
  const [preview, setPreview] = useState<PreviewState>({ status: 'idle', signature: '' });
  const [saving, setSaving] = useState(false);
  const [pendingConfirmation, setPendingConfirmation] = useState<Confirmation | null>(null);
  const [actionPending, setActionPending] = useState('');
  const [historySchedule, setHistorySchedule] = useState<LoadSchedule | null>(null);
  const [history, setHistory] = useState<HistoryState>({ status: 'idle', items: [] });
  const listController = useRef<AbortController | null>(null);
  const listSequence = useRef(0);
  const historyController = useRef<AbortController | null>(null);
  const previewController = useRef<AbortController | null>(null);
  const previewSequence = useRef(0);
  const confirmationDialog = useRef<HTMLDialogElement>(null);
  const cancelConfirmation = useRef<HTMLButtonElement>(null);
  const timezoneOptions = useMemo(timeZoneChoices, []);

  const draftSignature = JSON.stringify(draft);
  const dirty = draftSignature !== baseline;
  const cron = cronForDraft(draft);
  const previewSignature = `${draft.timezone.trim()}\n${cron}`;
  const previewIsCurrent = preview.status === 'ready' && preview.signature === previewSignature;

  const loadSchedules = useCallback(async () => {
    if (!enabled) { setLoadState('disabled'); return; }
    listController.current?.abort();
    const controller = new AbortController();
    listController.current = controller;
    const sequence = ++listSequence.current;
    setLoadState('loading');
    setLoadError('');
    try {
      const response = await api.listLoadSchedules(controller.signal);
      if (controller.signal.aborted || sequence !== listSequence.current) return;
      setSchedules(Array.isArray(response.items) ? response.items : []);
      setLoadState('ready');
    } catch (error) {
      if (controller.signal.aborted || sequence !== listSequence.current) return;
      setLoadError(errorMessage(error, 'Loading schedules'));
      setLoadState('error');
    }
  }, [api, enabled]);

  useEffect(() => {
    void loadSchedules();
    return () => {
      listController.current?.abort();
      historyController.current?.abort();
      previewController.current?.abort();
    };
  }, [loadSchedules]);

  useEffect(() => navigation.registerUnsavedChanges(dirty, 'Scheduled Load changes'), [dirty, navigation.registerUnsavedChanges]);

  useEffect(() => {
    const sequence = ++previewSequence.current;
    previewController.current?.abort();
    if (!enabled || !isIanaTimeZone(draft.timezone) || !cron) {
      setPreview({ status: 'idle', signature: previewSignature });
      return;
    }
    const controller = new AbortController();
    previewController.current = controller;
    setPreview({ status: 'loading', signature: previewSignature });
    const timer = window.setTimeout(() => {
      void api.previewLoadSchedule({ timezone: draft.timezone.trim(), cron }, controller.signal)
        .then(response => {
          if (!controller.signal.aborted && sequence === previewSequence.current) {
            setPreview({ status: 'ready', signature: previewSignature, nextRunAt: response.nextRunAt });
          }
        })
        .catch(error => {
          if (!controller.signal.aborted && sequence === previewSequence.current) {
            setPreview({ status: 'error', signature: previewSignature, error: errorMessage(error, 'Schedule preview') });
          }
        });
    }, 250);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [api, cron, draft.timezone, enabled, previewSignature]);

  useEffect(() => {
    const dialog = confirmationDialog.current;
    if (!dialog) return;
    if (pendingConfirmation && !dialog.open) {
      dialog.showModal();
      cancelConfirmation.current?.focus();
    } else if (!pendingConfirmation && dialog.open) dialog.close();
  }, [pendingConfirmation]);

  useEffect(() => {
    if (!historySchedule) {
      historyController.current?.abort();
      setHistory({ status: 'idle', items: [] });
      return;
    }
    historyController.current?.abort();
    const controller = new AbortController();
    historyController.current = controller;
    setHistory({ status: 'loading', items: [] });
    api.getLoadScheduleExecutions(historySchedule.id, 100, controller.signal)
      .then(response => { if (!controller.signal.aborted) setHistory({ status: 'ready', items: response.items || [] }); })
      .catch(error => { if (!controller.signal.aborted) setHistory({ status: 'error', items: [], error: errorMessage(error, 'Loading occurrence history') }); });
    return () => controller.abort();
  }, [api, historySchedule]);

  const visibleSchedules = useMemo(() => {
    const query = filter.trim().toLocaleLowerCase();
    return schedules.filter(schedule => {
      const matchesText = !query || [schedule.name, schedule.description, schedule.packageId, schedule.scenario, schedule.cron]
        .some(value => String(value || '').toLocaleLowerCase().includes(query));
      const matchesState = stateFilter === 'all' || (stateFilter === 'enabled' ? schedule.enabled : !schedule.enabled);
      return matchesText && matchesState && (!packageFilter || schedule.packageId === packageFilter);
    });
  }, [filter, packageFilter, schedules, stateFilter]);

  function updateDraft<K extends keyof ScheduleDraft>(key: K, value: ScheduleDraft[K]) {
    setDraft(current => ({ ...current, [key]: value }));
    setMutationError('');
    setNotice('');
  }

  function resetForm() {
    const fresh = createScheduleDraft();
    setDraft(fresh);
    setBaseline(JSON.stringify(fresh));
    setEditingId('');
    setMutationError('');
    setNotice('');
  }

  function editSchedule(schedule: LoadSchedule) {
    const next = draftFromSchedule(schedule);
    setDraft(next);
    setBaseline(JSON.stringify(next));
    setEditingId(schedule.id);
    setMutationError('');
    setNotice(`Editing ${schedule.name}.`);
    document.getElementById('schedule-editor-heading')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    document.getElementById('schedule-name')?.focus({ preventScroll: true });
  }

  async function submitSchedule(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMutationError('');
    setNotice('');
    let input;
    try { input = scheduleInputFromDraft(draft); }
    catch (error) { setMutationError(error instanceof Error ? error.message : 'Correct the schedule fields.'); return; }
    if (!previewIsCurrent) {
      setMutationError('Wait for the Server to confirm the next run before saving this schedule.');
      return;
    }
    setSaving(true);
    try {
      const saved = editingId
        ? await api.updateLoadSchedule(editingId, input)
        : await api.createLoadSchedule(input);
      resetForm();
      setNotice(`Saved ${saved.name}. Next run: ${formatScheduleTime(saved.nextRunAt, saved.timezone)} (${saved.timezone}).`);
      await loadSchedules();
    } catch (error) { setMutationError(errorMessage(error, 'Saving schedule')); }
    finally { setSaving(false); }
  }

  async function changeScheduleEnabled(schedule: LoadSchedule) {
    setActionPending(schedule.id);
    setMutationError('');
    try {
      const updated = await api.setLoadScheduleEnabled(schedule.id, !schedule.enabled);
      setSchedules(current => current.map(item => item.id === updated.id ? updated : item));
      setNotice(`${updated.name} ${updated.enabled ? 'resumed' : 'paused'}.`);
    } catch (error) { setMutationError(errorMessage(error, schedule.enabled ? 'Pausing schedule' : 'Resuming schedule')); }
    finally { setActionPending(''); }
  }

  async function confirmAction() {
    if (!pendingConfirmation) return;
    const pending = pendingConfirmation;
    setPendingConfirmation(null);
    setActionPending(pending.schedule.id);
    setMutationError('');
    setNotice('');
    try {
      if (pending.action === 'run') {
        const occurrence = await api.triggerLoadSchedule(pending.schedule.id);
        setNotice(`Run Now recorded as ${statusLabel(occurrence.status)}${occurrence.jobId ? ` · job ${occurrence.jobId}` : ''}${occurrence.reason ? ` · ${explainOccurrence(occurrence.reason)}` : ''}.`);
        setHistorySchedule(pending.schedule);
      } else {
        await api.deleteLoadSchedule(pending.schedule.id);
        setSchedules(current => current.filter(item => item.id !== pending.schedule.id));
        if (historySchedule?.id === pending.schedule.id) setHistorySchedule(null);
        if (editingId === pending.schedule.id) resetForm();
        setNotice(`Deleted ${pending.schedule.name}. Any already accepted Load jobs continue running.`);
      }
      await loadSchedules();
    } catch (error) { setMutationError(errorMessage(error, pending.action === 'run' ? 'Running schedule now' : 'Deleting schedule')); }
    finally { setActionPending(''); }
  }

  function openHistory(schedule: LoadSchedule) {
    setHistorySchedule(schedule);
    setNotice(`Showing the latest 100 occurrences for ${schedule.name}.`);
    window.setTimeout(() => document.getElementById('schedule-history-heading')?.focus(), 0);
  }

  const packageItems = packages.status === 'ready' ? packages.data : [];

  if (!enabled) return <section className="page-content att-schedules-page" aria-labelledby="schedules-title">
    <div className="page-heading"><div><p className="eyebrow">ATT Studio</p><h1 id="schedules-title">Scheduled Load</h1></div></div>
    <p className="att-schedule-callout" role="status">Scheduled Load is disabled by Server configuration. Ask the Server administrator to enable scheduling.</p>
  </section>;

  return <section className="page-content att-schedules-page" aria-labelledby="schedules-title">
    <header className="page-heading att-schedules-heading">
      <div><p className="eyebrow">Load testing</p><h1 id="schedules-title">Scheduled Load</h1>
        <p className="page-description">Manage recurring Load scenarios and inspect the Server's recorded occurrences.</p>
      </div>
      <button className="button button-primary" type="button" onClick={() => {
        if (dirty) { setMutationError('Save or clear the current schedule draft before starting another.'); return; }
        resetForm(); document.getElementById('schedule-editor-heading')?.focus();
      }}>Create schedule</button>
    </header>

    <p className="att-schedule-callout">The Server computes next-run previews using the selected IANA timezone. During spring-forward, a local time that does not exist is skipped. During fall-back, repeated local times can produce two instants; the Server enforces its minimum interval. Review the preview before enabling a schedule.</p>
    <p className="att-schedule-callout att-schedule-callout-warning">Missed runs are recorded as skipped and are not replayed after a restart or late poll. If the schedule's overlap limit or the Server's global scheduled-load capacity is reached, the occurrence is skipped, not queued. Run Now is confirmed and subject to Server rate and capacity limits.</p>

    <section className="att-schedule-panel" aria-labelledby="schedule-list-heading">
      <div className="att-schedule-section-heading">
        <div><h2 id="schedule-list-heading">Schedules</h2><p>Times are displayed in each schedule's timezone.</p></div>
        <button className="button button-quiet" type="button" onClick={() => void loadSchedules()} disabled={loadState === 'loading'}>Refresh schedules</button>
      </div>
      <div className="att-schedule-filters" role="group" aria-label="Filter schedules">
        <label>Search schedules
          <input type="search" value={filter} onChange={event => setFilter(event.currentTarget.value)} placeholder="Name, package, scenario or cron" />
        </label>
        <label>Status
          <select value={stateFilter} onChange={event => setStateFilter(event.currentTarget.value as typeof stateFilter)}>
            <option value="all">All schedules</option><option value="enabled">Enabled</option><option value="paused">Paused</option>
          </select>
        </label>
        <label>Package
          <select value={packageFilter} onChange={event => setPackageFilter(event.currentTarget.value)}>
            <option value="">All packages</option>
            {Array.from(new Set(schedules.map(item => item.packageId))).sort().map(packageId => <option key={packageId} value={packageId}>{packageId}</option>)}
          </select>
        </label>
      </div>
      <p className="att-schedule-status" role="status" aria-live="polite" aria-busy={loadState === 'loading'}>
        {loadState === 'loading' ? 'Loading schedules…' : loadState === 'ready' ? `${visibleSchedules.length} of ${schedules.length} schedules shown.` : loadState === 'error' ? loadError : ''}
      </p>
      {loadState === 'error' && <button className="button button-quiet" type="button" onClick={() => void loadSchedules()}>Retry schedule list</button>}
      {loadState === 'ready' && schedules.length === 0 && <p className="att-schedule-empty">No schedules have been created. Use Create schedule to add the first one.</p>}
      {loadState === 'ready' && schedules.length > 0 && visibleSchedules.length === 0 && <p className="att-schedule-empty">No schedules match these filters.</p>}
      {visibleSchedules.length > 0 && <>
        <div className="att-schedule-table-wrap">
          <table className="att-schedule-table">
            <thead><tr><th scope="col">Name</th><th scope="col">Package and scenario</th><th scope="col">Recurrence</th><th scope="col">Next run</th><th scope="col">Status</th><th scope="col">Actions</th></tr></thead>
            <tbody>{visibleSchedules.map(schedule => <tr key={schedule.id}>
              <th scope="row" data-label="Name"><span>{schedule.name}</span>{schedule.description && <small>{schedule.description}</small>}</th>
              <td data-label="Package and scenario"><span>{schedule.packageId}</span><small>{schedule.scenario}{schedule.config ? ` · ${schedule.config}` : ''}</small></td>
              <td data-label="Recurrence"><span>{recurrenceLabel(schedule)}</span><small>{schedule.timezone}</small></td>
              <td data-label="Next run">{formatScheduleTime(schedule.nextRunAt, schedule.timezone)}</td>
              <td data-label="Status"><span className={`att-schedule-state ${schedule.enabled ? 'is-enabled' : 'is-paused'}`}><span aria-hidden="true">{schedule.enabled ? '●' : '■'}</span> {schedule.enabled ? 'Enabled' : 'Paused'}</span></td>
              <td data-label="Actions"><div className="att-schedule-actions">
                <button type="button" onClick={() => openHistory(schedule)} aria-label={`History ${schedule.name}`}>History</button>
                <button type="button" onClick={() => editSchedule(schedule)} aria-label={`Edit ${schedule.name}`}>Edit</button>
                <button type="button" onClick={() => void changeScheduleEnabled(schedule)} disabled={actionPending === schedule.id} aria-label={`${schedule.enabled ? 'Pause' : 'Resume'} ${schedule.name}`}>{schedule.enabled ? 'Pause' : 'Resume'}</button>
                <button type="button" onClick={() => setPendingConfirmation({ action: 'run', schedule })} disabled={actionPending === schedule.id} aria-label={`Run now ${schedule.name}`}>Run now</button>
                <button type="button" className="att-schedule-delete" onClick={() => setPendingConfirmation({ action: 'delete', schedule })} disabled={actionPending === schedule.id} aria-label={`Delete ${schedule.name}`}>Delete</button>
              </div></td>
            </tr>)}</tbody>
          </table>
        </div>
        <section className="att-schedule-cards" aria-label="Schedule list">
          {visibleSchedules.map(schedule => <article className="att-schedule-card" key={schedule.id}>
            <div className="att-schedule-card-heading"><h3>{schedule.name}</h3><span className={`att-schedule-state ${schedule.enabled ? 'is-enabled' : 'is-paused'}`}>{schedule.enabled ? '● Enabled' : '■ Paused'}</span></div>
            {schedule.description && <p>{schedule.description}</p>}
            <dl><div><dt>Package</dt><dd>{schedule.packageId}</dd></div><div><dt>Scenario</dt><dd>{schedule.scenario}</dd></div>
              <div><dt>Recurrence</dt><dd>{recurrenceLabel(schedule)}</dd></div><div><dt>Next run · {schedule.timezone}</dt><dd>{formatScheduleTime(schedule.nextRunAt, schedule.timezone)}</dd></div></dl>
            <div className="att-schedule-actions">
              <button type="button" onClick={() => openHistory(schedule)} aria-label={`History ${schedule.name}`}>History</button>
              <button type="button" onClick={() => editSchedule(schedule)} aria-label={`Edit ${schedule.name}`}>Edit</button>
              <button type="button" onClick={() => void changeScheduleEnabled(schedule)} disabled={actionPending === schedule.id} aria-label={`${schedule.enabled ? 'Pause' : 'Resume'} ${schedule.name}`}>{schedule.enabled ? 'Pause' : 'Resume'}</button>
              <button type="button" onClick={() => setPendingConfirmation({ action: 'run', schedule })} disabled={actionPending === schedule.id} aria-label={`Run now ${schedule.name}`}>Run now</button>
              <button type="button" className="att-schedule-delete" onClick={() => setPendingConfirmation({ action: 'delete', schedule })} disabled={actionPending === schedule.id} aria-label={`Delete ${schedule.name}`}>Delete</button>
            </div>
          </article>)}
        </section>
      </>}
    </section>

    {historySchedule && <section className="att-schedule-panel att-schedule-history" aria-labelledby="schedule-history-heading">
      <div className="att-schedule-section-heading"><div><h2 id="schedule-history-heading" tabIndex={-1}>Execution history · {historySchedule.name}</h2><p>Latest 100 occurrences. Skips and submission failures remain visible even when no job was created.</p></div>
        <button className="button button-quiet" type="button" onClick={() => setHistorySchedule(null)}>Close history</button>
      </div>
      {history.status === 'loading' && <p role="status" aria-busy="true">Loading occurrence history…</p>}
      {history.status === 'error' && <p className="att-schedule-error" role="alert">{history.error}</p>}
      {history.status === 'ready' && history.items.length === 0 && <p className="att-schedule-empty">No executions have been recorded for this schedule.</p>}
      {history.status === 'ready' && history.items.length > 0 && <div className="att-schedule-table-wrap">
        <table className="att-schedule-table att-occurrence-table"><thead><tr><th scope="col">Scheduled for · {historySchedule.timezone}</th><th scope="col">Trigger</th><th scope="col">Occurrence</th><th scope="col">Status</th><th scope="col">Reason</th><th scope="col">Load job</th></tr></thead>
          <tbody>{history.items.map(item => <tr key={item.occurrenceId}>
            <td data-label={`Scheduled for · ${historySchedule.timezone}`}>{formatScheduleTime(item.scheduledFor, historySchedule.timezone)}</td>
            <td data-label="Trigger">{statusLabel(item.triggerType)}</td><td data-label="Occurrence"><code>{item.occurrenceId}</code></td>
            <td data-label="Status"><span className={`att-schedule-state ${item.status === 'submitted' ? 'is-enabled' : item.status === 'skipped' || item.status === 'failed-to-submit' ? 'is-paused' : ''}`}>{statusLabel(item.status)}</span></td>
            <td data-label="Reason">{item.reason ? explainOccurrence(item.reason) : '—'}</td>
            <td data-label="Load job">{item.jobId ? <button type="button" className="att-schedule-job-link" onClick={() => onOpenJob(item.jobId || '')}>{item.jobId}</button> : 'No job submitted'}</td>
          </tr>)}</tbody></table>
      </div>}
    </section>}

    <section className="att-schedule-panel att-schedule-editor" aria-labelledby="schedule-editor-heading">
      <div className="att-schedule-section-heading"><div><h2 id="schedule-editor-heading" tabIndex={-1}>{editingId ? `Edit schedule: ${draft.name || editingId}` : 'Create schedule'}</h2>
        <p>Scenario and config paths are package-relative. The Server validates the complete Load scenario when a run is submitted.</p></div>
        {editingId && <button type="button" className="button button-quiet" onClick={resetForm}>Cancel edit</button>}
      </div>
      {notice && <p className="att-schedule-notice" role="status" aria-live="polite">{notice}</p>}
      {mutationError && <p className="att-schedule-error" role="alert">{mutationError}</p>}
      {actionPending && <p className="att-schedule-status" role="status" aria-live="polite" aria-busy="true">Applying the confirmed action to {schedules.find(item => item.id === actionPending)?.name || 'the schedule'}…</p>}
      {packages.status === 'loading' && <p role="status" aria-busy="true">Loading configured packages…</p>}
      {packages.status === 'error' && <p className="att-schedule-error" role="alert">Configured packages could not be loaded: {packages.error.message}</p>}
      <form onSubmit={event => void submitSchedule(event)}>
        <div className="att-schedule-form-grid">
          <label htmlFor="schedule-name">Name <span aria-hidden="true">*</span>
            <input id="schedule-name" value={draft.name} onChange={event => updateDraft('name', event.currentTarget.value)} maxLength={120} required autoComplete="off" />
          </label>
          <label htmlFor="schedule-description">Description
            <input id="schedule-description" value={draft.description} onChange={event => updateDraft('description', event.currentTarget.value)} maxLength={500} />
          </label>
          <label htmlFor="schedule-tags">Tags <span className="att-schedule-hint">Comma-separated, at most 20</span>
            <input id="schedule-tags" value={draft.tags} onChange={event => updateDraft('tags', event.currentTarget.value)} maxLength={1000} />
          </label>
          <label htmlFor="schedule-package">Package <span aria-hidden="true">*</span>
            <select id="schedule-package" value={draft.packageId} onChange={event => updateDraft('packageId', event.currentTarget.value)} required disabled={packages.status !== 'ready'}>
              <option value="">Choose a configured package</option>{packageItems.map(item => <option key={item.packageId} value={item.packageId}>{item.name ? `${item.packageId} · ${item.name}` : item.packageId}</option>)}
            </select>
          </label>
          <label htmlFor="schedule-scenario">Scenario path <span className="att-schedule-hint">Package-relative</span>
            <input id="schedule-scenario" value={draft.scenario} onChange={event => updateDraft('scenario', event.currentTarget.value)} maxLength={512} placeholder="load/scenario.yaml" required />
          </label>
          <label htmlFor="schedule-config">Config path <span className="att-schedule-hint">Optional, package-relative</span>
            <input id="schedule-config" value={draft.config} onChange={event => updateDraft('config', event.currentTarget.value)} maxLength={512} placeholder="config/config.yaml" />
          </label>
          <label htmlFor="schedule-environment">Environment <span className="att-schedule-hint">Optional</span>
            <input id="schedule-environment" value={draft.environment} onChange={event => updateDraft('environment', event.currentTarget.value)} maxLength={128} placeholder="SIT" />
          </label>
          <label htmlFor="schedule-timezone">Timezone · IANA <span aria-hidden="true">*</span>
            <input id="schedule-timezone" list="schedule-timezone-options" value={draft.timezone} onChange={event => updateDraft('timezone', event.currentTarget.value)} maxLength={80} aria-invalid={Boolean(draft.timezone && !isIanaTimeZone(draft.timezone))} required />
            <datalist id="schedule-timezone-options">{timezoneOptions.map(zone => <option key={zone} value={zone} />)}</datalist>
          </label>
          <label htmlFor="schedule-recurrence">Recurrence
            <select id="schedule-recurrence" value={draft.recurrence} onChange={event => updateDraft('recurrence', event.currentTarget.value as ScheduleRecurrence)}>
              <option value="daily">Daily</option><option value="weekly">Weekly</option><option value="cron">Cron</option>
            </select>
          </label>
          {draft.recurrence !== 'cron' && <label htmlFor="schedule-time">Local time · {draft.timezone || 'timezone required'}
            <input id="schedule-time" type="time" value={draft.timeOfDay} onChange={event => updateDraft('timeOfDay', event.currentTarget.value)} required />
          </label>}
          {draft.recurrence === 'weekly' && <label htmlFor="schedule-weekday">Day(s) of week
            <select id="schedule-weekday" value={draft.dayOfWeek} onChange={event => updateDraft('dayOfWeek', event.currentTarget.value)}>{WEEKDAYS.map(day => <option key={day.value} value={day.value}>{day.label}</option>)}</select>
          </label>}
          {draft.recurrence === 'cron' && <label className="att-schedule-cron-field" htmlFor="schedule-cron">Cron · minute hour day-of-month month day-of-week
            <input id="schedule-cron" value={draft.cron} onChange={event => updateDraft('cron', event.currentTarget.value)} maxLength={120} placeholder="0 2 * * 1-5" required aria-describedby="schedule-cron-help" />
            <span id="schedule-cron-help" className="att-schedule-hint">Five fields; lists, ranges and steps are validated by the Server.</span>
          </label>}
          <label htmlFor="schedule-overlap">Maximum overlapping runs
            <input id="schedule-overlap" type="number" min="1" step="1" value={draft.maxConcurrentRuns} onChange={event => updateDraft('maxConcurrentRuns', event.currentTarget.value)} required />
            <span className="att-schedule-hint">When this limit or the Server's global capacity is reached, that occurrence is recorded as skipped.</span>
          </label>
          <label className="att-schedule-load-options" htmlFor="schedule-load-options">Load options · JSON object
            <textarea id="schedule-load-options" rows={5} spellCheck={false} value={draft.load} onChange={event => updateDraft('load', event.currentTarget.value)} aria-describedby="schedule-load-help" />
            <span id="schedule-load-help" className="att-schedule-hint">Optional Load overrides. The Server revalidates this scenario before submitting each job.</span>
          </label>
        </div>
        <section className="att-schedule-preview" aria-labelledby="schedule-preview-title">
          <h3 id="schedule-preview-title">Next-run preview</h3>
          <p role="status" aria-live="polite" aria-busy={preview.status === 'loading'}>
            {preview.status === 'loading' ? 'Calculating the next occurrence on the Server…'
              : preview.status === 'ready' ? `Next run: ${formatScheduleTime(preview.nextRunAt, draft.timezone)} · ${draft.timezone}`
                : preview.status === 'error' ? `Preview unavailable: ${preview.error}`
                  : !isIanaTimeZone(draft.timezone) ? 'Enter a valid IANA timezone to preview this schedule.'
                    : 'Enter a valid recurrence to preview the next run.'}
          </p>
          <p className="att-schedule-hint">This preview is for the next occurrence only. The Server applies timezone and DST rules and records each actual occurrence in UTC.</p>
        </section>
        <label className="att-schedule-enable"><input type="checkbox" checked={draft.enabled} onChange={event => updateDraft('enabled', event.currentTarget.checked)} /> Enable after saving</label>
        <div className="att-schedule-form-actions">
          <button className="button button-primary" type="submit" disabled={saving || !previewIsCurrent || packages.status !== 'ready' || packageItems.length === 0}>
            {saving ? 'Saving…' : editingId ? 'Save changes' : 'Save schedule'}
          </button>
          {!editingId && <button className="button button-quiet" type="button" onClick={resetForm}>Clear form</button>}
          {dirty && <span role="status">Unsaved schedule changes</span>}
        </div>
      </form>
    </section>

    <dialog className="att-schedule-confirm" ref={confirmationDialog} aria-labelledby="schedule-confirm-title" aria-describedby="schedule-confirm-description"
      onCancel={event => { event.preventDefault(); setPendingConfirmation(null); }}>
      <h2 id="schedule-confirm-title">{pendingConfirmation?.action === 'run' ? 'Run this schedule now?' : 'Delete this schedule?'}</h2>
      <p id="schedule-confirm-description">{pendingConfirmation?.action === 'run'
        ? `Submit ${pendingConfirmation.schedule.name} as a Load job now? The Server will apply its rate and capacity limits.`
        : `Delete ${pendingConfirmation?.schedule.name || 'this schedule'}? Any already accepted Load jobs will continue running.`}</p>
      <div className="att-schedule-confirm-actions">
        <button ref={cancelConfirmation} type="button" className="button button-quiet" onClick={() => setPendingConfirmation(null)}>Cancel</button>
        <button type="button" className={`button ${pendingConfirmation?.action === 'delete' ? 'button-danger' : 'button-primary'}`} onClick={() => void confirmAction()}>
          {pendingConfirmation?.action === 'run' ? 'Confirm Run Now' : 'Confirm delete'}
        </button>
      </div>
    </dialog>
  </section>;
}
