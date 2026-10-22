// @vitest-environment jsdom
import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NavigationGuardContext, type NavigationGuardApi } from '../../router';
import { ApiError, type AsyncState, type PackageSummary } from '../../types';
import { ScheduledLoadPage } from './ScheduledLoadPage';
import type { ScheduleDefinition, ScheduleOccurrence, SchedulesApi } from './types';

const sampleSchedule: ScheduleDefinition = {
  executionType: 'load', id: 'S-NIGHTLY', name: 'Nightly payments', description: 'Checkout soak', tags: ['nightly'],
  packageId: 'payments', scenario: 'load/nightly.yaml', config: 'config/config.yaml', environment: 'SIT',
  timezone: 'Asia/Hong_Kong', cron: '0 2 * * *', load: {}, maxConcurrentRuns: 1, enabled: true,
  nextRunAt: '2026-10-15T18:00:00Z',
};
const packages: AsyncState<PackageSummary[]> = { status: 'ready', data: [{ packageId: 'payments', name: 'Payments' }] };
const occurrences: ScheduleOccurrence[] = [
  { occurrenceId: 'O-SKIPPED', scheduleId: sampleSchedule.id, scheduledFor: '2026-10-14T18:00:00Z', triggerType: 'scheduled', status: 'skipped', reason: 'schedule-overlap-limit' },
  { occurrenceId: 'O-SUBMITTED', scheduleId: sampleSchedule.id, scheduledFor: '2026-10-13T18:00:00Z', triggerType: 'scheduled', status: 'submitted', jobId: 'JOB-204', jobStatus: 'PASS' },
  { occurrenceId: 'O-EXPIRED', scheduleId: sampleSchedule.id, scheduledFor: '2026-10-12T18:00:00Z', triggerType: 'scheduled', status: 'submitted', jobId: 'JOB-099', jobStatus: null },
  { occurrenceId: 'O-FAILED', scheduleId: sampleSchedule.id, scheduledFor: '2026-10-11T18:00:00Z', triggerType: 'scheduled', status: 'submitted', jobId: 'JOB-207', jobStatus: 'FAIL' },
  { occurrenceId: 'O-RUNNING', scheduleId: sampleSchedule.id, scheduledFor: '2026-10-10T18:00:00Z', triggerType: 'scheduled', status: 'submitted', jobId: 'JOB-205', jobStatus: 'RUNNING' },
];

if (!HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); };
}
if (!Element.prototype.scrollIntoView) Element.prototype.scrollIntoView = () => {};

function makeApi(overrides: Partial<SchedulesApi> = {}): SchedulesApi {
  return {
    listSchedules: vi.fn(async () => ({ items: [] })),
    previewSchedule: vi.fn(async input => ({ ...input, nextRunAt: '2026-10-15T18:00:00Z', nextOccurrences: [
      '2026-10-15T18:00:00Z', '2026-10-16T18:00:00Z', '2026-10-17T18:00:00Z', '2026-10-18T18:00:00Z', '2026-10-19T18:00:00Z',
    ] })),
    createSchedule: vi.fn(async input => ({ ...sampleSchedule, ...input, id: 'S-CREATED' })),
    updateSchedule: vi.fn(async (id, input) => ({ ...sampleSchedule, ...input, id })),
    setScheduleEnabled: vi.fn(async (id, enabled) => ({ ...sampleSchedule, id, enabled })),
    triggerSchedule: vi.fn(async () => ({ occurrenceId: 'O-NOW', scheduledFor: '2026-10-14T10:00:00Z', triggerType: 'manual', status: 'submitted', jobId: 'JOB-NOW' })),
    deleteSchedule: vi.fn(async id => ({ scheduleId: id, status: 'DELETED' })),
    getScheduleExecutions: vi.fn(async () => ({ items: occurrences })),
    ...overrides,
  };
}

let host: HTMLDivElement;
let root: Root;
const navigation: NavigationGuardApi = {
  navigate: vi.fn(),
  registerUnsavedChanges: vi.fn(() => () => {}),
};

