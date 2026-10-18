// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { RunPage } from './RunPage';
import { api } from '../../api';
import { NavigationGuardContext, type NavigationGuardApi } from '../../router';
import type { DebugFormResponse, JsonObject, ResourceIdentity } from '../explorer/types';
vi.mock('../../api', () => ({ api: { previewRun: vi.fn(), submitRun: vi.fn(), getDeclaredConfiguration: vi.fn(), getDebugForm: vi.fn() } }));
vi.mock('../code/CodeEditor', () => ({ CodeEditor: ({ label, value, readOnly, onChange }: { label: string; value: string; readOnly?: boolean; onChange?: (value: string) => void }) => <textarea aria-label={label} value={value} readOnly={readOnly} onChange={e => onChange?.(e.target.value)} /> }));
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
let host: HTMLDivElement; let root: Root;
const onAccepted = vi.fn(); const onSchedule = vi.fn(() => true); const release = vi.fn();
const guard = { registerUnsavedChanges: vi.fn(() => release) } as unknown as NavigationGuardApi;
beforeEach(async () => {
  vi.clearAllMocks(); vi.mocked(api.previewRun).mockResolvedValue({ draftId: 'R123', preview: { selectedCaseIds: ['cases.default.C1'] } });
  vi.mocked(api.submitRun).mockResolvedValue({ jobId: 'J123' });
  vi.mocked(api.getDeclaredConfiguration).mockResolvedValue({ environments: [{ name: 'SIT', state: 'active' }, { name: 'UAT', state: 'active' }] });
  vi.mocked(api.getDebugForm).mockImplementation(async identity => ({
    target: { type: identity.type as 'template' | 'flow' | 'tool', id: identity.resourceId }, input: {},
  }));
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
async function settleDebugForm(milliseconds = 220) { await act(async () => { await new Promise(resolve => window.setTimeout(resolve, milliseconds)); }); }
async function renderTarget(type: 'template' | 'flow' | 'tool', resourceId = 'resource-id', logicalId = 'payment.flow', environment = 'SIT') {
  await act(async () => root.render(<NavigationGuardContext.Provider value={guard}><RunPage key={`target:${type}:${resourceId}:${environment}`} packageId="payments"
    initialTarget={{ type, resourceId, logicalId }} initialEnvironment={environment} onAccepted={onAccepted} onSchedule={onSchedule} schedulingEnabled />
  </NavigationGuardContext.Provider>));
}
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
  await settleDebugForm();
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
  await settleDebugForm();
  await click('Validate'); await click('Schedule…');
  expect(onSchedule).toHaveBeenCalledWith(expect.objectContaining({ draftId: 'D123', executionType: 'run', legacyDebug: true }));
});

