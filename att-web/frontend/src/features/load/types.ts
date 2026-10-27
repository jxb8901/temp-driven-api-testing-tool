import type { ValidatedScheduleIntent } from "../schedules/types";
export type LoadModel = 'virtualUsers' | 'arrivalRate';
export type LoadTargetType = 'template' | 'flow' | 'tool';
export type JobStatus =
  | 'QUEUED' | 'PREPARING' | 'RUNNING' | 'CANCEL_REQUESTED'
  | 'PASS' | 'FAIL' | 'ERROR' | 'INVALID' | 'CANCELLED'
  | (string & {});

export interface PackageOption {
  packageId: string;
  displayName?: string;
}

export interface LoadCatalogItem {
  packageId: string;
  resourceId: string;
  path: string;
  kind: 'scenario' | 'template' | 'invalid' | string;
  validation: string;
  model?: LoadModel | null;
  workloadCount: number;
  sourceAvailable: boolean;
  runNow: boolean;
  schedule: boolean;
  createQuickLoad: boolean;
  diagnostic?: string;
  diagnostics?: ServerDiagnostic[];
  requestId?: string;
  validatedEnvironment?: string;
  scheduleDraftId?: string;
  schedulePreviewYaml?: string;
  scheduleDraftExpiresAt?: string;
  scheduleReason?: string;
}

export interface LoadCatalogPage {
  items: LoadCatalogItem[];
  total: number;
  nextCursor?: string | null;
}

export interface LoadCatalogDetail {
  packageId: string;
  resourceId: string;
  path: string;
  kind: 'scenario' | 'template' | 'invalid' | string;
  validation: string;
  model?: LoadModel | null;
  schemaVersion?: string;
  workloadCount: number;
  sourceAvailable: boolean;
  definition?: Record<string, unknown>;
  diagnostics?: ServerDiagnostic[];
  diagnostic?: string;
  redacted?: boolean;
}

export interface TargetResource {
  type: LoadTargetType;
  resourceId: string;
  logicalId: string;
  state: string;
}

export interface ServerDiagnostic {
  code?: string;
  reason?: string;
  field?: string;
  path?: string;
  file?: string;
  sourceFile?: string;
  resourceId?: string;
  action?: string;
  severity?: string;
  message?: string;
  summary?: string;
  detail?: string;
  suggestion?: string;
  line?: number;
  column?: number;
}

export interface LoadPolicyResponse {
  model: LoadModel;
  policy: Record<string, unknown>;
  limits?: LoadServerLimits;
  previewYaml?: string;
  redacted?: boolean;
  templates?: LoadPolicyTemplate[];
  templateCatalogTruncated?: boolean;
}

export interface LoadServerLimits {
  maxWorkloads: number;
  maxTargets: number;
  maxTotalUsers: number;
  maxAggregateArrivalRatePerSecond: number;
  maxConcurrentPerWorkload: number;
  maxTotalConcurrent: number;
  maxDurationSeconds: number;
}

export interface LoadPolicyTemplate {
  id: string;
  name: string;
  model: LoadModel;
  bundled?: boolean;
}

export interface QuickLoadFormResponse {
  target?: { type?: LoadTargetType; id?: string };
  model: LoadModel;
  input: Record<string, unknown>;
  preview?: Record<string, unknown>;
  previewYaml?: string;
  redacted?: boolean;
}

export interface LoadDraftInput {
  packageId: string;
  environment?: string;
  scenario: Record<string, unknown>;
}

export interface LoadDraftResponse {
  draftId: string;
  requestId?: string;
  packageId: string;
  environment?: string;
  model?: LoadModel;
  preview?: Record<string, unknown>;
  previewYaml?: string;
  redacted?: boolean;
  diagnostics?: ServerDiagnostic[];
  expiresAt: string;
}

export interface SubmitLoadDraftInput {
  packageId: string;
  draftId: string;
  principal?: string;
}

export interface JobAccepted {
  jobId: string;
  status: JobStatus;
  command?: string;
  packageId?: string;
}

