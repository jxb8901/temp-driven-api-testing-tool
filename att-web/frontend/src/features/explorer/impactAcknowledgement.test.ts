import { webcrypto } from 'node:crypto';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { canonicalImpactFreshness, createImpactAcknowledgement } from './impactAcknowledgement';
import type { SourceImpactReport } from './sourceManagement';

const freshness: NonNullable<SourceImpactReport['freshness']> = {
  schemaVersion: 'att-config-impact-freshness/v1', draftId: 'cs-1', draftFingerprint: 'a'.repeat(64),
  baseConfig: { kind: 'CONFIG_SOURCE', scopeId: 'central-runtime-config', revision: 'config-1' },
  candidateConfig: { kind: 'CONFIG_SOURCE', scopeId: 'central-runtime-config', revision: 'config-candidate-1' },
  packageRegistry: { kind: 'PACKAGE_REGISTRATION', scopeId: 'packages', revision: 'registry-1' },
  packageSources: [], manifests: [], engineSchema: { kind: 'ENGINE_SCHEMA', scopeId: 'runtime', revision: 'schema-1' }, scheduleSnapshots: [],
};
beforeAll(() => vi.stubGlobal('crypto', webcrypto));
describe('Config impact acknowledgement (#335 contract)', () => {
  it('matches Server SHA-256 freshness and actor-bound acknowledgement protocol', async () => {
    expect(canonicalImpactFreshness(freshness)).toContain('CONFIG_SOURCE\tcentral-runtime-config\tnull\tconfig-1');
    expect(await createImpactAcknowledgement(freshness, 'admin')).toEqual({
      freshnessFingerprint: '89d049ca4392286a7559208799b1555d114a06593c6da36f0200f3dd3b04966b',
      actor: 'admin',
      acknowledgementDigest: '61900e9b0cf1d85fa2b890d5d9e2e79fbf13a3979ae6476db3279009dbcd1fdf',
    });
  });
  it('sorts Package identities canonically and rejects a missing actor', async () => {
    const withPackages = { ...freshness, packageSources: [
      { kind: 'PACKAGE_SOURCE', scopeId: 'z', revision: '2' },
      { kind: 'PACKAGE_SOURCE', scopeId: 'a', revision: '1' },
    ] };
    expect(canonicalImpactFreshness(withPackages).indexOf('PACKAGE_SOURCE\ta'))
      .toBeLessThan(canonicalImpactFreshness(withPackages).indexOf('PACKAGE_SOURCE\tz'));
    await expect(createImpactAcknowledgement(freshness, '  ')).rejects.toThrow('administrator ID');
  });
});
