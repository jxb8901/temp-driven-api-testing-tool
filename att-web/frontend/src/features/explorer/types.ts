export type ResourceType = 'case' | 'template' | 'flow' | 'tool';
export type DebugResourceType = Exclude<ResourceType, 'case'>;
export type JsonPrimitive = string | number | boolean | null;
export type JsonValue = JsonPrimitive | JsonValue[] | { [key: string]: JsonValue };
export type JsonObject = Record<string, JsonValue>;

export interface ResourceIdentity {
  packageId: string;
  type: ResourceType;
  resourceId: string;
}

export interface ResourceSummary {
  type: ResourceType;
  resourceId: string;
  logicalId?: string;
  name?: string;
  description?: string;
  state?: string;
  sourceAvailable?: boolean;
  sourceReason?: string | null;
  redactionEnabled?: boolean;
  sourcePath?: string;
  diagnostics?: Diagnostic[];
  provenance?: { suite?: string; [key: string]: unknown };
  tags?: string[];
  references?: ResourceReference[];
  referencedBy?: ResourceReference[];
}

export interface ResourceReference {
  type?: ResourceType;
  resourceId?: string;
  logicalId?: string;
  name?: string;
  resolution?: string;
  targetKind?: string;
  callSite?: Record<string, unknown>;
}

export interface Diagnostic {
  code?: string;
  summary?: string;
  resourceId?: string;
  field?: string;
}

export interface ResourcePage {
  items: ResourceSummary[];
  total: number;
  nextCursor?: string | null;
  diagnostics?: Diagnostic[];
}

export interface ResourceDetail {
  resource: ResourceSummary;
  definition?: JsonValue;
  diagnostics?: Diagnostic[];
}

export interface SourceProjection {
  available: boolean;
  text?: string;
  format?: string;
  logicalPath?: string;
  reason?: string;
  fileReferences?: SafeSourceFileReference[];
  fileReferencesTruncated?: boolean;
}

export interface SafeSourceFileReference {
  referenceId: string;
  logicalPath: string;
  available?: boolean;
}

export interface ResourceSourceReference {
  type: ResourceIdentity['type'];
  resourceId: string;
  referenceId: string;
}

export interface EnvironmentProfile {
  name: string;
  state?: string;
  default?: boolean;
}

export interface ConfigurationField {
  state: 'visible' | 'hidden' | 'missing' | 'unavailable' | string;
  value?: JsonValue;
  origin?: string;
}

export interface ConfigurationComparisonField {
  path: string;
  left: ConfigurationField;
  right: ConfigurationField;
  change: string;
}

export interface ConfigurationEntry {
  id: string;
  source?: string;
  generated?: boolean;
  fields?: Record<string, ConfigurationField>;
  entries?: ConfigurationEntry[];
  references?: ResourceReference[];
}

export interface ConfigurationSection {
  id: string;
  title?: string;
  entryCount?: number;
  root?: { entryCount?: number };
  profiles?: Array<{ environment: string; state: string }>;
  entries?: ConfigurationEntry[];
}


export interface ConfigurationFileNode {
  name: string;
  path: string;
  kind: 'file' | 'directory';
  sizeBytes?: number;
  format?: string;
  sourceAvailable?: boolean;
  reason?: string;
  references?: ResourceReference[];
  diagnostics?: Diagnostic[];
}

export interface ConfigurationFilePage {
  path?: string;
  items: ConfigurationFileNode[];
  total?: number;
  truncated?: boolean;
  nextOffset?: number | null;
  nextCursor?: string | null;
}

export interface TestdataRecord {
  index: number;
  sequence?: number;
  value: JsonValue;
  redacted?: boolean;
}

export interface TestdataRecordsResponse {
  descriptorId: string;
  source?: string;
  environment?: string;
  state?: string;
  generated?: boolean;
  previewRequired?: boolean;
  previewGenerated?: boolean;
  recordCount?: number;
  generation?: JsonValue;
  offset: number;
  limit: number;
  total: number;
  nextOffset?: number | null;
  records: TestdataRecord[];
  diagnostics?: Diagnostic[];
  redactionEnabled?: boolean;
}

export interface ConfigurationResponse {
  state?: string;
  view?: 'declared' | 'effective' | 'compare' | string;
  environment?: string;
  leftEnvironment?: string;
  rightEnvironment?: string;
  environments?: EnvironmentProfile[];
  sections?: ConfigurationSection[];
  globals?: Record<string, ConfigurationField>;
  section?: string;
  fields?: ConfigurationComparisonField[];
  diagnostics?: Diagnostic[];
  nextOffset?: number | null;
  total?: number;
  redactionEnabled?: boolean;
}