it.each(['case', 'template', 'flow', 'tool', 'batch'])('preserves the validated %s selection and Debug option when scheduling', async targetType => {
  await change('Target type', targetType);
  await change('Environment', 'SIT');
  if (targetType !== 'batch') await change('Target ID', 'selected.target');
  if (targetType === 'template' || targetType === 'flow' || targetType === 'tool') await settleDebugForm();
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

it.each([
  { type: 'flow' as const, targetId: 'payment.flow', group: 'Variables', input: { inputs: { amount: 5, credential: { $attDebugKeepDefault: '/inputs/credential' } }, vars: { region: 'SIT' } } as JsonObject },
  { type: 'tool' as const, targetId: 'lookup-customer', group: 'Tool arguments', input: { inputs: { requestId: 'R1' }, arguments: { limit: 3 } } as JsonObject },
])('loads safe typed $type defaults and validates them with the canonical target', async fixture => {
  vi.mocked(api.getDebugForm).mockResolvedValue({ target: { type: fixture.type, id: fixture.targetId }, input: fixture.input, redacted: true });
  await renderTarget(fixture.type, 'resource-123', fixture.targetId);
  await settleDebugForm();

  expect(api.getDebugForm).toHaveBeenCalledWith({ packageId: 'payments', type: fixture.type, resourceId: 'resource-123' }, 'SIT', expect.any(AbortSignal));
  expect(host.textContent).toContain(fixture.group);
  expect(host.textContent).toContain('Hidden package defaults remain protected');
  if (fixture.type === 'flow') expect(host.textContent).toContain('Keep hidden package default');
  await click('Validate');
  expect(api.previewRun).toHaveBeenCalledWith(expect.objectContaining({ target: { type: fixture.type, id: fixture.targetId }, input: fixture.input }));
  await click('Run Now');
  expect(api.submitRun).toHaveBeenCalledWith({ packageId: 'payments', draftId: 'R123' });
});

it('refreshes defaults on environment changes and ignores an older response', async () => {
  let resolveSit!: (form: DebugFormResponse) => void;
  const sitResponse = new Promise<DebugFormResponse>(resolve => { resolveSit = resolve; });
  vi.mocked(api.getDebugForm).mockImplementation((_identity: ResourceIdentity, environment?: string) => environment === 'SIT'
    ? sitResponse
    : Promise.resolve({ target: { type: 'flow', id: 'payment.flow' }, input: { inputs: { amount: 9 }, vars: { region: 'UAT' } } }));
  await renderTarget('flow', 'resource-123', 'payment.flow');
  await settleDebugForm();
  expect(api.getDebugForm).toHaveBeenCalledTimes(1);

  await change('Environment', 'UAT');
  await settleDebugForm();
  resolveSit({ target: { type: 'flow', id: 'payment.flow' }, input: { inputs: { amount: 1 }, vars: { region: 'SIT' } } });
  await act(async () => { await sitResponse; });

  expect(api.getDebugForm).toHaveBeenCalledTimes(2);
  expect(api.getDebugForm).toHaveBeenLastCalledWith({ packageId: 'payments', type: 'flow', resourceId: 'resource-123' }, 'UAT', expect.any(AbortSignal));
  expect((host.querySelector('[aria-label="amount value"]') as HTMLInputElement).value).toBe('9');
  await click('Validate');
  expect(api.previewRun).toHaveBeenLastCalledWith(expect.objectContaining({ environment: 'UAT', input: { inputs: { amount: 9 }, vars: { region: 'UAT' } } }));
  await click('Schedule…');
  expect(onSchedule).toHaveBeenCalledWith(expect.objectContaining({ environment: 'UAT', target: { type: 'flow', id: 'payment.flow' } }));
});

it('releases the dirty-form guard for an accepted validated Schedule handoff', async () => {
  await change('Target ID', 'cases.default.C1');
  await click('Validate');
  await click('Schedule…');
  expect(release).toHaveBeenCalled();
  expect(onSchedule).toHaveBeenCalledWith(expect.objectContaining({ draftId: 'R123', executionType: 'run' }));
});

it('restores the dirty-form guard when the Schedule handoff is rejected', async () => {
  onSchedule.mockReturnValueOnce(false);
  await change('Target ID', 'cases.default.C1');
  await click('Validate');
  await click('Schedule…');
  expect(release).toHaveBeenCalled();
  expect(onSchedule).toHaveBeenCalledWith(expect.objectContaining({ draftId: 'R123', executionType: 'run' }));
  expect(guard.registerUnsavedChanges).toHaveBeenLastCalledWith(true, 'Run definition changes');
});

it('offers Batch RUN as the initial mode from the Explorer route', async () => {
  await act(async () => root.render(<NavigationGuardContext.Provider value={guard}><RunPage key="batch" packageId="payments" initialBatchMode
    onAccepted={onAccepted} onSchedule={onSchedule} schedulingEnabled /></NavigationGuardContext.Provider>));
  expect((control('Target type') as HTMLSelectElement).value).toBe('batch');
  expect(host.querySelector('[aria-label="Case selection"]')).not.toBeNull();
  await click('Validate');
  expect(api.previewRun).toHaveBeenCalledWith(expect.objectContaining({ selection: { all: true } }));
});
