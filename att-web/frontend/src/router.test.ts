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
    expect(parseRoute('#/advanced-load/payments')).toEqual({ kind: 'load', packageId: 'payments' });
    expect(parseRoute('#/jobs/J10045')).toEqual({ kind: 'jobs', jobId: 'J10045' });
    expect(parseRoute(formatRoute({ kind: 'schedules' }))).toEqual({ kind: 'schedules' });
    expect(parseRoute(formatRoute({ kind: 'settings' }))).toEqual({ kind: 'settings' });
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
});