beforeEach(() => {
  vi.useFakeTimers();
  vi.mocked(navigation.navigate).mockClear();
  vi.mocked(navigation.registerUnsavedChanges).mockClear();
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true);
  vi.spyOn(HTMLDialogElement.prototype, 'showModal').mockImplementation(function (this: HTMLDialogElement) { this.setAttribute('open', ''); });
  vi.spyOn(HTMLDialogElement.prototype, 'close').mockImplementation(function (this: HTMLDialogElement) { this.removeAttribute('open'); });
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

async function render(api: SchedulesApi, onOpenJob = vi.fn()) {
  await act(async () => root.render(<NavigationGuardContext.Provider value={navigation}>
    <ScheduledLoadPage api={api} packages={packages} enabled onOpenJob={onOpenJob} />
  </NavigationGuardContext.Provider>));
  await advance(300);
  return onOpenJob;
}

async function advance(milliseconds = 300) {
  await act(async () => { await vi.advanceTimersByTimeAsync(milliseconds); });
}

async function click(element: Element) {
  await act(async () => { element.dispatchEvent(new MouseEvent('click', { bubbles: true })); });
}

async function change(element: HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement, value: string) {
  await act(async () => {
    const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(element), 'value')?.set;
    setter?.call(element, value);
    element.dispatchEvent(new Event(element instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  });
}

describe('ScheduledLoadPage', () => {
  it('lands on definitions only, filters RUN/LOAD and owners, and opens a separate New view', async () => {
    const api = makeApi({ listSchedules: vi.fn(async () => ({ items: [
      { ...sampleSchedule, createdBy: 'alice' },
      { ...sampleSchedule, id: 'S-RUN', name: 'Reconciliation', executionType: 'run' as const, inlineSnapshot: true,
        debugTarget: { type: 'flow', id: 'reconcile' }, createdBy: 'bob', environment: 'UAT' },
    ] })) });
    await render(api);
    expect(host.querySelector('form')).toBeNull();
    expect(api.previewSchedule).not.toHaveBeenCalled();
    expect(api.getScheduleExecutions).not.toHaveBeenCalled();
    await change(host.querySelectorAll('.att-schedule-filters select')[0] as HTMLSelectElement, 'run');
    expect(host.textContent).toContain('Reconciliation');
    expect(host.textContent).not.toContain('Nightly payments');
    await change(host.querySelectorAll('.att-schedule-filters select')[3] as HTMLSelectElement, 'alice');
    expect(host.textContent).toContain('No schedules match');
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Clear filters')!);
    expect(host.textContent).toContain('2 of 2 schedules shown.');
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'New Schedule')!);
    expect(host.querySelector('form')).not.toBeNull();
    expect(host.querySelector('#schedule-list-heading')).toBeNull();
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Cancel')!);
    expect(host.querySelector('form')).toBeNull();
    expect(api.createSchedule).not.toHaveBeenCalled();
  });

  it('guards dirty Cancel and tab reload without writing browser storage', async () => {
    const setStorage = vi.spyOn(Storage.prototype, 'setItem');
    await render(makeApi());
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'New Schedule')!);
    await change(host.querySelector('#schedule-name') as HTMLInputElement, 'Keep this');
    const unload = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(unload);
    expect(unload.defaultPrevented).toBe(true);
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Cancel')!);
    expect(host.querySelector('.att-schedule-discard')?.hasAttribute('open')).toBe(true);
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Keep editing')!);
    expect((host.querySelector('#schedule-name') as HTMLInputElement).value).toBe('Keep this');
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Back to Schedules')!);
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Discard changes')!);
    expect(host.querySelector('form')).toBeNull();
    expect(setStorage).not.toHaveBeenCalled();
  });

  it.each(['run', 'load'] as const)('saves a validated %s draft ID without reconstructing its execution inputs', async executionType => {
    const api = makeApi();
    const initialDraft = { packageId: 'payments', environment: 'UAT', executionType, draftId: executionType === 'run' ? 'R-FROZEN' : 'A-FROZEN',
      previewYaml: 'Safe server preview', target: { type: 'tool', id: 'send' } };
    await act(async () => root.render(<NavigationGuardContext.Provider value={navigation}>
      <ScheduledLoadPage api={api} packages={packages} enabled onOpenJob={vi.fn()} route={{ kind: 'schedules', view: 'new' }} initialDraft={initialDraft} />
    </NavigationGuardContext.Provider>));
    await advance(400);
    expect(api.createSchedule).not.toHaveBeenCalled();
    expect(api.triggerSchedule).not.toHaveBeenCalled();
    await click(host.querySelector('button[type="submit"]')!);
    expect(api.createSchedule).toHaveBeenCalledWith(expect.objectContaining({
      executionType, draftId: initialDraft.draftId, environment: 'UAT', enabled: false, schemaVersion: 'att-schedule/v2',
    }));
    expect(vi.mocked(api.createSchedule).mock.calls[0][0]).not.toHaveProperty('scenario');
    expect(api.triggerSchedule).not.toHaveBeenCalled();
  });

  it('defaults a validated Load or Quick Load intent to a LOAD schedule', async () => {
    const api = makeApi();
    const initialDraft = { packageId: 'payments', environment: 'UAT', draftId: 'A-FROZEN', previewYaml: 'Safe server preview' };
    await act(async () => root.render(<NavigationGuardContext.Provider value={navigation}>
      <ScheduledLoadPage api={api} packages={packages} enabled onOpenJob={vi.fn()} route={{ kind: 'schedules', view: 'new' }} initialDraft={initialDraft} />
    </NavigationGuardContext.Provider>));
    await advance(400);
    await click(host.querySelector('button[type="submit"]')!);
    expect(api.createSchedule).toHaveBeenCalledWith(expect.objectContaining({
      executionType: 'load', draftId: 'A-FROZEN', environment: 'UAT', enabled: false, schemaVersion: 'att-schedule/v2',
    }));
    expect(api.triggerSchedule).not.toHaveBeenCalled();
  });

  it('shows an explicit return path when a validated Schedule intent is missing after refresh', async () => {
    const api = makeApi(); const onCancel = vi.fn();
    await act(async () => root.render(<NavigationGuardContext.Provider value={navigation}>
      <ScheduledLoadPage api={api} packages={packages} enabled onOpenJob={vi.fn()} onCancel={onCancel}
        route={{ kind: 'schedules', view: 'new', intent: 'validated', returnTo: '#/advanced-load?package=payments' }} />
    </NavigationGuardContext.Provider>));
    await advance(400);
    expect(host.textContent).toContain('Validated execution intent unavailable');
    expect(host.querySelector('#schedule-name')).toBeNull();
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Return to originating editor')!);
    expect(onCancel).toHaveBeenCalledOnce();
  });

  it('explains an expired catalog draft and returns to the source editor after save failure', async () => {
    const api = makeApi({ createSchedule: vi.fn(async () => { throw new ApiError('Draft unavailable', { status: 404, requestId: 'REQ-DRAFT' }); }) });
    const onCancel = vi.fn();
    const initialDraft = { packageId: 'payments', environment: 'SIT', draftId: `C${'a'.repeat(32)}`,
      previewYaml: 'schemaVersion: att-load/v1.7\n', executionType: 'load' as const };
    await act(async () => root.render(<NavigationGuardContext.Provider value={navigation}>
      <ScheduledLoadPage api={api} packages={packages} enabled onOpenJob={vi.fn()} onCancel={onCancel} onSaved={vi.fn()}
        route={{ kind: 'schedules', view: 'new', intent: 'validated', returnTo: '#/advanced-load?package=payments' }} initialDraft={initialDraft} />
    </NavigationGuardContext.Provider>));
    await advance(400);
    await click(host.querySelector('button[type="submit"]')!);
    expect(api.createSchedule).toHaveBeenCalledOnce();
    expect(host.textContent).toContain('validated execution draft expired or is unavailable');
    expect(host.textContent).toContain('Return to the originating editor');
    expect(api.triggerSchedule).not.toHaveBeenCalled();
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Return to originating editor')!);
    expect(onCancel).toHaveBeenCalledOnce();
  });

  it('blocks saving after a catalog draft expires while the Schedule form is open', async () => {
    const api = makeApi();
    const onCancel = vi.fn();
    await act(async () => root.render(<NavigationGuardContext.Provider value={navigation}>
      <ScheduledLoadPage api={api} packages={packages} enabled onOpenJob={vi.fn()} onCancel={onCancel}
        route={{ kind: 'schedules', view: 'new', intent: 'validated', returnTo: '#/advanced-load' }}
        initialDraft={{ packageId: 'payments', environment: 'SIT', draftId: `C${'d'.repeat(32)}`,
          previewYaml: 'schemaVersion: att-load/v1.7\n', expiresAt: new Date(Date.now() + 1000).toISOString() }} />
    </NavigationGuardContext.Provider>));
    await advance(400);
    expect(host.textContent).toContain('Validated draft expires');
    await advance(700);
    expect(host.textContent).toContain('This validated execution draft has expired.');
    await click(host.querySelector('button[type="submit"]')!);
    expect(api.createSchedule).not.toHaveBeenCalled();
    expect(host.textContent).toContain('Return to the originating editor');
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Return to originating editor')!);
    expect(onCancel).toHaveBeenCalledOnce();
  });

  it('loads an authorized edit deep link and keeps missing/forbidden definitions out of the form', async () => {
    const api = makeApi({ listSchedules: vi.fn(async () => ({ items: [sampleSchedule] })) });
    const show = async (id: string, source = api) => {
      await act(async () => root.render(<NavigationGuardContext.Provider value={navigation}>
        <ScheduledLoadPage key={id} api={source} packages={packages} enabled onOpenJob={vi.fn()} route={{ kind: 'schedules', view: 'edit', scheduleId: id }} />
      </NavigationGuardContext.Provider>));
      await advance(300);
    };
    await show(sampleSchedule.id);
    expect((host.querySelector('#schedule-name') as HTMLInputElement).value).toBe(sampleSchedule.name);
    expect(host.querySelector('#schedule-list-heading')).toBeNull();
    await show('missing');
    expect(host.textContent).toContain('unavailable');
    expect(host.querySelector('form')).toBeNull();
    await show('forbidden', makeApi({ listSchedules: vi.fn(async () => { throw new ApiError('Forbidden', { status: 403 }); }) }));
    expect(host.textContent).toContain('Your account does not have operational access to Schedules.');
    expect(host.querySelector('form')).toBeNull();
  });

  it('resolves history/list/edit by view and ID without resetting dirty edits on refresh', async () => {
    const other = { ...sampleSchedule, id: 'S-OTHER', name: 'Other schedule' };
    const api = makeApi({ listSchedules: vi.fn(async () => ({ items: [sampleSchedule, other] })) });
    let activeApi = api;
    const show = async (view?: 'edit' | 'history', scheduleId?: string) => {
      await act(async () => root.render(<NavigationGuardContext.Provider value={navigation}>
        <ScheduledLoadPage api={activeApi} packages={packages} enabled onOpenJob={vi.fn()} route={{ kind: 'schedules', view, scheduleId }} />
      </NavigationGuardContext.Provider>));
      await advance();
    };
    await show('history', sampleSchedule.id);
    expect(host.querySelector('#schedule-history-heading')).not.toBeNull();
    await show();
    await show('edit', sampleSchedule.id);
    expect((host.querySelector('#schedule-name') as HTMLInputElement).value).toBe(sampleSchedule.name);
    await change(host.querySelector('#schedule-name')!, 'Unsaved name');
    activeApi = { ...api, listSchedules: vi.fn(async () => ({ items: [{ ...sampleSchedule }, { ...other }] })) };
    await show('edit', sampleSchedule.id);
    expect((host.querySelector('#schedule-name') as HTMLInputElement).value).toBe('Unsaved name');
    await show('history', other.id);
    await show('edit', other.id);
    expect((host.querySelector('#schedule-name') as HTMLInputElement).value).toBe(other.name);
    await show('edit', 'missing');
    expect(host.querySelector('form')).toBeNull();
    expect(host.textContent).toContain('unavailable');
    await show();
    await show('edit', sampleSchedule.id);
    expect((host.querySelector('#schedule-name') as HTMLInputElement).value).toBe(sampleSchedule.name);
  });

  it('explains legacy RUN replacement without presenting a LOAD form', async () => {
    const legacy = { ...sampleSchedule, executionType: 'run' as const, inlineSnapshot: false };
    const api = makeApi({ listSchedules: vi.fn(async () => ({ items: [legacy] })) });
    await act(async () => root.render(<NavigationGuardContext.Provider value={navigation}>
      <ScheduledLoadPage api={api} packages={packages} enabled onOpenJob={vi.fn()} route={{ kind: 'schedules', view: 'edit', scheduleId: legacy.id }} />
    </NavigationGuardContext.Provider>));
    await advance();
    expect(host.textContent).toContain('legacy RUN record has no editable validated snapshot');
    expect(host.textContent).toContain('Choose and validate RUN');
    expect(host.querySelector('#schedule-scenario')).toBeNull();
  });

  it('requires explicit confirmation before resuming a paused definition', async () => {
    const api = makeApi({ listSchedules: vi.fn(async () => ({ items: [{ ...sampleSchedule, enabled: false }] })) });
    await render(api);
    await click(host.querySelector('[aria-label="Resume Nightly payments"]')!);
    expect(api.setScheduleEnabled).not.toHaveBeenCalled();
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Confirm state change')!);
    expect(api.setScheduleEnabled).toHaveBeenCalledWith(sampleSchedule.id, true);
  });
  it('shows a configured schedule, explains skipped occurrences in History, and links submitted jobs', async () => {
    const api = makeApi({ listSchedules: vi.fn(async () => ({ items: [sampleSchedule] })) });
    const onOpenJob = await render(api);
    expect(host.querySelector('h1')?.textContent).toBe('Schedules');
    expect(host.querySelector('.att-query-filter-panel[aria-label="Schedule filters"]')).not.toBeNull();
    expect(host.querySelector('.att-query-results-panel[aria-label="Schedule results"]')).not.toBeNull();
    expect(host.textContent).toContain('Nightly payments');
    await click(host.querySelector('[aria-label="History Nightly payments"]')!);
    await advance(0);
    expect(host.textContent).toContain('Skipped because this schedule already reached its concurrent-run limit.');
    const occurrenceLink = host.querySelector<HTMLAnchorElement>('.att-occurrence-link');
    expect(occurrenceLink?.getAttribute('href')).toContain(`#/schedules/${sampleSchedule.id}/history/O-SKIPPED`);
    expect(occurrenceLink?.getAttribute('aria-label')).toBe('View occurrence O-SKIPPED');
    expect(host.querySelectorAll('.att-occurrence-table button.att-schedule-job-link')).toHaveLength(3);
    expect(host.querySelector('.att-occurrence-table.att-data-table')).not.toBeNull();
    expect(host.querySelector('.att-occurrence-table .att-status-badge.att-status-skipped')?.textContent).toBe('skipped');
    await click([...host.querySelectorAll('.att-schedule-job-link')][0]);
    expect(onOpenJob).toHaveBeenCalledWith('JOB-204');
    expect(host.textContent).toContain('JOB-099 · no longer available');
    expect(api.getScheduleExecutions).toHaveBeenCalledWith(sampleSchedule.id, 100, expect.any(AbortSignal));
  });

  it('shows the job outcome next to the occurrence status and keeps expired jobs non-navigable', async () => {
    const api = makeApi({ listSchedules: vi.fn(async () => ({ items: [sampleSchedule] })) });
    await render(api);
    await click(host.querySelector('[aria-label="History Nightly payments"]')!);
    await advance(0);

    const rowFor = (occurrenceId: string) => [...host.querySelectorAll('.att-occurrence-table tbody tr')]
      .find(row => row.querySelector('.att-occurrence-link code')?.textContent === occurrenceId)!;
    const jobState = (occurrenceId: string) => rowFor(occurrenceId).querySelector('[data-label="Job"] .att-status-badge');

    expect(rowFor('O-SUBMITTED').querySelector('[data-label="Status"] .att-status-badge')?.textContent).toBe('submitted');
    expect(jobState('O-SUBMITTED')?.textContent).toBe('Job Passed');
    expect(jobState('O-SUBMITTED')?.className).toContain('att-status-pass');
    expect(jobState('O-FAILED')?.textContent).toBe('Job Failed');
    expect(jobState('O-FAILED')?.className).toContain('att-status-fail');
    expect(jobState('O-RUNNING')?.textContent).toBe('Job Running');
    expect(jobState('O-RUNNING')?.className).toContain('att-status-running');

    const expired = rowFor('O-EXPIRED');
    expect(expired.querySelector('[data-label="Job"] .att-status-badge')).toBeNull();
    expect(expired.querySelector('button.att-schedule-job-link')).toBeNull();
    expect(expired.querySelector('.att-schedule-job-unavailable')?.textContent).toContain('no longer available');
  });

  it('renders a linked deleted job as unavailable without a job link', async () => {
    const deleted = { ...occurrences[0], occurrenceId: 'O-DELETED', jobId: 'JOB-DELETED', jobStatus: null };
    const api = makeApi({ listSchedules: vi.fn(async () => ({ items: [sampleSchedule] })), getScheduleExecutions: vi.fn(async () => ({ items: [deleted] })) });
    await render(api);
    await click(host.querySelector('[aria-label="History Nightly payments"]')!);
    await advance(0);
    expect(host.querySelector('.att-schedule-job-unavailable')?.textContent).toContain('JOB-DELETED · no longer available');
    expect(host.querySelector('button.att-schedule-job-link')).toBeNull();
  });

  it('shows and copies the full occurrence ID while keeping its table link compact', async () => {
    const occurrenceId = `OCC-${'load-run-42-'.repeat(4)}`;
    const clipboardWrite = vi.fn(async (_value: string) => {});
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: clipboardWrite } });
    const api = makeApi({ listSchedules: vi.fn(async () => ({ items: [sampleSchedule] })),
      getScheduleExecutions: vi.fn(async () => ({ items: [{ ...occurrences[0], occurrenceId }] })) });
    await render(api);
    await click(host.querySelector('[aria-label="History Nightly payments"]')!);
    await advance(0);

    const link = host.querySelector<HTMLAnchorElement>('.att-occurrence-link')!;
    expect(link.textContent).toContain('…');
    expect(link.title).toBe(`Occurrence ID: ${occurrenceId}`);
    const fullId = host.querySelector('.att-occurrence-id-details')!;
    await click(fullId.querySelector('summary')!);
    expect((fullId as HTMLDetailsElement).open).toBe(true);
    expect(fullId.querySelector('code')?.textContent).toBe(occurrenceId);
    await click(fullId.querySelector('button')!);
    expect(clipboardWrite).toHaveBeenCalledWith(occurrenceId);
    expect(host.textContent).toContain(`Copied full occurrence ID ${occurrenceId}.`);
  });

  it('labels consumed one-time schedules as completed and filters them separately', async () => {
    const completed: ScheduleDefinition = {
      ...sampleSchedule, id: 'S-ONCE', name: 'One-time import', runAt: '2026-10-16T10:00:00Z',
      cron: undefined, nextRunAt: null,
    };
    const api = makeApi({ listSchedules: vi.fn(async () => ({ items: [sampleSchedule, completed] })) });
    await render(api);
    expect(host.textContent).toContain('One-time import');
    expect(host.querySelector('.att-schedule-table tbody tr:last-child [data-label="Status"]')?.textContent).toContain('Completed');
    expect(host.querySelector('.att-schedule-table tbody tr:last-child [data-label="Next run"]')?.textContent).toContain('No next run scheduled');

    const statusFilter = host.querySelectorAll('.att-schedule-filters select')[1] as HTMLSelectElement;
    await change(statusFilter, 'completed');
    expect(host.querySelector('.att-schedule-status')?.textContent).toBe('1 of 2 schedules shown.');
    expect(host.textContent).toContain('One-time import');
    expect(host.textContent).not.toContain('Nightly payments');
    await change(statusFilter, 'enabled');
    expect(host.textContent).toContain('Nightly payments');
    expect(host.textContent).not.toContain('One-time import');
  });

  it('saves a consumed one-time schedule metadata edit without a new preview', async () => {
    const completed: ScheduleDefinition = {
      ...sampleSchedule, id: 'S-ONCE', name: 'One-time import', runAt: '2026-10-16T10:00:00Z',
      cron: undefined, nextRunAt: null,
    };
    const api = makeApi({ listSchedules: vi.fn(async () => ({ items: [completed] })) });
    await render(api);
    vi.mocked(api.previewSchedule).mockClear();

    await click(host.querySelector('[aria-label="Edit One-time import"]')!);
    await advance(400);
    expect(host.textContent).toContain('Saved trigger unchanged');
    expect(host.textContent).not.toContain('Preview unavailable');
    expect(api.previewSchedule).not.toHaveBeenCalled();

    const save = [...host.querySelectorAll('button')].find(button => button.textContent === 'Save changes') as HTMLButtonElement;
    expect(save.disabled).toBe(false);
    await change(host.querySelector('#schedule-description') as HTMLInputElement, 'Post-mortem notes');
    await click(save);
    await advance(0);
    expect(api.previewSchedule).not.toHaveBeenCalled();
    expect(api.updateSchedule).toHaveBeenCalledTimes(1);
    const payload = vi.mocked(api.updateSchedule).mock.calls[0][1];
    expect(payload.name).toBe('One-time import');
    expect(payload.description).toBe('Post-mortem notes');
    expect(payload.runAt).toBe('2026-10-16T10:00:00.000Z');
    expect(payload.cron).toBeUndefined();
  });

  it('switches an existing schedule between cron and a one-time trigger with a single trigger field', async () => {
    const completed: ScheduleDefinition = {
      ...sampleSchedule, id: 'S-ONCE', name: 'One-time import', runAt: '2026-10-16T10:00:00Z',
      cron: undefined, nextRunAt: null,
    };
    const api = makeApi({ listSchedules: vi.fn(async () => ({ items: [sampleSchedule, completed] })) });
    await render(api);

    await click(host.querySelector('[aria-label="Edit Nightly payments"]')!);
    await change(host.querySelector('#schedule-recurrence') as HTMLSelectElement, 'once');
    await change(host.querySelector('#schedule-run-at') as HTMLInputElement, '2026-11-02T02:00:00+08:00');
    await advance(400);
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Save changes')!);
    await advance(0);
    expect(api.updateSchedule).toHaveBeenCalledTimes(1);
    const cronToOnce = vi.mocked(api.updateSchedule).mock.calls[0][1];
    expect(cronToOnce.runAt).toBe('2026-11-01T18:00:00.000Z');
    expect(cronToOnce.cron).toBeUndefined();

    await click(host.querySelector('[aria-label="Edit One-time import"]')!);
    await change(host.querySelector('#schedule-recurrence') as HTMLSelectElement, 'cron');
    await change(host.querySelector('#schedule-cron') as HTMLInputElement, '30 3 * * *');
    await advance(400);
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Save changes')!);
    await advance(0);
    expect(api.updateSchedule).toHaveBeenCalledTimes(2);
    const onceToCron = vi.mocked(api.updateSchedule).mock.calls[1][1];
    expect(onceToCron.cron).toBe('30 3 * * *');
    expect(onceToCron.runAt).toBeUndefined();
  });

  it('requires a server preview before creating a disabled schedule by default', async () => {
    const api = makeApi();
    await render(api);
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'New Schedule')!);
    await change(host.querySelector('#schedule-timezone') as HTMLInputElement, 'Asia/Hong_Kong');
    await advance(300);
    expect(host.textContent).toContain('This preview calculates the next five occurrence times from timezone and cron. It does not validate the package or test snapshot.');
    expect(host.textContent).toContain('Oct 20, 2026');
    expect(host.querySelectorAll('.att-schedule-occurrence-preview li')).toHaveLength(5);
    const save = [...host.querySelectorAll('button')].find(button => button.textContent === 'Save schedule') as HTMLButtonElement;
    expect(save.disabled).toBe(false);
    expect((host.querySelector('.att-schedule-enable input') as HTMLInputElement).checked).toBe(false);

    await change(host.querySelector('#schedule-name') as HTMLInputElement, 'Nightly payments');
    await change(host.querySelector('#schedule-package') as HTMLSelectElement, 'payments');
    await change(host.querySelector('#schedule-scenario') as HTMLInputElement, 'load/nightly.yaml');
    await click(save);
    await advance(0);
    expect(api.createSchedule).toHaveBeenCalledWith(expect.objectContaining({
      name: 'Nightly payments', packageId: 'payments', scenario: 'load/nightly.yaml', enabled: false,
      timezone: expect.any(String), cron: '0 2 * * *',
    }));
    expect(host.textContent).toContain('Saved Nightly payments');
    expect(host.textContent).toContain('Next run:');
    expect(api.previewSchedule).toHaveBeenCalled();
    expect(vi.mocked(api.previewSchedule).mock.calls.at(-1)?.[0]).toEqual({ timezone: expect.any(String), cron: '0 2 * * *' });
    expect(navigation.registerUnsavedChanges).toHaveBeenCalledWith(true, 'Schedules changes');
  });

  it('shows safe Worker validation diagnostics, package context, target, and request ID after a rejected save', async () => {
    const api = makeApi({ createSchedule: vi.fn(async () => {
      throw new ApiError('Load options must include a positive duration.', {
        status: 400, code: 'ATT-SERVER-INVALID-REQUEST', requestId: 'schedule-request-42',
        diagnostics: [{ code: 'WORKER_REQUEST_INVALID', summary: 'Load options must include a positive duration.',
          field: 'workloads[0].load.duration', file: 'load/nightly.yaml', line: 8, packageId: 'payments', environment: 'SIT',
          targets: [{ type: 'template', id: 'CHECKOUT' }] }],
      });
    }) });
    await render(api);
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'New Schedule')!);
    await change(host.querySelector('#schedule-timezone') as HTMLInputElement, 'Asia/Hong_Kong');
    await advance(300);
    await change(host.querySelector('#schedule-name') as HTMLInputElement, 'Nightly payments');
    await change(host.querySelector('#schedule-package') as HTMLSelectElement, 'payments');
    await change(host.querySelector('#schedule-scenario') as HTMLInputElement, 'load/nightly.yaml');
    await click(host.querySelector('.att-schedule-form-actions button[type="submit"]')!);
    await advance(0);

    const alert = host.querySelector('[role="alert"]');
    expect(alert?.textContent).toContain('WORKER_REQUEST_INVALID');
    expect(alert?.textContent).toContain('Load options must include a positive duration.');
    expect(alert?.textContent).toContain('workloads[0].load.duration');
    expect(alert?.textContent).toContain('load/nightly.yaml:8');
    expect(alert?.textContent).toContain('payments · SIT');
    expect(alert?.textContent).toContain('template · CHECKOUT');
    expect(alert?.textContent).toContain('schedule-request-42');
  });

  it('opens a validated Load snapshot in the full-page schedule editor and returns on cancel', async () => {
    const api = makeApi();
    const onCancel = vi.fn();
    await act(async () => root.render(<NavigationGuardContext.Provider value={navigation}>
      <ScheduledLoadPage api={api} packages={packages} enabled onOpenJob={vi.fn()} route={{ kind: 'schedules', view: 'new' }} onCancel={onCancel}
        initialDraft={{ packageId: 'payments', environment: 'SIT', draftId: 'A0123456789ABCDEF0123456789ABCDEF',
          previewYaml: 'schemaVersion: att-load/v1.6\nworkloads: []\n' }} />
    </NavigationGuardContext.Provider>));
    await advance(300);

    expect(host.querySelector('.att-schedule-create-dialog')).toBeNull();
    expect(host.querySelector('#schedule-editor-heading')?.textContent).toBe('New Schedule');
    expect(host.querySelector('#schedule-list-heading')).toBeNull();
    expect(host.querySelector('.att-schedule-snapshot-review pre')?.textContent).toBe('schemaVersion: att-load/v1.6\nworkloads: []\n');
    expect((host.querySelector('#schedule-environment') as HTMLInputElement).value).toBe('SIT');
    expect(navigation.navigate).not.toHaveBeenCalled();
    expect(navigation.registerUnsavedChanges).toHaveBeenCalledWith(false, 'Schedules changes');

    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Cancel')!);
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(navigation.navigate).not.toHaveBeenCalled();
  });

  it('returns to Schedules after saving and keeps the saved ID, next run and History action visible', async () => {
    const api = makeApi();
    const onSaved = vi.fn();
    function SaveFlow() {
      const [view, setView] = useState<'new' | undefined>('new');
      return <NavigationGuardContext.Provider value={navigation}>
        <ScheduledLoadPage api={api} packages={packages} enabled onOpenJob={vi.fn()} route={{ kind: 'schedules', view }} onSaved={saved => { onSaved(saved); setView(undefined); }}
          initialDraft={{ packageId: 'payments', environment: 'SIT', draftId: 'A0123456789ABCDEF0123456789ABCDEF',
            previewYaml: 'schemaVersion: att-load/v1.6\nworkloads: []\n' }} />
      </NavigationGuardContext.Provider>;
    }
    await act(async () => root.render(<SaveFlow />));
    await advance(300);

    await click(host.querySelector('.att-schedule-form-actions button[type="submit"]')!);
    await advance(0);
    expect(api.createSchedule).toHaveBeenCalledTimes(1);
    expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ id: 'S-CREATED', name: 'Validated LOAD snapshot' }));
    expect(host.querySelector('#schedule-list-heading')).not.toBeNull();
    expect(host.textContent).toContain('ID: S-CREATED');
    expect(host.textContent).toContain('Next run:');
    expect([...host.querySelectorAll('button')].some(button => button.textContent === 'View schedule history')).toBe(true);
  });

  it('keeps Run Now and delete behind explicit confirmation', async () => {
    let present = true;
    const api = makeApi({
      listSchedules: vi.fn(async () => ({ items: present ? [sampleSchedule] : [] })),
      deleteSchedule: vi.fn(async id => { present = false; return { scheduleId: id, status: 'DELETED' }; }),
    });
    await render(api);

    await click(host.querySelector('[aria-label="Run Now Nightly payments"]')!);
    expect(host.querySelector('.att-schedule-confirm')?.hasAttribute('open')).toBe(true);
    await click(host.querySelector('.att-schedule-confirm button')!);
    expect(api.triggerSchedule).not.toHaveBeenCalled();

    await click(host.querySelector('[aria-label="Run Now Nightly payments"]')!);
    await click([...host.querySelectorAll('.att-schedule-confirm button')].find(button => button.textContent === 'Confirm Run Now')!);
    await advance(0);
    expect(api.triggerSchedule).toHaveBeenCalledWith(sampleSchedule.id);
    expect(host.textContent).toContain('Run Now recorded as submitted');

    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Close history')!);
    await click(host.querySelector('[aria-label="Delete Nightly payments"]')!);
    await click([...host.querySelectorAll('.att-schedule-confirm button')].find(button => button.textContent === 'Confirm delete')!);
    await advance(0);
    expect(api.deleteSchedule).toHaveBeenCalledWith(sampleSchedule.id);
    expect(host.textContent).toContain('Deleted Nightly payments');
  });

  it('communicates authentication and administrator permission failures', async () => {
    const api = makeApi({ listSchedules: vi.fn(async () => { throw Object.assign(new Error('forbidden'), { status: 403 }); }) });
    await render(api);
    expect(host.textContent).toContain('Your account does not have operational access to Schedules.');
    expect(host.querySelector('[role="status"]')).not.toBeNull();
  });

  it('refreshes an open History panel while a queued job is still active, then stops polling at its terminal state', async () => {
    const queued: ScheduleOccurrence = { occurrenceId: 'O-QUEUED', scheduleId: sampleSchedule.id, scheduledFor: '2026-10-16T18:00:00Z', triggerType: 'scheduled', status: 'submitted', jobId: 'JOB-900', jobStatus: 'QUEUED' };
    const getScheduleExecutions = vi.fn()
      .mockResolvedValueOnce({ items: [queued] })
      .mockResolvedValue({ items: [{ ...queued, jobStatus: 'FAIL' }] });
    const api = makeApi({ listSchedules: vi.fn(async () => ({ items: [sampleSchedule] })), getScheduleExecutions });
    await render(api);

    const jobState = () => host.querySelector('[data-label="Job"] .att-status-badge');
    await click(host.querySelector('[aria-label="History Nightly payments"]')!);
    await advance(0);
    expect(jobState()?.textContent).toBe('Job Queued');
    expect(getScheduleExecutions).toHaveBeenCalledTimes(1);

    // The active job keeps the panel current without the user reopening History.
    await advance(5000);
    expect(jobState()?.textContent).toBe('Job Failed');
    expect(jobState()?.className).toContain('att-status-fail');
    expect(getScheduleExecutions).toHaveBeenCalledTimes(2);

    // Once the occurrence is terminal the poll timer is cleared, so no further requests are issued.
    await advance(20000);
    expect(getScheduleExecutions).toHaveBeenCalledTimes(2);
  });

  it('reloads History after Run Now even when the same schedule is already open', async () => {
    let items: ScheduleOccurrence[] = occurrences;
    const getScheduleExecutions = vi.fn(async () => ({ items }));
    const api = makeApi({
      listSchedules: vi.fn(async () => ({ items: [sampleSchedule] })),
      getScheduleExecutions,
      triggerSchedule: vi.fn(async () => ({ occurrenceId: 'O-NOW', scheduledFor: '2026-10-14T10:00:00Z', triggerType: 'manual', status: 'submitted', jobId: 'JOB-NOW', jobStatus: 'QUEUED' })),
    });
    await render(api);

    await click(host.querySelector('[aria-label="History Nightly payments"]')!);
    await advance(0);
    expect(getScheduleExecutions).toHaveBeenCalledTimes(1);
    expect(host.textContent).not.toContain('O-NOW');

    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Close history')!);
    items = [{ occurrenceId: 'O-NOW', scheduleId: sampleSchedule.id, scheduledFor: '2026-10-14T10:00:00Z', triggerType: 'manual', status: 'submitted', jobId: 'JOB-NOW', jobStatus: 'QUEUED' }, ...occurrences];
    await click(host.querySelector('[aria-label="Run Now Nightly payments"]')!);
    await click([...host.querySelectorAll('.att-schedule-confirm button')].find(button => button.textContent === 'Confirm Run Now')!);
    await advance(0);

    expect(api.triggerSchedule).toHaveBeenCalledWith(sampleSchedule.id);
    expect(getScheduleExecutions).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain('O-NOW');
  });
});
