// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LoadCatalogPage } from './LoadCatalogPage';
import { ApiError } from '../../types';

vi.mock('../code/CodeEditor', () => ({ CodeEditor: ({ value }: { value: string }) => <pre>{value}</pre> }));
const openDetail = vi.fn();

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
      validateLoadCatalogScenario: vi.fn(async () => ({ validation: 'valid', scheduleAvailable: true, draftId: 'C'.concat('a'.repeat(32)),
        previewYaml: 'schemaVersion: att-load/v1.7\n' })),
      getLoadSource: vi.fn(async () => ({ available: true, text: 'schemaVersion: att-load/v1.6\n', redacted: true })),
      submitLoadScenario: vi.fn(async () => ({ jobId: 'JOB-LOAD' })),
      listLoadJobs: vi.fn(async () => ({ items: [] })),
    };
    const onSchedule = vi.fn(); const onJobAccepted = vi.fn(); openDetail.mockReset();
    await act(async () => root.render(<LoadCatalogPage api={api as never} packages={{ status: 'ready', data: [{ packageId: 'payments' }] }}
      inlineLoadEnabled schedulingEnabled environment="SIT" onEnvironmentChange={vi.fn()} onNewLoad={vi.fn()}
      onQuickLoad={vi.fn()} onJobAccepted={onJobAccepted} onOpenJob={vi.fn()} onSchedule={onSchedule} onOpenDetail={openDetail} />));
    await flush();

    expect(host.querySelector('.att-query-filter-panel[aria-label="LOAD Catalog filters"]')).not.toBeNull();
    expect(host.querySelector('.att-query-results-panel[aria-label="Available LOAD YAML resources"]')).not.toBeNull();
    expect(host.querySelector('.att-query-results-panel[aria-label="Recent LOAD job results"]')).not.toBeNull();
    expect(host.textContent).toContain('Executable Scenario');
    expect(host.textContent).toContain('Load Template');
    const validate = [...host.querySelectorAll('button')].find(button => button.textContent === 'Validate')!;
    await act(async () => validate.click()); await flush();
    expect(api.validateLoadCatalogScenario).toHaveBeenCalledWith('payments', 'load/nightly.yaml', 'SIT');
    const view = [...host.querySelectorAll('button')].find(button => button.textContent === 'View')!;
    await act(async () => view.click());
    expect(openDetail).toHaveBeenCalledWith(expect.objectContaining({ resourceId: 'load/nightly.yaml' }), 'SIT', expect.stringContaining('#/advanced-load?'));
    expect(host.textContent).not.toContain('Redacted read-only YAML preview');
    const schedule = [...host.querySelectorAll('button')].find(button => button.textContent === 'Schedule')!;
    await act(async () => schedule.click());
    expect(onSchedule).toHaveBeenCalledWith({ packageId: 'payments', environment: 'SIT', draftId: `C${'a'.repeat(32)}`,
      previewYaml: 'schemaVersion: att-load/v1.7\n', executionType: 'load' });
    await act(async () => [...host.querySelectorAll('button')].find(button => button.textContent === 'Clear filters')!.click());
    expect((host.querySelector('[aria-label="Catalog Environment for payments"]') as HTMLSelectElement).value).toBe('SIT');
  });

  it('offers explicit policy-template validation and refreshes its catalog status', async () => {
    const template = { packageId: 'payments', resourceId: 'load/policy.yaml', path: 'load/policy.yaml', kind: 'template', validation: 'valid', model: 'arrivalRate', workloadCount: 0, sourceAvailable: true, runNow: false, schedule: false, createQuickLoad: true };
    const api = {
      listLoadCatalog: vi.fn(async (_packageId: string, input: { query?: string }) => ({
        items: [input.query ? { ...template, validation: 'invalid', diagnostic: 'Policy duration is required.' } : template], total: 1,
      })),
      listLoadJobs: vi.fn(async () => ({ items: [] })),
    };
    await act(async () => root.render(<LoadCatalogPage api={api as never} packages={{ status: 'ready', data: [{ packageId: 'payments' }] }}
      inlineLoadEnabled schedulingEnabled environment="SIT" onEnvironmentChange={vi.fn()} onNewLoad={vi.fn()}
      onQuickLoad={vi.fn()} onJobAccepted={vi.fn()} onOpenJob={vi.fn()} onSchedule={vi.fn()} onOpenDetail={openDetail} />));
    await flush();
    const validate = [...host.querySelectorAll('button')].find(button => button.textContent === 'Validate')!;
    await act(async () => validate.click());
    await flush();
    expect(api.listLoadCatalog).toHaveBeenLastCalledWith('payments', { query: 'load/policy.yaml', limit: 100 });
    expect(host.textContent).toContain('Policy duration is required.');
    expect(host.textContent).toContain('policy validation refreshed: invalid');
  });

  it('uses declared profile options and gives unverified scenarios a persistent actionable reason', async () => {
    const api = {
      getDeclaredConfiguration: vi.fn(async () => ({ environments: [
        { name: 'SIT', state: 'active' }, { name: 'UAT', state: 'active' }, { name: 'RETIRED', state: 'disabled' },
      ] })),
      listLoadCatalog: vi.fn(async () => ({ items: [unverifiedScenario()], total: 1 })),
      listLoadJobs: vi.fn(async () => ({ items: [] })),
    };
    await act(async () => root.render(<LoadCatalogPage api={api as never} packages={{ status: 'ready', data: [{ packageId: 'payments' }] }}
      inlineLoadEnabled schedulingEnabled environment="" onEnvironmentChange={vi.fn()} onNewLoad={vi.fn()}
      onQuickLoad={vi.fn()} onJobAccepted={vi.fn()} onOpenJob={vi.fn()} onSchedule={vi.fn()} onOpenDetail={openDetail} />));
    await flush();
    const environment = host.querySelector<HTMLSelectElement>('[aria-label="Catalog Environment for payments"]')!;
    expect([...environment.options].map(option => option.value)).toEqual(['', 'SIT', 'UAT']);
    expect(host.querySelector('input[aria-label="Catalog Environment"]')).toBeNull();
    expect(host.textContent).toContain('Validate for Package default before running or scheduling.');
    expect(host.textContent).not.toContain('Scheduling disabled by Server policy.');
    expect((host.querySelector('button') as HTMLButtonElement).disabled).toBe(false);
  });

  it('keeps invalid diagnostics and a View details route beside disabled scenario actions', async () => {
    const invalid = { ...unverifiedScenario(), validation: 'invalid', diagnostic: 'Target dependency is missing.', requestId: 'REQ-42',
      diagnostics: [{ code: 'ATT-LOAD-TARGET', summary: 'Target dependency is missing.', field: 'workloads[0].target.id', suggestion: 'Choose a registered target.' }] };
    const api = { listLoadCatalog: vi.fn(async () => ({ items: [invalid], total: 1 })), listLoadJobs: vi.fn(async () => ({ items: [] })) };
    openDetail.mockReset();
    await act(async () => root.render(<LoadCatalogPage api={api as never} packages={{ status: 'ready', data: [{ packageId: 'payments' }] }}
      inlineLoadEnabled schedulingEnabled environment="" onEnvironmentChange={vi.fn()} onNewLoad={vi.fn()}
      onQuickLoad={vi.fn()} onJobAccepted={vi.fn()} onOpenJob={vi.fn()} onSchedule={vi.fn()} onOpenDetail={openDetail} />));
    await flush();
    expect(host.textContent).toContain('Request ID: REQ-42');
    expect(host.textContent).toContain('Choose a registered target.');
    expect(host.textContent).toContain('Validation failed. Review the diagnostic details before running or scheduling.');
    const runNow = [...host.querySelectorAll('button')].find(button => button.textContent === 'Run Now')! as HTMLButtonElement;
    expect(runNow.disabled).toBe(true);
    const details = [...host.querySelectorAll('button')].find(button => button.textContent === 'View details')!;
    await act(async () => details.click());
    expect(openDetail).toHaveBeenCalledWith(expect.objectContaining({ resourceId: 'load/nightly.yaml' }), '', expect.stringContaining('#/advanced-load'));
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
      onQuickLoad={vi.fn()} onJobAccepted={vi.fn()} onOpenJob={vi.fn()} onSchedule={vi.fn()} onOpenDetail={openDetail} />));
    await flush();

    const validate = [...host.querySelectorAll('button')].find(button => button.textContent === 'Validate')!;
    await act(async () => validate.click());
    await flush();
    const environmentInput = [...host.querySelectorAll('select')].find(input => input.value === 'SIT')!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set?.call(environmentInput, '');
      environmentInput.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await act(async () => resolveValidation({ validation: 'valid' }));
    await flush();

    expect(api.validateLoadCatalogScenario).toHaveBeenCalledWith('payments', 'load/nightly.yaml', 'SIT');
    const runNow = [...host.querySelectorAll('button')].find(button => button.textContent === 'Run Now')! as HTMLButtonElement;
    expect(runNow.disabled).toBe(true);
    expect(host.textContent).toContain('unverified');
  });

  it('finishes an initial catalog request when the environment changes', async () => {
    let resolveCatalog: ((page: unknown) => void) | undefined;
    const api = {
      listLoadCatalog: vi.fn(() => new Promise(resolve => { resolveCatalog = resolve; })),
      listLoadJobs: vi.fn(async () => ({ items: [] })),
    };
    await act(async () => root.render(<LoadCatalogPage api={api as never} packages={{ status: 'ready', data: [{ packageId: 'payments' }] }}
      inlineLoadEnabled schedulingEnabled environment="SIT" onEnvironmentChange={vi.fn()} onNewLoad={vi.fn()}
      onQuickLoad={vi.fn()} onJobAccepted={vi.fn()} onOpenJob={vi.fn()} onSchedule={vi.fn()} onOpenDetail={openDetail} />));
    const environmentInput = [...host.querySelectorAll('select')].find(input => input.value === 'SIT')!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set?.call(environmentInput, '');
      environmentInput.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await act(async () => resolveCatalog!({ items: [unverifiedScenario()], total: 1 }));
    await flush();
    expect(host.textContent).not.toContain('Discovering package Load YAML resources');
    expect(host.textContent).toContain('load/nightly.yaml');
    const refresh = [...host.querySelectorAll('button')].find(button => button.textContent === 'Refresh resources')! as HTMLButtonElement;
    expect(refresh.disabled).toBe(false);
  });

  it('finishes Load more when the environment changes', async () => {
    let resolveAppend: ((page: unknown) => void) | undefined;
    const api = {
      listLoadCatalog: vi.fn((_packageId: string, input: { cursor?: string }) => input.cursor
        ? new Promise(resolve => { resolveAppend = resolve; })
        : Promise.resolve({ items: [unverifiedScenario()], total: 2, nextCursor: 'next' })),
      listLoadJobs: vi.fn(async () => ({ items: [] })),
    };
    await act(async () => root.render(<LoadCatalogPage api={api as never} packages={{ status: 'ready', data: [{ packageId: 'payments' }] }}
      inlineLoadEnabled schedulingEnabled environment="SIT" onEnvironmentChange={vi.fn()} onNewLoad={vi.fn()}
      onQuickLoad={vi.fn()} onJobAccepted={vi.fn()} onOpenJob={vi.fn()} onSchedule={vi.fn()} onOpenDetail={openDetail} />));
    await flush();
    const loadMore = [...host.querySelectorAll('button')].find(button => button.textContent === 'Load more scenarios')! as HTMLButtonElement;
    await act(async () => loadMore.click());
    const environmentInput = [...host.querySelectorAll('select')].find(input => input.value === 'SIT')!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set?.call(environmentInput, '');
      environmentInput.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await act(async () => resolveAppend!({ items: [unverifiedScenario('load/next.yaml')], total: 2, nextCursor: 'next-again' }));
    await flush();
    const updatedLoadMore = [...host.querySelectorAll('button')].find(button => button.textContent === 'Load more scenarios')! as HTMLButtonElement;
    expect(updatedLoadMore.disabled).toBe(false);
    expect(updatedLoadMore.textContent).toBe('Load more scenarios');
    expect(host.textContent).toContain('load/next.yaml');
  });

  it('finishes a package retry when the environment changes', async () => {
    let resolveRetry: ((page: unknown) => void) | undefined;
    let attempts = 0;
    const api = {
      listLoadCatalog: vi.fn(() => ++attempts === 1
        ? Promise.reject(new Error('Catalog unavailable'))
        : new Promise(resolve => { resolveRetry = resolve; })),
      listLoadJobs: vi.fn(async () => ({ items: [] })),
    };
    await act(async () => root.render(<LoadCatalogPage api={api as never} packages={{ status: 'ready', data: [{ packageId: 'payments' }] }}
      inlineLoadEnabled schedulingEnabled environment="SIT" onEnvironmentChange={vi.fn()} onNewLoad={vi.fn()}
      onQuickLoad={vi.fn()} onJobAccepted={vi.fn()} onOpenJob={vi.fn()} onSchedule={vi.fn()} onOpenDetail={openDetail} />));
    await flush();
    const retry = [...host.querySelectorAll('button')].find(button => button.textContent === 'Retry payments')!;
    await act(async () => retry.click());
    const environmentInput = [...host.querySelectorAll('select')].find(input => input.value === 'SIT')!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')?.set?.call(environmentInput, '');
      environmentInput.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await act(async () => resolveRetry!({ items: [unverifiedScenario()], total: 1 }));
    await flush();
    expect(host.textContent).not.toContain('Retrying…');
    expect(host.textContent).not.toContain('payments: Catalog unavailable');
    expect(host.textContent).toContain('load/nightly.yaml');
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
      onQuickLoad={vi.fn()} onJobAccepted={vi.fn()} onOpenJob={vi.fn()} onSchedule={vi.fn()} onOpenDetail={openDetail} />));
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
    let healthyPages = 0;
    const api = {
      listLoadCatalog: vi.fn(async (packageId: string) => {
        if (packageId === 'broken' && brokenAttempts++ === 0) throw new ApiError('Worker configuration is missing', {
          status: 422, code: 'ATT-CFG-001', diagnostics: [{ code: 'ATT-CFG-001', suggestion: 'Configure required Tomcat environment variables.' }],
        });
        return { items: [{ ...unverifiedScenario(healthyPages++ ? 'load/healthy-next.yaml' : 'load/healthy.yaml'), packageId }], total: 1,
          ...(packageId === 'healthy' && healthyPages === 1 ? { nextCursor: 'healthy-next' } : {}) };
      }),
      listLoadJobs: vi.fn(async () => ({ items: [{ jobId: 'JOB-LOAD-1', packageId: 'healthy', status: 'SUCCESS', createdAt: '2026-01-01T00:00:00Z' }] })),
    };
    await act(async () => root.render(<LoadCatalogPage api={api as never} packages={{ status: 'ready', data: [{ packageId: 'broken' }, { packageId: 'healthy' }] }}
      inlineLoadEnabled schedulingEnabled environment="SIT" onEnvironmentChange={vi.fn()} onNewLoad={vi.fn()}
      onQuickLoad={vi.fn()} onJobAccepted={vi.fn()} onOpenJob={vi.fn()} onSchedule={vi.fn()} onOpenDetail={openDetail} />));
    await flush();

    expect(host.textContent).toContain('load/healthy.yaml');
    expect(host.textContent).toContain('JOB-LOAD-1');
    expect(host.textContent).toContain('broken: Worker configuration is missing');
    expect(host.textContent).toContain('Configure required Tomcat environment variables.');
    const loadMore = [...host.querySelectorAll('button')].find(button => button.textContent === 'Load more scenarios')!;
    await act(async () => loadMore.click());
    await flush();
    expect(host.textContent).toContain('broken: Worker configuration is missing');
    expect([...host.querySelectorAll('button')].some(button => button.textContent === 'Retry broken')).toBe(true);
    const retry = [...host.querySelectorAll('button')].find(button => button.textContent === 'Retry broken')!;
    await act(async () => retry.click());
    await flush();
    expect(api.listLoadCatalog).toHaveBeenCalledTimes(4);
    expect(api.listLoadCatalog.mock.calls.map(([packageId]) => packageId)).toEqual(['broken', 'healthy', 'healthy', 'broken']);
    expect(host.textContent).not.toContain('Retry broken');
  });

  it('clears old-query retry controls and ignores a detached retry while the new search is loading', async () => {
    let resolveSearchPackage: ((page: unknown) => void) | undefined;
    let brokenAttempts = 0;
    const api = {
      listLoadCatalog: vi.fn((packageId: string, input: { query?: string }) => {
        if (!input.query && packageId === 'broken' && brokenAttempts++ === 0) return Promise.reject(new Error('Old query failed'));
        if (input.query === 'nightly' && packageId === 'broken') return new Promise(resolve => { resolveSearchPackage = resolve; });
        return Promise.resolve({ items: [{ ...unverifiedScenario(input.query ? 'load/nightly-healthy.yaml' : 'load/healthy.yaml'), packageId }], total: 1 });
      }),
      listLoadJobs: vi.fn(async () => ({ items: [] })),
    };
    await act(async () => root.render(<LoadCatalogPage api={api as never} packages={{ status: 'ready', data: [{ packageId: 'broken' }, { packageId: 'healthy' }] }}
      inlineLoadEnabled schedulingEnabled environment="SIT" onEnvironmentChange={vi.fn()} onNewLoad={vi.fn()}
      onQuickLoad={vi.fn()} onJobAccepted={vi.fn()} onOpenJob={vi.fn()} onSchedule={vi.fn()} onOpenDetail={openDetail} />));
    await flush();
    const staleRetry = [...host.querySelectorAll('button')].find(button => button.textContent === 'Retry broken')!;
    const search = host.querySelector('input[type="search"]')!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(search, 'nightly');
      search.dispatchEvent(new Event('input', { bubbles: true }));
    });
    expect([...host.querySelectorAll('button')].some(button => button.textContent === 'Retry broken')).toBe(false);
    const callsBeforeStaleRetry = api.listLoadCatalog.mock.calls.length;
    await act(async () => staleRetry.click());
    expect(api.listLoadCatalog.mock.calls).toHaveLength(callsBeforeStaleRetry);
    await act(async () => resolveSearchPackage!({ items: [{ ...unverifiedScenario('load/nightly.yaml'), packageId: 'broken' }], total: 1 }));
    await flush();
    expect(host.textContent).toContain('load/nightly.yaml');
    expect(host.textContent).toContain('load/nightly-healthy.yaml');
    expect(api.listLoadCatalog.mock.calls.filter(([, input]) => input.query === 'nightly').map(([packageId]) => packageId)).toEqual(['broken', 'healthy']);
  });
});
