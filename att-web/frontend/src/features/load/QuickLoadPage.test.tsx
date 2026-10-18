// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { QuickLoadPage } from './QuickLoadPage';
import type { LoadJobsApi, LoadTargetType } from './types';
import { ApiError } from '../../types';

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
  it('shows server diagnostics and focuses the matching field from the review action', async () => {
    const api = {
      getLoadPolicy: vi.fn(async () => ({ model: 'virtualUsers' as const, policy: { schemaVersion: 'att-load/v1.6', load: { users: 1, duration: '30s' } } })),
      getTargetLoadForm: vi.fn(async () => ({ target: { type: 'template' as const, id: 'checkout' }, model: 'virtualUsers' as const, input: { inputs: {}, vars: {} } })),
      createLoadDraft: vi.fn(async () => { throw new ApiError('Duration is not supported.', {
        status: 400, code: 'LOAD_VALIDATION_FAILED', requestId: 'req-quick-123',
        diagnostics: [{ severity: 'ERROR', code: 'DURATION_INVALID', path: 'scenario.load.duration', summary: 'Use a supported duration.',
          detail: 'Flow checkout depends on a missing Tool; /srv/private/flow.yaml token=hidden-value',
          suggestion: 'Add the required Tool and verify password=hidden-password.' }],
      }); }),
      submitLoadDraft: vi.fn(async () => ({ jobId: 'JOB-QUICK' })),
    } as unknown as LoadJobsApi;
    await act(async () => root.render(<QuickLoadPage
      api={api} packageId="payments" target={{ type: 'template', resourceId: 'checkout' }}
      environment="" environments={[]} environmentState="ready" inlineLoadEnabled
      onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()} onCancel={vi.fn()}
      registerUnsavedChanges={() => () => {}}
    />));
    await flush();

    const validate = [...host.querySelectorAll('button')].find(button => button.textContent === 'Validate')!;
    await act(async () => validate.click());
    await flush();
    expect(host.textContent).toContain('DURATION_INVALID');
    expect(host.textContent).toContain('Use a supported duration.');
    expect(host.textContent).toContain('Flow checkout depends on a missing Tool');
    expect(host.textContent).toContain('Suggestion: Add the required Tool and verify password=[REDACTED]');
    expect(host.textContent).not.toContain('/srv/private');
    expect(host.textContent).not.toContain('hidden-value');
    expect(host.textContent).not.toContain('hidden-password');
    expect(host.textContent).toContain('req-quick-123');
    const review = [...host.querySelectorAll('button')].find(button => button.textContent === 'Review field')!;
    await act(async () => review.click());
    expect(document.activeElement).toBe(host.querySelector('input[placeholder="e.g. 30s or 5m"]'));
  });

  it('renders package dependency diagnostics without offering a field focus action', async () => {
    const api = {
      getLoadPolicy: vi.fn(async () => ({ model: 'virtualUsers' as const, policy: { schemaVersion: 'att-load/v1.6', load: { users: 1, duration: '30s' } } })),
      getTargetLoadForm: vi.fn(async () => ({ target: { type: 'flow' as const, id: 'checkout.v1' }, model: 'virtualUsers' as const, input: { inputs: {}, vars: {} } })),
      createLoadDraft: vi.fn(async () => { throw new ApiError('Target validation failed.', {
        status: 400, requestId: 'REQ-FLOW', diagnostics: [{ code: 'ATT-LOAD-001', field: 'target',
          summary: 'Invalid load target dependencies', detail: "Target flow 'checkout.v1': required Tool 'payments.charge' is unavailable",
          suggestion: 'Register the Tool dependency and retry.' }],
      }); }),
    } as unknown as LoadJobsApi;
    await act(async () => root.render(<QuickLoadPage api={api} packageId="payments"
      target={{ type: 'flow', resourceId: 'opaque-flow', logicalId: 'checkout.v1' }} environment="UAT" environments={['UAT']}
      environmentState="ready" inlineLoadEnabled onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()} onCancel={vi.fn()}
      registerUnsavedChanges={() => () => {}} />));
    await flush();
    await act(async () => [...host.querySelectorAll('button')].find(button => button.textContent === 'Validate')!.click());
    await flush();
    expect(host.textContent).toContain("Target flow 'checkout.v1'");
    expect(host.textContent).toContain("required Tool 'payments.charge' is unavailable");
    expect(host.textContent).toContain('Register the Tool dependency and retry.');
    expect([...host.querySelectorAll('button')].some(button => button.textContent === 'Review field')).toBe(false);
  });

  it('loads the selected bundled Quick Smoke policy without submitting and validates its preview', async () => {
    const api = {
      getLoadPolicy: vi.fn(async (_packageId: string, model: 'virtualUsers' | 'arrivalRate', _environment?: string, _signal?: AbortSignal, templateId?: string) => ({
        model,
        policy: templateId === 'bundled:quick-smoke'
          ? { schemaVersion: 'att-load/v1.6', load: { users: 2, duration: '10s' }, execution: { thinkTime: '250ms' }, evidence: { mode: 'failures' } }
          : { schemaVersion: 'att-load/v1.6', load: { users: 1, duration: '30s' } },
        templates: [{ id: 'bundled:quick-smoke', name: 'Quick Smoke · 2 users · 10s', model: 'virtualUsers' as const, bundled: true }],
      })),
      getTargetLoadForm: vi.fn(async () => ({ target: { type: 'template' as const, id: 'checkout' }, model: 'virtualUsers' as const, input: { inputs: { account: 'A-1' }, vars: {} } })),
      createLoadDraft: vi.fn(async () => ({
        draftId: 'L0123456789ABCDEF0123456789ABCDEF', packageId: 'payments', model: 'virtualUsers' as const,
        previewYaml: 'schemaVersion: att-load/v1.6\n', expiresAt: new Date(Date.now() + 60_000).toISOString(),
      })),
      submitLoadDraft: vi.fn(async () => ({ jobId: 'JOB-QUICK' })),
    } as unknown as LoadJobsApi;
    const onJobAccepted = vi.fn();
    await act(async () => root.render(<QuickLoadPage
      api={api} packageId="payments" target={{ type: 'template', resourceId: 'opaque:r_4f%2F' }}
      environment="" environments={['staging']} environmentState="ready" inlineLoadEnabled
      onEnvironmentChange={vi.fn()} onJobAccepted={onJobAccepted} onCancel={vi.fn()}
      registerUnsavedChanges={() => () => {}}
    />));
    await flush();

    const preset = host.querySelector<HTMLSelectElement>('select[aria-label="Load template or preset"]')!;
    await act(async () => {
      preset.value = 'bundled:quick-smoke';
      preset.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await flush();
    expect(api.getLoadPolicy).toHaveBeenLastCalledWith('payments', 'virtualUsers', undefined, expect.any(AbortSignal), 'bundled:quick-smoke');
    expect(api.createLoadDraft).not.toHaveBeenCalled();
    expect([...host.querySelectorAll('button')].find(button => button.textContent === 'Schedule…')?.disabled).toBe(true);

    const validate = [...host.querySelectorAll('button')].find(button => button.textContent === 'Validate')!;
    await act(async () => validate.click());
    await flush();
    expect(api.createLoadDraft).toHaveBeenCalledTimes(1);
    const input = vi.mocked(api.createLoadDraft).mock.calls[0][0];
    const scenario = input.scenario as Record<string, unknown>;
    const workload = (scenario.workloads as Array<Record<string, unknown>>)[0];
    expect(api.getTargetLoadForm).toHaveBeenCalledWith('payments', 'template', 'opaque:r_4f%2F', 'virtualUsers', undefined, expect.any(AbortSignal));
    expect(workload.target).toEqual({ type: 'template', id: 'checkout' });
    expect(workload.load).toEqual({ users: 2 });
    expect((scenario.load as Record<string, unknown>).duration).toBe('10s');
    expect((scenario.execution as Record<string, unknown>).thinkTime).toBe('250ms');
    expect((scenario.evidence as Record<string, unknown>).mode).toBe('failures');
    expect(api.submitLoadDraft).not.toHaveBeenCalled();
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    const run = [...host.querySelectorAll('button')].find(button => button.textContent === 'Run Now')!;
    await act(async () => run.click());
    expect(api.submitLoadDraft).toHaveBeenCalledWith({ packageId: 'payments', draftId: 'L0123456789ABCDEF0123456789ABCDEF' });
    expect(onJobAccepted).toHaveBeenCalledWith('JOB-QUICK');
    confirm.mockRestore();
  });

  it('ignores delayed validation after form edits, environment changes, and target changes', async () => {
    const pending: Array<(value: {
      draftId: string; packageId: string; model: 'virtualUsers'; previewYaml: string; expiresAt: string;
    }) => void> = [];
    const api = {
      getLoadPolicy: vi.fn(async () => ({ model: 'virtualUsers' as const, policy: { schemaVersion: 'att-load/v1.6', load: { users: 1, duration: '30s' } } })),
      getTargetLoadForm: vi.fn(async (_packageId: string, type: LoadTargetType, resourceId: string) => ({ target: { type, id: resourceId }, model: 'virtualUsers' as const, input: { inputs: {}, vars: {} } })),
      createLoadDraft: vi.fn(() => new Promise(resolve => pending.push(resolve))),
      submitLoadDraft: vi.fn(async () => ({ jobId: 'JOB-QUICK' })),
    } as unknown as LoadJobsApi;
    const onEnvironmentChange = vi.fn();
    let environment = 'SIT';
    let target = { type: 'flow', resourceId: 'checkout' };
    const renderPage = () => root.render(<QuickLoadPage
      api={api} packageId="payments" target={target} environment={environment}
      environments={['SIT', 'UAT']} environmentState="ready" inlineLoadEnabled
      onEnvironmentChange={onEnvironmentChange} onJobAccepted={vi.fn()} onCancel={vi.fn()}
      registerUnsavedChanges={() => () => {}}
    />);
    const clickValidate = async () => {
      const button = [...host.querySelectorAll('button')].find(item => item.textContent === 'Validate')!;
      await act(async () => button.click());
      await flush();
    };
    const resolveLatest = async () => {
      pending[pending.length - 1]({ draftId: 'L0123456789ABCDEF0123456789ABCDEF', packageId: 'payments', model: 'virtualUsers', previewYaml: 'schemaVersion: att-load/v1.6\n', expiresAt: new Date(Date.now() + 60_000).toISOString() });
      await flush();
    };

    await act(async () => renderPage());
    await flush();

    await clickValidate();
    expect(pending).toHaveLength(1);
    const duration = host.querySelector<HTMLInputElement>('input[placeholder="e.g. 30s or 5m"]')!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(duration, '5m');
      duration.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await resolveLatest();
    expect([...host.querySelectorAll('button')].find(item => item.textContent === 'Run Now')!.disabled).toBe(true);

    await clickValidate();
    expect(pending).toHaveLength(2);
    const environmentSelect = host.querySelectorAll('select')[2];
    await act(async () => {
      (environmentSelect as HTMLSelectElement).value = 'UAT';
      environmentSelect.dispatchEvent(new Event('change', { bubbles: true }));
      environment = 'UAT';
      renderPage();
    });
    await flush();
    await resolveLatest();
    expect(onEnvironmentChange).toHaveBeenCalledWith('UAT');
    expect([...host.querySelectorAll('button')].find(item => item.textContent === 'Run Now')!.disabled).toBe(true);

    await clickValidate();
    expect(pending).toHaveLength(3);
    await act(async () => {
      target = { type: 'flow', resourceId: 'refund' };
      renderPage();
    });
    await flush();
    await resolveLatest();
    expect(host.textContent).toContain('flow · refund');
    expect([...host.querySelectorAll('button')].find(item => item.textContent === 'Run Now')!.disabled).toBe(true);
    expect(api.submitLoadDraft).not.toHaveBeenCalled();
  });

  it('loads the matching package policy when switching between Virtual Users and Arrival Rate', async () => {
    const api = {
      getLoadPolicy: vi.fn(async (_packageId: string, model: 'virtualUsers' | 'arrivalRate') => ({
        model,
        templates: [{ id: 'bundled:quick-smoke', name: 'Quick Smoke', model: 'virtualUsers' as const, bundled: true }],
        policy: model === 'virtualUsers'
          ? { schemaVersion: 'att-load/v1.6', load: { users: 3, duration: '20s' }, thresholds: { p95: '<= 250ms' } }
          : { schemaVersion: 'att-load/v1.6', load: { arrivalRate: '12/s', maxConcurrent: 4, duration: '2m' }, thresholds: { achievedArrivalRate: '>= 90%' } },
      })),
      getTargetLoadForm: vi.fn(async (_packageId: string, type: LoadTargetType, resourceId: string) => ({ target: { type, id: resourceId }, model: 'virtualUsers' as const, input: { inputs: {}, vars: {} } })),
      createLoadDraft: vi.fn(async () => ({
        draftId: 'L0123456789ABCDEF0123456789ABCDEF', packageId: 'payments', model: 'virtualUsers' as const,
        previewYaml: 'schemaVersion: att-load/v1.6\n', expiresAt: new Date(Date.now() + 60_000).toISOString(),
      })),
      submitLoadDraft: vi.fn(async () => ({ jobId: 'JOB-QUICK' })),
    } as unknown as LoadJobsApi;
    await act(async () => root.render(<QuickLoadPage
      api={api} packageId="payments" target={{ type: 'template', resourceId: 'checkout' }}
      environment="" environments={[]} environmentState="ready" inlineLoadEnabled
      onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()} onCancel={vi.fn()}
      registerUnsavedChanges={() => () => {}}
    />));
    await flush();
    expect(host.querySelector<HTMLInputElement>('input[placeholder="e.g. 30s or 5m"]')?.value).toBe('20s');
    expect(host.querySelector<HTMLInputElement>('input[type="number"]')?.value).toBe('3');

    const model = host.querySelectorAll('select')[1] as HTMLSelectElement;
    await act(async () => {
      model.value = 'arrivalRate';
      model.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await flush();
    expect(api.getLoadPolicy).toHaveBeenLastCalledWith('payments', 'arrivalRate', undefined, expect.any(AbortSignal), undefined);
    expect(host.querySelector<HTMLInputElement>('input[placeholder="e.g. 5/s"]')?.value).toBe('12/s');
    expect(host.querySelector<HTMLInputElement>('input[placeholder="e.g. 30s or 5m"]')?.value).toBe('2m');
    expect((host.querySelector('select[aria-label="Load template or preset"] option[value="bundled:quick-smoke"]') as HTMLOptionElement).disabled).toBe(false);

    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => {
      model.value = 'virtualUsers';
      model.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await flush();
    expect(host.querySelector<HTMLInputElement>('input[placeholder="e.g. 30s or 5m"]')?.value).toBe('20s');
    expect(host.querySelector<HTMLInputElement>('input[type="number"]')?.value).toBe('3');
    confirm.mockRestore();
  });

  it('loads arbitrary compatible templates and preserves their supported advanced fields', async () => {
    const templates = [
      { id: 'load/east-vu.yaml', name: 'east-vu.yaml', model: 'virtualUsers' as const },
      { id: 'load/east-arrival.yaml', name: 'east-arrival.yaml', model: 'arrivalRate' as const },
    ];
    const api = {
      getLoadPolicy: vi.fn(async (_packageId: string, model: 'virtualUsers' | 'arrivalRate', _environment?: string, _signal?: AbortSignal, templateId?: string) => ({
        model,
        templates,
        policy: templateId === 'load/east-arrival.yaml'
          ? { schemaVersion: 'att-load/v1.6', load: { arrivalRate: '7/s', maxConcurrent: 5, duration: '45s', warmup: '5s' }, execution: { thinkTime: '80ms', customExecutionOption: 'preserve-me' }, thresholds: { p95: '<= 400ms', customThreshold: { mode: 'strict' } }, evidence: { mode: 'failures' }, customRootField: { enabled: true } }
          : { schemaVersion: 'att-load/v1.6', load: { users: 2, duration: '20s' } },
      })),
      getTargetLoadForm: vi.fn(async (_packageId: string, type: LoadTargetType, resourceId: string) => ({ target: { type, id: resourceId }, model: 'virtualUsers' as const, input: { inputs: {}, vars: {} } })),
      createLoadDraft: vi.fn(async () => ({ draftId: 'draft', packageId: 'payments', model: 'arrivalRate' as const, previewYaml: 'validated' })),
      submitLoadDraft: vi.fn(async () => ({ jobId: 'JOB-QUICK' })),
    } as unknown as LoadJobsApi;
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    await act(async () => root.render(<QuickLoadPage
      api={api} packageId="payments" target={{ type: 'flow', resourceId: 'checkout' }} environment="" environments={[]}
      environmentState="ready" inlineLoadEnabled onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()} onCancel={vi.fn()}
      registerUnsavedChanges={() => () => {}}
    />));
    await flush();
    const templatesSelect = host.querySelector<HTMLSelectElement>('select[aria-label="Load template or preset"]')!;
    expect([...templatesSelect.options].map(option => option.value)).toContain('load/east-arrival.yaml');
    await act(async () => {
      templatesSelect.value = 'load/east-arrival.yaml';
      templatesSelect.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await flush();
    expect(api.getLoadPolicy).toHaveBeenLastCalledWith('payments', 'arrivalRate', undefined, expect.any(AbortSignal), 'load/east-arrival.yaml');
    expect(host.querySelectorAll('select')[1].value).toBe('arrivalRate');
    expect(host.querySelector<HTMLInputElement>('input[placeholder="e.g. 5/s"]')?.value).toBe('7/s');
    expect(host.querySelector<HTMLInputElement>('input[placeholder="e.g. 30s or 5m"]')?.value).toBe('45s');

    const advancedToggle = [...host.querySelectorAll('button')].find(button => button.textContent?.startsWith('Advanced Settings'))!;
    await act(async () => advancedToggle.click());
    await flush();
    expect(host.textContent).toContain('customExecutionOption');
    expect(host.textContent).toContain('customThreshold');
    expect(host.textContent).toContain('customRootField');
    expect(api.createLoadDraft).not.toHaveBeenCalled();
    expect(api.submitLoadDraft).not.toHaveBeenCalled();
    confirm.mockRestore();
  });

  it('keeps a selected nested policy visible when it is outside the bounded template list', async () => {
    const api = {
      getLoadPolicy: vi.fn(async () => ({ model: 'arrivalRate' as const, templates: [], templateCatalogTruncated: true,
        policy: { schemaVersion: 'att-load/v1.6', load: { arrivalRate: '5/s', duration: '30s' } } })),
      getTargetLoadForm: vi.fn(async () => ({ target: { type: 'flow' as const, id: 'checkout' }, model: 'arrivalRate' as const, input: { inputs: {}, vars: {} } })),
    } as unknown as LoadJobsApi;
    await act(async () => root.render(<QuickLoadPage api={api} packageId="payments" target={{ type: 'flow', resourceId: 'checkout' }}
      initialTemplateId="load/nested/policy.yaml" initialModel="arrivalRate" environment="" environments={[]}
      environmentState="ready" inlineLoadEnabled onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()} onCancel={vi.fn()}
      registerUnsavedChanges={() => () => {}} />));
    await flush();
    const select = host.querySelector<HTMLSelectElement>('select[aria-label="Load template or preset"]')!;
    expect(select.value).toBe('load/nested/policy.yaml');
    expect([...select.options].find(option => option.value === select.value)?.textContent).toContain('Selected package template');
    expect(api.getLoadPolicy).toHaveBeenCalledWith('payments', 'arrivalRate', undefined, expect.any(AbortSignal), 'load/nested/policy.yaml');
  });

  it('keeps the logical Flow ID and shows sanitized target discovery diagnostics with retry', async () => {
    const api = {
      getLoadPolicy: vi.fn(async () => ({ model: 'virtualUsers' as const, policy: { schemaVersion: 'att-load/v1.6', load: { users: 1, duration: '30s' } } })),
      getTargetLoadForm: vi.fn()
        .mockRejectedValueOnce(new ApiError('Target defaults could not be loaded.', {
          status: 400, code: 'QUICK_LOAD_TARGET_UNAVAILABLE', requestId: 'req-target-456',
          diagnostics: [{ severity: 'ERROR', code: 'FLOW_DEFAULTS_UNAVAILABLE', summary: 'Retry safe target discovery.', path: '/private/server/path' }],
        }))
        .mockResolvedValueOnce({ target: { type: 'flow' as const, id: 'ready.flow.v1' }, model: 'virtualUsers' as const, input: { inputs: {}, vars: {} } }),
    } as unknown as LoadJobsApi;
    await act(async () => root.render(<QuickLoadPage api={api} packageId="payments"
      target={{ type: 'flow', resourceId: 'opaque-flow-resource', logicalId: 'ready.flow.v1' }}
      environment="UAT" environments={['UAT']} environmentState="ready" inlineLoadEnabled
      onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()} onCancel={vi.fn()}
      registerUnsavedChanges={() => () => {}} />));
    await flush();

    expect(host.querySelector('.att-section-heading strong')?.textContent).toBe('flow · ready.flow.v1');
    expect(host.textContent).toContain('Target defaults could not be loaded.');
    expect(host.textContent).toContain('FLOW_DEFAULTS_UNAVAILABLE');
    expect(host.textContent).toContain('Retry safe target discovery.');
    expect(host.textContent).toContain('req-target-456');
    expect(host.textContent).not.toContain('/private/server/path');
    expect([...host.querySelectorAll('button')].find(button => button.textContent === 'Validate')?.disabled).toBe(false);

    await act(async () => [...host.querySelectorAll('button')].find(button => button.textContent === 'Retry target defaults')!.click());
    await flush();
    expect(api.getTargetLoadForm).toHaveBeenCalledTimes(2);
    expect(api.getTargetLoadForm).toHaveBeenLastCalledWith('payments', 'flow', 'opaque-flow-resource', 'virtualUsers', 'UAT', expect.any(AbortSignal));
    expect(host.textContent).toContain('Target defaults are ready.');
  });

  it('uses opaque resource IDs from the standalone target picker and submits the logical Flow ID', async () => {
    const api = {
      listTargets: vi.fn(async () => [{ type: 'flow' as const, resourceId: 'opaque-flow-resource', logicalId: 'ready.flow.v1', state: 'ready' }]),
      getLoadPolicy: vi.fn(async () => ({ model: 'virtualUsers' as const, policy: { schemaVersion: 'att-load/v1.6', load: { users: 1, duration: '30s' } } })),
      getTargetLoadForm: vi.fn(async () => ({ target: { type: 'flow' as const, id: 'ready.flow.v1' }, model: 'virtualUsers' as const, input: { inputs: {}, vars: {} } })),
      createLoadDraft: vi.fn(async () => ({ draftId: 'L0123456789ABCDEF0123456789ABCDEF', packageId: 'payments', model: 'virtualUsers' as const, previewYaml: '', expiresAt: new Date(Date.now() + 60_000).toISOString() })),
    } as unknown as LoadJobsApi;
    await act(async () => root.render(<QuickLoadPage api={api} packageId="payments" environment="UAT"
      environments={['UAT']} environmentState="ready" inlineLoadEnabled onEnvironmentChange={vi.fn()}
      onJobAccepted={vi.fn()} onCancel={vi.fn()} registerUnsavedChanges={() => () => {}} />));
    await flush();

    const picker = host.querySelector<HTMLSelectElement>('select[aria-label="Quick Load target"]')!;
    await act(async () => { picker.value = 'flow:opaque-flow-resource'; picker.dispatchEvent(new Event('change', { bubbles: true })); });
    await flush();
    expect(api.getTargetLoadForm).toHaveBeenCalledWith('payments', 'flow', 'opaque-flow-resource', 'virtualUsers', 'UAT', expect.any(AbortSignal));
    expect(host.querySelector('.att-section-heading strong')?.textContent).toBe('flow · ready.flow.v1');

    await act(async () => [...host.querySelectorAll('button')].find(button => button.textContent === 'Validate')!.click());
    await flush();
    const draftInput = vi.mocked(api.createLoadDraft).mock.calls[0][0];
    const workload = (draftInput.scenario.workloads as Array<Record<string, unknown>>)[0];
    expect(workload.target).toEqual({ type: 'flow', id: 'ready.flow.v1' });
  });

  it('confirms before switching a package target after editing inputs', async () => {
    const api = {
      listTargets: vi.fn(async (_packageId: string, type: LoadTargetType) => type === 'template'
        ? [{ resourceId: 'opaque-one', logicalId: 'one', state: 'ready' }, { resourceId: 'opaque-two', logicalId: 'two', state: 'ready' }] : []),
      getLoadPolicy: vi.fn(async () => ({ model: 'virtualUsers' as const, policy: { schemaVersion: 'att-load/v1.6', load: { users: 1, duration: '30s' } } })),
      getTargetLoadForm: vi.fn(async (_packageId: string, type: LoadTargetType, resourceId: string) => ({ target: { type, id: resourceId }, model: 'virtualUsers' as const, input: { inputs: {}, vars: {} } })),
    } as unknown as LoadJobsApi;
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false);
    await act(async () => root.render(<QuickLoadPage api={api} packageId="payments" environment="" environments={[]}
      environmentState="ready" inlineLoadEnabled onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()} onCancel={vi.fn()}
      registerUnsavedChanges={() => () => {}} />));
    await flush();
    const target = host.querySelector<HTMLSelectElement>('select[aria-label="Quick Load target"]')!;
    await act(async () => { target.value = 'template:opaque-one'; target.dispatchEvent(new Event('change', { bubbles: true })); });
    await flush();
    const duration = host.querySelector<HTMLInputElement>('input[placeholder="e.g. 30s or 5m"]')!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(duration, '2m');
      duration.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await act(async () => { target.value = 'template:opaque-two'; target.dispatchEvent(new Event('change', { bubbles: true })); });
    expect(confirm).toHaveBeenCalledOnce();
    expect(target.value).toBe('template:opaque-one');
    expect(duration.value).toBe('2m');
    confirm.mockRestore();
  });
});
