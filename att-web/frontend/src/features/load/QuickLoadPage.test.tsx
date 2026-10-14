// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QuickLoadPage } from './QuickLoadPage';
import type { LoadJobsApi } from './types';

vi.mock('../code/CodeEditor', () => ({
  CodeEditor: ({ value, label }: { value: string; label: string }) => <pre aria-label={label}>{value}</pre>,
}));
vi.mock('../explorer/TypedSidecarForm', () => ({ TypedSidecarForm: () => <div>Target inputs</div> }));

let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});
afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
});
async function flush() {
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });
}

describe('QuickLoadPage', () => {
  it('applies the documented Quick smoke preset without submitting and validates its preview', async () => {
    const api = {
      getLoadPolicy: vi.fn(async () => ({
        model: 'virtualUsers' as const,
        policy: { schemaVersion: 'att-load/v1.6', load: { users: 1, duration: '30s' } },
      })),
      getTargetLoadForm: vi.fn(async () => ({ model: 'virtualUsers' as const, input: { inputs: { account: 'A-1' }, vars: {} } })),
      createLoadDraft: vi.fn(async () => ({
        draftId: 'L0123456789ABCDEF0123456789ABCDEF', packageId: 'payments', model: 'virtualUsers' as const,
        previewYaml: 'schemaVersion: att-load/v1.6\n', expiresAt: new Date(Date.now() + 60_000).toISOString(),
      })),
      submitLoadDraft: vi.fn(async () => ({ jobId: 'JOB-QUICK' })),
    } as unknown as LoadJobsApi;
    await act(async () => root.render(<QuickLoadPage
      api={api} packageId="payments" target={{ type: 'template', resourceId: 'checkout' }}
      environment="" environments={['staging']} environmentState="ready" inlineLoadEnabled
      onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()} onCancel={vi.fn()}
      registerUnsavedChanges={() => () => {}}
    />));
    await flush();

    const preset = host.querySelector<HTMLSelectElement>('select[aria-label="Load template or preset"]')!;
    await act(async () => {
      preset.value = 'quick-profile';
      preset.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await flush();
    expect(host.textContent).toContain('examples/load/quick-profile.yaml');
    expect(api.createLoadDraft).not.toHaveBeenCalled();
    expect([...host.querySelectorAll('button')].find(button => button.textContent === 'Schedule…')?.disabled).toBe(true);

    const validate = [...host.querySelectorAll('button')].find(button => button.textContent === 'Validate')!;
    await act(async () => validate.click());
    await flush();
    expect(api.createLoadDraft).toHaveBeenCalledTimes(1);
    const input = vi.mocked(api.createLoadDraft).mock.calls[0][0];
    const scenario = input.scenario as Record<string, unknown>;
    const workload = (scenario.workloads as Array<Record<string, unknown>>)[0];
    expect(workload.target).toEqual({ type: 'template', id: 'checkout' });
    expect(workload.load).toEqual({ users: 2 });
    expect((scenario.load as Record<string, unknown>).duration).toBe('10s');
    expect((scenario.execution as Record<string, unknown>).thinkTime).toBe('250ms');
    expect((scenario.evidence as Record<string, unknown>).mode).toBe('failures');
    expect(api.submitLoadDraft).not.toHaveBeenCalled();
  });
});
