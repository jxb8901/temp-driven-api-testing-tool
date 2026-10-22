// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../types';
import { LoadResourceDetailPage } from './LoadResourceDetailPage';
import type { LoadCatalogDetail } from './types';

vi.mock('../code/CodeEditor', () => ({ CodeEditor: ({ value }: { value: string }) => <pre>{value}</pre> }));

let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); });
async function flush() { await act(async () => { await new Promise(resolve => window.setTimeout(resolve, 0)); }); }

function scenarioDetail(): LoadCatalogDetail {
  return { packageId: 'payments', resourceId: 'load/nested/nightly.yaml', path: 'load/nested/nightly.yaml', kind: 'scenario',
    validation: 'unverified', model: 'virtualUsers', schemaVersion: 'att-load/v1.8', workloadCount: 1, sourceAvailable: false,
    definition: { load: { users: 2, duration: '30s' }, workloads: [{ id: 'nightly', target: { type: 'flow', id: 'FLOW.v1' },
      inputs: { password: { '$att': 'keep-default' } }, load: { users: 2, duration: '30s' } }] }, redacted: true };
}

describe('LoadResourceDetailPage', () => {
  it('shows structured metadata and loads optional YAML source only when requested', async () => {
    const api = { getLoadCatalogDetail: vi.fn(async () => scenarioDetail()),
      getLoadSource: vi.fn(async () => ({ available: false, reason: 'visibility-policy' })),
      validateLoadCatalogScenario: vi.fn(async () => ({ validation: 'valid', model: 'virtualUsers' })) };
    const onBack = vi.fn();
    await act(async () => root.render(<LoadResourceDetailPage api={api} packageId="payments" resourceId="load/nested/nightly.yaml"
      environment="UAT" environments={['SIT', 'UAT']} inlineLoadEnabled onEnvironmentChange={vi.fn()} onBack={onBack} onQuickLoad={vi.fn()} />));
    await flush();
    expect(host.querySelector('h1')?.textContent).toBe('load/nested/nightly.yaml');
    expect(host.textContent).toContain('Schema version');
    expect(host.textContent).toContain('Workloads & Targets');
    expect(host.textContent).toContain('FLOW.v1');
    expect(host.textContent).toContain('keep-default');
    expect(api.getLoadSource).not.toHaveBeenCalled();
    expect(host.textContent).not.toContain('schemaVersion: att-load/v1.8');

    const validate = host.querySelector<HTMLButtonElement>('button[aria-label="Validate for UAT"]')!;
    await act(async () => validate.click()); await flush();
    expect(api.validateLoadCatalogScenario).toHaveBeenCalledWith('payments', 'load/nested/nightly.yaml', 'UAT', expect.any(AbortSignal));
    expect(host.textContent).toContain('Valid for UAT.');

    const showSource = [...host.querySelectorAll('button')].find(button => button.textContent === 'Show YAML Source')!;
    await act(async () => showSource.click()); await flush();
    expect(api.getLoadSource).toHaveBeenCalledWith('payments', 'load/nested/nightly.yaml', expect.any(AbortSignal));
    expect(host.textContent).toContain('YAML Source is unavailable: visibility-policy.');
    expect(onBack).not.toHaveBeenCalled();
  });

  it('shows sanitized validation diagnostics and a useful Request ID', async () => {
    const api = { getLoadCatalogDetail: vi.fn(async () => scenarioDetail()),
      getLoadSource: vi.fn(async () => ({ available: false, reason: 'visibility-policy' })),
      validateLoadCatalogScenario: vi.fn(async () => { throw new ApiError('Failed /home/ci/private/load.yaml password=topsecret', {
        status: 422, requestId: 'REQ-LOAD-1', diagnostics: [{ code: 'ATT-LOAD-TARGET', summary: 'Target could not be resolved',
          field: 'workloads[0].target.id', path: '/home/ci/private/template.yaml', line: 8, suggestion: 'Check password=topsecret before retrying.' }],
      }); }) };
    await act(async () => root.render(<LoadResourceDetailPage api={api} packageId="payments" resourceId="load/nested/nightly.yaml"
      environment="" environments={[]} inlineLoadEnabled onEnvironmentChange={vi.fn()} onBack={vi.fn()} onQuickLoad={vi.fn()} />));
    await flush();
    const validate = host.querySelector<HTMLButtonElement>('button[aria-label="Validate for Package default"]')!;
    await act(async () => validate.click()); await flush();
    expect(host.textContent).toContain('Request ID: REQ-LOAD-1');
    expect(host.textContent).toContain('Target could not be resolved');
    expect(host.textContent).toContain('Suggestion: Check password=[REDACTED] before retrying.');
    expect(host.textContent).not.toContain('topsecret');
    expect(host.textContent).not.toContain('/home/ci/private');
  });

  it('ignores validation and YAML source responses after identity changes', async () => {
    const validationResults: Array<(value: { validation: string; model: string }) => void> = [];
    let finishSourceA: ((value: { available: boolean; text?: string }) => void) | undefined;
    const api = {
      getLoadCatalogDetail: vi.fn(async (_packageId: string, resourceId: string) => ({ ...scenarioDetail(), resourceId, path: resourceId })),
      getLoadSource: vi.fn((_packageId: string, resourceId: string, _signal?: AbortSignal) => resourceId === 'load/A.yaml'
        ? new Promise<{ available: boolean; text?: string }>(resolve => { finishSourceA = resolve; })
        : Promise.resolve({ available: true, text: 'source-B-only' })),
      validateLoadCatalogScenario: vi.fn((_packageId: string, _resourceId: string, _environment?: string, _signal?: AbortSignal) =>
        new Promise<{ validation: string; model: string }>(resolve => { validationResults.push(resolve); })),
    };
    let resourceId = 'load/A.yaml';
    let environment = 'UAT';
    const renderPage = () => root.render(<LoadResourceDetailPage api={api} packageId="payments" resourceId={resourceId}
      environment={environment} environments={['SIT', 'UAT']} inlineLoadEnabled onEnvironmentChange={vi.fn()}
      onBack={vi.fn()} onQuickLoad={vi.fn()} />);
    await act(async () => renderPage()); await flush();

    await act(async () => host.querySelector<HTMLButtonElement>('button[aria-label="Validate for UAT"]')!.click());
    await act(async () => [...host.querySelectorAll('button')].find(button => button.textContent === 'Show YAML Source')!.click());
    expect(api.getLoadSource).toHaveBeenCalledWith('payments', 'load/A.yaml', expect.any(AbortSignal));

    resourceId = 'load/B.yaml'; environment = 'SIT';
    await act(async () => renderPage()); await flush();
    expect(host.textContent).not.toContain('Valid for UAT');
    expect(host.textContent).not.toContain('source-A-secret');
    await act(async () => validationResults[0]({ validation: 'valid', model: 'arrivalRate' }));
    await act(async () => finishSourceA?.({ available: true, text: 'source-A-secret' }));
    await flush();
    expect(host.textContent).not.toContain('Valid for UAT');
    expect(host.textContent).not.toContain('source-A-secret');
    await act(async () => host.querySelector<HTMLButtonElement>('button[aria-label="Validate for SIT"]')!.click());
    expect(api.validateLoadCatalogScenario).toHaveBeenLastCalledWith('payments', 'load/B.yaml', 'SIT', expect.any(AbortSignal));
    await act(async () => validationResults[1]({ validation: 'valid', model: 'virtualUsers' })); await flush();
    expect(host.textContent).toContain('Valid for SIT.');
  });
});
