import { afterEach, expect, it, vi } from 'vitest';
import { api } from './api';
const json = (value: unknown) => new Response(JSON.stringify(value));
afterEach(() => vi.unstubAllGlobals());
it('negotiates RUN support and preserves legacy resource and Case adapters', async () => {
  let current = false;
  let native = false;
  const calls: { path: string; body: unknown }[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: URL, init: RequestInit) => {
    if (url.pathname.endsWith('/version')) return json({ version: current ? '4.2.1' : '4.2.0',
      unifiedRunVersion: current ? 'att-execution/v1' : undefined,
      unifiedRunVersions: current ? ['att-execution/v1', ...(native ? ['att-execution/v2'] : [])] : [] });
    calls.push({ path: url.pathname, body: init.body ? JSON.parse(String(init.body)) : undefined });
    return json({ draftId: current ? 'R123' : 'D123', jobId: 'J123' });
  }));
  const definition = { schemaVersion: 'att-execution/v1' as const, executionType: 'run' as const, packageId: 'p', environment: 'UAT', target: { type: 'flow', id: 'checkout.v1' }, input: { inputs: { amount: null } }, options: { debug: false } };
  await api.getVersion();
  const legacy = await api.previewRun(definition); await api.submitRun({ packageId: 'p', draftId: legacy.draftId });
  expect(() => api.previewRun({ ...definition, target: { type: 'case', id: 'cases.default.C1' }, input: undefined })).toThrow(/Case and batch Run validation require ATT Server 4\.2\.1/);
  expect(() => api.previewRun({ ...definition, schemaVersion: 'att-execution/v2', target: { type: 'nativeSuite', definitionIds: ['p.definition'], selection: { all: true } }, input: undefined })).toThrow(/requires an ATT Server that advertises att-execution\/v2/);
  expect(() => api.previewRun({ ...definition, target: undefined, input: undefined, selection: { all: true } })).toThrow(/legacy Case action in Explorer/);
  const nativeRun = { schemaVersion: 'att-execution/v2' as const, executionType: 'run' as const, packageId: 'p',
    target: { type: 'nativeCase', definitionId: 'payments.refund', selection: { resolvedCaseIds: ['R-001'] } }, options: { debug: false } };
  expect(() => api.previewRun(nativeRun)).toThrow(/att-execution\/v2/);
  await api.runCase({ packageId: 'p', suite: 'testcase/a.xlsx', caseId: 'a.default.C1', environment: 'UAT' });
  expect(calls.map(call => call.path.split('/').slice(-2).join('/'))).toEqual(['drafts/debug', 'jobs/debug', 'jobs/run']);
  expect(calls[0].body).toEqual({ packageId: 'p', environment: 'UAT', target: definition.target, input: definition.input });
  expect(calls[2].body).toMatchObject({ environment: 'UAT', suites: ['testcase/a.xlsx'], caseIds: ['a.default.C1'] });
  calls.length = 0; current = true; await api.getVersion();
  await api.previewRun(definition);
  const nativeSuite = { schemaVersion: 'att-execution/v2' as const, executionType: 'run' as const, packageId: 'p',
    target: { type: 'nativeSuite', definitionIds: ['p.definition'], selection: { all: true } }, options: { debug: false } };
  expect(() => api.previewRun(nativeSuite)).toThrow(/att-execution\/v2/);
  expect(() => api.previewRun(nativeRun)).toThrow(/att-execution\/v2/);
  await api.runCase({ packageId: 'p', suite: 'testcase/a.xlsx', caseId: 'a.default.C1', environment: 'SIT' });
  expect(calls.map(call => call.path.split('/').slice(-2).join('/'))).toEqual(['drafts/run', 'drafts/run']);
  expect(calls[0].body).toEqual(definition);
  expect(calls[1].body).toMatchObject({ environment: 'SIT', selection: { suites: ['testcase/a.xlsx'], caseIds: ['a.default.C1'] }, options: { debug: false } });
  calls.length = 0; native = true; await api.getVersion();
  expect(api.supportsNativeCaseRun()).toBe(true);
  await api.previewRun(nativeSuite);
  await api.previewRun(nativeRun);
  await api.submitRun({ packageId: 'p', draftId: 'R123' });
  expect(calls.map(call => call.path.split('/').slice(-2).join('/'))).toEqual(['drafts/run', 'drafts/run', 'jobs/run']);
  expect(calls[0].body).toEqual(nativeSuite);
  expect(calls[1].body).toEqual(nativeRun);
});
