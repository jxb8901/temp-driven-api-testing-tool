import type { ApiEnvelope } from '../../types';

export interface ScheduleDefinition {
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

export interface SchedulesApi {
  listSchedules(signal?: AbortSignal): Promise<ApiEnvelope<ScheduleDefinition>>;
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
