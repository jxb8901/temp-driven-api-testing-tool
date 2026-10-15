import { describe, expect, it } from 'vitest';
import { appReducer, initialAppState } from './state';
import type { JobSummary } from './types';

const existingJob: JobSummary = {
  jobId: 'J100000000000001', command: 'load', packageId: 'payments', status: 'PASS',
  createdAt: '2026-10-15T05:00:00Z',
};

describe('dashboard job refresh state', () => {
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
