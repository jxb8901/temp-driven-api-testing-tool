import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from './api';

const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), {
  status,
  headers: { 'Content-Type': 'application/json' },
});

describe('job action API contracts', () => {
  let fetcher: ReturnType<typeof vi.fn>;
  let base: HTMLBaseElement;

  beforeEach(() => {
    base = document.createElement('base');
    base.href = 'http://test.local/att/ui/';
    document.head.append(base);
    fetcher = vi.fn(async (url: URL, _init: RequestInit = {}) => {
      if (url.pathname.endsWith('/jobs/pin') || url.pathname.endsWith('/jobs/delete')) return json({ items: [] });
      return json({ jobId: 'JOB-1', outcome: 'DELETED' });
    });
    vi.stubGlobal('fetch', fetcher);
  });

  afterEach(() => {
    base.remove();
    vi.unstubAllGlobals();
  });

  it('uses the exact POST endpoints and request bodies for delete and pin actions', async () => {
    await api.deleteJob('JOB/1');
    await api.deleteJobs(['JOB-1', 'JOB-2']);
    await api.pinJob('JOB/1', true, 'release evidence');
    await api.pinJobs(['JOB-1', 'JOB-2'], false);

    expect(fetcher.mock.calls.map(([url, init]) => ({
      url: String(url),
      method: (init as RequestInit).method,
      body: JSON.parse(String((init as RequestInit).body)),
    }))).toEqual([
      { url: 'http://test.local/att/api/v1/jobs/JOB%2F1/delete', method: 'POST', body: { confirm: true } },
      { url: 'http://test.local/att/api/v1/jobs/delete', method: 'POST', body: { confirm: true, jobIds: ['JOB-1', 'JOB-2'] } },
      { url: 'http://test.local/att/api/v1/jobs/JOB%2F1/pin', method: 'POST', body: { pinned: true, pinReason: 'release evidence' } },
      { url: 'http://test.local/att/api/v1/jobs/pin', method: 'POST', body: { pinned: false, jobIds: ['JOB-1', 'JOB-2'] } },
    ]);
  });

  it('resolves deletion outcome maps for 202 recovery and 409 refusal responses', async () => {
    fetcher
      .mockImplementationOnce(async () => json({ jobId: 'JOB-RECOVER', outcome: 'DELETION_PENDING_RECOVERY' }, 202))
      .mockImplementationOnce(async () => json({ jobId: 'JOB-PINNED', outcome: 'PINNED_UNPIN_FIRST' }, 409));

    await expect(api.deleteJob('JOB-RECOVER')).resolves.toEqual({ jobId: 'JOB-RECOVER', outcome: 'DELETION_PENDING_RECOVERY' });
    await expect(api.deleteJob('JOB-PINNED')).resolves.toEqual({ jobId: 'JOB-PINNED', outcome: 'PINNED_UNPIN_FIRST' });
  });

  it('keeps normal pin ineligibility error envelopes as ApiError responses', async () => {
    fetcher.mockImplementationOnce(async () => json({
      error: { code: 'ATT-SERVER-JOB-NOT-ELIGIBLE', summary: 'Only terminal Jobs can be pinned.' },
    }, 409));

    await expect(api.pinJob('JOB-RUNNING', true)).rejects.toMatchObject({
      status: 409,
      code: 'ATT-SERVER-JOB-NOT-ELIGIBLE',
      message: 'Only terminal Jobs can be pinned.',
    });
  });
});
