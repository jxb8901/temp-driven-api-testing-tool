import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react';
import { ApiError, type AsyncState, type PackageSummary } from '../../types';
import { useNavigationGuard, type AppRoute } from '../../router';
import type { SchedulesApi, ScheduleDefinition, ScheduleOccurrence, ValidatedScheduleIntent } from './types';
import {
  createScheduleDraft, cronForDraft, draftFromSchedule, explainOccurrence,
  formatScheduleTime, isIanaTimeZone, localTimeZone, scheduleInputFromDraft, WEEKDAYS,
  type ScheduleDraft, type ScheduleRecurrence,
} from './schedule';
import './schedules.css';

type LoadState = 'loading' | 'ready' | 'error' | 'disabled';
type PreviewState = { status: 'idle' | 'loading' | 'ready' | 'unchanged' | 'error'; signature: string; nextRunAt?: string; nextOccurrences?: string[]; error?: string };
type Confirmation = { action: 'run' | 'delete' | 'toggle'; schedule: ScheduleDefinition };
type HistoryState = { status: 'idle' | 'loading' | 'ready' | 'error'; items: ScheduleOccurrence[]; error?: string };
type ScheduleDiagnosticTarget = { type: string; id: string };
type ScheduleValidationDiagnostic = {
  code: string; summary: string; detail?: string; field?: string; file?: string; sourceFile?: string;
  suggestion?: string; packageId?: string; environment?: string; resourceId?: string;
  line?: number; sourceColumn?: number; targets?: ScheduleDiagnosticTarget[];
};

export interface ScheduledLoadPageProps {
  api: SchedulesApi;
  packages: AsyncState<PackageSummary[]>;
  enabled: boolean;
  onOpenJob(jobId: string): void;
  initialDraft?: ValidatedScheduleIntent | null;
  onInitialDraftConsumed?(): void;
  modal?: boolean;
  onClose?(): void;
  route?: Extract<AppRoute, { kind: 'schedules' }>;
}

function errorStatus(error: unknown): number {
  return error instanceof ApiError || (typeof error === 'object' && error !== null && 'status' in error)
    ? Number((error as { status?: unknown }).status || 0) : 0;
}

function errorMessage(error: unknown, operation: string): string {
  const status = errorStatus(error);
  if (status === 401) return 'Your Tomcat session is not authenticated. Sign in and retry.';
  if (status === 403) return 'Your account does not have the configured Schedules administrator role.';
  if (status === 404) return 'Schedules is disabled by the Server or this schedule no longer exists.';
  if (status === 409) return 'This schedule changed on the Server. Reload the schedule list before editing it again.';
  if (status === 429) return 'The Server is at its scheduled-load capacity. Review the occurrence history and retry later.';
  return error instanceof Error ? error.message : `${operation} failed. Check the connection and retry.`;
}

function scheduleDiagnostics(error: unknown): ScheduleValidationDiagnostic[] {
  if (!(error instanceof ApiError) || !Array.isArray(error.diagnostics)) return [];
  return error.diagnostics.slice(0, 20).flatMap((raw): ScheduleValidationDiagnostic[] => {
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return [];
    const value = raw as Record<string, unknown>;
    const safeText = (key: string, maximum = 1000): string | undefined => {
      const item = value[key];
      return typeof item === 'string' && item.trim() ? item.slice(0, maximum) : undefined;
    };
    const targets = Array.isArray(value.targets) ? value.targets.slice(0, 32).flatMap((target): ScheduleDiagnosticTarget[] => {
      if (!target || typeof target !== 'object' || Array.isArray(target)) return [];
      const item = target as Record<string, unknown>;
      return typeof item.type === 'string' && typeof item.id === 'string'
        ? [{ type: item.type.slice(0, 80), id: item.id.slice(0, 256) }] : [];
    }) : undefined;
    const numberValue = (key: string): number | undefined => typeof value[key] === 'number' && Number.isFinite(value[key]) ? Number(value[key]) : undefined;
    const summary = safeText('summary') || safeText('message') || 'The Server rejected this schedule input.';
    return [{
      code: safeText('code', 120) || 'ATT-SERVER-DRAFT-INVALID', summary,
      detail: safeText('detail'), field: safeText('field', 300), file: safeText('file', 512),
      sourceFile: safeText('sourceFile', 512), suggestion: safeText('suggestion'),
      packageId: safeText('packageId', 120), environment: safeText('environment', 120),
      resourceId: safeText('resourceId', 300), line: numberValue('line'), sourceColumn: numberValue('sourceColumn'), targets,
    }];
  });
}

function focusDiagnosticField(field: string): void {
  const value = field.toLowerCase();
  const exact: Array<[RegExp, string]> = [
    [/^name(?:\.|$)/, 'schedule-name'], [/^packageid(?:\.|$)/, 'schedule-package'],
    [/^scenario(?:\.|$)/, 'schedule-scenario'], [/^config(?:\.|$)/, 'schedule-config'],
    [/^environment(?:\.|$)/, 'schedule-environment'], [/^timezone(?:\.|$)/, 'schedule-timezone'],
    [/^(?:cron|recurrence)(?:\.|$)/, 'schedule-cron'], [/^maxconcurrentruns(?:\.|$)/, 'schedule-overlap'],
  ];
  const match = exact.find(([pattern]) => pattern.test(value));
  let target = match ? document.getElementById(match[1]) : null;
  if (!target && /workload|target|scenario|load|debug/.test(value)) {
    const snapshotDetails = document.querySelector<HTMLDetailsElement>('.att-schedule-snapshot-review');
    if (snapshotDetails) snapshotDetails.open = true;
    target = document.getElementById('schedule-snapshot-content') || document.getElementById('schedule-scenario')
      || document.getElementById('schedule-load-options');
  }
  (target || document.getElementById('schedule-editor-heading'))?.focus();
}

function recurrenceLabel(schedule: ScheduleDefinition): string {
  if (schedule.runAt) return `Once · ${schedule.runAt}`;
  const minute = /^(\d{1,2}) (\d{1,2}) \* \* \*$/.exec((schedule.cron || '').trim());
  if (minute) return `Daily at ${String(Number(minute[2])).padStart(2, '0')}:${String(Number(minute[1])).padStart(2, '0')}`;
  const weekly = /^(\d{1,2}) (\d{1,2}) \* \* (1-5|0,6|[0-6])$/.exec((schedule.cron || '').trim());
  if (weekly) {
    const days = WEEKDAYS.find(day => day.value === weekly[3])?.label || `day ${weekly[3]}`;
    return `Weekly · ${days} at ${String(Number(weekly[2])).padStart(2, '0')}:${String(Number(weekly[1])).padStart(2, '0')}`;
  }
  return `Cron · ${schedule.cron || ''}`;
}

type ScheduleState = 'completed' | 'enabled' | 'paused';

