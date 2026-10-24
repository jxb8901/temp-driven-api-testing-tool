import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../types';
import { readArtifactText } from './artifactTransport';

afterEach(() => vi.restoreAllMocks());

describe('authenticated archive artifact transport', () => {
  it('uses same-origin session credentials and avoids caching artifact text', async () => {
    const reader = {
      read: vi.fn().mockResolvedValueOnce({ done: false, value: new TextEncoder().encode('approved') })
        .mockResolvedValueOnce({ done: true }),
      cancel: vi.fn(async () => {}),
    };
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true, body: { getReader: () => reader },
    } as unknown as Response);
    const signal = new AbortController().signal;

    await expect(readArtifactText('/api/v1/jobs/JOB-1/artifacts/view/report.json', signal)).resolves.toBe('approved');
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/jobs/JOB-1/artifacts/view/report.json', {
      signal, credentials: 'same-origin', cache: 'no-store',
    });
    expect(reader.cancel).toHaveBeenCalledTimes(1);
  });

  it('retains HTTP denial status so standalone viewers can show the right recovery state', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: false, status: 401, headers: { get: (name: string) => name === 'X-Request-Id' ? 'request-1' : null },
    } as unknown as Response);

    await expect(readArtifactText('/api/v1/jobs/JOB-1/artifacts/view/report.html')).rejects.toMatchObject({
      name: 'ApiError', status: 401, requestId: 'request-1',
    } satisfies Partial<ApiError>);
  });
});
