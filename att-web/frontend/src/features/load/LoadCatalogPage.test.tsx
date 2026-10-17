// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LoadCatalogPage } from './LoadCatalogPage';

vi.mock('../code/CodeEditor', () => ({ CodeEditor: ({ value }: { value: string }) => <pre>{value}</pre> }));

let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); });
async function flush() { await act(async () => { await Promise.resolve(); await Promise.resolve(); }); }

describe('LoadCatalogPage', () => {
  it('shows validated scenarios and policy templates, previews safe source, and schedules the selected package file', async () => {
    const api = {
      listLoadCatalog: vi.fn(async () => ({ items: [
        { packageId: 'payments', resourceId: 'load/nightly.yaml', path: 'load/nightly.yaml', kind: 'scenario', validation: 'valid', model: 'virtualUsers', workloadCount: 1, sourceAvailable: true, runNow: true, schedule: true, createQuickLoad: false },
        { packageId: 'payments', resourceId: 'load/policy.yaml', path: 'load/policy.yaml', kind: 'template', validation: 'valid', model: 'arrivalRate', workloadCount: 0, sourceAvailable: true, runNow: false, schedule: false, createQuickLoad: true },
      ], total: 2 })),
      validateLoadCatalogScenario: vi.fn(async () => ({ validation: 'valid' })),
      getLoadSource: vi.fn(async () => ({ available: true, text: 'schemaVersion: att-load/v1.6\n', redacted: true })),
      submitLoadScenario: vi.fn(async () => ({ jobId: 'JOB-LOAD' })),
      listLoadJobs: vi.fn(async () => ({ items: [] })),
    };
    const onSchedule = vi.fn(); const onJobAccepted = vi.fn();
    await act(async () => root.render(<LoadCatalogPage api={api as never} packages={{ status: 'ready', data: [{ packageId: 'payments' }] }}
      inlineLoadEnabled schedulingEnabled environment="SIT" onEnvironmentChange={vi.fn()} onNewLoad={vi.fn()}
      onQuickLoad={vi.fn()} onJobAccepted={onJobAccepted} onOpenJob={vi.fn()} onSchedule={onSchedule} />));
    await flush();

    expect(host.textContent).toContain('Executable Scenario');
    expect(host.textContent).toContain('Load Template');
    const validate = [...host.querySelectorAll('button')].find(button => button.textContent === 'Validate for Environment')!;
    await act(async () => validate.click()); await flush();
    expect(api.validateLoadCatalogScenario).toHaveBeenCalledWith('payments', 'load/nightly.yaml', 'SIT');
    const preview = [...host.querySelectorAll('button')].find(button => button.textContent === 'View YAML')!;
    await act(async () => preview.click()); await flush();
    expect(host.textContent).toContain('Redacted read-only YAML preview');
    expect(host.textContent).toContain('schemaVersion: att-load/v1.6');
    const schedule = [...host.querySelectorAll('button')].find(button => button.textContent === 'Schedule')!;
    await act(async () => schedule.click());
    expect(onSchedule).toHaveBeenCalledWith({ packageId: 'payments', environment: 'SIT', scenario: 'load/nightly.yaml', executionType: 'load' });
  });
});