export interface TargetIdentity {
  type?: string; id?: string; signature?: string; targets?: Array<{ type: string; id: string }>;
  workloadCount?: number; bindingCount?: number; truncated?: boolean;
  workloads?: Array<{ name: string; targets: Array<{type: string; id: string}>; model: string; users?: number; arrivalRate?: number | string; maxConcurrent?: number; duration?: string; totalDurationMs?: number; warmup?: string; rampUp?: string; rampDown?: string }>;
}
export interface LoadLiveProgressCounts {
  scheduled?: number; started?: number; completed?: number; passed?: number; sutFailures?: number;
  runtimeErrors?: number; dropped?: number; other?: number; active?: number; queued?: number;
  schedulerLagMeanMs?: number | null;
  latency?: { sampleCount?: number; meanMs?: number | null; p95Ms?: number | null; p99Ms?: number | null; percentileMethod?: string };
}
export interface LoadLiveProgressPoint {
  capturedAt: string; serverReceivedAt?: string; elapsedMs: number; phase: string;
  counts: LoadLiveProgressCounts; measuredCounts?: LoadLiveProgressCounts;
  rates?: { completedTps?: number; successfulTps?: number };
  latency?: { sampleCount?: number; meanMs?: number | null; p95Ms?: number | null; p99Ms?: number | null; percentileMethod?: string };
  workloads?: Record<string, LoadLiveProgressCounts>;
}
export interface LoadLiveProgressSnapshot extends LoadLiveProgressPoint {
  schemaVersion: 'att-load-live-progress/v1';
  runId?: string;
  telemetryPersistence?: { state: 'HEALTHY' | 'DEGRADED'; diagnostic?: string; retryAfterMs?: number };
  checkpointInterval?: LoadCheckpointInterval;
  latestFailure?: { status: string; capturedAt: string; code?: string };
  phases?: Record<string, LoadLiveProgressCounts>;
  workloadsTruncated?: boolean;
}
export interface LoadCheckpointInterval {
  schemaVersion: 'att-load-checkpoint-interval/v1'; state: 'WAITING' | 'DEGRADED' | 'VERIFIED';
  startSequence?: number; endSequence?: number; startAt?: string; endAt?: string; durationMs?: number;
  counts?: Record<string, number>; coverageGap?: boolean;
  latencySampleCount?: number; latencyMeanMs?: number;
}
export interface JobsQuery {
  offset?: number; limit?: number; principal?: string; principalMode?: string; packageId?: string; executionType?: string;
  status?: string; from?: string; to?: string; search?: string; sort?: string; deletion?: string; pinnedOnly?: boolean; groupBy?: string; signal?: AbortSignal;
}
export interface JobsQueryResult {
  items: JobSummary[]; total: number; offset: number; limit: number; nextOffset?: number | null;
  groups: Array<{ packageId: string; environment: string; targetGroup: string; total: number; lastCreatedAt: string; statuses: Record<string,number> }>;
  principalSuggestions: string[]; packageSuggestions: string[];
}
export interface JobSummary {
  scheduled?: boolean; targetIdentity?: TargetIdentity; targetGroup?: string; deletionState?: string | null;
  rerunOfJobId?: string;
  pinned?: boolean;
  pinnedAt?: string | null;
  pinnedBy?: string | null;
  pinReason?: string | null;
  executionType?: string;
  scenario?: string;
  environment?: string;
  model?: LoadModel | string;
  jobId: string;
  command: string;
  packageId: string;
  principal?: string;
  status: JobStatus;
  createdAt?: string;
  startedAt?: string | null;
  finishedAt?: string | null;
  exitCode?: number | null;
}

export interface JobView extends JobSummary {
  archiveMetrics?: ArtifactArchiveMetrics | null;
  loadProgress?: LoadLiveProgressSnapshot;
  loadProgressHistory?: LoadLiveProgressPoint[];
  loadProgressTrend?: LoadLiveProgressPoint[];
  loadCheckpointHistory?: LoadCheckpointInterval[];
  performance?: {
    timings?: Record<string, unknown>;
    worker?: Record<string, unknown>;
  };
}

export interface JobResultSummary {
  jobId: string;
  command?: string;
  packageId?: string;
  status?: JobStatus;
  exitCode?: number | null;
}

export interface JobResultResponse {
  job: JobResultSummary;
  result: Record<string, unknown> | null;
  diagnostic?: Record<string, unknown> | null;
}

export interface JobArtifact {
  path: string;
  sha256?: string;
  size?: number;
  report?: boolean;
  mediaType?: string;
}

