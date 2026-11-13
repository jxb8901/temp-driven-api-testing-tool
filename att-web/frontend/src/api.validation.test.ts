// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from './api';
import { ApiError } from './types';
import type { ValidationRequest } from './features/validation/types';

const report = {
  schemaVersion: 'att-validation/v1', scope: 'flow', backend: 'SERVER', sections: [], diagnostics: [],
  counts: { pass: 0, fail: 0, warn: 0, skip: 0, errors: 0, warnings: 0 },
  overallStatus: 'PARTIAL', complete: false, exitCode: 1, requestId: 'request-355',
};

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('api.validateResource', () => {
  it('posts only the versioned read-only validation request through same-origin JSON transport', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify(report), {
      status: 200, headers: { 'Content-Type': 'application/json', 'X-Request-Id': 'request-355' },
    }));
    vi.stubGlobal('fetch', fetchMock);
    const input: ValidationRequest = { targetKind: 'flow', packageId: 'payments', target: 'PAYMENT.submit' };
    const actual = await api.validateResource(input, new AbortController().signal);
    expect(actual).toEqual(report);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [URL, RequestInit];
    expect(String(url)).toMatch(/\/api\/v1\/validation$/);
    expect(init.method).toBe('POST');
    expect(init.credentials).toBe('same-origin');
    expect(new Headers(init.headers).get('Content-Type')).toBe('application/json');
    expect(JSON.parse(String(init.body))).toEqual({ command: 'validate', ...input });
    expect(String(init.body)).not.toContain('job');
  });

  it('normalizes all Server scope enum values and accepts an INVALID HTTP 200 report', async () => {
    const scopes = ['CONFIG', 'CONFIG_PACKAGE', 'PACKAGE', 'CASE', 'SUITE', 'TEMPLATE', 'FLOW', 'TOOL', 'LOAD', 'SELECTED', 'ALL'];
    let scopeIndex = 0;
    const fetchMock = vi.fn(async () => {
      const scope = scopes[scopeIndex++];
      const invalid = scope === 'CONFIG_PACKAGE';
      return new Response(JSON.stringify({
        ...report,
        scope,
        overallStatus: invalid ? 'INVALID' : 'PASS',
        complete: true,
        exitCode: invalid ? 2 : 0,
        counts: { pass: 0, fail: invalid ? 1 : 0, warn: 0, skip: 0, errors: invalid ? 1 : 0, warnings: 0 },
      }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    });
    vi.stubGlobal('fetch', fetchMock);

    const actual = [];
    for (let index = 0; index < scopes.length; index++) actual.push(await api.validateResource({ targetKind: 'config' }));

    expect(actual.map(item => item.scope)).toEqual(scopes.map(scope => scope.toLowerCase()));
    expect(actual[1]).toMatchObject({ scope: 'config_package', overallStatus: 'INVALID', exitCode: 2 });
    expect(fetchMock).toHaveBeenCalledTimes(scopes.length);
  });

  it('rejects a response that does not match att-validation/v1', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ schemaVersion: 'att-validation/v9' }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    })));
    await expect(api.validateResource({ targetKind: 'config' })).rejects.toMatchObject({
      name: 'ApiError', status: 0, message: expect.stringContaining('unsupported or incomplete'),
    } satisfies Partial<ApiError>);
  });

  it('rejects malformed sections and diagnostics before they reach the report renderer', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ ...report, diagnostics: [{}] }), {
      status: 200, headers: { 'Content-Type': 'application/json' },
    })));
    await expect(api.validateResource({ targetKind: 'config' })).rejects.toMatchObject({
      name: 'ApiError', status: 0, message: expect.stringContaining('unsupported or incomplete'),
    });
  });
});
