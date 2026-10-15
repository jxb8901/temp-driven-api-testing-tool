// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DashboardPage } from './DashboardPage';
import { initialAppState, type AppState } from './state';
import type { JobSummary } from './types';

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

const jobs: JobSummary[] = [
  { jobId: 'J-LATEST', command: 'load', packageId: 'payments', status: 'RUNNING', createdAt: '2026-10-15T10:00:00Z' },
  { jobId: 'J-FAIL', command: 'run', packageId: 'payments', status: 'FAIL', createdAt: '2026-10-15T09:00:00Z' },
  { jobId: 'J-ERROR', command: 'debug', packageId: 'payments', status: 'ERROR', createdAt: '2026-10-15T08:00:00Z' },
  { jobId: 'J-PASS', command: 'run', packageId: 'payments', status: 'PASS', createdAt: '2026-10-15T07:00:00Z' },
];

function stateWithJobs(jobState: AppState['jobs']): AppState {
  return {
    ...initialAppState,
    serverVersion: { status: 'ready', data: { apiVersion: '1', inlineLoadEnabled: false } },
    packages: { status: 'ready', data: [] },
    jobs: jobState,
  };
}

describe('DashboardPage job summary', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
  });

  afterEach(() => {
    act(() => root.unmount());
    host.remove();
  });

  function render(state: AppState, options: { refreshing?: boolean; onRetryJobs?: () => void } = {}) {
    act(() => root.render(<DashboardPage view="dashboard" state={state}
      onPackageChange={vi.fn()} onJobChange={vi.fn()} onRetryPackages={vi.fn()}
      onRetryJobs={options.onRetryJobs || vi.fn()} jobsRefreshing={options.refreshing}
      jobsUpdatedAt="2026-10-15T10:05:00Z" />));
  }

  function card(label: string): HTMLElement {
    const found = [...host.querySelectorAll<HTMLElement>('.summary-card')]
      .find(element => element.querySelector('.summary-label')?.textContent === label);
    if (!found) throw new Error(`Missing summary card: ${label}`);
    return found;
  }

  it('shows recent total, active, PASS/FAIL/ERROR counts, and latest job metadata from returned rows', () => {
    render(stateWithJobs({ status: 'ready', data: jobs }));

    expect(card('Recent jobs').querySelector('.summary-value')?.textContent).toBe('4');
    expect(card('Active jobs in results').querySelector('.summary-value')?.textContent).toBe('1');
    const outcomeCounts = card('Recent outcomes').querySelectorAll('.summary-outcome-count');
    expect([...outcomeCounts].map(item => [item.querySelector('span')?.textContent, item.querySelector('strong')?.textContent]))
      .toEqual([['PASS', '1'], ['FAIL', '1'], ['ERROR', '1']]);
    expect(card('Latest job').textContent).toContain('J-LATEST · load · payments · 2026-10-15T10:00:00Z');
  });

  it('shows zero outcome counts for an empty result window', () => {
    render(stateWithJobs({ status: 'ready', data: [] }));

    expect(card('Recent jobs').querySelector('.summary-value')?.textContent).toBe('0');
    expect(card('Active jobs in results').querySelector('.summary-value')?.textContent).toBe('0');
    expect([...card('Recent outcomes').querySelectorAll('.summary-outcome-count strong')].map(item => item.textContent))
      .toEqual(['0', '0', '0']);
    expect(card('Latest job').textContent).toContain('No jobs yet');
  });

  it('keeps the last returned rows visible while refresh is pending and exposes the refresh state', () => {
    render(stateWithJobs({ status: 'ready', data: jobs }), { refreshing: true });

    expect(host.textContent).toContain('J-LATEST');
    expect(host.textContent).toContain('Refreshing…');
    expect((host.querySelector('#retry-jobs') as HTMLButtonElement).disabled).toBe(true);
  });

  it('shows unavailable instead of zero when job retrieval fails and provides retry', () => {
    const retry = vi.fn();
    render(stateWithJobs({ status: 'error', error: new Error('Server offline') }), { onRetryJobs: retry });

    expect(host.textContent).toContain('Could not load recent jobs: Server offline');
    expect(card('Recent jobs').querySelector('.summary-value')?.textContent).toBe('—');
    expect(card('Active jobs in results').querySelector('.summary-value')?.textContent).toBe('—');
    expect(card('Latest job').querySelector('.summary-value')?.textContent).toBe('—');
    act(() => host.querySelector('#retry-jobs')?.dispatchEvent(new MouseEvent('click', { bubbles: true })));
    expect(retry).toHaveBeenCalledOnce();
  });

  it('shows a loading state while the first job request is pending', () => {
    render(stateWithJobs({ status: 'loading' }));

    expect(host.textContent).toContain('Loading recent jobs…');
    expect(card('Recent jobs').querySelector('.summary-value')?.textContent).toBe('—');
  });
});
