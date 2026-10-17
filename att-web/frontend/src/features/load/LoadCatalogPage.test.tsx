// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LoadCatalogPage } from './LoadCatalogPage';
import { ApiError } from '../../types';

vi.mock('../code/CodeEditor', () => ({ CodeEditor: ({ value }: { value: string }) => <pre>{value}</pre> }));

let host: HTMLDivElement;
let root: Root;
beforeEach(() => {
  host = document.createElement('div'); document.body.append(host); root = createRoot(host);
  (globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});
afterEach(async () => { await act(async () => root.unmount()); host.remove(); });
async function flush() { await act(async () => { await new Promise(resolve => window.setTimeout(resolve, 0)); }); }

function unverifiedScenario(path = 'load/nightly.yaml') {
  return { packageId: 'payments', resourceId: path, path, kind: 'scenario', validation: 'unverified', workloadCount: 1, sourceAvailable: true, runNow: false, schedule: false, createQuickLoad: false };
}

describe('LoadCatalogPage', () => {
  it('shows validated scenarios and policy templates, previews safe source, and schedules the selected package file', async () => {
    const api = {
      listLoadCatalog: vi.fn(async () => ({ items: [
        { ...unverifiedScenario(), validation: 'valid', validatedEnvironment: 'SIT', model: 'virtualUsers', runNow: true, schedule: true },
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

  it('ignores stale validation results after the environment changes', async () => {
    let resolveValidation: (value: { validation: string }) => void = () => {};
    const api = {
      listLoadCatalog: vi.fn(async () => ({ items: [unverifiedScenario()], total: 1 })),
      validateLoadCatalogScenario: vi.fn(() => new Promise<{ validation: string }>(resolve => { resolveValidation = resolve; })),
      getLoadSource: vi.fn(async () => ({ available: true, text: '', redacted: true })),
      submitLoadScenario: vi.fn(async () => ({ jobId: 'JOB-LOAD' })),
      listLoadJobs: vi.fn(async () => ({ items: [] })),
    };
    await act(async () => root.render(<LoadCatalogPage api={api as never} packages={{ status: 'ready', data: [{ packageId: 'payments' }] }}
      inlineLoadEnabled schedulingEnabled environment="SIT" onEnvironmentChange={vi.fn()} onNewLoad={vi.fn()}
      onQuickLoad={vi.fn()} onJobAccepted={vi.fn()} onOpenJob={vi.fn()} onSchedule={vi.fn()} />));
    await flush();

    const validate = [...host.querySelectorAll('button')].find(button => button.textContent === 'Validate for Environment')!;
    await act(async () => validate.click());
    await flush();
    const environmentInput = [...host.querySelectorAll('input')].find(input => input.value === 'SIT')!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(environmentInput, 'UAT');
      environmentInput.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await act(async () => resolveValidation({ validation: 'valid' }));
    await flush();

    expect(api.validateLoadCatalogScenario).toHaveBeenCalledWith('payments', 'load/nightly.yaml', 'SIT');
    const runNow = [...host.querySelectorAll('button')].find(button => button.textContent === 'Run Now')! as HTMLButtonElement;
    expect(runNow.disabled).toBe(true);
    expect(host.textContent).toContain('unverified');
  });

  it('aborts stale catalog appends when the search changes', async () => {
    let appendSignal: AbortSignal | null = null;
    let resolveAppend: ((page: unknown) => void) | undefined;
    let resolveSearch: ((page: unknown) => void) | undefined;
    const api = {
      listLoadCatalog: vi.fn((_packageId: string, input: { query?: string; cursor?: string }, signal: AbortSignal) => {
        if (input.cursor) {
          appendSignal = signal;
          return new Promise(resolve => { resolveAppend = resolve; });
        }
        if (input.query) return new Promise(resolve => { resolveSearch = resolve; });
        return Promise.resolve({ items: [unverifiedScenario()], total: 1, nextCursor: 'next-a' });
      }),
      listLoadJobs: vi.fn(async () => ({ items: [] })),
    };
    await act(async () => root.render(<LoadCatalogPage api={api as never} packages={{ status: 'ready', data: [{ packageId: 'payments' }] }}
      inlineLoadEnabled schedulingEnabled environment="SIT" onEnvironmentChange={vi.fn()} onNewLoad={vi.fn()}
      onQuickLoad={vi.fn()} onJobAccepted={vi.fn()} onOpenJob={vi.fn()} onSchedule={vi.fn()} />));
    await flush();
    const loadMore = [...host.querySelectorAll('button')].find(button => button.textContent === 'Load more scenarios')!;
    await act(async () => loadMore.click());
    expect(appendSignal).not.toBeNull();

    const searchInput = [...host.querySelectorAll('input')].find(input => input.type === 'search')!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(searchInput, 'nightly');
      searchInput.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await act(async () => resolveSearch!({ items: [unverifiedScenario('search-result.yaml')], total: 1 }));
    await act(async () => resolveAppend!({ items: [{ ...unverifiedScenario('stale.yaml') }], total: 2, nextCursor: 'stale-cursor' }));
    await flush();
    expect(api.listLoadCatalog).toHaveBeenCalledWith('payments', { query: 'nightly', cursor: undefined, limit: 100 }, expect.anything());
    expect(appendSignal!.aborted).toBe(true);
    expect(host.textContent).toContain('search-result.yaml');
    expect(host.textContent).not.toContain('stale.yaml');
    expect([...host.querySelectorAll('button')].some(button => button.textContent === 'Load more scenarios')).toBe(false);
  });

  it('keeps healthy package resources and recent jobs available when one package fails, with package retry', async () => {
    let brokenAttempts = 0;
    const api = {
      listLoadCatalog: vi.fn(async (packageId: string) => {
        if (packageId === 'broken' && brokenAttempts++ === 0) throw new ApiError('Worker configuration is missing', {
          status: 422, code: 'ATT-CFG-001', diagnostics: [{ code: 'ATT-CFG-001', suggestion: 'Configure required Tomcat environment variables.' }],
        });
        return { items: [{ ...unverifiedScenario('load/healthy.yaml'), packageId }], total: 1 };
      }),
      listLoadJobs: vi.fn(async () => ({ items: [{ jobId: 'JOB-LOAD-1', packageId: 'healthy', status: 'SUCCESS', createdAt: '2026-01-01T00:00:00Z' }] })),
    };
    await act(async () => root.render(<LoadCatalogPage api={api as never} packages={{ status: 'ready', data: [{ packageId: 'broken' }, { packageId: 'healthy' }] }}
      inlineLoadEnabled schedulingEnabled environment="SIT" onEnvironmentChange={vi.fn()} onNewLoad={vi.fn()}
      onQuickLoad={vi.fn()} onJobAccepted={vi.fn()} onOpenJob={vi.fn()} onSchedule={vi.fn()} />));
    await flush();

    expect(host.textContent).toContain('load/healthy.yaml');
    expect(host.textContent).toContain('JOB-LOAD-1');
    expect(host.textContent).toContain('broken: Worker configuration is missing');
    expect(host.textContent).toContain('Configure required Tomcat environment variables.');
    const retry = [...host.querySelectorAll('button')].find(button => button.textContent === 'Retry broken')!;
    await act(async () => retry.click());
    await flush();
    expect(api.listLoadCatalog).toHaveBeenCalledTimes(3);
    expect(api.listLoadCatalog.mock.calls.map(([packageId]) => packageId)).toEqual(['broken', 'healthy', 'broken']);
    expect(host.textContent).not.toContain('Retry broken');
  });
});