export interface ArtifactArchiveMetrics {
  version: number;
  attempts: number;
  retries: number;
  outcome: string;
  failurePhase?: string | null;
  failureReason?: string | null;
  queueWaitMs?: number | null;
  publicationWallMs?: number | null;
  scanClassificationRedactionMs?: number | null;
  zipWriteMs?: number | null;
  verificationFsyncMs?: number | null;
  cleanupMs?: number | null;
  writerCpuMs?: number | null;
  writerAllocatedBytes?: number | null;
  sourceFiles?: number | null;
  sourceBytes?: number | null;
  includedEntries?: number | null;
  omittedEntries?: number | null;
  includedBytes?: number | null;
  zipBytes?: number | null;
  compressionRatio?: number | null;
}

export interface JobArtifactArchive {
  archiveSizeBytes?: number | null;
  archiveMetrics?: ArtifactArchiveMetrics | null;
  archiveStatus: 'PENDING' | 'ARCHIVING' | 'READY' | 'FAILED' | 'EXPIRED';
  items: JobArtifact[];
  primaryReports: JobArtifact[];
  nextOffset?: number | null;
  complete?: boolean;
  omissions?: Array<{ count: number; reason: string }>;
  retryable?: boolean;
}

export interface JobEvent {
  id?: string;
  type: 'status' | 'progress' | 'log' | 'diagnostic' | 'result' | string;
  data: unknown;
  receivedAt?: string;
}

export interface JobEventHandlers {
  onOpen: () => void;
  onError: () => void;
  onEvent: (event: JobEvent) => void;
}

/** Feature boundary. Shared api.ts implements these operations; components never build REST paths. */
export interface LoadJobsApi {
  queryJobs?(input: JobsQuery): Promise<JobsQueryResult>;
  setDeletionRequested?(jobIds: string[], requested: boolean): Promise<Array<{jobId: string; outcome: string}>>;
  getJobReplay?(jobId: string): Promise<JobReplay>;
  createJobReplayDraft?(jobId: string, options?: { sourcePolicy?: 'current' | 'strict'; overrides?: Record<string, unknown> }): Promise<JobReplay>;
  getJobReplayEditorDraft?(jobId: string, draftRef: string): Promise<JobReplayEditorDraft>;
  rerunJob?(jobId: string, intentId: string, sourcePolicy?: 'current' | 'strict'): Promise<{ jobId: string }>;

  listLoadCatalog?(packageId: string, input?: { query?: string; cursor?: string; limit?: number }, signal?: AbortSignal): Promise<LoadCatalogPage>;
  getLoadCatalogDetail?(packageId: string, resourceId: string, signal?: AbortSignal): Promise<LoadCatalogDetail>;
  getLoadSource?(packageId: string, resourceId: string, signal?: AbortSignal): Promise<{ available: boolean; logicalPath?: string; text?: string; redacted?: boolean; reason?: string }>;
  submitLoadScenario?(input: { packageId: string; scenario: string; environment?: string }): Promise<JobAccepted>;
  listLoadJobs?(input?: { offset?: number; limit?: number; principal?: string; pinnedOnly?: boolean; signal?: AbortSignal }): Promise<{ items: JobSummary[]; nextOffset?: number | null }>;
  getLoadPolicy(packageId: string, model: LoadModel, environment?: string, signal?: AbortSignal, templateId?: string): Promise<LoadPolicyResponse>;
  getTargetLoadForm(
    packageId: string,
    type: LoadTargetType,
    resourceId: string,
    model: LoadModel,
    environment?: string,
    signal?: AbortSignal,
  ): Promise<QuickLoadFormResponse>;
  listTargets(packageId: string, type: LoadTargetType, signal?: AbortSignal): Promise<TargetResource[]>;
  createLoadDraft(input: LoadDraftInput, signal?: AbortSignal): Promise<LoadDraftResponse>;
  submitLoadDraft(input: SubmitLoadDraftInput): Promise<JobAccepted>;
  listJobs(input?: { principal?: string; pinnedOnly?: boolean; signal?: AbortSignal }): Promise<JobSummary[]>;
  getJob(jobId: string, signal?: AbortSignal): Promise<JobView>;
  getJobResult(jobId: string, signal?: AbortSignal): Promise<JobResultResponse>;
  getJobArtifacts(jobId: string, signal?: AbortSignal): Promise<JobArtifact[]>;
  getJobArtifactArchive?(jobId: string, offset?: number, signal?: AbortSignal): Promise<JobArtifactArchive>;
  getJobArtifactText?(jobId: string, path: string, signal?: AbortSignal): Promise<string>;
  retryJobArtifactArchive?(jobId: string): Promise<unknown>;
  artifactArchiveHref?(jobId: string): string;
  pinJob?(jobId: string, pinned: boolean, pinReason?: string): Promise<JobView>;
  pinJobs?(jobIds: string[], pinned: boolean, pinReason?: string): Promise<Array<{ jobId: string; outcome: string }>>;
  deleteJob?(jobId: string): Promise<{ jobId: string; outcome: string }>;
  deleteJobs?(jobIds: string[]): Promise<Array<{ jobId: string; outcome: string }>>;
  cancelJob(jobId: string): Promise<{ jobId: string; status: JobStatus }>;
  subscribeJob(jobId: string, handlers: JobEventHandlers): () => void;
  artifactHref(jobId: string, path: string): string;
}

