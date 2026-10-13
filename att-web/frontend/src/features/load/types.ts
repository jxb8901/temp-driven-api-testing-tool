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

export interface TargetResource {
  type: LoadTargetType;
  logicalId: string;
  state: string;
}

export interface ServerDiagnostic {
  code?: string;
  path?: string;
  severity?: string;
  message: string;
}

export interface LoadPolicyResponse {
  model: LoadModel;
  policy: Record<string, unknown>;
  previewYaml?: string;
  redacted?: boolean;
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

export interface JobSummary {
  jobId: string;
  command: string;
  packageId: string;
  principal: string;
  status: JobStatus;
  createdAt: string;
  startedAt?: string | null;
  finishedAt?: string | null;
  exitCode?: number | null;
}

export interface JobView extends JobSummary {
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
  size?: number;
}

export interface JobEvent {
  id?: string;
  type: 'status' | 'progress' | 'log' | 'diagnostic' | 'result' | string;
  data: unknown;
}

export interface JobEventHandlers {
  onOpen: () => void;
  onError: () => void;
  onEvent: (event: JobEvent) => void;
}

/** Feature boundary. Shared api.ts implements these operations; components never build REST paths. */
export interface LoadJobsApi {
  getLoadPolicy(packageId: string, model: LoadModel, environment?: string, signal?: AbortSignal): Promise<LoadPolicyResponse>;
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
  listJobs(input?: { principal?: string; signal?: AbortSignal }): Promise<JobSummary[]>;
  getJob(jobId: string, signal?: AbortSignal): Promise<JobView>;
  getJobResult(jobId: string, signal?: AbortSignal): Promise<JobResultResponse>;
  getJobArtifacts(jobId: string, signal?: AbortSignal): Promise<JobArtifact[]>;
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
  inlineLoadEnabled: boolean;
  principal?: string;
  onPackageChange: (packageId: string) => void;
  onEnvironmentChange: (environment: string) => void;
  onJobAccepted: (jobId: string) => void;
  confirmStart?: (message: string) => boolean | Promise<boolean>;
}

export interface JobsFeatureProps {
  api: LoadJobsApi;
  jobId?: string;
  principal?: string;
  onOpenJob: (jobId: string) => void;
  onShowList: () => void;
  confirmCancel?: (message: string) => boolean | Promise<boolean>;
}
