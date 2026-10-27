import { describe, expect, it } from 'vitest';
import { formatRoute, parseRoute } from './router';

describe('ATT hash route contract', () => {
  it('keeps collection aliases useful and typed', () => {
    expect(parseRoute('#/')).toEqual({ kind: 'dashboard' });
    expect(parseRoute('#/packages')).toEqual({ kind: 'packages' });
    expect(parseRoute('#/jobs')).toEqual({ kind: 'jobs' });
    expect(parseRoute('#/advanced-load')).toEqual({ kind: 'load' });
    expect(parseRoute('#/load-schedules')).toEqual({ kind: 'schedules' });
    expect(parseRoute('#/scheduled-load')).toEqual({ kind: 'schedules' });
    expect(parseRoute('#/settings')).toEqual({ kind: 'settings' });
  });

  it('round-trips package resource, Load, and job deep links', () => {
    const resource = { kind: 'package', packageId: 'payments and ledger', resource: { type: 'template', resourceId: 'PAYMENT%2Fv1' } } as const;
    expect(parseRoute(formatRoute(resource))).toEqual(resource);
    const relations = { ...resource, resource: { ...resource.resource, tab: 'relations' as const } };
    expect(parseRoute(formatRoute(relations))).toEqual(relations);
    expect(parseRoute('#/packages/payments/resources/flow/checkout?detail=source')).toEqual({
      kind: 'package', packageId: 'payments', resource: { type: 'flow', resourceId: 'checkout', tab: 'source' },
    });
    expect(parseRoute('#/advanced-load/payments')).toEqual({ kind: 'load', packageId: 'payments' });
    expect(parseRoute('#/jobs/J10045')).toEqual({ kind: 'jobs', jobId: 'J10045' });
    expect(parseRoute('#/jobs/J10045/console?view=events')).toEqual({ kind: 'job-console', jobId: 'J10045', view: 'events' });
    expect(parseRoute('#/jobs/J%2F10045/console?view=live')).toEqual({ kind: 'job-console', jobId: 'J/10045', view: 'live' });
    expect(formatRoute({ kind: 'job-console', jobId: 'J/10045', view: 'events' })).toBe('#/jobs/J%2F10045/console?view=events');
    const artifact = { kind: 'job-artifact', jobId: 'J/10045', path: 'load/run/report/index.html' } as const;
    expect(parseRoute(formatRoute(artifact))).toEqual(artifact);
    expect(formatRoute(artifact)).toBe('#/jobs/J%2F10045/artifact?path=load%2Frun%2Freport%2Findex.html');
    expect(parseRoute(formatRoute({ kind: 'schedules' }))).toEqual({ kind: 'schedules' });
    expect(parseRoute(formatRoute({ kind: 'settings' }))).toEqual({ kind: 'settings' });
    expect(parseRoute(formatRoute({ kind: 'run', packageId: 'payments', environment: 'UAT', batch: true }))).toEqual({
      kind: 'run', packageId: 'payments', environment: 'UAT', batch: true,
    });
  });

  it('round-trips safe resource-source references and rejects malformed reference tokens', () => {
    const route = {
      kind: 'configuration', packageId: 'payments', filePath: 'templates/payment/payload/request.xml',
      fileOrigin: 'templates/payment/flow.yaml', tab: 'source',
      sourceReference: { type: 'flow', resourceId: 'flow.checkout', referenceId: 'a'.repeat(64) },
    } as const;
    expect(parseRoute(formatRoute(route))).toEqual(route);
    expect(parseRoute('#/configuration/payments?tab=source&sourceType=flow&sourceResourceId=flow.checkout&sourceReferenceId=bad'))
      .not.toHaveProperty('sourceReference');
  });

  it('round-trips template, Scenarios and LOAD-filtered Jobs detail links', () => {
    for(const route of [{kind:'load',tab:'scenarios'},{kind:'load',packageId:'p',templateId:'builtin:multi-workload-mix'},{kind:'jobs',executionType:'load'},{kind:'jobs',jobId:'J123',executionType:'load'}] as const) expect(parseRoute(formatRoute(route))).toEqual(route);
    const replay = { kind:'load', packageId:'payments', environment:'UAT', replayDraftId:'C0123456789ABCDEF0123456789ABCDEF', replaySourceJobId:'J0123456789ABCDEF' } as const;
    expect(parseRoute(formatRoute(replay))).toEqual(replay);
    expect(formatRoute(replay)).toContain('environment=UAT');
  });

  it('round-trips LOAD resource deep links and preserves LOAD Catalog return filters', () => {
    const catalog = { kind: 'load', catalog: { query: 'nightly payments', packageFilter: 'payments', kindFilter: 'scenario',
      modelFilter: 'arrivalRate', validationFilter: 'unverified', environment: 'UAT', scrollY: 842 } } as const;
    const catalogHref = formatRoute(catalog);
    expect(parseRoute(catalogHref)).toEqual(catalog);
    const detail = { kind: 'load-resource', packageId: 'payments + ledger', resourceId: 'load/nested/nightly.yaml',
      environment: 'UAT', returnTo: catalogHref } as const;
    expect(parseRoute(formatRoute(detail))).toEqual(detail);
    expect(parseRoute('#/load/resources/payments/load%2Fnested%2Fnightly.yaml?environment=UAT')).toEqual({
      kind: 'load-resource', packageId: 'payments', resourceId: 'load/nested/nightly.yaml', environment: 'UAT',
    });
  });

  it('keeps only a safe return route for validated Schedule handoffs', () => {
    const route = { kind: 'schedules', view: 'new', intent: 'validated', returnTo: '#/load/resources/payments/load%2Fnested%2Fnightly.yaml?environment=UAT' } as const;
    expect(parseRoute(formatRoute(route))).toEqual(route);
    expect(parseRoute('#/schedules/new?intent=validated&returnTo=https%3A%2F%2Fevil.example')).toEqual({
      kind: 'schedules', view: 'new', intent: 'validated',
    });
  });

  it('round-trips opaque Configuration-source reference tokens', () => {
    const route={kind:'configuration',packageId:'payments',filePath:'config/queries/result.json',
      fileOrigin:'config/queries/lookup.sql',configurationReferenceId:'aB_-token',tab:'source'} as const;
    expect(parseRoute(formatRoute(route))).toEqual(route);
    expect(parseRoute('#/configuration/payments?configurationReferenceId=bad%2Ftoken'))
      .not.toHaveProperty('configurationReferenceId');
  });
});
