import { describe, expect, it } from 'vitest';
import { formatRoute, parseRoute } from './router';

describe('ATT hash route contract', () => {
  it('keeps collection aliases useful and typed', () => {
    expect(parseRoute('#/')).toEqual({ kind: 'dashboard' });
    expect(parseRoute('#/packages')).toEqual({ kind: 'packages' });
    expect(parseRoute('#/jobs')).toEqual({ kind: 'jobs' });
    expect(parseRoute('#/advanced-load')).toEqual({ kind: 'load' });
  });

  it('round-trips package resource, Load, and job deep links', () => {
    const resource = { kind: 'package', packageId: 'payments and ledger', resource: { type: 'template', resourceId: 'PAYMENT%2Fv1' } } as const;
    expect(parseRoute(formatRoute(resource))).toEqual(resource);
    expect(parseRoute('#/advanced-load/payments')).toEqual({ kind: 'load', packageId: 'payments' });
    expect(parseRoute('#/jobs/J10045')).toEqual({ kind: 'jobs', jobId: 'J10045' });
  });
});
