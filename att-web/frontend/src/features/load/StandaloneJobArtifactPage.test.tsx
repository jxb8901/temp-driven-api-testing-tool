// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../types';
import { parseRoute } from '../../router';
import { StandaloneJobArtifactPage, isSafeArtifactRoutePath } from './StandaloneJobArtifactPage';
import type { JobArtifactArchive, LoadJobsApi } from './types';

const htmlReport = '<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'sha256-reviewed\'; style-src \'unsafe-inline\'"></head><body><h1>Approved report</h1><a href="../case.log">Case log</a></body></html>';
const readyArchive: JobArtifactArchive = {
  archiveStatus: 'READY', items: [{ path: 'case.log', size: 17 }],
  primaryReports: [{ path: 'run/report/index.html', report: true, size: 200 }],
};

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

function makeApi(overrides: Partial<LoadJobsApi> = {}): LoadJobsApi {
  return {
    getJob: vi.fn(async () => ({ jobId: 'JOB-1', command: 'run', packageId: 'demo', status: 'PASS' as const })),
    getJobArtifactArchive: vi.fn(async () => readyArchive),
    getJobArtifactText: vi.fn(async (_jobId: string, path: string) => path.endsWith('.html') ? htmlReport : 'safe log text'),
    getJobArtifacts: vi.fn(async () => []),
    getJobResult: vi.fn(),
    cancelJob: vi.fn(),
    subscribeJob: vi.fn(() => () => {}),
    artifactHref: vi.fn((_jobId: string, path: string) => '/api/artifacts/' + encodeURIComponent(path)),
    ...overrides,
  } as unknown as LoadJobsApi;
}

let host: HTMLDivElement;
let root: Root;

beforeEach(() => {
  window.history.replaceState(null, '', window.location.pathname + window.location.search);
  host = document.createElement('div');
  document.body.append(host);
  root = createRoot(host);
});

afterEach(async () => {
  await act(async () => root.unmount());
  host.remove();
});

async function settle() {
  await act(async () => { for (let i = 0; i < 8; i += 1) await Promise.resolve(); });
}

describe('standalone approved Job artifact viewer', () => {
  it('accepts only safe relative route paths before calling the API', async () => {
    expect(isSafeArtifactRoutePath('load/run/report/index.html')).toBe(true);
    for (const path of ['', '../secret', '/absolute.log', 'a//b.log', 'a\\b.log', 'a/./b.log', 'bad\u0000.log', 'percent%name.log', 'CON.txt', 'trailing.'])
      expect(isSafeArtifactRoutePath(path)).toBe(false);

    const api = makeApi();
    await act(async () => root.render(<StandaloneJobArtifactPage api={api} jobId="JOB-1" path="../secret" />));
    await settle();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('path is invalid');
    expect(api.getJob).not.toHaveBeenCalled();
    expect(api.getJobArtifactText).not.toHaveBeenCalled();
  });

  it('checks Job access and archive readiness before a read-only text request', async () => {
    const api = makeApi();
    await act(async () => root.render(<StandaloneJobArtifactPage api={api} jobId="JOB-1" path="case.log" />));
    await settle();

    expect(api.getJob).toHaveBeenCalledWith('JOB-1', expect.any(AbortSignal));
    expect(api.getJobArtifactArchive).toHaveBeenCalledWith('JOB-1', 0, expect.any(AbortSignal));
    expect(api.getJobArtifactText).toHaveBeenCalledWith('JOB-1', 'case.log', expect.any(AbortSignal));
    expect(host.textContent).toContain('Read-only preview');
    expect(host.querySelector('[aria-label="case.log; line numbers shown, read only"]')).not.toBeNull();
  });

  it('keeps sanitized HTML in the opaque-origin sandbox and routes approved relative links through the hash route', async () => {
    const api = makeApi();
    await act(async () => root.render(<StandaloneJobArtifactPage api={api} jobId="JOB-1" path="run/report/index.html" />));
    await settle();
    const frame = host.querySelector('iframe')!;
    expect(frame).not.toBeNull();
    expect(frame.getAttribute('sandbox')).toBe('allow-scripts');
    expect(frame.hasAttribute('allow-same-origin')).toBe(false);
    expect(frame.hasAttribute('src')).toBe(false);
    expect(frame.getAttribute('srcdoc')).toContain('Approved report');
    expect(api.getJobArtifactText).toHaveBeenCalledWith('JOB-1', 'run/report/index.html', expect.any(AbortSignal));

    await act(async () => {
      window.dispatchEvent(new MessageEvent('message', {
        source: frame.contentWindow, data: { type: 'att-report-navigation', href: '../case.log' },
      }));
    });
    expect(parseRoute(window.location.hash)).toEqual({ kind: 'job-artifact', jobId: 'JOB-1', path: 'run/case.log' });
  });

  it('shows deleted, unauthorized, and missing-artifact states without falling back to raw job output', async () => {
    const deleted = makeApi({ getJob: vi.fn(async () => { throw new ApiError('not found', { status: 404 }); }) });
    await act(async () => root.render(<StandaloneJobArtifactPage api={deleted} jobId="DELETED" path="case.log" />));
    await settle();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('Job is unavailable or was deleted');
    expect(deleted.getJobArtifactText).not.toHaveBeenCalled();

    await act(async () => root.render(<StandaloneJobArtifactPage api={makeApi({ getJob: vi.fn(async () => { throw new ApiError('denied', { status: 403 }); }) })} jobId="JOB-1" path="case.log" />));
    await settle();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('not authorized');
    expect(host.querySelector('a[href*="login.html"]')).toBeNull();

    const expiredSession = makeApi({ getJob: vi.fn(async () => { throw new ApiError('expired', { status: 401 }); }) });
    await act(async () => root.render(<StandaloneJobArtifactPage api={expiredSession} jobId="JOB-1" path="case.log" />));
    await settle();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('session has expired');
    expect(host.querySelector('a[href*="login.html"]')?.textContent).toBe('Sign in again');

    const missing = makeApi({ getJobArtifactText: vi.fn(async () => { throw new ApiError('not found', { status: 404 }); }) });
    await act(async () => root.render(<StandaloneJobArtifactPage api={missing} jobId="JOB-1" path="not-listed.log" />));
    await settle();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('not approved for preview');
  });

  it('reports expired archives without attempting to read the selected path', async () => {
    const api = makeApi({ getJobArtifactArchive: vi.fn(async () => ({ archiveStatus: 'EXPIRED' as const, items: [], primaryReports: [] })) });
    await act(async () => root.render(<StandaloneJobArtifactPage api={api} jobId="JOB-1" path="case.log" />));
    await settle();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain('archive has expired');
    expect(api.getJobArtifactText).not.toHaveBeenCalled();
  });
});
