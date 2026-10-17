// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NavigationGuardContext, type NavigationGuardApi } from '../../router';
import { ApiError, type AsyncState, type PackageSummary } from '../../types';
import { ScheduledLoadPage } from './ScheduledLoadPage';
import type { LoadSchedule, ScheduleOccurrence, ScheduledLoadApi } from './types';

const sampleSchedule: LoadSchedule = {
  id: 'S-NIGHTLY', name: 'Nightly payments', description: 'Checkout soak', tags: ['nightly'],
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

function makeApi(overrides: Partial<ScheduledLoadApi> = {}): ScheduledLoadApi {
  return {
    listLoadSchedules: vi.fn(async () => ({ items: [] })),
    previewLoadSchedule: vi.fn(async input => ({ ...input, nextRunAt: '2026-10-15T18:00:00Z', nextOccurrences: [
      '2026-10-15T18:00:00Z', '2026-10-16T18:00:00Z', '2026-10-17T18:00:00Z', '2026-10-18T18:00:00Z', '2026-10-19T18:00:00Z',
    ] })),
    createLoadSchedule: vi.fn(async input => ({ ...sampleSchedule, ...input, id: 'S-CREATED' })),
    updateLoadSchedule: vi.fn(async (id, input) => ({ ...sampleSchedule, ...input, id })),
    setLoadScheduleEnabled: vi.fn(async (id, enabled) => ({ ...sampleSchedule, id, enabled })),
    triggerLoadSchedule: vi.fn(async () => ({ occurrenceId: 'O-NOW', scheduledFor: '2026-10-14T10:00:00Z', triggerType: 'manual', status: 'submitted', jobId: 'JOB-NOW' })),
    deleteLoadSchedule: vi.fn(async id => ({ scheduleId: id, status: 'DELETED' })),
    getLoadScheduleExecutions: vi.fn(async () => ({ items: occurrences })),
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

async function render(api: ScheduledLoadApi, onOpenJob = vi.fn()) {
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
  it('shows a configured schedule, explains skips, and links submitted jobs', async () => {
    const api = makeApi({ listLoadSchedules: vi.fn(async () => ({ items: [sampleSchedule] })) });
    const onOpenJob = await render(api);
    expect(host.querySelector('h1')?.textContent).toBe('Schedules');
    expect(host.textContent).toContain('Nightly payments');
    expect(host.textContent).toContain('Missed runs are recorded as skipped');

    await click(host.querySelector('[aria-label="History Nightly payments"]')!);
    await advance(0);
    expect(host.textContent).toContain('Skipped because this schedule already reached its concurrent-run limit.');
    await click([...host.querySelectorAll('.att-schedule-job-link')][0]);
    expect(onOpenJob).toHaveBeenCalledWith('JOB-204');
    expect(host.textContent).toContain('JOB-099 · no longer available');
    expect(api.getLoadScheduleExecutions).toHaveBeenCalledWith(sampleSchedule.id, 100, expect.any(AbortSignal));
  });

  it('shows the job outcome next to the occurrence status and keeps expired jobs non-navigable', async () => {
    const api = makeApi({ listLoadSchedules: vi.fn(async () => ({ items: [sampleSchedule] })) });
    await render(api);
    await click(host.querySelector('[aria-label="History Nightly payments"]')!);
    await advance(0);

    const rowFor = (occurrenceId: string) => [...host.querySelectorAll('.att-occurrence-table tbody tr')]
      .find(row => row.querySelector('code')?.textContent === occurrenceId)!;
    const jobState = (occurrenceId: string) => rowFor(occurrenceId).querySelector('.att-schedule-job-state');

    expect(rowFor('O-SUBMITTED').querySelector('.att-schedule-state')?.textContent).toBe('submitted');
    expect(jobState('O-SUBMITTED')?.textContent).toBe('Job Passed');
    expect(jobState('O-SUBMITTED')?.className).toContain('is-enabled');
    expect(jobState('O-FAILED')?.textContent).toBe('Job Failed');
    expect(jobState('O-FAILED')?.className).toContain('is-failed');
    expect(jobState('O-RUNNING')?.textContent).toBe('Job Running');
    expect(jobState('O-RUNNING')?.className).toContain('is-running');

    const expired = rowFor('O-EXPIRED');
    expect(expired.querySelector('.att-schedule-job-state')).toBeNull();
    expect(expired.querySelector('button.att-schedule-job-link')).toBeNull();
    expect(expired.querySelector('.att-schedule-job-unavailable')?.textContent).toContain('no longer available');
  });

  it('labels consumed one-time schedules as completed and filters them separately', async () => {
    const completed: LoadSchedule = {
      ...sampleSchedule, id: 'S-ONCE', name: 'One-time import', runAt: '2026-10-16T10:00:00Z',
      cron: undefined, nextRunAt: null,
    };
    const api = makeApi({ listLoadSchedules: vi.fn(async () => ({ items: [sampleSchedule, completed] })) });
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
    const completed: LoadSchedule = {
      ...sampleSchedule, id: 'S-ONCE', name: 'One-time import', runAt: '2026-10-16T10:00:00Z',
      cron: undefined, nextRunAt: null,
    };
    const api = makeApi({ listLoadSchedules: vi.fn(async () => ({ items: [completed] })) });
    await render(api);
    vi.mocked(api.previewLoadSchedule).mockClear();

    await click(host.querySelector('[aria-label="Edit One-time import"]')!);
    await advance(400);
    expect(host.textContent).toContain('Saved trigger unchanged');
    expect(host.textContent).not.toContain('Preview unavailable');
    expect(api.previewLoadSchedule).not.toHaveBeenCalled();

    const save = [...host.querySelectorAll('button')].find(button => button.textContent === 'Save changes') as HTMLButtonElement;
    expect(save.disabled).toBe(false);
    await change(host.querySelector('#schedule-description') as HTMLInputElement, 'Post-mortem notes');
    await click(save);
    await advance(0);
    expect(api.previewLoadSchedule).not.toHaveBeenCalled();
    expect(api.updateLoadSchedule).toHaveBeenCalledTimes(1);
    const payload = vi.mocked(api.updateLoadSchedule).mock.calls[0][1];
    expect(payload.name).toBe('One-time import');
    expect(payload.description).toBe('Post-mortem notes');
    expect(payload.runAt).toBe('2026-10-16T10:00:00.000Z');
    expect(payload.cron).toBeUndefined();
  });

  it('switches an existing schedule between cron and a one-time trigger with a single trigger field', async () => {
    const completed: LoadSchedule = {
      ...sampleSchedule, id: 'S-ONCE', name: 'One-time import', runAt: '2026-10-16T10:00:00Z',
      cron: undefined, nextRunAt: null,
    };
    const api = makeApi({ listLoadSchedules: vi.fn(async () => ({ items: [sampleSchedule, completed] })) });
    await render(api);

    await click(host.querySelector('[aria-label="Edit Nightly payments"]')!);
    await change(host.querySelector('#schedule-recurrence') as HTMLSelectElement, 'once');
    await change(host.querySelector('#schedule-run-at') as HTMLInputElement, '2026-11-02T02:00:00+08:00');
    await advance(400);
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Save changes')!);
    await advance(0);
    expect(api.updateLoadSchedule).toHaveBeenCalledTimes(1);
    const cronToOnce = vi.mocked(api.updateLoadSchedule).mock.calls[0][1];
    expect(cronToOnce.runAt).toBe('2026-11-01T18:00:00.000Z');
    expect(cronToOnce.cron).toBeUndefined();

    await click(host.querySelector('[aria-label="Edit One-time import"]')!);
    await change(host.querySelector('#schedule-recurrence') as HTMLSelectElement, 'cron');
    await change(host.querySelector('#schedule-cron') as HTMLInputElement, '30 3 * * *');
    await advance(400);
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Save changes')!);
    await advance(0);
    expect(api.updateLoadSchedule).toHaveBeenCalledTimes(2);
    const onceToCron = vi.mocked(api.updateLoadSchedule).mock.calls[1][1];
    expect(onceToCron.cron).toBe('30 3 * * *');
    expect(onceToCron.runAt).toBeUndefined();
  });

  it('requires a server preview before creating a disabled schedule by default', async () => {
    const api = makeApi();
    await render(api);
    await change(host.querySelector('#schedule-timezone') as HTMLInputElement, 'Asia/Hong_Kong');
    await advance(300);
    expect(host.textContent).toContain('No schedules have been created');
    expect(host.textContent).toContain('This preview calculates the next five occurrence times from timezone and cron. It does not validate the package or test snapshot.');
    expect(host.textContent).toContain('Oct 20, 2026');
    expect(host.querySelectorAll('.att-schedule-occurrence-preview li')).toHaveLength(5);
    const save = [...host.querySelectorAll('button')].find(button => button.textContent === 'Save schedule') as HTMLButtonElement;
    expect(save.disabled).toBe(false);
    expect((host.querySelector('.att-schedule-enable input') as HTMLInputElement).checked).toBe(false);

    await change(host.querySelector('#schedule-name') as HTMLInputElement, 'Nightly payments');
    await change(host.querySelector('#schedule-package') as HTMLSelectElement, 'payments');
    await change(host.querySelector('#schedule-scenario') as HTMLInputElement, 'load/nightly.yaml');
    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Create schedule')!);
    expect((host.querySelector('#schedule-name') as HTMLInputElement).value).toBe('Nightly payments');
    expect(host.textContent).toContain('Save or clear the current schedule draft before starting another.');
    await click(save);
    await advance(0);
    expect(api.createLoadSchedule).toHaveBeenCalledWith(expect.objectContaining({
      name: 'Nightly payments', packageId: 'payments', scenario: 'load/nightly.yaml', enabled: false,
      timezone: expect.any(String), cron: '0 2 * * *',
    }));
    expect(host.textContent).toContain('Saved Nightly payments. Next run:');
    expect(api.previewLoadSchedule).toHaveBeenCalled();
    expect(vi.mocked(api.previewLoadSchedule).mock.calls.at(-1)?.[0]).toEqual({ timezone: expect.any(String), cron: '0 2 * * *' });
    expect(navigation.registerUnsavedChanges).toHaveBeenCalledWith(true, 'Schedules changes');
  });

  it('shows safe Worker validation diagnostics, package context, target, and request ID after a rejected save', async () => {
    const api = makeApi({ createLoadSchedule: vi.fn(async () => {
      throw new ApiError('Load options must include a positive duration.', {
        status: 400, code: 'ATT-SERVER-INVALID-REQUEST', requestId: 'schedule-request-42',
        diagnostics: [{ code: 'WORKER_REQUEST_INVALID', summary: 'Load options must include a positive duration.',
          field: 'workloads[0].load.duration', file: 'load/nightly.yaml', line: 8, packageId: 'payments', environment: 'SIT',
          targets: [{ type: 'template', id: 'CHECKOUT' }] }],
      });
    }) });
    await render(api);
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

  it('opens a validated Load snapshot in a dialog and returns to the still-mounted Load editor on cancel', async () => {
    const api = makeApi();
    const onClose = vi.fn();
    await act(async () => root.render(<NavigationGuardContext.Provider value={navigation}>
      <ScheduledLoadPage api={api} packages={packages} enabled onOpenJob={vi.fn()} modal onClose={onClose}
        initialDraft={{ packageId: 'payments', environment: 'SIT', draftId: 'A0123456789ABCDEF0123456789ABCDEF',
          previewYaml: 'schemaVersion: att-load/v1.6\nworkloads: []\n' }} />
    </NavigationGuardContext.Provider>));
    await advance(300);

    const dialog = host.querySelector<HTMLDialogElement>('.att-schedule-create-dialog');
    expect(dialog?.open).toBe(true);
    expect(host.querySelector('#schedule-list-heading')).toBeNull();
    expect(host.querySelector('.att-schedule-snapshot-review pre')?.textContent).toBe('schemaVersion: att-load/v1.6\nworkloads: []\n');
    expect((host.querySelector('#schedule-environment') as HTMLInputElement).value).toBe('SIT');
    expect(navigation.navigate).not.toHaveBeenCalled();
    expect(navigation.registerUnsavedChanges).not.toHaveBeenCalledWith(expect.any(Boolean), 'Schedules changes');

    await click([...host.querySelectorAll('button')].find(button => button.textContent === 'Cancel and return to editor')!);
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(navigation.navigate).not.toHaveBeenCalled();
  });

  it('keeps Run Now and delete behind explicit confirmation', async () => {
    let present = true;
    const api = makeApi({
      listLoadSchedules: vi.fn(async () => ({ items: present ? [sampleSchedule] : [] })),
      deleteLoadSchedule: vi.fn(async id => { present = false; return { scheduleId: id, status: 'DELETED' }; }),
    });
    await render(api);

    await click(host.querySelector('[aria-label="Run now Nightly payments"]')!);
    expect(host.querySelector('.att-schedule-confirm')?.hasAttribute('open')).toBe(true);
    await click(host.querySelector('.att-schedule-confirm button')!);
    expect(api.triggerLoadSchedule).not.toHaveBeenCalled();

    await click(host.querySelector('[aria-label="Run now Nightly payments"]')!);
    await click([...host.querySelectorAll('.att-schedule-confirm button')].find(button => button.textContent === 'Confirm Run Now')!);
    await advance(0);
    expect(api.triggerLoadSchedule).toHaveBeenCalledWith(sampleSchedule.id);
    expect(host.textContent).toContain('Run Now recorded as submitted');

    await click(host.querySelector('[aria-label="Delete Nightly payments"]')!);
    await click([...host.querySelectorAll('.att-schedule-confirm button')].find(button => button.textContent === 'Confirm delete')!);
    await advance(0);
    expect(api.deleteLoadSchedule).toHaveBeenCalledWith(sampleSchedule.id);
    expect(host.textContent).toContain('Deleted Nightly payments');
  });

  it('communicates authentication and administrator permission failures', async () => {
    const api = makeApi({ listLoadSchedules: vi.fn(async () => { throw Object.assign(new Error('forbidden'), { status: 403 }); }) });
    await render(api);
    expect(host.textContent).toContain('Your account does not have the configured Schedules administrator role.');
    expect(host.querySelector('[role="status"]')).not.toBeNull();
  });

  it('refreshes an open History panel while a queued job is still active, then stops polling at its terminal state', async () => {
    const queued: ScheduleOccurrence = { occurrenceId: 'O-QUEUED', scheduleId: sampleSchedule.id, scheduledFor: '2026-10-16T18:00:00Z', triggerType: 'scheduled', status: 'submitted', jobId: 'JOB-900', jobStatus: 'QUEUED' };
    const getLoadScheduleExecutions = vi.fn()
      .mockResolvedValueOnce({ items: [queued] })
      .mockResolvedValue({ items: [{ ...queued, jobStatus: 'FAIL' }] });
    const api = makeApi({ listLoadSchedules: vi.fn(async () => ({ items: [sampleSchedule] })), getLoadScheduleExecutions });
    await render(api);

    const jobState = () => host.querySelector('.att-schedule-job-state');
    await click(host.querySelector('[aria-label="History Nightly payments"]')!);
    await advance(0);
    expect(jobState()?.textContent).toBe('Job Queued');
    expect(getLoadScheduleExecutions).toHaveBeenCalledTimes(1);

    // The active job keeps the panel current without the user reopening History.
    await advance(5000);
    expect(jobState()?.textContent).toBe('Job Failed');
    expect(jobState()?.className).toContain('is-failed');
    expect(getLoadScheduleExecutions).toHaveBeenCalledTimes(2);

    // Once the occurrence is terminal the poll timer is cleared, so no further requests are issued.
    await advance(20000);
    expect(getLoadScheduleExecutions).toHaveBeenCalledTimes(2);
  });

  it('reloads History after Run Now even when the same schedule is already open', async () => {
    let items: ScheduleOccurrence[] = occurrences;
    const getLoadScheduleExecutions = vi.fn(async () => ({ items }));
    const api = makeApi({
      listLoadSchedules: vi.fn(async () => ({ items: [sampleSchedule] })),
      getLoadScheduleExecutions,
      triggerLoadSchedule: vi.fn(async () => ({ occurrenceId: 'O-NOW', scheduledFor: '2026-10-14T10:00:00Z', triggerType: 'manual', status: 'submitted', jobId: 'JOB-NOW', jobStatus: 'QUEUED' })),
    });
    await render(api);

    await click(host.querySelector('[aria-label="History Nightly payments"]')!);
    await advance(0);
    expect(getLoadScheduleExecutions).toHaveBeenCalledTimes(1);
    expect(host.textContent).not.toContain('O-NOW');

    items = [{ occurrenceId: 'O-NOW', scheduleId: sampleSchedule.id, scheduledFor: '2026-10-14T10:00:00Z', triggerType: 'manual', status: 'submitted', jobId: 'JOB-NOW', jobStatus: 'QUEUED' }, ...occurrences];
    await click(host.querySelector('[aria-label="Run now Nightly payments"]')!);
    await click([...host.querySelectorAll('.att-schedule-confirm button')].find(button => button.textContent === 'Confirm Run Now')!);
    await advance(0);

    expect(api.triggerLoadSchedule).toHaveBeenCalledWith(sampleSchedule.id);
    expect(getLoadScheduleExecutions).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain('O-NOW');
  });
});