export interface LoadFeatureProps {
  api: LoadJobsApi;
  packages: PackageOption[];
  selectedPackage: string;
  selectedEnvironment: string;
  environments?: string[];
  environmentState?: 'loading' | 'ready' | 'error';
  inlineLoadEnabled: boolean;
  schedulingEnabled?: boolean;
  replayDraftId?: string;
  replaySourceJobId?: string;
  principal?: string;
  onRetryEnvironments?: () => void;
  onPackageChange: (packageId: string) => void;
  onEnvironmentChange: (environment: string) => void;
  onJobAccepted: (jobId: string) => void;
  onSchedule?: (input: { packageId: string; environment?: string; draftId: string; previewYaml?: string; expiresAt?: string }) => boolean | void;
  confirmStart?: (message: string) => boolean | Promise<boolean>;
}

export interface JobsFeatureProps {
  initialTab?: 'overview' | 'live' | 'events' | 'results' | 'artifacts' | 'metrics';
  onTabChange?(tab: 'overview' | 'live' | 'events' | 'results' | 'artifacts' | 'metrics'): void;
  standalone?: boolean;
  executionType?: 'load';
  onExecutionTypeChange?(value: 'load' | undefined): void;
  onSchedule?(intent: ValidatedScheduleIntent): void;
  onEditSchedule?(id: string): void;

  api: LoadJobsApi;
  jobId?: string;
  principal?: string;
  onOpenJob: (jobId: string) => void;
  onShowList: () => void;
  confirmCancel?: (message: string) => boolean | Promise<boolean>;
}

export interface JobReplay {
  jobId: string; available: boolean; reason?: string; rerunOfJobId?: string; originatingScheduleId?: string;
  revisionPolicy?: string; revision?: string; sourcePath?: string;
  definition?: { executionType: 'run' | 'load'; packageId: string; environment?: string | null; [key: string]: unknown };
  originalDefinition?: { executionType: 'run' | 'load'; packageId: string; environment?: string | null; [key: string]: unknown };
  originalConfiguration?: Record<string, unknown>; configuration?: Record<string, unknown>;
  intentId?: string; draftId?: string; expiresAt?: string; preview?: Record<string, unknown>;
  sourcePolicy?: 'current' | 'strict'; originalRevision?: string; currentRevision?: string; draftRef?: string; missingFields?: string[];
  validationState?: 'valid' | 'invalid'; errorCode?: string;
  diagnostics?: ServerDiagnostic[];
}

export interface JobReplayEditorDraft {
  schemaVersion: 'att-job-replay-draft/v1'; draftRef: string; sourceJobId: string; packageId: string;
  executionType: 'run' | 'load'; sourcePolicy: 'current' | 'strict'; originalRevision: string; currentRevision?: string;
  definition: NonNullable<JobReplay['definition']>; originalDefinition?: NonNullable<JobReplay['definition']>;
  originalConfiguration?: Record<string, unknown>; configuration: Record<string, unknown>; expiresAt: string; missingFields: string[];
  validationState?: 'valid' | 'invalid'; errorCode?: string; diagnostics?: ServerDiagnostic[];
}
