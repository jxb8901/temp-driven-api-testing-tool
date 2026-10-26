// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { JobReplayActions } from './JobReplayActions';
import type { JobReplay } from './types';
let host: HTMLDivElement; let root: Root;
beforeEach(() => { (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true; host = document.createElement('div'); document.body.append(host); root = createRoot(host); });
afterEach(async () => { await act(async () => root.unmount()); host.remove(); });
async function click(text: string) {
  const button = [...document.querySelectorAll('button')].find(item => item.textContent === text);
  expect(button, text).toBeTruthy(); await act(async () => button!.click());
}
const retained: JobReplay = { jobId: 'JOLD', available: true, definition: { executionType: 'run', packageId: 'p', environment: 'SIT', target: { type: 'tool', id: 'version' }, options: { debug: true } } };
function setup(snapshot = retained) {
  return { getJobReplay: vi.fn().mockResolvedValue(snapshot), createJobReplayDraft: vi.fn().mockResolvedValue({ ...snapshot, intentId: 'intent', draftId: 'draft', draftRef: 'R0123456789ABCDEF0123456789ABCDEF', sourcePolicy: 'current', originatingScheduleId: 'SOLD' }),
    getJobReplayEditorDraft: vi.fn().mockResolvedValue({ schemaVersion: 'att-job-replay-draft/v1', draftRef: 'R0123456789ABCDEF0123456789ABCDEF', sourceJobId: 'JOLD', packageId: 'p', executionType: 'run', sourcePolicy: 'current', originalRevision: 'a'.repeat(64), currentRevision: 'b'.repeat(64), definition: retained.definition!, configuration: {}, expiresAt: '2099-01-01T00:00:00Z', missingFields: [] }), rerunJob: vi.fn().mockResolvedValue({ jobId: 'JNEW' }) };
}
const openRerun = 'Rerun with same settings';
it('requires confirmation and prevents double submissions while pending', async () => {
  const api = setup(), onOpenJob = vi.fn(); let finish!: (value: { jobId: string }) => void;
  api.rerunJob.mockReturnValue(new Promise(resolve => { finish = resolve; }));
  await act(async () => root.render(<JobReplayActions api={api} jobId="JOLD" onOpenJob={onOpenJob} />));
  await click(openRerun); expect(api.rerunJob).not.toHaveBeenCalled(); expect(document.body.textContent).toContain('version');
  expect(document.body.textContent).toContain('Schedule package-change policy does not control manual Rerun');
  await click('Confirm rerun'); await click('Confirm rerun'); expect(api.rerunJob).toHaveBeenCalledTimes(1);
  await act(async () => finish({ jobId: 'JNEW' })); expect(onOpenJob).toHaveBeenCalledWith('JNEW');
});
it('opens a new schedule form without executing or editing the originating schedule', async () => {
  const api = setup(), onSchedule = vi.fn(), onEditSchedule = vi.fn();
  await act(async () => root.render(<JobReplayActions api={api} jobId="JOLD" onOpenJob={vi.fn()} onSchedule={onSchedule} onEditSchedule={onEditSchedule} />));
  await click('Schedule / Reschedule…'); expect(onSchedule).not.toHaveBeenCalled();
  expect(document.body.textContent).toContain('View / Edit originating schedule'); await click('Continue to New Schedule');
  expect(onSchedule).toHaveBeenCalledWith(expect.objectContaining({ executionType: 'run', packageId: 'p', environment: 'SIT', draftId: 'draft' }));
  expect(api.rerunJob).not.toHaveBeenCalled(); expect(onEditSchedule).not.toHaveBeenCalled();
});
it('shows historical unavailability without creating a draft', async () => {
  const api = setup({ jobId: 'JOLD', available: false, reason: 'Rerun unavailable for this historical job' });
  await act(async () => root.render(<JobReplayActions api={api} jobId="JOLD" onOpenJob={vi.fn()} />));
  await click(openRerun); expect(document.body.textContent).toContain('Rerun unavailable'); expect(api.createJobReplayDraft).not.toHaveBeenCalled();
  expect(document.querySelector<HTMLButtonElement>('dialog footer button')?.disabled).toBe(true); await click('Cancel'); expect(document.querySelector('dialog')).toBeNull();
});
it('retries the same intent after an uncertain submission result', async () => {
  const api = setup(); api.rerunJob.mockRejectedValueOnce(new Error('Connection lost'));
  await act(async () => root.render(<JobReplayActions api={api} jobId="JOLD" onOpenJob={vi.fn()} />));
  await click(openRerun); await click('Confirm rerun'); expect(document.body.textContent).toContain('Connection lost');
  await click('Confirm rerun'); expect(api.rerunJob.mock.calls).toEqual([['JOLD', 'intent', 'current'], ['JOLD', 'intent', 'current']]);
});

it('offers strict source selection and a safe current-package recovery', async () => {
  const api = setup(), onOpenJob = vi.fn();
  api.createJobReplayDraft.mockResolvedValueOnce({ ...retained, intentId:'current-intent',draftId:'current-draft',sourcePolicy:'current' })
    .mockRejectedValueOnce(new Error('Original package revision conflict'));
  await act(async () => root.render(<JobReplayActions api={api} jobId="JOLD" onOpenJob={onOpenJob} />));
  await click(openRerun);
  const strict = [...document.querySelectorAll<HTMLInputElement>('input[type=radio]')].find(input => input.parentElement?.textContent?.includes('Require original revision'))!;
  await act(async () => strict.click()); await click('Review selected revision');
  expect(document.body.textContent).toContain('Original package revision conflict');
  await click('Review with current Package');
  expect(api.createJobReplayDraft.mock.calls).toEqual([['JOLD', { sourcePolicy: 'current' }], ['JOLD', { sourcePolicy: 'strict' }], ['JOLD', { sourcePolicy: 'current' }]]);
});

it('loads only an opaque server draft reference before opening the standard RUN editor', async () => {
  const api = setup(); const navigate = vi.fn();
  const { NavigationGuardContext } = await import('../../router');
  await act(async () => root.render(<NavigationGuardContext.Provider value={{navigate, registerUnsavedChanges: vi.fn(() => vi.fn())} as never}><JobReplayActions api={api} jobId="JOLD" onOpenJob={vi.fn()} /></NavigationGuardContext.Provider>));
  await click('Configure & Rerun');
  expect(api.getJobReplay).toHaveBeenCalledWith('JOLD');
  expect(api.createJobReplayDraft).toHaveBeenCalledWith('JOLD', { sourcePolicy: 'current' });
  expect(api.getJobReplayEditorDraft).not.toHaveBeenCalled();
  expect(navigate).toHaveBeenCalledWith({kind:'run',packageId:'p',environment:'SIT',replayDraftId:'R0123456789ABCDEF0123456789ABCDEF',replaySourceJobId:'JOLD'});
});

it('portals a compact review outside a table, hides raw JSON and traps/restores keyboard focus', async () => {
  const api = setup();
  await act(async () => root.render(<table><tbody><tr><td><JobReplayActions api={api} jobId="JOLD" onOpenJob={vi.fn()} onSchedule={vi.fn()} /></td></tr></tbody></table>));
  const opener = [...host.querySelectorAll('button')].find(button => button.textContent === openRerun)!;
  opener.focus(); await click(openRerun);
  const dialog = document.querySelector('dialog')!;
  expect(dialog.parentElement).toBe(document.body); expect(host.querySelector('dialog')).toBeNull();
  expect(dialog.querySelector('details')?.open).toBe(false);
  expect(dialog.querySelector('dl')?.textContent).toContain('tool · version');
  expect(document.activeElement).toBe(dialog.querySelector('h2'));
  const cancel = [...dialog.querySelectorAll('button')].find(button => button.textContent === 'Cancel')!;
  cancel.focus(); await act(async () => { cancel.dispatchEvent(new KeyboardEvent('keydown', {key:'Tab',bubbles:true,cancelable:true})); });
  expect(document.activeElement).toBe(dialog.querySelector('input[type="radio"]'));
  await act(async () => { document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', {key:'Tab',shiftKey:true,bubbles:true,cancelable:true})); });
  expect(document.activeElement).toBe(cancel);
  await act(async () => { dialog.dispatchEvent(new Event('cancel', {cancelable:true})); });
  expect(document.querySelector('dialog')).toBeNull(); expect(document.activeElement).toBe(opener);
});
it('preserves the navigation guard while reviewing and unregisters on close', async () => {
  const unregister = vi.fn(), registerUnsavedChanges = vi.fn(() => unregister);
  const { NavigationGuardContext } = await import('../../router');
  await act(async () => root.render(<NavigationGuardContext.Provider value={{registerUnsavedChanges} as never}><JobReplayActions api={setup()} jobId="JOLD" onOpenJob={vi.fn()} /></NavigationGuardContext.Provider>));
  await click(openRerun); expect(registerUnsavedChanges).toHaveBeenCalledWith(true, expect.any(String));
  await click('Cancel'); expect(unregister).toHaveBeenCalled();
});
