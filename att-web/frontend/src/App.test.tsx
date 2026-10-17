// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { api } from './api';
import type { JobSummary } from './types';

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

describe('App Dashboard job refresh', () => {
  let host: HTMLDivElement;
  let root: Root;

  beforeEach(() => {
    localStorage.setItem('att-ui-language', 'en');
    localStorage.setItem('att-ui-theme', 'light');
    window.history.replaceState(null, '', window.location.pathname + window.location.search);
    host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
    vi.spyOn(api, 'getVersion').mockResolvedValue({ apiVersion: '1', inlineLoadEnabled: false });
    vi.spyOn(api, 'getPackages').mockResolvedValue({ items: [] });
    vi.spyOn(api, 'getAuthCapabilities').mockResolvedValue({ configurationAdmin: false, capabilities: { operate: true, accountManage: true } });
    vi.spyOn(api, 'listJobs').mockResolvedValue([]);
  });

  afterEach(() => {
    act(() => root.unmount());
    host.remove();
    vi.useRealTimers();
    vi.restoreAllMocks();
    window.history.replaceState(null, '', window.location.pathname + window.location.search);
  });

  async function settle() {
    await act(async () => {
      for (let i = 0; i < 6; i++) await Promise.resolve();
    });
  }

  function clickNavigation(name: 'dashboard' | 'jobs') {
    const link = host.querySelector(`[data-nav="${name}"]`);
    if (!link) throw new Error(`Missing ${name} navigation link`);
    act(() => link.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 })));
  }

  it('refreshes after returning to Dashboard and shows newly completed Debug and Load rows', async () => {
    const updatedRows: JobSummary[] = [
      { jobId: 'J-LOAD', command: 'load', packageId: 'payments', status: 'ERROR', createdAt: '2026-10-15T10:00:00Z' },
      { jobId: 'J-DEBUG', command: 'run', packageId: 'payments', status: 'FAIL', createdAt: '2026-10-15T09:00:00Z' },
    ];
    const getJobs = vi.spyOn(api, 'getJobs')
      .mockResolvedValueOnce({ items: [] })
      .mockResolvedValueOnce({ items: updatedRows });

    await act(async () => root.render(<App />));
    await settle();
    expect(host.textContent).toContain('No jobs yet');
    expect(getJobs).toHaveBeenCalledTimes(1);

    clickNavigation('jobs');
    await settle();
    clickNavigation('dashboard');
    await settle();

    expect(getJobs).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain('J-LOAD');
    expect(host.textContent).toContain('J-DEBUG');
    const cards = [...host.querySelectorAll<HTMLElement>('.summary-card')];
    const card = (label: string) => cards.find(item => item.querySelector('.summary-label')?.textContent === label);
    expect(card('Recent jobs')?.querySelector('.summary-value')?.textContent).toBe('2');
    const outcomes = [...(card('Recent outcomes')?.querySelectorAll<HTMLElement>('.summary-outcome-count') || [])];
    expect(outcomes.map(item => [item.querySelector('span')?.textContent, item.querySelector('strong')?.textContent]))
      .toEqual([['PASS', '0'], ['FAIL', '1'], ['ERROR', '1']]);
    expect(host.textContent).not.toContain('No jobs yet');
  });

  it('polls while a job is active and updates Dashboard when it reaches a terminal status', async () => {
    const running: JobSummary = {
      jobId: 'J-LOAD', command: 'load', packageId: 'payments', status: 'RUNNING', createdAt: '2026-10-15T10:00:00Z',
    };
    const passed: JobSummary = { ...running, status: 'PASS', finishedAt: '2026-10-15T10:00:05Z' };
    const getJobs = vi.spyOn(api, 'getJobs')
      .mockResolvedValueOnce({ items: [running] })
      .mockResolvedValueOnce({ items: [passed] });
    vi.useFakeTimers();

    await act(async () => root.render(<App />));
    await settle();
    expect(host.querySelector('[aria-label="Dashboard summary"]')?.textContent).toContain('RUNNING');
    expect(getJobs).toHaveBeenCalledTimes(1);

    await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
    await settle();

    expect(getJobs).toHaveBeenCalledTimes(2);
    expect(host.querySelector('[aria-label="Dashboard summary"]')?.textContent).toContain('PASS');
    expect(host.querySelector('[aria-label="Dashboard summary"]')?.textContent).toContain('1');
  });
});
