import { describe, expect, it } from 'vitest';
import { resolveExecutionRevisionIdentity, resolvePackageSourceRevisionIdentity } from './sourceManagement';

const packageSource = {
  schemaVersion: 'att-source-revision/v1', kind: 'PACKAGE_SOURCE', scopeId: 'payments', qualifier: null, revision: 'package-1',
};
const legacyBusinessSource = { ...packageSource, kind: 'BUSINESS_SOURCE' };
const executionRevision = {
  schemaVersion: 'att-source-revision/v1', kind: 'EXECUTION_REVISION', scopeId: 'payments@SIT', qualifier: null, revision: 'execution-1',
};

describe('source revision identities', () => {
  it('resolves the typed Package source identity and keeps execution identity separate', () => {
    const revisions = { packageSourceRevision: packageSource, executionRevisionDigest: executionRevision };
    expect(resolvePackageSourceRevisionIdentity(revisions)).toEqual(packageSource);
    expect(resolveExecutionRevisionIdentity(revisions)).toEqual(executionRevision);
  });

  it('accepts the legacy businessSource alias as Package source without treating it as execution', () => {
    const revisions = { businessSource: legacyBusinessSource };
    expect(resolvePackageSourceRevisionIdentity(revisions)).toEqual(legacyBusinessSource);
    expect(resolveExecutionRevisionIdentity(revisions)).toBeNull();
  });

  it('fails closed for mismatched aliases and never uses execution revision as Package source', () => {
    expect(resolvePackageSourceRevisionIdentity({
      packageSourceRevision: packageSource,
      businessSource: { ...legacyBusinessSource, revision: 'different-package' },
    })).toBeNull();
    expect(resolvePackageSourceRevisionIdentity({ packageSourceRevision: executionRevision })).toBeNull();
    expect(resolvePackageSourceRevisionIdentity({ executionRevisionDigest: executionRevision })).toBeNull();
  });
});
