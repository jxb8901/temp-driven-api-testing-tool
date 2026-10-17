// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { RunPage } from './RunPage';
import { api } from '../../api';
import { NavigationGuardContext, type NavigationGuardApi } from '../../router';
vi.mock('../../api', () => ({ api: { previewRun: vi.fn(), submitRun: vi.fn() } }));
vi.mock('../code/CodeEditor', () => ({ CodeEditor: ({ label, value, readOnly, onChange }: { label: string; value: string; readOnly?: boolean; onChange?: (value: string) => void }) => <textarea aria-label={label} value={value} readOnly={readOnly} onChange={e => onChange?.(e.target.value)} /> }));
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
let host: HTMLDivElement; let root: Root;
const onAccepted = vi.fn(); const onSchedule = vi.fn();
const guard = { registerUnsavedChanges: vi.fn(() => () => {}) } as unknown as NavigationGuardApi;
beforeEach(async () => {
  vi.clearAllMocks(); vi.mocked(api.previewRun).mockResolvedValue({ draftId: 'R123', preview: { selectedCaseIds: ['cases.default.C1'] } });
  vi.mocked(api.submitRun).mockResolvedValue({ jobId: 'J123' });
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
  await act(async () => root.render(<NavigationGuardContext.Provider value={guard}><RunPage packageId="payments" onAccepted={onAccepted} onSchedule={onSchedule} schedulingEnabled /></NavigationGuardContext.Provider>));
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); });
function control(label: string) { return [...host.querySelectorAll('label')].find(item => item.textContent?.startsWith(label))!.querySelector('input,select') as HTMLInputElement | HTMLSelectElement; }
async function change(label: string, value: string) {
  const element = control(label); const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(element), 'value')!.set!;
  await act(async () => { setter.call(element, value); element.dispatchEvent(new Event(element.tagName === 'SELECT' ? 'change' : 'input', { bubbles: true })); });
}
async function click(name: string) { const button = [...host.querySelectorAll('button')].find(item => item.textContent === name)!; await act(async () => button.click()); }
it('validates before submit and clears review when the environment changes', async () => {
  await change('Target ID', 'cases.default.C1');
  expect(api.previewRun).not.toHaveBeenCalled(); expect(api.submitRun).not.toHaveBeenCalled();
  await click('Validate');
  expect(api.previewRun).toHaveBeenCalledWith({ schemaVersion: 'att-execution/v1', executionType: 'run', packageId: 'payments', target: { type: 'case', id: 'cases.default.C1' }, options: { debug: false } });
  expect(host.textContent).toContain('Validation passed'); expect(api.submitRun).not.toHaveBeenCalled();
  await change('Environment', 'UAT'); expect(host.querySelector('[aria-label="Review RUN"]')).toBeNull();
  await click('Validate'); await click('Run Now');
  expect(api.submitRun).toHaveBeenCalledTimes(1); expect(api.submitRun).toHaveBeenCalledWith({ packageId: 'payments', draftId: 'R123' });
  expect(onAccepted).toHaveBeenCalledWith({ packageId: 'payments', jobId: 'J123' });
});
it('schedules the reviewed snapshot and diagnostic changes do not execute', async () => {
  await change('Target type', 'flow'); await change('Target ID', 'payment.flow');
  await click('Validate'); await click('Schedule…');
  expect(onSchedule).toHaveBeenCalledWith(expect.objectContaining({ draftId: 'R123', executionType: 'run', target: { type: 'flow', id: 'payment.flow' } }));
  expect(api.submitRun).not.toHaveBeenCalled();
  await act(async () => (control('Debug diagnostics') as HTMLInputElement).click());
  expect(host.querySelector('[aria-label="Review RUN"]')).toBeNull();
  await click('Validate'); expect(api.previewRun).toHaveBeenLastCalledWith(expect.objectContaining({ options: { debug: true } }));
});
it('schedules a mixed-version resource fallback as legacy Debug', async () => {
  vi.mocked(api.previewRun).mockResolvedValueOnce({ draftId: 'D123', preview: { target: { type: 'flow', id: 'payment.flow' } } });
  await change('Target type', 'flow'); await change('Target ID', 'payment.flow');
  await click('Validate'); await click('Schedule…');
  expect(onSchedule).toHaveBeenCalledWith(expect.objectContaining({ draftId: 'D123', executionType: 'run', legacyDebug: true }));
});

it.each(['case', 'template', 'flow', 'tool', 'batch'])('preserves the validated %s selection and Debug option when scheduling', async targetType => {
  await change('Target type', targetType);
  await change('Environment', 'SIT');
  if (targetType !== 'batch') await change('Target ID', 'selected.target');
  await act(async () => (control('Debug diagnostics') as HTMLInputElement).click());
  if (targetType === 'batch') {
    const selection = host.querySelector('textarea[aria-label="Case selection"]')!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!.call(selection, '{"suites":["payments"],"tags":["smoke"],"caseIds":["C1","C3"]}');
      selection.dispatchEvent(new Event('input', { bubbles: true }));
    });
  }
  await click('Validate');
  const validated = vi.mocked(api.previewRun).mock.calls.at(-1)![0];
  expect(validated).toMatchObject({ executionType: 'run', environment: 'SIT', options: { debug: true } });
  if (targetType === 'batch') expect(validated.selection).toEqual({ suites: ['payments'], tags: ['smoke'], caseIds: ['C1', 'C3'] });
  else expect(validated.target).toEqual({ type: targetType, id: 'selected.target' });
  await click('Schedule…');
  expect(onSchedule).toHaveBeenLastCalledWith(expect.objectContaining({ executionType: 'run', draftId: 'R123', environment: 'SIT' }));
  expect(api.previewRun).toHaveBeenCalledTimes(1);
  expect(api.submitRun).not.toHaveBeenCalled();
});
