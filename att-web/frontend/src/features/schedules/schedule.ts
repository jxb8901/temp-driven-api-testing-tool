import type { LoadSchedule, LoadScheduleInput } from './types';

export type ScheduleRecurrence = 'daily' | 'weekly' | 'cron';

export interface ScheduleDraft {
  name: string;
  description: string;
  tags: string;
  packageId: string;
  scenario: string;
  config: string;
  environment: string;
  timezone: string;
  recurrence: ScheduleRecurrence;
  timeOfDay: string;
  dayOfWeek: string;
  cron: string;
  maxConcurrentRuns: string;
  load: string;
  enabled: boolean;
}

export const WEEKDAYS = [
  { value: '1-5', label: 'Monday–Friday' },
  { value: '0,6', label: 'Weekends' },
  { value: '1', label: 'Monday' },
  { value: '2', label: 'Tuesday' },
  { value: '3', label: 'Wednesday' },
  { value: '4', label: 'Thursday' },
  { value: '5', label: 'Friday' },
  { value: '6', label: 'Saturday' },
  { value: '0', label: 'Sunday' },
] as const;

export function localTimeZone(): string {
  try { return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; }
  catch { return 'UTC'; }
}

export function isIanaTimeZone(value: string): boolean {
  if (!value.trim()) return false;
  try {
    new Intl.DateTimeFormat('en', { timeZone: value }).format(new Date(0));
    return true;
  } catch { return false; }
}

export function createScheduleDraft(timezone = localTimeZone()): ScheduleDraft {
  return {
    name: '', description: '', tags: '', packageId: '', scenario: '', config: '', environment: '', timezone,
    recurrence: 'daily', timeOfDay: '02:00', dayOfWeek: '1-5', cron: '', maxConcurrentRuns: '1', load: '{}', enabled: false,
  };
}

export function cronForDraft(draft: ScheduleDraft): string {
  if (draft.recurrence === 'cron') return draft.cron.trim();
  const match = /^(?:[01]\d|2[0-3]):[0-5]\d$/.exec(draft.timeOfDay);
  if (!match) return '';
  const [hour, minute] = draft.timeOfDay.split(':');
  return `${String(Number(minute))} ${String(Number(hour))} * * ${draft.recurrence === 'weekly' ? draft.dayOfWeek : '*'}`;
}

export function recurrenceFromCron(cron: string): Pick<ScheduleDraft, 'recurrence' | 'timeOfDay' | 'dayOfWeek'> {
  const normalized = cron.trim().replace(/\s+/g, ' ');
  let match = /^(\d{1,2}) (\d{1,2}) \* \* \*$/.exec(normalized);
  if (match) {
    const time = cronTime(match);
    if (time) return { recurrence: 'daily', timeOfDay: time, dayOfWeek: '1-5' };
  }
  match = /^(\d{1,2}) (\d{1,2}) \* \* (1-5|0,6|[0-6])$/.exec(normalized);
  if (match) {
    const time = cronTime(match);
    if (time) return { recurrence: 'weekly', timeOfDay: time, dayOfWeek: match[3] };
  }
  return { recurrence: 'cron', timeOfDay: '02:00', dayOfWeek: '1-5' };
}

function cronTime(match: RegExpExecArray): string | null {
  const minute = Number(match[1]);
  const hour = Number(match[2]);
  if (!Number.isInteger(minute) || minute < 0 || minute > 59 || !Number.isInteger(hour) || hour < 0 || hour > 23) return null;
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;
}

export function draftFromSchedule(schedule: LoadSchedule): ScheduleDraft {
  return {
    name: schedule.name || '', description: schedule.description || '', tags: (schedule.tags || []).join(', '),
    packageId: schedule.packageId || '', scenario: schedule.scenario || '', config: schedule.config || '',
    environment: schedule.environment || '', timezone: schedule.timezone || 'UTC',
    ...recurrenceFromCron(schedule.cron || ''), cron: schedule.cron || '',
    maxConcurrentRuns: String(schedule.maxConcurrentRuns || 1), load: JSON.stringify(schedule.load || {}, null, 2),
    enabled: schedule.enabled === true,
  };
}

export function scheduleInputFromDraft(draft: ScheduleDraft): LoadScheduleInput {
  const load: unknown = JSON.parse(draft.load || '{}');
  if (!load || typeof load !== 'object' || Array.isArray(load)) throw new Error('Load options must be a JSON object.');
  const entries = Object.entries(load as Record<string, unknown>);
  if (entries.some(([, value]) => typeof value !== 'string')) {
    throw new Error('Load options must be a JSON object with string values.');
  }
  const tags = [...new Set(draft.tags.split(',').map(tag => tag.trim()).filter(Boolean))];
  if (tags.length > 20) throw new Error('A schedule can have at most 20 tags.');
  const maxConcurrentRuns = Number(draft.maxConcurrentRuns);
  if (!Number.isSafeInteger(maxConcurrentRuns) || maxConcurrentRuns < 1) {
    throw new Error('Maximum overlapping runs must be a positive whole number.');
  }
  const cron = cronForDraft(draft);
  if (!cron) throw new Error('Enter a valid local time before previewing this schedule.');
  if (!isIanaTimeZone(draft.timezone)) throw new Error('Enter a valid IANA timezone, such as Asia/Hong_Kong.');
  return {
    name: draft.name.trim(), description: draft.description.trim(), tags,
    packageId: draft.packageId, scenario: draft.scenario.trim(), config: draft.config.trim(),
    environment: draft.environment.trim(), timezone: draft.timezone.trim(), cron,
    load: load as Record<string, string>, maxConcurrentRuns, enabled: draft.enabled,
  };
}

export function formatScheduleTime(value: string | null | undefined, timezone: string, locale?: string): string {
  if (!value) return 'No next run scheduled';
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return 'Next run time unavailable';
  try {
    return new Intl.DateTimeFormat(locale, {
      year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit',
      timeZone: timezone, timeZoneName: 'short',
    }).format(date);
  } catch { return `${date.toISOString()} (${timezone})`; }
}

export function explainOccurrence(reason?: string | null): string {
  if (!reason) return '';
  if (reason.startsWith('misfire-no-backfill')) return 'Missed times are recorded as skipped; the Server does not replay them after a restart or late poll.';
  if (reason === 'schedule-overlap-limit') return 'Skipped because this schedule already reached its concurrent-run limit.';
  if (reason === 'global-scheduled-load-capacity') return 'Skipped because the Server reached its scheduled-load capacity.';
  if (reason === 'manual-trigger-rate-limit') return 'Skipped because the Server minimum interval limits repeated manual runs.';
  return reason;
}