function scheduleState(schedule: ScheduleDefinition): ScheduleState {
  if (schedule.enabled && schedule.runAt && schedule.nextRunAt === null) return 'completed';
  return schedule.enabled ? 'enabled' : 'paused';
}

function scheduleStateLabel(state: ScheduleState): string {
  return state === 'completed' ? 'Completed' : state === 'enabled' ? 'Enabled' : 'Paused';
}

function scheduleStateIcon(state: ScheduleState): string {
  return state === 'completed' ? '✓' : state === 'enabled' ? '●' : '■';
}

function ScheduleStateBadge({ schedule }: { schedule: ScheduleDefinition }): React.JSX.Element {
  const state = scheduleState(schedule);
  return <span className={`att-schedule-state is-${state}`} title={state === 'completed' ? 'The one-time firing was consumed; check History for its outcome.' : undefined}>
    <span aria-hidden="true">{scheduleStateIcon(state)}</span> {scheduleStateLabel(state)}
  </span>;
}

function jobSummary(schedule: ScheduleDefinition): string {
  if (schedule.executionType === 'run') {
    const target = schedule.debugTarget;
    return target ? `${target.type} · ${target.id}${schedule.legacyDebug ? ' · Debug mode' : ''}`
      : schedule.selectedCaseIds?.length ? `Cases · ${schedule.selectedCaseIds.join(', ')}` : 'Validated RUN snapshot';
  }
  return schedule.inlineSnapshot ? 'Validated Load snapshot' : schedule.scenario || 'Scenario unavailable';
}

function timeZoneChoices(): string[] {
  const intl = Intl as typeof Intl & { supportedValuesOf?: (key: 'timeZone') => string[] };
  const zones = intl.supportedValuesOf?.('timeZone') || [];
  return [...new Set(['UTC', localTimeZone(), ...zones])].sort((left, right) => left.localeCompare(right));
}

function statusLabel(status: string): string {
  return status.replaceAll('-', ' ').replaceAll('_', ' ');
}

const JOB_STATUS_LABELS: Record<string, string> = {
  QUEUED: 'Queued', PREPARING: 'Preparing', RUNNING: 'Running', CANCEL_REQUESTED: 'Cancelling',
  CANCELLED: 'Cancelled', PASS: 'Passed', FAIL: 'Failed', ERROR: 'Error', INVALID: 'Invalid',
};

/** The job's own state, so a submitted occurrence never hides a failed result. */
function jobStatusLabel(status: string): string {
  return JOB_STATUS_LABELS[status.toUpperCase()] || statusLabel(status);
}

function jobStatusTone(status: string): string {
  const normalized = status.toUpperCase();
  if (normalized === 'PASS') return 'is-enabled';
  if (normalized === 'FAIL' || normalized === 'ERROR' || normalized === 'INVALID') return 'is-failed';
  if (normalized === 'QUEUED' || normalized === 'PREPARING' || normalized === 'RUNNING' || normalized === 'CANCEL_REQUESTED') return 'is-running';
  return 'is-paused';
}

/** Job states that still move on their own, so an open History panel keeps them current. */
const ACTIVE_JOB_STATUSES = ['QUEUED', 'PREPARING', 'RUNNING', 'CANCEL_REQUESTED'];
const HISTORY_REFRESH_MS = 5000;

function hasActiveJob(items: ScheduleOccurrence[]): boolean {
  return items.some(item => Boolean(item.jobStatus) && ACTIVE_JOB_STATUSES.includes(String(item.jobStatus).toUpperCase()));
}

