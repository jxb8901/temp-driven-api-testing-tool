import type { ResourceIdentity } from './types';

export type SourceDomain = 'PACKAGE_SOURCE' | 'CENTRAL_RUNTIME_CONFIG';
export type PackageAuthoringSourceKind = 'manifest' | 'resource' | 'file' | 'load' | 'testdata';
export type SourceOperationKind = 'create' | 'replace' | 'delete' | 'move';
export type SourceChangeAction = 'validate' | 'review' | 'publish' | 'discard';

export interface PackageAuthoringSourceQuery {
  kind: PackageAuthoringSourceKind;
  resourceType?: ResourceIdentity['type'];
  resourceId?: string;
  path?: string;
  origin?: string;
  referenceId?: string;
  environment?: string;
}

export interface ManagedAuthoringSource {
  available: boolean;
  present: boolean;
  logicalPath: string;
  format: string;
  text: string;
  sourceEtag: string;
  revision: string;
  baseSourceRevision: string;
  registryRevision?: string;
  configRevision?: string;
  schemaRevision?: string;
  revisions?: SourceRevisionSet;
}

export interface SourceOperation {
  operation: SourceOperationKind;
  path: string;
  targetPath?: string;
  content?: string;
  contentBase64?: string;
}

export interface ChangedSourceFile {
  operation: SourceOperationKind | string;
  path: string;
  targetPath?: string;
  oldHash?: string | null;
  newHash?: string | null;
  oldBytes?: number;
  newBytes?: number;
  automatic?: boolean;
  inboundReferences?: Array<{
    schemaVersion?: string;
    kind?: string;
    packageId?: string | null;
    environment?: string | null;
    logicalId?: string | null;
    canonicalPath?: string | null;
    sourceFingerprint?: string | null;
    relationship?: string | null;
    callSite?: string | null;
    certainty?: 'KNOWN' | 'UNKNOWN' | string;
    /** Legacy projection accepted while older servers are in service. */
    descriptorPath?: string;
    referenceCount?: number;
  }>;
  contentDiff?: string[];
  contentSummary?: string;
}

export interface SourceChangeSet {
  changeSetId: string;
  domain: SourceDomain;
  scopeId: string;
  state: 'draft' | 'validated' | 'reviewed' | 'published' | string;
  revisions?: SourceRevisionSet;
  baseSourceRevision: string;
  registryRevision?: string;
  manifestRevision?: string;
  configRevision?: string;
  schemaRevision?: string;
  validationRevision?: string;
  expiresAt?: string;
  changedFiles: ChangedSourceFile[];
  snapshotGenerated?: boolean;
  snapshotPath?: string;
}

export interface SourcePublishResult {
  published: boolean;
  domain: SourceDomain;
  scopeId: string;
  changeSetId: string;
  beforeRevision: string;
  revision: string;
  validationRevision?: string;
  auditConfirmed?: boolean;
  configGateRevision?: string;
  publishedRevision?: string;
  activeRevision?: string;
  activationState?: 'ACTIVE' | 'PENDING' | 'FAILED' | 'UNAVAILABLE' | string;
  activationFailureCode?: string | null;
  activationFailureMessage?: string | null;
}

export interface SourceRevisionIdentity {
  schemaVersion?: 'att-source-revision/v1' | string;
  kind: string;
  scopeId: string;
  qualifier?: string | null;
  revision: string;
}

export interface SourceRevisionSet {
  schemaVersion?: 'att-source-revision-set/v1' | string;
  packageRegistration?: SourceRevisionIdentity;
  packageSourceRevision?: SourceRevisionIdentity;
  /** Compatibility alias for packageSourceRevision. */
  businessSource?: SourceRevisionIdentity;
  /** Execution-bound identity; it is not a Package source edit revision. */
  executionRevisionDigest?: SourceRevisionIdentity;
  changeSet?: SourceRevisionIdentity;
  manifest?: SourceRevisionIdentity;
  configSource?: SourceRevisionIdentity;
  effectiveConfig?: SourceRevisionIdentity;
  environment?: SourceRevisionIdentity;
  engineSchema?: SourceRevisionIdentity;
  scheduleSnapshot?: SourceRevisionIdentity;
}

