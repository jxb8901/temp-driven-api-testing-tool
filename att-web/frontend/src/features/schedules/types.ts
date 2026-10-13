import type { ApiEnvelope } from '../../types';

export interface LoadSchedule {
  id: string;
  name: string;
  description?: string | null;
  tags?: string[];
  packageId: string;
  scenario: string;
  loadModel?: string | null;
  config?: string | null;
  environment?: string | null;
  timezone: string;
  cron: string;
  load?: Record<string, string>;
  maxConcurrentRuns: number;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
  nextRunAt?: string | null;
}

export interface LoadScheduleInput {
  name: string;
  description: string;
  tags: string[];
  packageId: string;
  scenario: string;
  config: string;
  environment: string;
  timezone: string;
  cron: string;
  load: Record<string, string>;
  maxConcurrentRuns: number;
  enabled: boolean;
}

export interface SchedulePreviewInput {
  timezone: string;
  cron: string;
}

export interface SchedulePreview extends SchedulePreviewInput {
  nextRunAt: string;
}

export interface ScheduleOccurrence {
  occurrenceId: string;
  scheduleId?: string;
  scheduledFor: string;
  triggerType: string;
  status: string;
  reason?: string | null;
  jobId?: string | null;
  createdAt?: string;
}

export interface DeleteScheduleResponse {
  scheduleId: string;
  status: 'DELETED' | string;
}

export interface ScheduledLoadApi {
  listLoadSchedules(signal?: AbortSignal): Promise<ApiEnvelope<LoadSchedule>>;
  previewLoadSchedule(input: SchedulePreviewInput, signal?: AbortSignal): Promise<SchedulePreview>;
  createLoadSchedule(input: LoadScheduleInput, signal?: AbortSignal): Promise<LoadSchedule>;
  updateLoadSchedule(id: string, input: LoadScheduleInput, signal?: AbortSignal): Promise<LoadSchedule>;
  setLoadScheduleEnabled(id: string, enabled: boolean, signal?: AbortSignal): Promise<LoadSchedule>;
  triggerLoadSchedule(id: string, signal?: AbortSignal): Promise<ScheduleOccurrence>;
  deleteLoadSchedule(id: string, signal?: AbortSignal): Promise<DeleteScheduleResponse>;
  getLoadScheduleExecutions(id: string, limit?: number, signal?: AbortSignal): Promise<ApiEnvelope<ScheduleOccurrence>>;
}
