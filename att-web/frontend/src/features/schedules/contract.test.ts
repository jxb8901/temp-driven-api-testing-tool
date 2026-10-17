import { describe, expect, it } from 'vitest';
import { formatRoute, parseRoute } from '../../router';
import { draftFromSchedule, scheduleInputFromDraft } from './schedule';
import { normalizeSchedule, scheduleTransport, type ScheduleDefinition } from './types';

const definition: ScheduleDefinition = {
  id: 'S /1', name: 'Nightly', executionType: 'run', packageId: 'payments', environment: 'UAT',
  inlineSnapshot: true, timezone: 'America/New_York', cron: '30 1 * * *',
  maxConcurrentRuns: 2, enabled: false, createdBy: 'owner',
};

describe('unified schedule UI contract', () => {
  it.each(['new', 'edit', 'history'] as const)('round-trips the %s view without execution input in its URL', view => {
    const route = { kind: 'schedules' as const, view, ...(view === 'new' ? {} : { scheduleId: definition.id }) };
    expect(parseRoute(formatRoute(route))).toEqual(route);
  });

  it('round-trips an occurrence and accepts both old route aliases', () => {
    const route = { kind: 'schedules', view: 'history', scheduleId: definition.id, occurrenceId: 'O/ 2' } as const;
    expect(parseRoute(formatRoute(route))).toEqual(route);
    for (const alias of ['load-schedules', 'scheduled-load']) {
      expect(parseRoute(`#/${alias}/S1/edit`)).toEqual({ kind: 'schedules', view: 'edit', scheduleId: 'S1' });
      expect(formatRoute(parseRoute(`#/${alias}`))).toBe('#/schedules');
    }
  });

  it('edits a frozen batch without sending or expanding its Case IDs', () => {
    const input = scheduleInputFromDraft(draftFromSchedule({ ...definition, selectedCaseIds: ['suite.C1', 'suite.C3'] }));
    expect(scheduleTransport(input)).toMatchObject({ schemaVersion: 'att-schedule/v2', executionType: 'run', environment: 'UAT' });
    expect(input).not.toHaveProperty('load');
    expect(input).not.toHaveProperty('scenario');
    expect(input).not.toHaveProperty('draftId');
    expect(input).not.toHaveProperty('selectedCaseIds');
  });

  it('keeps legacy Debug as RUN in the UI and preserves its backend type only in the adapter', () => {
    const schedule = normalizeSchedule({ ...definition, jobType: 'debug' });
    expect(schedule).toMatchObject({ executionType: 'run', legacyDebug: true });
    expect(schedule).not.toHaveProperty('jobType');
    const transport = scheduleTransport(scheduleInputFromDraft(draftFromSchedule(schedule)));
    expect(transport).toHaveProperty('jobType', 'debug');
    expect(transport).not.toHaveProperty('executionType');
    expect(transport).not.toHaveProperty('legacyDebug');
  });

  it('negotiates old LOAD transport explicitly and never sends canonical RUN to an old server', () => {
    const input = scheduleInputFromDraft(draftFromSchedule(definition));
    expect(() => scheduleTransport(input, false)).toThrow(/unified RUN/);
    const legacy = scheduleTransport({ ...input, executionType: 'load' }, false);
    expect(legacy).toHaveProperty('jobType', 'load');
    expect(legacy).not.toHaveProperty('schemaVersion');
    expect(legacy).not.toHaveProperty('executionType');
  });
});
