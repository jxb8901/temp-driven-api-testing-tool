import { useContext, useEffect, useId, useRef, useState } from 'react';
import { NavigationGuardContext } from '../../router';
import type { ValidatedScheduleIntent } from '../schedules/types';
import type { JobReplay, LoadJobsApi } from './types';

type ReplayApi = Pick<LoadJobsApi, 'getJobReplay' | 'createJobReplayDraft' | 'rerunJob'>;

/** Shared by Details, Jobs and LOAD history. Opening a review never submits a job. */
export function JobReplayActions({ api, jobId, onOpenJob, onSchedule, onEditSchedule, configuration }: {
  api: ReplayApi; jobId: string; onOpenJob(id: string): void;
  onSchedule?(intent: ValidatedScheduleIntent): void;
  onEditSchedule?(id: string): void;
  configuration?: { executionType: string; packageId: string };
}) {
  const [review, setReview] = useState<JobReplay | null>(null);
  const [mode, setMode] = useState<'run' | 'schedule' | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const lock = useRef(false);
  const generation = useRef(0);
  const heading = useRef<HTMLHeadingElement>(null);
  const opener = useRef<HTMLElement | null>(null);
  const unregister = useRef<(() => void) | undefined>(undefined);
  const navigation = useContext(NavigationGuardContext);
  const registerUnsavedChanges = navigation?.registerUnsavedChanges;
  const id = useId();
  useEffect(() => {
    if (!mode) return;
    heading.current?.focus();
    unregister.current = registerUnsavedChanges?.(true, 'A job replay review is open. Leave without creating a job or schedule?');
    return () => { unregister.current?.(); unregister.current = undefined; };
  }, [mode, registerUnsavedChanges]);
  useEffect(() => () => { generation.current++; }, []);
  useEffect(() => {
    if (!mode) return;
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy && !document.querySelector('dialog[open]')) { event.preventDefault(); close(); }
    };
    document.addEventListener('keydown', escape);
    return () => document.removeEventListener('keydown', escape);
  }, [mode, busy]);
  if (!api.getJobReplay || !api.createJobReplayDraft || !api.rerunJob) return null;

  function close() {
    generation.current++; setMode(null); setReview(null); setError(''); setBusy(false); lock.current = false;
    unregister.current?.(); opener.current?.focus();
  }
  async function open(next: 'run' | 'schedule') {
    if (lock.current) return;
    lock.current = true; opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const request = ++generation.current;
    setMode(next); setBusy(true); setReview(null); setError('');
    try {
      const metadata = await api.getJobReplay!(jobId);
      if (request !== generation.current) return;
      if (!metadata.available) { setReview(metadata); return; }
      const draft = await api.createJobReplayDraft!(jobId);
      if (request === generation.current) setReview(draft);
    } catch (reason) {
      if (request === generation.current) setError(reason instanceof Error ? reason.message : 'The job cannot be reconstructed. Configure a new run from the current package.');
    } finally { if (request === generation.current) { setBusy(false); lock.current = false; } }
  }
  async function confirm() {
    if (lock.current || !review?.available || !review.intentId || !review.definition || !review.draftId) return;
    if (mode === 'schedule') {
      unregister.current?.();
      onSchedule?.({ executionType: review.definition.executionType, packageId: review.definition.packageId,
        environment: review.definition.environment || undefined, draftId: review.draftId, expiresAt: review.expiresAt,
        previewYaml: JSON.stringify(review.definition, null, 2) });
      close(); return;
    }
    lock.current = true; setBusy(true); setError('');
    try {
      const accepted = await api.rerunJob!(jobId, review.intentId);
      unregister.current?.(); close(); onOpenJob(accepted.jobId);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Submission failed. Retry this confirmation to check the same intent.'); }
    finally { setBusy(false); lock.current = false; }
  }
  return <span className="att-replay-actions">
    <button type="button" disabled={busy} onClick={() => void open('run')}>Rerun</button>
    {onSchedule && <button type="button" disabled={busy} onClick={() => void open('schedule')}>Schedule / Reschedule…</button>}
    {mode && <section role="region" aria-labelledby={id} className="att-replay-review">
      <h2 id={id} ref={heading} tabIndex={-1}>{mode === 'run' ? 'Review rerun' : 'Create new schedule from job'} {jobId}</h2>
      <p>Replay the previous safe snapshot. Package changes are rejected; configure a new run to use changed source files.</p>
      {busy && <p role="status">Revalidating job inputs…</p>}
      {error && <p role="alert">{error}</p>}
      {review && !review.available && <p role="status">{review.reason}</p>}
      {review?.sourcePath && <p>Package source: <code>{review.sourcePath}</code></p>}
      {configuration && navigation && <button type="button" disabled={busy} onClick={() => {
        unregister.current?.(); close(); navigation.navigate({ kind: configuration.executionType === 'load' ? 'load' : 'run', packageId: configuration.packageId });
      }}>Configure new run from current package</button>}
      {review?.definition && <pre className="att-code-block">{JSON.stringify(review.definition, null, 2)}</pre>}
      {review?.originatingScheduleId && onEditSchedule && <button type="button" onClick={() => { unregister.current?.(); onEditSchedule(review.originatingScheduleId!); close(); }}>View / Edit originating schedule</button>}
      <button type="button" disabled={busy || !review?.available || !review.intentId} onClick={() => void confirm()}>{mode === 'run' ? 'Confirm rerun' : 'Continue to New Schedule'}</button>
      <button type="button" disabled={busy} onClick={close}>Cancel</button>
    </section>}
  </span>;
}
