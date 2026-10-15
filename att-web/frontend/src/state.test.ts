import { describe, expect, it } from 'vitest';
import { appReducer, initialAppState, latestServerJobWindow } from './state';
import type { JobSummary } from './types';

const existingJob: JobSummary = {
  jobId: 'J100000000000001', command: 'load', packageId: 'payments', status: 'PASS',
  createdAt: '2026-10-15T05:00:00Z',
};

describe('dashboard job refresh state', () => {
  it('uses only the first 100 rows in the Server-provided newest-first order', () => {
    const rows = Array.from({ length: 101 }, (_, index): JobSummary => ({
      jobId: `J${String(index).padStart(15, '0')}`, command: 'load', packageId: 'payments',
      status: index === 100 ? 'ERROR' : 'PASS', createdAt: new Date(2026, 0, 1, 0, index).toISOString(),
    }));

    const selected = latestServerJobWindow(rows);

    expect(selected).toHaveLength(100);
    expect(selected[0]).toBe(rows[0]);
    expect(selected[99]).toBe(rows[99]);
    expect(selected).not.toContain(rows[100]);
  });

  it('keeps the last successful rows visible while a refresh is pending', () => {
    const ready = appReducer(initialAppState, { type: 'jobs/ready', data: [existingJob] });
    const refreshing = appReducer(ready, { type: 'jobs/loading' });

    expect(refreshing).toBe(ready);
    expect(refreshing.jobs).toEqual({ status: 'ready', data: [existingJob] });
  });

  it('replaces the rows when the refresh completes', () => {
    const ready = appReducer(initialAppState, { type: 'jobs/ready', data: [existingJob] });
    const updated: JobSummary = { ...existingJob, jobId: 'J100000000000002', status: 'RUNNING' };

    expect(appReducer(ready, { type: 'jobs/ready', data: [updated] }).jobs)
      .toEqual({ status: 'ready', data: [updated] });
  });
});
