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
  const button = [...host.querySelectorAll('button')].find(item => item.textContent === text);
  expect(button, text).toBeTruthy(); await act(async () => button!.click());
}
const retained: JobReplay = { jobId: 'JOLD', available: true, definition: { executionType: 'run', packageId: 'p', environment: 'SIT', target: { type: 'tool', id: 'version' }, options: { debug: true } } };
function setup(snapshot = retained) {
  return { getJobReplay: vi.fn().mockResolvedValue(snapshot), createJobReplayDraft: vi.fn().mockResolvedValue({ ...snapshot, intentId: 'intent', draftId: 'draft', originatingScheduleId: 'SOLD' }), rerunJob: vi.fn().mockResolvedValue({ jobId: 'JNEW' }) };
}
it('requires confirmation and prevents double submissions while pending', async () => {
  const api = setup(), onOpenJob = vi.fn(); let finish!: (value: { jobId: string }) => void;
  api.rerunJob.mockReturnValue(new Promise(resolve => { finish = resolve; }));
  await act(async () => root.render(<JobReplayActions api={api} jobId="JOLD" onOpenJob={onOpenJob} />));
  await click('Rerun'); expect(api.rerunJob).not.toHaveBeenCalled(); expect(host.textContent).toContain('version');
  await click('Confirm rerun'); await click('Confirm rerun'); expect(api.rerunJob).toHaveBeenCalledTimes(1);
  await act(async () => finish({ jobId: 'JNEW' })); expect(onOpenJob).toHaveBeenCalledWith('JNEW');
});
it('opens a new schedule form without executing or editing the originating schedule', async () => {
  const api = setup(), onSchedule = vi.fn(), onEditSchedule = vi.fn();
  await act(async () => root.render(<JobReplayActions api={api} jobId="JOLD" onOpenJob={vi.fn()} onSchedule={onSchedule} onEditSchedule={onEditSchedule} />));
  await click('Schedule / Reschedule…'); expect(onSchedule).not.toHaveBeenCalled();
  expect(host.textContent).toContain('View / Edit originating schedule'); await click('Continue to New Schedule');
  expect(onSchedule).toHaveBeenCalledWith(expect.objectContaining({ executionType: 'run', packageId: 'p', environment: 'SIT', draftId: 'draft' }));
  expect(api.rerunJob).not.toHaveBeenCalled(); expect(onEditSchedule).not.toHaveBeenCalled();
});
it('shows historical unavailability without creating a draft', async () => {
  const api = setup({ jobId: 'JOLD', available: false, reason: 'Rerun unavailable for this historical job' });
  await act(async () => root.render(<JobReplayActions api={api} jobId="JOLD" onOpenJob={vi.fn()} />));
  await click('Rerun'); expect(host.textContent).toContain('Rerun unavailable'); expect(api.createJobReplayDraft).not.toHaveBeenCalled();
  expect(host.querySelector<HTMLButtonElement>('section button')?.disabled).toBe(true); await click('Cancel'); expect(host.querySelector('section')).toBeNull();
});
it('retries the same intent after an uncertain submission result', async () => {
  const api = setup(); api.rerunJob.mockRejectedValueOnce(new Error('Connection lost'));
  await act(async () => root.render(<JobReplayActions api={api} jobId="JOLD" onOpenJob={vi.fn()} />));
  await click('Rerun'); await click('Confirm rerun'); expect(host.textContent).toContain('Connection lost');
  await click('Confirm rerun'); expect(api.rerunJob.mock.calls).toEqual([['JOLD', 'intent'], ['JOLD', 'intent']]);
});
