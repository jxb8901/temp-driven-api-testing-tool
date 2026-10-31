import type { ApiEnvelope } from '../../types';
export type PackageChangePolicy = 'strict' | 'pause' | 'ignore';

export interface ScheduleDefinition {
  definitionVersion?: number;
  snapshotVersion?: number;
  targetIdentity?: import('../load/types').TargetIdentity;
  packageChangePolicy?: PackageChangePolicy | null;
  effectivePackageChangePolicy?: PackageChangePolicy;
  serverPackageChangePolicy?: PackageChangePolicy;
  storedRevisionDigest?: string;
  currentRevisionDigest?: string;
  sourceStatus?: string;
  autoPauseReason?: string;
  autoPausedAt?: string;
  sourceJobId?: string;
  id: string;
  name: string;
  description?: string | null;
  tags?: string[];
  packageId: string;
  executionType: 'run' | 'load';
  schemaVersion?: 'att-schedule/v2';
  /** Transport adapter only: persisted Debug schedules retain their original backend type. */
  legacyDebug?: boolean;
  createdBy?: string;
  executionPrincipal?: string;
  executionIdentityMigratedFrom?: string;
  executionIdentityMigratedBy?: string;
  executionIdentityMigratedAt?: string;
  selectedCaseIds?: string[];
  revisionPolicy?: string;
  recentStatus?: string;
  debugTarget?: { type: string; id: string } | null;
  runOptions?: { debug?: boolean; failFast?: boolean } | null;
  executionSettings?: {
    thresholds?: Record<string, unknown>;
    evidence?: Record<string, unknown>;
    testdata?: unknown;
    execution?: Record<string, unknown>;
    seed?: number | string;
  } | null;
  scenario?: string | null;
  inlineSnapshot?: boolean;
  loadModel?: string | null;
  config?: string | null;
  environment?: string | null;
  timezone: string;
  cron?: string;
  runAt?: string;
  load?: Record<string, string>;
  maxConcurrentRuns: number;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
  nextRunAt?: string | null;
}

export interface ScheduleInput {
  packageChangePolicy?: PackageChangePolicy | null;
  name: string;
  description: string;
  tags: string[];
  packageId: string;
  executionType: 'run' | 'load';
  schemaVersion: 'att-schedule/v2';
  legacyDebug?: boolean;
  scenario?: string;
  draftId?: string;
  config?: string;
  environment: string;
  timezone: string;
  cron?: string;
  runAt?: string;
  load?: Record<string, string>;
  maxConcurrentRuns: number;
  enabled: boolean;
}

export interface SchedulePreviewInput {
  timezone: string;
  cron?: string;
  runAt?: string;
}

export interface SchedulePreview extends SchedulePreviewInput {
  nextRunAt: string;
  nextOccurrences?: string[];
}

export interface ValidatedScheduleIntent {
  packageId: string;
  environment?: string;
  draftId?: string;
  scenario?: string;
  previewYaml?: string;
  expiresAt?: string;
  executionType?: 'run' | 'load';
  legacyDebug?: boolean;
  target?: { type: string; id: string };
}

export interface ScheduleOccurrence {
  occurrenceId: string;
  scheduleId?: string;
  scheduledFor: string;
  triggerType: string;
  status: string;
  reason?: string | null;
  jobId?: string | null;
  jobStatus?: string | null;
  createdAt?: string;
}

export interface DeleteScheduleResponse {
  scheduleId: string;
  status: 'DELETED' | string;
}

export interface ScheduleExecutionReview {
  draftId: string; definitionVersion: number; snapshotVersion: number; nextRunAt?: string | null;
  revisionDigest: string; sourcePolicy: string; expiresAt: string;
  originalPreview: { previewYaml?: string; [key: string]: unknown }; preview: { previewYaml?: string; [key: string]: unknown };
}

export interface SchedulesApi {
  createScheduleExecutionDraft?(id: string, input: { expectedDefinitionVersion: number; overrides?: Record<string, unknown>; packageEdit?: Record<string, unknown> }): Promise<ScheduleExecutionReview>;
  replaceScheduleExecution?(id: string, input: { draftId: string; expectedDefinitionVersion: number; confirm: true }): Promise<ScheduleDefinition>;
  listSchedules(signal?: AbortSignal): Promise<ApiEnvelope<ScheduleDefinition>>;
  getSchedule?(id: string, signal?: AbortSignal): Promise<ScheduleDefinition>;
  previewSchedule(input: SchedulePreviewInput, signal?: AbortSignal): Promise<SchedulePreview>;
  createSchedule(input: ScheduleInput, signal?: AbortSignal): Promise<ScheduleDefinition>;
  updateSchedule(id: string, input: ScheduleInput, signal?: AbortSignal): Promise<ScheduleDefinition>;
  setScheduleEnabled(id: string, enabled: boolean, signal?: AbortSignal): Promise<ScheduleDefinition>;
  triggerSchedule(id: string, signal?: AbortSignal): Promise<ScheduleOccurrence>;
  deleteSchedule(id: string, signal?: AbortSignal): Promise<DeleteScheduleResponse>;
  getScheduleExecutions(id: string, limit?: number, signal?: AbortSignal): Promise<ApiEnvelope<ScheduleOccurrence>>;
}

/** Decode old servers/stored Debug definitions without exposing a third execution type. */
export function normalizeSchedule(value: ScheduleDefinition & { jobType?: string }): ScheduleDefinition {
  const { jobType, ...schedule } = value;
  return {
    ...schedule,
    executionType: value.executionType || (jobType === 'run' || jobType === 'debug' ? 'run' : 'load'),
    ...(jobType === 'debug' ? { legacyDebug: true } : {}),
  };
}

export function scheduleTransport(input: ScheduleInput, unified = true) {
  const { legacyDebug, ...canonical } = input;
  if (!legacyDebug && unified) return canonical;
  const { executionType: _type, ...legacy } = canonical;
  if (!unified) {
    const { schemaVersion: _version, ...oldServer } = legacy;
    if (input.executionType === 'run' && !legacyDebug) throw new Error('RUN schedules require a Server with unified RUN support. Validate again on a compatible Server.');
    return { ...oldServer, jobType: legacyDebug ? 'debug' : 'load' };
  }
  return { ...legacy, jobType: 'debug' };
}
