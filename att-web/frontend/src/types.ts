export interface ApiEnvelope<T> {
  items: T[];
  requestId?: string;
}

export interface ApiErrorBody {
  code?: string;
  summary?: string;
  detail?: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly requestId?: string;
  readonly details?: unknown;
  readonly diagnostics?: unknown;

  constructor(message: string, options: { status: number; code?: string; requestId?: string; details?: unknown; diagnostics?: unknown }) {
    super(message);
    this.name = 'ApiError';
    this.status = options.status;
    this.code = options.code;
    this.requestId = options.requestId;
    this.details = options.details;
    this.diagnostics = options.diagnostics;
  }
}

export interface ServerVersion {
  unifiedRunVersion?: string;
  version?: string;
  apiVersion: string;
  buildTime?: string;
  gitCommit?: string;
  javaMinimum?: number;
  inlineLoadEnabled: boolean;
  loadSchedulingEnabled?: boolean;
  authMode?: 'container' | 'local';
  workspaceDiscoveryEnabled?: boolean;
  workspaceRefreshMode?: 'automatic' | 'manual';
  workspaceRefreshIntervalSeconds?: number;
  workspaceDiscoveryStatus?: 'disabled' | 'ready' | 'refreshing' | 'unavailable' | string;
  workspaceLastRefreshedAt?: string;
}

export interface LocalAuthUser {
  loginId: string;
  displayName: string;
  role: 'ADMIN' | 'USER';
  active: boolean;
  updatedAt?: string;
}

export interface PackageSummary {
  packageId: string;
  name?: string;
  packageType?: 'shared' | 'workspace' | string;
  canExecute?: boolean;
  workspaceOwner?: string;
  discovery?: { status?: string; lastRefreshedAt?: string };
  validation?: { status?: string; revision?: string; lastValidatedAt?: string; errorCount?: number; warningCount?: number };
}

export type JobStatus =
  | 'QUEUED'
  | 'PREPARING'
  | 'RUNNING'
  | 'CANCEL_REQUESTED'
  | 'PASS'
  | 'FAIL'
  | 'INVALID'
  | 'ERROR'
  | 'CANCELLED'
  | string;

export interface JobSummary {
  executionType?: string;
  jobId: string;
  packageId: string;
  command: string;
  principal?: string;
  status: JobStatus;
  createdAt?: string;
  startedAt?: string | null;
  finishedAt?: string | null;
  exitCode?: number | null;
}

export type AsyncState<T> =
  | { status: 'loading' }
  | { status: 'ready'; data: T }
  | { status: 'error'; error: Error };

export interface ServerCapabilities {
  apiVersion: string | null;
  inlineLoadEnabled: boolean;
  loadSchedulingEnabled: boolean;
}
