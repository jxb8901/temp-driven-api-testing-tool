// @vitest-environment jsdom
import React, { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LoadPage } from './LoadPage';
import type { LoadJobsApi } from './types';

function makeApi(): LoadJobsApi {
  return {
    getLoadPolicy: vi.fn(async () => ({
      model: 'virtualUsers',
      policy: { schemaVersion: 'att-load/v1.6', load: { warmup: '5s', rampUp: '10s', duration: '30s', rampDown: '5s' } },
      redacted: true,
    })),
    getTargetLoadForm: vi.fn(async () => ({
      model: 'virtualUsers', target: { type: 'template', id: 'Payments.create' },
      input: { inputs: { accountId: 'acct-1', retry: 2 }, vars: { token: { $attDebugKeepDefault: 'hidden' } } }, redacted: true,
    })),
    listTargets: vi.fn(async () => [{ type: 'template', logicalId: 'Payments.create', state: 'ready' }]),
    createLoadDraft: vi.fn(async () => ({
      draftId: 'A0123456789ABCDEF0123456789ABCDEF', packageId: 'payments', model: 'virtualUsers',
      previewYaml: 'schemaVersion: att-load/v1.6', expiresAt: new Date(Date.now() + 60_000).toISOString(), redacted: true,
    })),
    submitLoadDraft: vi.fn(async () => ({ jobId: 'JOB-LOAD', status: 'QUEUED' })),
    listJobs: vi.fn(async () => []), getJob: vi.fn(), getJobResult: vi.fn(), getJobArtifacts: vi.fn(), cancelJob: vi.fn(),
    subscribeJob: vi.fn(() => () => {}), artifactHref: vi.fn((_jobId, path) => path),
  };
}

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

async function click(element: Element) {
  await act(async () => { element.dispatchEvent(new MouseEvent('click', { bubbles: true })); });
}

describe('LoadPage React feature', () => {
  it('uses section navigation, typed workload controls, and typed sidecar fields before Server validation', async () => {
    const api = makeApi();
    const onJobAccepted = vi.fn();
    await act(async () => root.render(<LoadPage
      api={api}
      packages={[{ packageId: 'payments', displayName: 'Payments' }]}
      selectedPackage="payments"
      selectedEnvironment=""
      environments={['SIT']}
      inlineLoadEnabled
      onPackageChange={vi.fn()}
      onEnvironmentChange={vi.fn()}
      onJobAccepted={onJobAccepted}
      confirmStart={() => true}
    />));
    await flush();

    expect(host.querySelectorAll('.att-section-nav button')).toHaveLength(4);
    expect(host.querySelector('.att-section-nav [aria-controls="load-workloads"]')).not.toBeNull();
    const measured = host.querySelector('input[aria-label="Measured duration value"]') as HTMLInputElement;
    expect(measured.value).toBe('30');
    await act(async () => {
      host.querySelector<HTMLButtonElement>('.att-section-nav [aria-controls="load-workloads"]')?.click();
    });
    expect(document.activeElement?.id).toBe('load-workloads');
    expect(window.location.hash).not.toContain('load-workloads');

    const model = [...host.querySelectorAll('label')].find(label => label.textContent?.includes('Load model'))?.querySelector('select');
    expect(model).not.toBeNull();
    await act(async () => {
      model!.value = 'arrivalRate';
      model!.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await flush();
    expect(host.querySelector('input[aria-label="Arrival rate value"]')).not.toBeNull();
    expect(host.querySelector('.att-think-time')?.hasAttribute('disabled')).toBe(true);
    expect(host.textContent).not.toContain('Weighted mix (Virtual Users only)');

    await act(async () => {
      model!.value = 'virtualUsers';
      model!.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await flush();
    const idInput = host.querySelector('.att-target-editor input[list]') as HTMLInputElement;
    await act(async () => {
      idInput.value = 'Payments.create';
      idInput.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await act(async () => { idInput.dispatchEvent(new Event('change', { bubbles: true })); });
    await flush();
    const sidecar = [...host.querySelectorAll('button')].find(button => button.textContent === 'Load sidecar defaults');
    expect(sidecar).toBeDefined();
    await click(sidecar!);
    await flush();
    expect(api.getTargetLoadForm).toHaveBeenCalledWith('payments', 'template', 'Payments.create', 'virtualUsers', undefined, expect.any(AbortSignal));
    expect(host.querySelector('input[aria-label="accountId value"]')).not.toBeNull();
    expect(host.textContent).toContain('Keep hidden package default');
    expect(host.querySelector('input[aria-label="accountId value"]')?.getAttribute('value')).toBeNull();
    expect((host.querySelector('input[aria-label="accountId value"]') as HTMLInputElement).value).toBe('acct-1');

    const validate = [...host.querySelectorAll('button')].find(button => button.textContent === 'Validate scenario');
    expect(validate).toBeDefined();
    await click(validate!);
    await flush();
    expect(api.createLoadDraft).toHaveBeenCalledTimes(1);
    const body = vi.mocked(api.createLoadDraft).mock.calls[0][0];
    const scenario = body.scenario as { workloads: Array<{ target: { id: string }; inputs: { accountId: string }; vars: Record<string, unknown> }> };
    expect(scenario.workloads[0].target.id).toBe('Payments.create');
    expect(scenario.workloads[0].inputs.accountId).toBe('acct-1');
    expect(scenario.workloads[0].vars.token).toEqual({ $attDebugKeepDefault: 'hidden' });
  });
});
