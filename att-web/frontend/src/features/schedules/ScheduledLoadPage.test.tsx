// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NavigationGuardContext, type NavigationGuardApi } from '../../router';
import type { AsyncState, PackageSummary } from '../../types';
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
  { occurrenceId: 'O-SUBMITTED', scheduleId: sampleSchedule.id, scheduledFor: '2026-10-13T18:00:00Z', triggerType: 'scheduled', status: 'submitted', jobId: 'JOB-204' },
];

if (!HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', ''); };
  HTMLDialogElement.prototype.close = function () { this.removeAttribute('open'); };
}

function makeApi(overrides: Partial<ScheduledLoadApi> = {}): ScheduledLoadApi {
  return {
    listLoadSchedules: vi.fn(async () => ({ items: [] })),
    previewLoadSchedule: vi.fn(async input => ({ ...input, nextRunAt: '2026-10-15T18:00:00Z' })),
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
    expect(host.querySelector('h1')?.textContent).toBe('Scheduled Load');
    expect(host.textContent).toContain('Nightly payments');
    expect(host.textContent).toContain('Missed runs are recorded as skipped');

    await click(host.querySelector('[aria-label="History Nightly payments"]')!);
    await advance(0);
    expect(host.textContent).toContain('Skipped because this schedule already reached its concurrent-run limit.');
    await click([...host.querySelectorAll('.att-schedule-job-link')][0]);
    expect(onOpenJob).toHaveBeenCalledWith('JOB-204');
    expect(api.getLoadScheduleExecutions).toHaveBeenCalledWith(sampleSchedule.id, 100, expect.any(AbortSignal));
  });

  it('requires a server preview before creating a disabled schedule by default', async () => {
    const api = makeApi();
    await render(api);
    expect(host.textContent).toContain('No schedules have been created');
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
    expect(navigation.registerUnsavedChanges).toHaveBeenCalledWith(true, 'Scheduled Load changes');
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
    expect(host.textContent).toContain('Your account does not have the configured Scheduled Load administrator role.');
    expect(host.querySelector('[role="status"]')).not.toBeNull();
  });
});