function hasRevisionKind(value: unknown, kind: string): value is SourceRevisionIdentity {
  if (typeof value !== 'object' || value === null) return false;
  const identity = value as Partial<SourceRevisionIdentity>;
  return identity.kind === kind
    && (identity.schemaVersion === undefined || identity.schemaVersion === 'att-source-revision/v1')
    && typeof identity.scopeId === 'string' && identity.scopeId.length > 0
    && typeof identity.revision === 'string' && identity.revision.length > 0
    && (identity.qualifier === undefined || identity.qualifier === null || typeof identity.qualifier === 'string');
}

export function isPackageSourceRevisionIdentity(value: unknown): value is SourceRevisionIdentity {
  return hasRevisionKind(value, 'PACKAGE_SOURCE') || hasRevisionKind(value, 'BUSINESS_SOURCE');
}

export function resolvePackageSourceRevisionIdentity(revisions?: SourceRevisionSet | null): SourceRevisionIdentity | null {
  const canonical = revisions?.packageSourceRevision;
  const legacy = revisions?.businessSource;
  if (canonical != null && !hasRevisionKind(canonical, 'PACKAGE_SOURCE')) return null;
  if (legacy != null && !hasRevisionKind(legacy, 'BUSINESS_SOURCE')) return null;
  if (canonical && legacy && (canonical.scopeId !== legacy.scopeId
    || canonical.qualifier !== legacy.qualifier || canonical.revision !== legacy.revision)) return null;
  return canonical || legacy || null;
}

export function resolveExecutionRevisionIdentity(revisions?: SourceRevisionSet | null): SourceRevisionIdentity | null {
  const execution = revisions?.executionRevisionDigest;
  return execution == null ? null : hasRevisionKind(execution, 'EXECUTION_REVISION') ? execution : null;
}

export function isSourceRevisionIdentity(value: unknown, kind: string): value is SourceRevisionIdentity {
  return hasRevisionKind(value, kind);
}

export interface SourceValidationDiagnostic {
  code?: string;
  severity?: 'ERROR' | 'WARNING' | 'INFO' | string;
  logicalPath?: string | null;
  field?: string | null;
  line?: number | null;
  message?: string | null;
  remedy?: string | null;
}

export interface SourceAffectedPackage {
  packageId: string;
  environment: string;
  certainty: 'CONFIRMED_AFFECTED' | 'POTENTIALLY_AFFECTED' | 'UNAFFECTED' | string;
  safeReason?: string | null;
  changedLogicalIds?: string[];
}

export interface SourceAffectedSchedule {
  scheduleId: string;
  packageId?: string | null;
  environment?: string | null;
  action: 'UNCHANGED' | 'REVALIDATE' | 'PAUSE_AND_REAPPROVE' | string;
  safeReason?: string | null;
}

/** ADMIN acknowledgement is bound to this central Config publish, never a separate request. */
export interface SourceImpactAcknowledgement {
  freshnessFingerprint: string;
  actor: string;
  acknowledgementDigest: string;
}

export interface SourceImpactReport {
  schemaVersion?: 'att-config-impact-report/v1' | string;
  providerState: 'AVAILABLE' | 'UNAVAILABLE' | 'STALE' | string;
  freshness?: {
    schemaVersion?: string;
    draftId: string;
    draftFingerprint: string;
    baseConfig: SourceRevisionIdentity;
    candidateConfig: SourceRevisionIdentity;
    packageRegistry: SourceRevisionIdentity;
    packageSources: SourceRevisionIdentity[];
    manifests: SourceRevisionIdentity[];
    engineSchema: SourceRevisionIdentity;
    scheduleSnapshots: SourceRevisionIdentity[];
  } | null;
  coverage?: 'COMPLETE' | 'PARTIAL' | string | null;
  decision?: 'COMPATIBLE' | 'REQUIRES_REVIEW' | 'BLOCKED' | string | null;
  affectedPackages?: SourceAffectedPackage[];
  schedules?: { schemaVersion?: string; items: SourceAffectedSchedule[]; nextCursor?: string | null; totalEstimate?: number; truncated?: boolean };
  unavailableCode?: string | null;
  acknowledgementRequired?: boolean;
  activationStatus?: {
    state?: 'ACTIVE' | 'PENDING' | 'FAILED' | 'UNAVAILABLE' | string;
    publishedRevision?: string | null;
    activeRevision?: string | null;
    failureCode?: string | null;
    failureMessage?: string | null;
  } | null;
}

export interface SourceSnapshotRequest {
  expectedSourceRevision: string;
  workbookPath: string;
  expectedWorkbookHash: string;
  expectedSidecarHash: string;
}
