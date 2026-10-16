import { describe, expect, it } from 'vitest';
import {
  createScheduleDraft, cronForDraft, draftFromSchedule, explainOccurrence,
  formatScheduleTime, isIanaTimeZone, recurrenceFromCron, scheduleInputFromDraft,
} from './schedule';
import type { LoadSchedule } from './types';

describe('Scheduled execution draft helpers', () => {
  it('preserves a one-time RUN snapshot without introducing cron or Load overrides', () => {
    const draft = { ...createScheduleDraft('Asia/Hong_Kong'), name: 'Once', packageId: 'p', jobType: 'run' as const, draftId: 'R123', inlineSnapshot: true, recurrence: 'once' as const, runAt: '2026-11-01T02:30:00+08:00' };
    const input = scheduleInputFromDraft(draft);
    expect(input).toMatchObject({ jobType: 'run', draftId: 'R123', runAt: '2026-10-31T18:30:00.000Z' });
    expect(input).not.toHaveProperty('cron'); expect(input).not.toHaveProperty('load');
    const restored = draftFromSchedule({ id: 'S1', name: 'Once', packageId: 'p', jobType: 'run', inlineSnapshot: true, timezone: 'UTC', runAt: input.runAt, maxConcurrentRuns: 1, enabled: false });
    expect(restored).toMatchObject({ recurrence: 'once', jobType: 'run', runAt: input.runAt });
    expect(() => scheduleInputFromDraft({ ...draft, runAt: '2026-11-01T02:30:00' })).toThrow(/offset/);
  });
  it('converts daily, weekly, and cron recurrence drafts', () => {
    const draft = createScheduleDraft('Asia/Hong_Kong');
    expect(cronForDraft(draft)).toBe('0 2 * * *');
    expect(cronForDraft({ ...draft, recurrence: 'weekly', timeOfDay: '08:05', dayOfWeek: '1-5' })).toBe('5 8 * * 1-5');
    expect(cronForDraft({ ...draft, recurrence: 'cron', cron: '*/15 8-17 * * 1-5' })).toBe('*/15 8-17 * * 1-5');
    expect(recurrenceFromCron('30 6 * * 0,6')).toEqual({ recurrence: 'weekly', timeOfDay: '06:30', dayOfWeek: '0,6' });
    expect(recurrenceFromCron('0 2 1 * *').recurrence).toBe('cron');
    expect(recurrenceFromCron('99 99 * * *').recurrence).toBe('cron');
    expect(recurrenceFromCron('60 10 * * 1-5').recurrence).toBe('cron');
    expect(recurrenceFromCron('59 23 * * *')).toMatchObject({ recurrence: 'daily', timeOfDay: '23:59' });
    const malformed = draftFromSchedule({
      id: 'S-INVALID-CRON', name: 'Legacy schedule', packageId: 'payments', scenario: 'load/nightly.yaml',
      timezone: 'UTC', cron: '99 99 * * *', maxConcurrentRuns: 1, enabled: false,
    });
    expect(scheduleInputFromDraft(malformed).cron).toBe('99 99 * * *');
  });

  it('validates IANA timezones and formats server instants in the selected zone', () => {
    expect(isIanaTimeZone('Asia/Hong_Kong')).toBe(true);
    expect(isIanaTimeZone('Mars/Olympus')).toBe(false);
    expect(formatScheduleTime('2026-10-14T02:00:00Z', 'Asia/Hong_Kong', 'en')).toMatch(/HKT|GMT\+8/);
    expect(formatScheduleTime(null, 'UTC')).toBe('No next run scheduled');
  });

  it('builds disabled-by-default payloads and rejects invalid Load options', () => {
    const draft = { ...createScheduleDraft('UTC'), name: ' Nightly ', packageId: 'payments', scenario: 'load/nightly.yaml', tags: 'nightly, smoke, nightly' };
    expect(draft.enabled).toBe(false);
    expect(scheduleInputFromDraft(draft)).toMatchObject({
      name: 'Nightly', packageId: 'payments', scenario: 'load/nightly.yaml', timezone: 'UTC',
      cron: '0 2 * * *', tags: ['nightly', 'smoke'], enabled: false,
    });
    expect(() => scheduleInputFromDraft({ ...draft, load: '{"users": 10}' })).toThrow(/string values/);
    expect(() => scheduleInputFromDraft({ ...draft, timezone: 'invalid-zone' })).toThrow(/IANA timezone/);
    expect(() => scheduleInputFromDraft({ ...draft, maxConcurrentRuns: '0' })).toThrow(/positive whole number/);
  });

  it('maps existing schedules to editor drafts and explains skipped occurrences', () => {
    const schedule: LoadSchedule = {
      id: 'S-1', name: 'Morning', packageId: 'payments', scenario: 'load.yaml', timezone: 'UTC',
      cron: '15 7 * * 1-5', maxConcurrentRuns: 2, enabled: true,
    };
    expect(draftFromSchedule(schedule)).toMatchObject({ recurrence: 'weekly', timeOfDay: '07:15', enabled: true, maxConcurrentRuns: '2' });
    expect(explainOccurrence('misfire-no-backfill')).toMatch(/does not replay/);
    expect(explainOccurrence('schedule-overlap-limit')).toMatch(/concurrent-run limit/);
  });
});