export function ScheduledLoadPage({ api, packages, enabled, onOpenJob, initialDraft, onInitialDraftConsumed, modal = false, onClose, route }: ScheduledLoadPageProps): React.JSX.Element {
  const navigation = useNavigationGuard();
  const [localView, setLocalView] = useState<'list' | 'new' | 'edit' | 'history'>(modal ? 'new' : route?.view || 'list');
  const view = route?.view || localView;
  const editorOpen = modal || view === 'new' || view === 'edit';
  const [discardRequested, setDiscardRequested] = useState(false);
  const releaseGuard = useRef<() => void>(() => {});
  const loadedRoute = useRef('');
  const [schedules, setSchedules] = useState<ScheduleDefinition[]>([]);
  const [loadState, setLoadState] = useState<LoadState>(enabled ? 'loading' : 'disabled');
  const [loadError, setLoadError] = useState('');
  const [notice, setNotice] = useState('');
  const [mutationError, setMutationError] = useState('');
  const [validationDiagnostics, setValidationDiagnostics] = useState<ScheduleValidationDiagnostic[]>([]);
  const [validationRequestId, setValidationRequestId] = useState('');
  const [filter, setFilter] = useState('');
  const [stateFilter, setStateFilter] = useState<'all' | ScheduleState>('all');
  const [packageFilter, setPackageFilter] = useState('');
  const [executionFilter, setExecutionFilter] = useState('all');
  const [ownerFilter, setOwnerFilter] = useState('');
  const [draft, setDraft] = useState<ScheduleDraft>(() => createScheduleDraft());
  const [scenarioPreviewYaml, setScenarioPreviewYaml] = useState('');
  const [saved, setSaved] = useState(false);
  const [editingId, setEditingId] = useState('');
  const [editingNextRunAt, setEditingNextRunAt] = useState<string | undefined>(undefined);
  const [baseline, setBaseline] = useState(() => JSON.stringify(createScheduleDraft()));
  const [preview, setPreview] = useState<PreviewState>({ status: 'idle', signature: '' });
  const [saving, setSaving] = useState(false);
  const [pendingConfirmation, setPendingConfirmation] = useState<Confirmation | null>(null);
  const [actionPending, setActionPending] = useState('');
  const [historySchedule, setHistorySchedule] = useState<ScheduleDefinition | null>(null);
  const [history, setHistory] = useState<HistoryState>({ status: 'idle', items: [] });
  const [historyRefresh, setHistoryRefresh] = useState(0);
  const listController = useRef<AbortController | null>(null);
  const listSequence = useRef(0);
  const historyController = useRef<AbortController | null>(null);
  const previewController = useRef<AbortController | null>(null);
  const previewSequence = useRef(0);
  const confirmationDialog = useRef<HTMLDialogElement>(null);
  const modalDialog = useRef<HTMLDialogElement>(null);
  const discardDialog = useRef<HTMLDialogElement>(null);
  const dirtyRef = useRef(false);
  const cancelConfirmation = useRef<HTMLButtonElement>(null);
  const timezoneOptions = useMemo(timeZoneChoices, []);

  const draftSignature = JSON.stringify(draft);
  const dirty = draftSignature !== baseline;
  dirtyRef.current = dirty;
  const cron = cronForDraft(draft);
  const previewSignature = `${draft.timezone.trim()}\n${cron}`;
  const previewIsCurrent = (preview.status === 'ready' || preview.status === 'unchanged') && preview.signature === previewSignature;
  // A metadata-only edit keeps the stored trigger, so the Server already holds a valid next run and no new preview is required.
  const triggerUnchanged = useMemo(() => {
    if (!editingId) return false;
    let saved: ScheduleDraft;
    try { saved = JSON.parse(baseline) as ScheduleDraft; } catch { return false; }
    return saved.timezone.trim() === draft.timezone.trim() && saved.recurrence === draft.recurrence && cronForDraft(saved) === cron;
  }, [baseline, cron, draft.recurrence, draft.timezone, editingId]);

  const loadSchedules = useCallback(async () => {
    if (!enabled) { setLoadState('disabled'); return; }
    listController.current?.abort();
    const controller = new AbortController();
    listController.current = controller;
    const sequence = ++listSequence.current;
    setLoadState('loading');
    setLoadError('');
    try {
      const response = await api.listSchedules(controller.signal);
      if (controller.signal.aborted || sequence !== listSequence.current) return;
      setSchedules(Array.isArray(response.items) ? response.items : []);
      setLoadState('ready');
    } catch (error) {
      if (controller.signal.aborted || sequence !== listSequence.current) return;
      setSchedules([]);
      setHistorySchedule(null);
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

  useEffect(() => {
    const release = navigation.registerUnsavedChanges(editorOpen && dirty, 'Schedules changes');
    releaseGuard.current = release;
    return release;
  }, [dirty, editorOpen, navigation.registerUnsavedChanges]);

  useEffect(() => {
    if (!editorOpen || !dirty) return;
    const beforeUnload = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [dirty, editorOpen]);

  // Resolve bookmarked edit/history views only from the authorized list response.
  useEffect(() => {
    if (!route?.scheduleId || loadState !== 'ready' || loadedRoute.current === route.scheduleId) return;
    const schedule = schedules.find(item => item.id === route.scheduleId);
    if (!schedule) return;
    loadedRoute.current = route.scheduleId;
    if (route.view === 'edit') loadEditor(schedule);
    if (route.view === 'history') setHistorySchedule(schedule);
  }, [route, loadState, schedules]);

  useEffect(() => {
    if (editorOpen) document.getElementById('schedule-editor-heading')?.focus();
    else if (view === 'history') document.getElementById('schedule-history-heading')?.focus();
  }, [editorOpen, view, editingId, history.status, loadState]);

  useEffect(() => {
    if (!initialDraft?.draftId) return;
    const seeded = createScheduleDraft();
    seeded.executionType = initialDraft.executionType || 'load';
    seeded.legacyDebug = initialDraft.legacyDebug;
    seeded.name = `Validated ${seeded.executionType === 'run' ? 'RUN' : 'LOAD'} snapshot`;
    seeded.packageId = initialDraft.packageId;
    seeded.environment = initialDraft.environment || '';
    seeded.debugTargetType = initialDraft.target?.type || '';
    seeded.debugTargetId = initialDraft.target?.id || '';
    seeded.inlineSnapshot = true;
    seeded.draftId = initialDraft.draftId;
    setScenarioPreviewYaml(initialDraft.previewYaml || '');
    setDraft(seeded);
    setBaseline(JSON.stringify(seeded));
    setEditingId('');
    setEditingNextRunAt(undefined);
    setMutationError('');
    setNotice(`Schedule form loaded from the validated ${seeded.executionType === 'run' ? 'RUN' : 'LOAD'} draft. Choose a recurrence and review the Server next-run preview.`);
    onInitialDraftConsumed?.();
  }, [initialDraft, onInitialDraftConsumed]);

  useEffect(() => {
    const dialog = modalDialog.current;
    if (!modal || !dialog) return;
    const previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const cancel = (event: Event) => { event.preventDefault(); if (dirtyRef.current) setDiscardRequested(true); else onClose?.(); };
    dialog.addEventListener('cancel', cancel);
    if (!dialog.open) dialog.showModal();
    dialog.querySelector<HTMLElement>('#schedule-name')?.focus();
    return () => {
      dialog.removeEventListener('cancel', cancel);
      if (dialog.open) dialog.close();
      previousFocus?.focus();
    };
  }, [modal, onClose]);

  useEffect(() => {
    const dialog = discardDialog.current;
    if (discardRequested && dialog && !dialog.open) dialog.showModal();
    if (!discardRequested && dialog?.open) dialog.close();
  }, [discardRequested]);

  useEffect(() => {
    const sequence = ++previewSequence.current;
    previewController.current?.abort();
    if (!enabled || !editorOpen || loadState !== 'ready' || !isIanaTimeZone(draft.timezone) || !cron) {
      setPreview({ status: 'idle', signature: previewSignature });
      return;
    }
    if (triggerUnchanged) {
      setPreview({ status: 'unchanged', signature: previewSignature, nextRunAt: editingNextRunAt });
      return;
    }
    const controller = new AbortController();
    previewController.current = controller;
    setPreview({ status: 'loading', signature: previewSignature });
    const timer = window.setTimeout(() => {
      void api.previewSchedule({ timezone: draft.timezone.trim(), ...(draft.recurrence === 'once' ? { runAt: draft.runAt } : { cron }) }, controller.signal)
        .then(response => {
          if (!controller.signal.aborted && sequence === previewSequence.current) {
            setPreview({ status: 'ready', signature: previewSignature, nextRunAt: response.nextRunAt,
              nextOccurrences: response.nextOccurrences?.length ? response.nextOccurrences : [response.nextRunAt] });
          }
        })
        .catch(error => {
          if (!controller.signal.aborted && sequence === previewSequence.current) {
            setPreview({ status: 'error', signature: previewSignature, error: errorMessage(error, 'Schedule preview') });
          }
        });
    }, 250);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [api, cron, draft.timezone, editingNextRunAt, enabled, editorOpen, loadState, previewSignature, triggerUnchanged]);

  useEffect(() => {
    const dialog = confirmationDialog.current;
    if (!dialog) return;
    if (pendingConfirmation && !dialog.open) {
      dialog.showModal();
      cancelConfirmation.current?.focus();
    } else if (!pendingConfirmation && dialog.open) dialog.close();
  }, [pendingConfirmation]);

  const refreshHistory = useCallback(async (schedule: ScheduleDefinition) => {
    historyController.current?.abort();
    const controller = new AbortController();
    historyController.current = controller;
    try {
      const response = await api.getScheduleExecutions(schedule.id, 100, controller.signal);
      if (controller.signal.aborted) return;
      setHistory({ status: 'ready', items: response.items || [] });
    } catch (error) {
      if (controller.signal.aborted) return;
      // A failed background refresh keeps the occurrences already on screen and offers a manual retry.
      setHistory(current => current.items.length > 0
        ? { ...current, error: errorMessage(error, 'Refreshing occurrence history') }
        : { status: 'error', items: [], error: errorMessage(error, 'Loading occurrence history') });
    }
  }, [api]);

  useEffect(() => {
    if (!historySchedule) {
      historyController.current?.abort();
      setHistory({ status: 'idle', items: [] });
      return;
    }
    setHistory({ status: 'loading', items: [] });
    void refreshHistory(historySchedule);
    return () => historyController.current?.abort();
  }, [historySchedule, historyRefresh, refreshHistory]);

  // A submitted occurrence stays visually current while its job is still queued or running, so the
  // panel follows the queued-to-terminal transition without the user closing and reopening History.
  const hasActiveHistoryJob = history.status === 'ready' && hasActiveJob(history.items);
  useEffect(() => {
    if (!historySchedule || !hasActiveHistoryJob) return;
    const timer = window.setInterval(() => void refreshHistory(historySchedule), HISTORY_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [historySchedule, hasActiveHistoryJob, refreshHistory]);

  const visibleSchedules = useMemo(() => {
    const query = filter.trim().toLocaleLowerCase();
    return schedules.filter(schedule => {
      const matchesText = !query || [schedule.name, schedule.description, schedule.packageId, schedule.environment, schedule.createdBy, jobSummary(schedule), schedule.cron]
        .some(value => String(value || '').toLocaleLowerCase().includes(query));
      const matchesState = stateFilter === 'all' || scheduleState(schedule) === stateFilter;
      return matchesText && matchesState && (executionFilter === 'all' || executionFilter === schedule.executionType) && (!packageFilter || schedule.packageId === packageFilter) && (!ownerFilter || schedule.createdBy === ownerFilter);
    });
  }, [executionFilter, filter, ownerFilter, packageFilter, schedules, stateFilter]);

  function updateDraft<K extends keyof ScheduleDraft>(key: K, value: ScheduleDraft[K]) {
    setDraft(current => ({ ...current, [key]: value }));
    setSaved(false);
    setMutationError('');
    setValidationDiagnostics([]); setValidationRequestId('');
    setNotice('');
  }

  function resetForm() {
    const fresh = createScheduleDraft();
    setDraft(fresh);
    setBaseline(JSON.stringify(fresh));
    setEditingId('');
    setEditingNextRunAt(undefined);
    setSaved(false);
    setMutationError('');
    setValidationDiagnostics([]); setValidationRequestId('');
    setNotice('');
  }

  function goTo(next: 'list' | 'new' | 'edit' | 'history', schedule?: ScheduleDefinition) {
    if (route) navigation.navigate({ kind: 'schedules', ...(next === 'list' ? {} : { view: next }), ...(schedule ? { scheduleId: schedule.id } : {}) });
    else setLocalView(next);
  }

  function closeEditor() {
    if (saving) return;
    if (dirty) { setDiscardRequested(true); return; }
    if (modal) onClose?.();
    else goTo('list');
  }

  function discardEditor() {
    releaseGuard.current();
    resetForm();
    setDiscardRequested(false);
    if (modal) onClose?.();
    else goTo('list');
  }

  function editSchedule(schedule: ScheduleDefinition) {
    if (!route) loadEditor(schedule);
    goTo('edit', schedule);
  }

  function loadEditor(schedule: ScheduleDefinition) {
    const next = draftFromSchedule(schedule);
    setDraft(next);
    setBaseline(JSON.stringify(next));
    setEditingId(schedule.id);
    setEditingNextRunAt(schedule.nextRunAt || undefined);
    setMutationError('');
    setValidationDiagnostics([]); setValidationRequestId('');
    setNotice(`Editing ${schedule.name}.`);
    document.getElementById('schedule-editor-heading')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    document.getElementById('schedule-name')?.focus({ preventScroll: true });
  }

  async function submitSchedule(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setMutationError('');
    setValidationDiagnostics([]); setValidationRequestId('');
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
        ? await api.updateSchedule(editingId, input)
        : await api.createSchedule(input);
      if (modal) {
        setBaseline(JSON.stringify(draft));
        setEditingId('');
        setSaved(true);
      } else {
        releaseGuard.current();
        resetForm();
        goTo('list');
      }
      setNotice(`Saved ${saved.name}. Next run: ${formatScheduleTime(saved.nextRunAt, saved.timezone)} (${saved.timezone}).`);
      await loadSchedules();
    } catch (error) {
      const diagnostics = scheduleDiagnostics(error);
      setValidationDiagnostics(diagnostics);
      setValidationRequestId(error instanceof ApiError ? error.requestId || '' : '');
      setMutationError(diagnostics.length ? 'The Server rejected this schedule. Review the validation details below.' : errorMessage(error, 'Saving schedule'));
    }
    finally { setSaving(false); }
  }

  async function changeScheduleEnabled(schedule: ScheduleDefinition) {
    setActionPending(schedule.id);
    setMutationError('');
    try {
      const updated = await api.setScheduleEnabled(schedule.id, !schedule.enabled);
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
        const occurrence = await api.triggerSchedule(pending.schedule.id);
        setNotice(`Run Now recorded as ${statusLabel(occurrence.status)}${occurrence.jobId ? ` · job ${occurrence.jobId}${occurrence.jobStatus ? ` · ${jobStatusLabel(occurrence.jobStatus)}` : ''}` : ''}${occurrence.reason ? ` · ${explainOccurrence(occurrence.reason)}` : ''}.`);
        setHistorySchedule(pending.schedule);
        goTo('history', pending.schedule);
        // Reload even when the same schedule is already selected, so the new occurrence appears
        // immediately instead of after the next automatic refresh.
        setHistoryRefresh(value => value + 1);
      } else if (pending.action === 'toggle') {
        await changeScheduleEnabled(pending.schedule);
      } else {
        await api.deleteSchedule(pending.schedule.id);
        setSchedules(current => current.filter(item => item.id !== pending.schedule.id));
        if (historySchedule?.id === pending.schedule.id) setHistorySchedule(null);
        if (editingId === pending.schedule.id) resetForm();
        setNotice(`Deleted ${pending.schedule.name}. Any already accepted jobs continue running.`);
      }
      await loadSchedules();
    } catch (error) { setMutationError(errorMessage(error, pending.action === 'run' ? 'Running schedule now' : 'Deleting schedule')); }
    finally { setActionPending(''); }
  }

  function openHistory(schedule: ScheduleDefinition) {
    setHistorySchedule(schedule);
    goTo('history', schedule);
    setNotice(`Showing the latest 100 occurrences for ${schedule.name}.`);
    window.setTimeout(() => document.getElementById('schedule-history-heading')?.focus(), 0);
  }

  const packageItems = packages.status === 'ready' ? packages.data : [];

  if (!enabled) return <section className={modal ? 'att-schedule-disabled-modal' : 'page-content att-schedules-page'} aria-labelledby="schedules-title">
    <div className="page-heading"><div><p className="eyebrow">ATT Studio</p><h1 id="schedules-title">Schedules</h1></div></div>
    <p className="att-schedule-callout" role="status">Schedules is disabled by Server configuration. Ask the Server administrator to enable scheduling.</p>
    {modal && <button className="button button-quiet" type="button" onClick={onClose}>Return to Load</button>}
  </section>;

  const pageContent = <>
    {!modal && view !== 'list' && <button type="button" className="button button-quiet" onClick={editorOpen ? closeEditor : () => goTo('list')}>Back to Schedules</button>}
    {!editorOpen && notice && <p className="att-schedule-notice" role="status">{notice}</p>}
    {!editorOpen && mutationError && <p className="att-schedule-error" role="alert">{mutationError}</p>}
    {!editorOpen && actionPending && <p role="status" aria-busy="true">Applying schedule action…</p>}
    {editorOpen && loadState === 'loading' && <p role="status">Checking schedule access…</p>}
    {editorOpen && loadState === 'error' && <div role="alert"><p>{loadError}</p><button type="button" onClick={() => void loadSchedules()}>Retry schedule access</button></div>}
    {route?.scheduleId && loadState === 'loading' && <p role="status">Loading schedule…</p>}
    {route?.scheduleId && loadState === 'error' && <p role="alert">{loadError}</p>}
    {route?.scheduleId && loadState === 'ready' && !schedules.some(item => item.id === route.scheduleId) && <p role="alert">This schedule is unavailable or you do not have access. Return to Schedules and refresh.</p>}
    {!modal && <>
    {view === 'list' && <>
    <header className="page-heading att-schedules-heading">
      <div><p className="eyebrow">Execution schedules</p><h1 id="schedules-title">Schedules</h1>
        <p className="page-description">Manage recurring RUN and LOAD jobs and inspect the Server's recorded occurrences.</p>
      </div>
      <button className="button button-primary" type="button" onClick={() => {
        if (dirty) { setMutationError('Save or clear the current schedule draft before starting another.'); return; }
        resetForm(); goTo('new');
      }} disabled={loadState !== 'ready'}>New Schedule</button>
    </header>

    <p className="att-schedule-callout">The Server computes next-run previews using the selected IANA timezone. During spring-forward, a local time that does not exist is skipped. During fall-back, repeated local times can produce two instants; the Server enforces its minimum interval. Review the preview before enabling a schedule.</p>
    <p className="att-schedule-callout att-schedule-callout-warning">Missed runs are recorded as skipped and are not replayed after a restart or late poll. If the schedule's overlap limit or the Server's global scheduled-job capacity is reached, the occurrence is skipped, not queued. Run Now is confirmed and subject to Server rate and capacity limits.</p>

    <section className="att-schedule-panel" aria-labelledby="schedule-list-heading">
      <div className="att-schedule-section-heading">
        <div><h2 id="schedule-list-heading">Schedules</h2><p>Times are displayed in each schedule's timezone.</p></div>
        <button className="button button-quiet" type="button" onClick={() => void loadSchedules()} disabled={loadState === 'loading'}>Refresh schedules</button>
      </div>
      <div className="att-schedule-filters" role="group" aria-label="Filter schedules">
        <label>Search schedules
          <input type="search" value={filter} onChange={event => setFilter(event.currentTarget.value)} placeholder="Name, package, scenario or cron" />
        </label>
        <label>Execution type<select value={executionFilter} onChange={event => setExecutionFilter(event.currentTarget.value)}><option value="all">RUN and LOAD</option><option value="run">RUN</option><option value="load">LOAD</option></select></label>
        <label>Status
          <select value={stateFilter} onChange={event => setStateFilter(event.currentTarget.value as typeof stateFilter)}>
            <option value="all">All schedules</option><option value="enabled">Enabled</option><option value="paused">Paused</option><option value="completed">Completed</option>
          </select>
        </label>
        <label>Package
          <select value={packageFilter} onChange={event => setPackageFilter(event.currentTarget.value)}>
            <option value="">All packages</option>
            {Array.from(new Set(schedules.map(item => item.packageId))).sort().map(packageId => <option key={packageId} value={packageId}>{packageId}</option>)}
          </select>
        </label>
        {schedules.some(item => item.createdBy) && <label>Owner
          <select value={ownerFilter} onChange={event => setOwnerFilter(event.currentTarget.value)}>
            <option value="">All owners</option>
            {[...new Set(schedules.map(item => item.createdBy).filter(Boolean))].sort().map(owner => <option key={owner} value={owner}>{owner}</option>)}
          </select>
        </label>}
      </div>
      <p className="att-schedule-status" role="status" aria-live="polite" aria-busy={loadState === 'loading'}>
        {loadState === 'loading' ? 'Loading schedules…' : loadState === 'ready' ? `${visibleSchedules.length} of ${schedules.length} schedules shown.` : loadState === 'error' ? loadError : ''}
      </p>
      {loadState === 'error' && <button className="button button-quiet" type="button" onClick={() => void loadSchedules()}>Retry schedule list</button>}
      {loadState === 'ready' && schedules.length === 0 && <p className="att-schedule-empty">No schedules have been created. Use New Schedule to add the first one.</p>}
      {loadState === 'ready' && schedules.length > 0 && visibleSchedules.length === 0 && <p className="att-schedule-empty">No schedules match these filters.</p>}
      {visibleSchedules.length > 0 && <>
        <div className="att-schedule-table-wrap">
          <table className="att-schedule-table">
            <thead><tr><th scope="col">Name / type</th><th scope="col">Target / package / environment</th><th scope="col">Recurrence</th><th scope="col">Next run</th><th scope="col">State / owner</th><th scope="col">Actions</th></tr></thead>
            <tbody>{visibleSchedules.map(schedule => <tr key={schedule.id}>
              <th scope="row" data-label="Name"><span>{schedule.name}</span><small>{schedule.executionType.toUpperCase()}</small>{schedule.description && <small>{schedule.description}</small>}</th>
              <td data-label="Package and scenario"><span>{schedule.packageId} · {schedule.environment || 'Package default'}</span><small>{jobSummary(schedule)}{schedule.loadModel ? ` · ${schedule.loadModel}` : ''}{schedule.config ? ` · ${schedule.config}` : ''}</small></td>
              <td data-label="Recurrence"><span>{recurrenceLabel(schedule)}</span><small>{schedule.timezone}</small></td>
              <td data-label="Next run">{formatScheduleTime(schedule.nextRunAt, schedule.timezone)}</td>
              <td data-label="Status"><ScheduleStateBadge schedule={schedule} />{schedule.createdBy && <small>Owner · {schedule.createdBy}</small>}{schedule.recentStatus && <small>{schedule.recentStatus}</small>}</td>
              <td data-label="Actions"><div className="att-schedule-actions">
                <button type="button" onClick={() => openHistory(schedule)} aria-label={`History ${schedule.name}`}>History</button>
                <button type="button" onClick={() => editSchedule(schedule)} aria-label={`Edit ${schedule.name}`}>Edit</button>
                <button type="button" onClick={() => setPendingConfirmation({ action: 'toggle', schedule })} disabled={Boolean(actionPending)} aria-label={`${schedule.enabled ? 'Pause' : 'Resume'} ${schedule.name}`}>{schedule.enabled ? 'Pause' : 'Resume'}</button>
                <button type="button" onClick={() => setPendingConfirmation({ action: 'run', schedule })} disabled={actionPending === schedule.id} aria-label={`Run Now ${schedule.name}`}>Run Now</button>
                <button type="button" className="att-schedule-delete" onClick={() => setPendingConfirmation({ action: 'delete', schedule })} disabled={actionPending === schedule.id} aria-label={`Delete ${schedule.name}`}>Delete</button>
              </div></td>
            </tr>)}</tbody>
          </table>
        </div>
        <section className="att-schedule-cards" aria-label="Schedule list">
          {visibleSchedules.map(schedule => <article className="att-schedule-card" key={schedule.id}>
            <div className="att-schedule-card-heading"><h3>{schedule.name}</h3><ScheduleStateBadge schedule={schedule} /></div>
            {schedule.description && <p>{schedule.description}</p>}
            <dl><div><dt>Type</dt><dd>{schedule.executionType.toUpperCase()}</dd></div><div><dt>Package</dt><dd>{schedule.packageId}</dd></div><div><dt>Environment</dt><dd>{schedule.environment || 'Package default'}</dd></div><div><dt>Target</dt><dd>{jobSummary(schedule)}</dd></div>
              {schedule.createdBy && <div><dt>Owner</dt><dd>{schedule.createdBy}</dd></div>}
              {schedule.recentStatus && <div><dt>Recent status</dt><dd>{schedule.recentStatus}</dd></div>}
              <div><dt>Recurrence</dt><dd>{recurrenceLabel(schedule)}</dd></div><div><dt>Next run · {schedule.timezone}</dt><dd>{formatScheduleTime(schedule.nextRunAt, schedule.timezone)}</dd></div></dl>
            <div className="att-schedule-actions">
              <button type="button" onClick={() => openHistory(schedule)} aria-label={`History ${schedule.name}`}>History</button>
              <button type="button" onClick={() => editSchedule(schedule)} aria-label={`Edit ${schedule.name}`}>Edit</button>
              <button type="button" onClick={() => setPendingConfirmation({ action: 'toggle', schedule })} disabled={Boolean(actionPending)} aria-label={`${schedule.enabled ? 'Pause' : 'Resume'} ${schedule.name}`}>{schedule.enabled ? 'Pause' : 'Resume'}</button>
              <button type="button" onClick={() => setPendingConfirmation({ action: 'run', schedule })} disabled={actionPending === schedule.id} aria-label={`Run Now ${schedule.name}`}>Run Now</button>
              <button type="button" className="att-schedule-delete" onClick={() => setPendingConfirmation({ action: 'delete', schedule })} disabled={actionPending === schedule.id} aria-label={`Delete ${schedule.name}`}>Delete</button>
            </div>
          </article>)}
        </section>
      </>}
    </section>
    </>}

    {view === 'history' && historySchedule && <section className="att-schedule-panel att-schedule-history" aria-labelledby="schedule-history-heading">
      <div className="att-schedule-section-heading"><div><h2 id="schedule-history-heading" tabIndex={-1}>Execution history · {historySchedule.name}</h2><p>Latest 100 occurrences. Skips and submission failures remain visible even when no job was created. Occurrences whose job is still queued or running refresh automatically.</p></div>
        <div className="att-schedule-actions">
          <button className="button button-quiet" type="button" disabled={history.status === 'loading'} onClick={() => setHistoryRefresh(value => value + 1)}>Refresh history</button>
          <button className="button button-quiet" type="button" onClick={() => { setHistorySchedule(null); goTo('list'); }}>Close history</button>
        </div>
      </div>
      {history.status === 'loading' && <p role="status" aria-busy="true">Loading occurrence history…</p>}
      {history.status === 'error' && <p className="att-schedule-error" role="alert">{history.error}</p>}
      {history.status === 'ready' && history.error && <p className="att-schedule-error" role="alert">{history.error}</p>}
      {history.status === 'ready' && history.items.length === 0 && <p className="att-schedule-empty">No executions have been recorded for this schedule.</p>}
      {history.status === 'ready' && history.items.length > 0 && <div className="att-schedule-table-wrap">
        <table className="att-schedule-table att-occurrence-table"><thead><tr><th scope="col">Scheduled for · {historySchedule.timezone}</th><th scope="col">Trigger</th><th scope="col">Occurrence</th><th scope="col">Status</th><th scope="col">Reason</th><th scope="col">Job</th></tr></thead>
          <tbody>{history.items.map(item => <tr key={item.occurrenceId} id={`occurrence-${item.occurrenceId}`} aria-current={route?.occurrenceId === item.occurrenceId ? 'true' : undefined}>
            <td data-label={`Scheduled for · ${historySchedule.timezone}`}>{formatScheduleTime(item.scheduledFor, historySchedule.timezone)}</td>
            <td data-label="Trigger">{statusLabel(item.triggerType)}</td><td data-label="Occurrence"><button type="button" onClick={() => navigation.navigate({ kind: 'schedules', view: 'history', scheduleId: historySchedule.id, occurrenceId: item.occurrenceId })}><code>{item.occurrenceId}</code></button></td>
            <td data-label="Status"><span className={`att-schedule-state ${item.status === 'submitted' ? 'is-enabled' : item.status === 'skipped' || item.status === 'failed-to-submit' ? 'is-paused' : ''}`}>{statusLabel(item.status)}</span></td>
            <td data-label="Reason">{item.reason ? explainOccurrence(item.reason) : '—'}</td>
            <td data-label="Job">{item.jobId ? <>
              {item.jobStatus
                ? <button type="button" className="att-schedule-job-link" onClick={() => onOpenJob(item.jobId || '')}>{item.jobId}</button>
                : <span className="att-schedule-job-unavailable">{item.jobId} · no longer available</span>}
              {item.jobStatus && <small className={`att-schedule-job-state ${jobStatusTone(item.jobStatus)}`}>Job {jobStatusLabel(item.jobStatus)}</small>}
            </> : 'No job submitted'}</td>
          </tr>)}</tbody></table>
      </div>}
      {route?.occurrenceId && history.status === 'ready' && !history.items.some(item => item.occurrenceId === route.occurrenceId) && <p role="status">This occurrence is outside the latest 100 retained records or is no longer available.</p>}
    </section>}
    </>}

    {editorOpen && loadState === 'ready' && (view !== 'edit' || editingId) && <section className={`att-schedule-panel att-schedule-editor${modal ? ' att-schedule-modal-panel' : ''}`} aria-labelledby="schedule-editor-heading">
      <div className="att-schedule-section-heading"><div><h2 id="schedule-editor-heading" tabIndex={-1}>{editingId ? `Edit schedule: ${draft.name || editingId}` : 'New Schedule'}</h2>
        <p>{draft.inlineSnapshot ? `This schedule stores the immutable ${draft.executionType === 'run' ? 'RUN definition' : 'LOAD scenario'} snapshot from your validated draft. The editor stays open behind this form.` : 'Scenario and config paths are package-relative. The next-run preview checks recurrence times only; the Server validates the package scenario before saving and again before each scheduled run.'}</p></div>
        <button type="button" className="button button-quiet" onClick={closeEditor} disabled={saving}>{modal ? 'Cancel and return to editor' : 'Cancel'}</button>
      </div>
      <p><strong>{draft.executionType.toUpperCase()}</strong>{draft.legacyDebug ? ' · Debug mode' : ''} · {draft.inlineSnapshot ? 'Immutable validated snapshot' : 'Package scenario'}. Saving never triggers a job immediately.</p>
      {!editingId && !draft.inlineSnapshot && <label>Execution type
        <select value={draft.executionType} onChange={event => updateDraft('executionType', event.currentTarget.value as 'run' | 'load')}>
          <option value="load">LOAD</option><option value="run">RUN</option>
        </select>
      </label>}
      {draft.executionType === 'run' && !draft.inlineSnapshot && <p>Validate a Case, Template, Flow, Tool or batch selection first, then choose Schedule from Review.
        <button type="button" className="button button-primary" onClick={() => navigation.navigate({ kind: 'run' })}>Choose and validate RUN</button>
      </p>}
      <p className="att-schedule-hint">Missed runs are skipped without backfill. Overlap and global capacity limits skip occurrences. History and job retention follow Server policy. Package revisions are revalidated before execution; RUN snapshots reject changed package resources. DST can skip a local time or repeat it twice.</p>
      {modal && scenarioPreviewYaml && <details className="att-schedule-snapshot-review">
        <summary>Review the validated {draft.executionType === 'run' ? 'RUN definition' : 'LOAD scenario'} snapshot</summary>
        <pre id="schedule-snapshot-content" tabIndex={0} aria-label="Validated Load scenario snapshot">{scenarioPreviewYaml}</pre>
      </details>}
      {notice && <p className="att-schedule-notice" role="status" aria-live="polite">{notice}</p>}
      {mutationError && <div className="att-schedule-error" role="alert" aria-live="assertive">
        <p>{mutationError}</p>
        {validationDiagnostics.length > 0 && <section aria-labelledby="schedule-validation-heading">
          <h3 id="schedule-validation-heading">Validation details</h3>
          <ul className="att-schedule-diagnostics">{validationDiagnostics.map((diagnostic, index) => <li key={`${diagnostic.code}:${index}`}>
            <p><code>{diagnostic.code}</code> {diagnostic.summary}</p>
            {diagnostic.detail && <p>{diagnostic.detail}</p>}
            {diagnostic.field && <p><strong>Field:</strong> <code>{diagnostic.field}</code></p>}
            {(diagnostic.file || diagnostic.sourceFile) && <p><strong>Location:</strong> <code>{diagnostic.sourceFile || diagnostic.file}{diagnostic.line ? `:${diagnostic.line}${diagnostic.sourceColumn ? `:${diagnostic.sourceColumn}` : ''}` : ''}</code></p>}
            {(diagnostic.packageId || diagnostic.environment) && <p><strong>Package / environment:</strong> {[diagnostic.packageId, diagnostic.environment].filter(Boolean).join(' · ')}</p>}
            {diagnostic.resourceId && <p><strong>Resource:</strong> <code>{diagnostic.resourceId}</code></p>}
            {diagnostic.targets?.length ? <p><strong>Targets:</strong> {diagnostic.targets.map(target => `${target.type} · ${target.id}`).join(', ')}</p> : null}
            {diagnostic.suggestion && <p><strong>Suggestion:</strong> {diagnostic.suggestion}</p>}
            {diagnostic.field && <button type="button" className="button button-quiet" onClick={() => focusDiagnosticField(diagnostic.field || '')}>Review field</button>}
          </li>)}</ul>
        </section>}
        {validationRequestId && <p><strong>Request ID:</strong> <code>{validationRequestId}</code></p>}
      </div>}
      {actionPending && <p className="att-schedule-status" role="status" aria-live="polite" aria-busy="true">Applying the confirmed action to {schedules.find(item => item.id === actionPending)?.name || 'the schedule'}…</p>}
      {packages.status === 'loading' && <p role="status" aria-busy="true">Loading configured packages…</p>}
      {packages.status === 'error' && <p className="att-schedule-error" role="alert">Configured packages could not be loaded: {packages.error.message}</p>}
      {(draft.executionType === 'load' || draft.inlineSnapshot) && <form onSubmit={event => void submitSchedule(event)}>
        <fieldset disabled={saving || saved} className="att-schedule-fields">
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
            <select id="schedule-package" value={draft.packageId} onChange={event => updateDraft('packageId', event.currentTarget.value)} required disabled={packages.status !== 'ready' || draft.inlineSnapshot}>
              <option value="">Choose a configured package</option>{packageItems.map(item => <option key={item.packageId} value={item.packageId}>{item.name ? `${item.packageId} · ${item.name}` : item.packageId}</option>)}
            </select>
          </label>
          {draft.executionType === 'run' && <label htmlFor="schedule-debug-target">RUN target
            <input id="schedule-debug-target" value={draft.debugTargetId ? `${draft.debugTargetType} · ${draft.debugTargetId}` : 'Validated Case / batch selection'} readOnly disabled />
          </label>}
          {draft.executionType === 'load' && <label htmlFor="schedule-scenario">{draft.inlineSnapshot ? 'Scenario source' : 'Scenario path'} <span className="att-schedule-hint">{draft.inlineSnapshot ? 'Immutable validated snapshot' : 'Package-relative'}</span>
            <input id="schedule-scenario" value={draft.inlineSnapshot ? 'Validated Load snapshot' : draft.scenario} onChange={event => updateDraft('scenario', event.currentTarget.value)} maxLength={512} placeholder="load/scenario.yaml" required={!draft.inlineSnapshot} disabled={draft.inlineSnapshot} />
          </label>}
          {draft.executionType === 'load' && <label htmlFor="schedule-config">Config path <span className="att-schedule-hint">Optional, package-relative</span>
            <input id="schedule-config" value={draft.config} onChange={event => updateDraft('config', event.currentTarget.value)} maxLength={512} placeholder="config/config.yaml" disabled={draft.inlineSnapshot} />
          </label>}
          <label htmlFor="schedule-environment">Environment <span className="att-schedule-hint">Optional</span>
            <input id="schedule-environment" value={draft.environment} onChange={event => updateDraft('environment', event.currentTarget.value)} maxLength={128} placeholder="SIT" disabled={draft.inlineSnapshot} />
          </label>
          <label htmlFor="schedule-timezone">Timezone · IANA <span aria-hidden="true">*</span>
            <input id="schedule-timezone" list="schedule-timezone-options" value={draft.timezone} onChange={event => updateDraft('timezone', event.currentTarget.value)} maxLength={80} aria-invalid={Boolean(draft.timezone && !isIanaTimeZone(draft.timezone))} required />
            <datalist id="schedule-timezone-options">{timezoneOptions.map(zone => <option key={zone} value={zone} />)}</datalist>
          </label>
          <label htmlFor="schedule-recurrence">Recurrence
            <select id="schedule-recurrence" value={draft.recurrence} onChange={event => updateDraft('recurrence', event.currentTarget.value as ScheduleRecurrence)}>
              <option value="once">Once</option><option value="daily">Daily</option><option value="weekly">Weekly</option><option value="cron">Cron</option>
            </select>
          </label>
          {draft.recurrence !== 'cron' && draft.recurrence !== 'once' && <label htmlFor="schedule-time">Local time · {draft.timezone || 'timezone required'}
            <input id="schedule-time" type="time" value={draft.timeOfDay} onChange={event => updateDraft('timeOfDay', event.currentTarget.value)} required />
          </label>}
          {draft.recurrence === 'once' && <label htmlFor="schedule-run-at">Run at · ISO-8601 with UTC offset
            <input id="schedule-run-at" value={draft.runAt} onChange={event => updateDraft('runAt', event.currentTarget.value)} placeholder="2026-10-20T02:00:00+08:00" required />
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
          {draft.executionType === 'load' && <label className="att-schedule-load-options" htmlFor="schedule-load-options">Load options · JSON object
            <textarea id="schedule-load-options" rows={5} spellCheck={false} value={draft.load} onChange={event => updateDraft('load', event.currentTarget.value)} aria-describedby="schedule-load-help" disabled={draft.inlineSnapshot} />
            <span id="schedule-load-help" className="att-schedule-hint">{draft.inlineSnapshot ? 'Load values are part of this immutable scenario snapshot.' : 'Optional Load overrides. The Server revalidates this scenario before submitting each job.'}</span>
          </label>}
        </div>
        <section className="att-schedule-preview" aria-labelledby="schedule-preview-title">
          <h3 id="schedule-preview-title">Next-occurrences preview</h3>
          <p role="status" aria-live="polite" aria-busy={preview.status === 'loading'}>
            {preview.status === 'loading' ? 'Calculating the next occurrence on the Server…'
              : preview.status === 'ready' ? `Next run: ${formatScheduleTime(preview.nextRunAt, draft.timezone)} · ${draft.timezone}`
                : preview.status === 'unchanged' ? (preview.nextRunAt
                  ? `Saved trigger unchanged · next run: ${formatScheduleTime(preview.nextRunAt, draft.timezone)} · ${draft.timezone}`
                  : 'Saved trigger unchanged · no next run scheduled · the Server keeps the current trigger.')
                  : preview.status === 'error' ? `Preview unavailable: ${preview.error}`
                    : !isIanaTimeZone(draft.timezone) ? 'Enter a valid IANA timezone to preview this schedule.'
                      : 'Enter a valid recurrence to preview the next run.'}
          </p>
          {preview.status === 'ready' && <ol className="att-schedule-occurrence-preview" aria-label="Next five scheduled occurrences">
            {(preview.nextOccurrences || []).map(instant => <li key={instant}>{formatScheduleTime(instant, draft.timezone)}</li>)}
          </ol>}
          <p className="att-schedule-hint">This preview calculates the next five occurrence times from timezone and cron. It does not validate the package or test snapshot. The Server validates the full schedule when you save and validates it again before submitting each occurrence; it applies timezone and DST rules and records actual occurrences in UTC. Saving an unchanged trigger keeps the schedule the Server already holds, so a completed one-time schedule can still be renamed or re-described.</p>
        </section>
        <label className="att-schedule-enable"><input type="checkbox" checked={draft.enabled} onChange={event => updateDraft('enabled', event.currentTarget.checked)} /> Enable after saving</label>
        </fieldset>
        <div className="att-schedule-form-actions">
          <button className="button button-primary" type="submit" disabled={saving || saved || !previewIsCurrent || packages.status !== 'ready' || packageItems.length === 0}>
            {saving ? 'Saving…' : saved ? 'Saved' : editingId ? 'Save changes' : 'Save schedule'}
          </button>
          {modal && <button className="button button-quiet" type="button" onClick={closeEditor} disabled={saving}>Return to editor</button>}
          {dirty && <span role="status">Unsaved schedule changes</span>}
        </div>
      </form>}
    </section>}

    <dialog ref={discardDialog} className="att-schedule-discard" aria-labelledby="schedule-discard-title" onCancel={event => { event.preventDefault(); setDiscardRequested(false); }}>
      <h2 id="schedule-discard-title">Discard unsaved schedule changes?</h2>
      <button type="button" className="button button-primary" autoFocus onClick={() => setDiscardRequested(false)}>Keep editing</button>
      <button type="button" className="button button-danger" onClick={discardEditor}>Discard changes</button>
    </dialog>

    {!modal && <dialog className="att-schedule-confirm" ref={confirmationDialog} aria-labelledby="schedule-confirm-title" aria-describedby="schedule-confirm-description"
      onCancel={event => { event.preventDefault(); setPendingConfirmation(null); }}>
      <h2 id="schedule-confirm-title">{pendingConfirmation?.action === 'run' ? 'Run this schedule now?' : pendingConfirmation?.action === 'toggle' ? `${pendingConfirmation.schedule.enabled ? 'Pause' : 'Resume'} this schedule?` : 'Delete this schedule?'}</h2>
      <p id="schedule-confirm-description">{pendingConfirmation?.action === 'run'
        ? `Submit ${pendingConfirmation.schedule.name} as a ${pendingConfirmation.schedule.executionType.toUpperCase()} job now? The Server will apply its rate and capacity limits.`
        : pendingConfirmation?.action === 'toggle' ? `${pendingConfirmation.schedule.enabled ? 'Pause future occurrences for' : 'Enable future execution of'} ${pendingConfirmation.schedule.name}? Already accepted jobs continue running.`
          : `Delete ${pendingConfirmation?.schedule.name || 'this schedule'}? Any already accepted jobs will continue running.`}</p>
      <div className="att-schedule-confirm-actions">
        <button ref={cancelConfirmation} type="button" className="button button-quiet" onClick={() => setPendingConfirmation(null)}>Cancel</button>
        <button type="button" className={`button ${pendingConfirmation?.action === 'delete' ? 'button-danger' : 'button-primary'}`} onClick={() => void confirmAction()}>
          {pendingConfirmation?.action === 'run' ? 'Confirm Run Now' : pendingConfirmation?.action === 'toggle' ? 'Confirm state change' : 'Confirm delete'}
        </button>
      </div>
    </dialog>}
  </>;

  if (modal) return <dialog className="att-schedule-create-dialog" ref={modalDialog} aria-label="New Schedule"
    onClick={event => { if (event.target === event.currentTarget) closeEditor(); }}>{pageContent}</dialog>;
  return <section className="page-content att-schedules-page" aria-label="Schedules">{pageContent}</section>;
}
