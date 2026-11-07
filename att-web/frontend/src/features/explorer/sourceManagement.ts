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
  inboundReferences?: Array<{ descriptorPath?: string; referenceCount?: number }>;
  contentDiff?: string[];
  contentSummary?: string;
}

export interface SourceChangeSet {
  changeSetId: string;
  domain: SourceDomain;
  scopeId: string;
  state: 'draft' | 'validated' | 'reviewed' | 'published' | string;
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
  businessSource?: SourceRevisionIdentity;
  changeSet?: SourceRevisionIdentity;
  manifest?: SourceRevisionIdentity;
  configSource?: SourceRevisionIdentity;
  effectiveConfig?: SourceRevisionIdentity;
  environment?: SourceRevisionIdentity;
  engineSchema?: SourceRevisionIdentity;
  scheduleSnapshot?: SourceRevisionIdentity;
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