export interface DebugTarget {
  type: DebugResourceType;
  id: string;
}

export interface DebugFormResponse {
  target: DebugTarget;
  input: JsonObject;
  redacted?: boolean;
}

export interface DebugDraftRequest {
  packageId: string;
  target: DebugTarget;
  input: JsonObject;
  environment?: string;
}

export interface DraftResponse {
  draftId: string;
  preview?: JsonValue;
  previewYaml?: string;
  redacted?: boolean;
  expiresAt?: string;
  diagnostics?: Diagnostic[];
}

export interface AcceptedJob {
  jobId: string;
}

export interface ResourceQuery {
  query?: string;
  type?: ResourceType | 'all';
  cursor?: string;
  limit?: number;
  sort?: ResourceSortField;
  direction?: 'asc' | 'desc';
}

export type ResourceSortField = 'name' | 'type' | 'logicalId' | 'state';

export interface EffectiveConfigurationQuery {
  environment?: string;
  section?: string;
  offset?: number;
  limit?: number;
}

export interface ConfigurationComparisonQuery {
  left: string;
  right: string;
  section: string;
  includeInherited?: boolean;
  offset?: number;
  limit?: number;
}

/** Operation-level facade implemented by the shared same-origin API module. */
export interface ExplorerApi {
  listResources(packageId: string, query: ResourceQuery, signal?: AbortSignal): Promise<ResourcePage>;
  getResource(identity: ResourceIdentity, signal?: AbortSignal): Promise<ResourceDetail>;
  getSafeSource(identity: ResourceIdentity, signal?: AbortSignal): Promise<SourceProjection>;
  getResourceSourceFile?(identity: ResourceIdentity, referenceId: string, signal?: AbortSignal, origin?: string): Promise<SourceProjection>;
  getDeclaredConfiguration(packageId: string, signal?: AbortSignal): Promise<ConfigurationResponse>;
  getEffectiveConfiguration(packageId: string, query: EffectiveConfigurationQuery, signal?: AbortSignal): Promise<ConfigurationResponse>;
  compareConfiguration(packageId: string, query: ConfigurationComparisonQuery, signal?: AbortSignal): Promise<ConfigurationResponse>;
  listConfigurationFiles?(packageId: string, path?: string, query?: string, offset?: number, signal?: AbortSignal): Promise<ConfigurationFilePage>;
  getConfigurationFileSource?(packageId: string, path: string, origin?: string, signal?: AbortSignal): Promise<SourceProjection>;
  getTestdataRecords?(packageId: string, descriptorId: string, query: { environment?: string; offset?: number; limit?: number; previewGenerated?: boolean }, signal?: AbortSignal): Promise<TestdataRecordsResponse>;
  getDebugForm(identity: ResourceIdentity, environment?: string, signal?: AbortSignal): Promise<DebugFormResponse>;
  previewDebug(input: DebugDraftRequest, signal?: AbortSignal): Promise<DraftResponse>;
  submitDebug(input: { packageId: string; draftId: string; principal?: string }, signal?: AbortSignal): Promise<AcceptedJob>;
  runCase(input: { packageId: string; suite: string; caseId: string; principal?: string }, signal?: AbortSignal): Promise<AcceptedJob>;
}

export interface ExplorerPageProps {
  registerUnsavedChanges: (isDirty: boolean, description?: string) => () => void;
  api: ExplorerApi;
  packageId: string;
  /** Route values are untrusted URL segments; Explorer validates the type before API use. */
  resourceRoute: { type: string; resourceId: string; tab?: 'definition' | 'source' | 'relations' } | null;
  environment: string;
  principalLabel?: string;
  onResourceRouteChange: (route: (Omit<ResourceIdentity, 'packageId'> & { tab?: 'definition' | 'source' | 'relations' }) | null) => void;
  onEnvironmentChange: (environment: string) => void;
  onJobAccepted: (jobId: string) => void;
  onPrincipalLabelChange?: (label: string) => void;
  onOpenQuickLoad?: (identity: ResourceIdentity) => void;
  onOpenConfiguration?: (identity: ResourceIdentity) => void;
  onOpenConfigurationFile?: (path: string, origin?: string, reference?: ResourceSourceReference) => void;
}
