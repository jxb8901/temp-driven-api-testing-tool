import { describe, expect, it } from 'vitest';
import {
  createScheduleDraft, cronForDraft, draftFromSchedule, explainOccurrence,
  formatScheduleTime, isIanaTimeZone, recurrenceFromCron, scheduleInputFromDraft,
} from './schedule';
import type { LoadSchedule } from './types';

describe('Scheduled Load draft helpers', () => {
  it('converts daily, weekly, and cron recurrence drafts', () => {
    const draft = createScheduleDraft('Asia/Hong_Kong');
    expect(cronForDraft(draft)).toBe('0 2 * * *');
    expect(cronForDraft({ ...draft, recurrence: 'weekly', timeOfDay: '08:05', dayOfWeek: '1-5' })).toBe('5 8 * * 1-5');
    expect(cronForDraft({ ...draft, recurrence: 'cron', cron: '*/15 8-17 * * 1-5' })).toBe('*/15 8-17 * * 1-5');
    expect(recurrenceFromCron('30 6 * * 0,6')).toEqual({ recurrence: 'weekly', timeOfDay: '06:30', dayOfWeek: '0,6' });
    expect(recurrenceFromCron('0 2 1 * *').recurrence).toBe('cron');
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
