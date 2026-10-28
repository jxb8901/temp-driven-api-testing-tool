import { useContext, useEffect, useId, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { NavigationGuardContext } from '../../router';
import type { ValidatedScheduleIntent } from '../schedules/types';
import type { JobReplay, LoadJobsApi } from './types';

function snapshotTargets(definition: NonNullable<JobReplay['definition']>): string {
  const targets = new Set<string>();
  function add(value: unknown) { if (value && typeof value === 'object' && 'type' in value && 'id' in value) targets.add(`${String(value.type)} · ${String(value.id)}`); }
  add(definition.target);
  const selection = definition.selection as {caseIds?: unknown[]} | undefined;
  if (selection?.caseIds?.length) return `Cases · ${selection.caseIds.length} selected`;
  const scenario = definition.scenario as {workloads?: Array<{target?: unknown; mix?: Array<{target?: unknown}>}>} | undefined;
  for (const workload of scenario?.workloads || []) { add(workload.target); for (const entry of workload.mix || []) add(entry.target); }
  const shown = [...targets].slice(0, 4).join(', ');
  return shown ? shown + (targets.size > 4 ? ` (+${targets.size - 4} more)` : '') : 'Not available';
}

function durationMillis(value: unknown): number | null {
  if (typeof value !== 'string') return null;
  const match = /^(\d+)(ms|s|m|h)$/.exec(value.trim());
  if (!match) return null;
  const unit = match[2] === 'ms' ? 1 : match[2] === 's' ? 1000 : match[2] === 'm' ? 60000 : 3600000;
  return Number(match[1]) * unit;
}

function effectiveDuration(definition: NonNullable<JobReplay['definition']> | undefined): string {
  if (!definition || definition.executionType !== 'load') return 'Not applicable';
  const scenario = definition.scenario as { load?: Record<string, unknown>; workloads?: Array<{ load?: Record<string, unknown> }> } | undefined;
  let total = 0;
  for (const phase of ['rampUp', 'warmup', 'duration', 'rampDown']) {
    const root = durationMillis(scenario?.load?.[phase]);
    const values = (scenario?.workloads || []).map(row => durationMillis(row.load?.[phase])).filter((value): value is number => value !== null);
    total += root ?? (values.length ? Math.max(...values) : 0);
  }
  if (!total) return 'Unavailable';
  const hours=Math.floor(total/3600000),minutes=Math.floor((total%3600000)/60000),seconds=Math.floor((total%60000)/1000),milliseconds=total%1000;
  return [hours?`${hours}h`:null,minutes?`${minutes}m`:null,seconds?`${seconds}s`:null,milliseconds?`${milliseconds}ms`:null].filter(Boolean).join(' ');
}

function arrivalRates(definition: NonNullable<JobReplay['definition']> | undefined): string {
  if (!definition || definition.executionType !== 'load') return 'Not applicable';
  const scenario = definition.scenario as { load?: { arrivalRate?: unknown }; workloads?: Array<{ id?: string; load?: { arrivalRate?: unknown } }> } | undefined;
  const values = (scenario?.workloads || []).map((row, index) => {
    const rate = row.load?.arrivalRate ?? scenario?.load?.arrivalRate;
    return rate == null ? null : `${row.id || `workload ${index + 1}`}: ${String(rate)}`;
  }).filter((value): value is string => value !== null);
  return values.length ? values.join(', ') : 'Virtual Users model; no target arrival rate configured';
}

type ReplayApi = Pick<LoadJobsApi, 'getJobReplay' | 'createJobReplayDraft' | 'getJobReplayEditorDraft' | 'rerunJob'>;

/** Shared by Details, Jobs and LOAD history. Opening a review never submits a job. */
export function JobReplayActions({ api, jobId, onOpenJob, onSchedule, onEditSchedule }: {
  api: ReplayApi; jobId: string; onOpenJob(id: string): void;
  onSchedule?(intent: ValidatedScheduleIntent): void;
  onEditSchedule?(id: string): void;
  configuration?: { executionType: string; packageId: string };
}) {
  const [review, setReview] = useState<JobReplay | null>(null);
  const [mode, setMode] = useState<'run' | 'schedule' | null>(null);
  const [sourcePolicy, setSourcePolicy] = useState<'current' | 'strict'>('current');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const lock = useRef(false);
  const generation = useRef(0);
  const dialog = useRef<HTMLDialogElement>(null);
  const heading = useRef<HTMLHeadingElement>(null);
  const opener = useRef<HTMLElement | null>(null);
  const unregister = useRef<(() => void) | undefined>(undefined);
  const navigation = useContext(NavigationGuardContext);
  const registerUnsavedChanges = navigation?.registerUnsavedChanges;
  const id = useId();
  useEffect(() => {
    if (!mode) return;
    const element = dialog.current;
    if (element && !element.open) { if (element.showModal) element.showModal(); else element.setAttribute('open', ''); }
    heading.current?.focus();
    unregister.current = registerUnsavedChanges?.(true, 'A job replay review is open. Leave without creating a job or schedule?');
    return () => { element?.close?.(); unregister.current?.(); unregister.current = undefined; };
  }, [mode, registerUnsavedChanges]);
  useEffect(() => () => { generation.current++; }, []);
  if (!api.getJobReplay || !api.createJobReplayDraft || !api.rerunJob) return null;

  function close() {
    dialog.current?.close?.();
    generation.current++; setMode(null); setReview(null); setError(''); setBusy(false); lock.current = false;
    unregister.current?.(); opener.current?.focus();
  }
  async function open(next: 'run' | 'schedule') {
    if (lock.current) return;
    lock.current = true; opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const request = ++generation.current;
    setMode(next); setBusy(true); setReview(null); setError(''); setSourcePolicy(next === 'schedule' ? 'strict' : 'current');
    try {
      const metadata = await api.getJobReplay!(jobId);
      if (request !== generation.current) return;
      if (!metadata.available) { setReview(metadata); return; }
      const policy = next === 'schedule' ? 'strict' : 'current';
      const draft = await api.createJobReplayDraft!(jobId, { sourcePolicy: policy });
      if (request !== generation.current) return;
      setReview(draft);
    } catch (reason) {
      if (request === generation.current) setError(reason instanceof Error ? reason.message : 'The job cannot be reconstructed. Configure a new run from the current package.');
    } finally { if (request === generation.current) { setBusy(false); lock.current = false; } }
  }
  async function reviewWithPolicy(policy: 'current' | 'strict') {
    if (lock.current) return;
    lock.current = true;setBusy(true);setError('');setSourcePolicy(policy);
    const request=++generation.current;
    try {const draft=await api.createJobReplayDraft!(jobId,{sourcePolicy:policy});if(request===generation.current)setReview(draft);}
    catch(reason){if(request===generation.current){setReview(null);setError(reason instanceof Error?reason.message:'The Package could not be validated for this replay.');}}
    finally{if(request===generation.current){setBusy(false);lock.current=false;}}
  }
  async function configure() {
    if(lock.current||!api.getJobReplayEditorDraft||!navigation)return;
    lock.current=true;setMode('run');setSourcePolicy('current');setReview(null);setBusy(true);setError('');
    try {
      const metadata=await api.getJobReplay!(jobId);
      if(!metadata.available)throw new Error(metadata.reason||'This Job has no safely retained execution definition.');
      const draft=await api.createJobReplayDraft!(jobId,{sourcePolicy:'current'});
      if(!draft.draftRef||!draft.definition)throw new Error('The Server did not return an editable replay draft reference.');
      const environment=draft.configuration?.environmentOrigin==='package'?undefined:draft.definition.environment||undefined;
      const route=draft.definition.executionType==='load'
        ?{kind:'load' as const,packageId:draft.definition.packageId,environment,replayDraftId:draft.draftRef,replaySourceJobId:jobId}
        :{kind:'run' as const,packageId:draft.definition.packageId,environment,replayDraftId:draft.draftRef,replaySourceJobId:jobId};
      unregister.current?.();navigation.navigate(route);
    } catch(reason){setError(reason instanceof Error?reason.message:'The replay draft could not be opened.');}
    finally{setBusy(false);lock.current=false;}
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
      const accepted = await api.rerunJob!(jobId, review.intentId, sourcePolicy);
      unregister.current?.(); close(); onOpenJob(accepted.jobId);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Submission failed. Retry this confirmation to check the same intent.'); }
    finally { setBusy(false); lock.current = false; }
  }
  return <span className="att-replay-actions">
    <button type="button" disabled={busy} onClick={() => void open('run')}>Rerun</button>
    {onSchedule && <button type="button" disabled={busy} onClick={() => void open('schedule')}>Schedule / Reschedule…</button>}
    {mode && createPortal(<dialog ref={dialog} aria-labelledby={id} aria-describedby={`${id}-description`} className="att-replay-review" onCancel={event => { event.preventDefault(); if (!busy) close(); }} onKeyDown={event => {
      if (event.key !== 'Tab') return;
      const items = [...event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled), summary, [href], input:not(:disabled), select:not(:disabled), [tabindex="0"]')].filter(item => !item.closest('details:not([open])') || item.tagName === 'SUMMARY');
      const first = items[0], last = items[items.length - 1];
      if (!first) { event.preventDefault(); heading.current?.focus(); }
      else if (event.shiftKey && (document.activeElement === first || document.activeElement === heading.current)) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }}>
      <h2 id={id} ref={heading} tabIndex={-1}>{mode === 'run' ? 'Review rerun' : 'Create new schedule from job'} {jobId}</h2>
      <p id={`${id}-description`}>Review the retained settings against the selected Package revision. Schedule package-change policy does not control manual Rerun.</p>
      {busy && <p role="status">Revalidating job inputs…</p>}
      {error && <p role="alert">{error}</p>}
      {mode === 'run' && <fieldset disabled={busy}><legend>Package revision</legend>
        <label><input type="radio" name={`${id}-source-policy`} checked={sourcePolicy === 'current'} onChange={() => setSourcePolicy('current')} />Use current Package</label>
        <label><input type="radio" name={`${id}-source-policy`} checked={sourcePolicy === 'strict'} onChange={() => setSourcePolicy('strict')} />Require original revision (strict)<small>Runs only when the current Package matches the recorded revision; metadata cannot restore missing source files.</small></label>
        <button type="button" disabled={busy || (review?.sourcePolicy === sourcePolicy)} onClick={() => void reviewWithPolicy(sourcePolicy)}>Review selected revision</button>
      </fieldset>}
      {mode === 'run' && sourcePolicy === 'strict' && error && <button type="button" disabled={busy} onClick={() => void reviewWithPolicy('current')}>Review with current Package</button>}
      {review && !review.available && <p role="status">{review.reason}</p>}
      {review?.missingFields?.length ? <p role="alert">Some retained fields are missing: {review.missingFields.join(', ')}. Choose Configure &amp; Rerun to enter them before execution.</p> : null}
      {review?.definition?.executionType === 'load' && <p role="note">LOAD phase order is Ramp-up → Warm-up → Steady → Ramp-down. Rerunning a saved LOAD configuration uses this order; the original job history is unchanged.</p>}
      {review?.definition && <dl className="att-replay-summary">
        <div><dt>Type</dt><dd>{review.definition.executionType.toUpperCase()}</dd></div>
        <div><dt>Package</dt><dd>{review.definition.packageId}</dd></div>
        <div><dt>Target</dt><dd>{snapshotTargets(review.definition)}</dd></div>
        <div><dt>Environment</dt><dd>{review.definition.environment || 'Package default'}</dd></div>
        <div><dt>Source</dt><dd>{review.sourcePath || 'Retained snapshot'}</dd></div>
        <div><dt>Original revision</dt><dd><code>{review.originalRevision || review.revision || 'Not available'}</code></dd></div>
        <div><dt>Current revision</dt><dd><code>{review.currentRevision || review.revision || 'Not available'}</code></dd></div>
        <div><dt>Original configuration</dt><dd>{String(review.originalConfiguration?.environment || review.definition.environment || 'Package default recorded for the Job')}</dd></div>
        <div><dt>Current configuration</dt><dd>{String(review.configuration?.environment || review.definition.environment || 'Package default resolved now')}</dd></div>
      </dl>}
      {mode === 'run' && review?.originalDefinition && review.definition && <section className="att-replay-diff" aria-label="Before and after replay changes">
        <h3>Before → after</h3>
        <dl>
          <div><dt>Target / selection</dt><dd>{snapshotTargets(review.originalDefinition)} → {snapshotTargets(review.definition)}</dd></div>
          <div><dt>Effective total duration</dt><dd>{effectiveDuration(review.originalDefinition)} → {effectiveDuration(review.definition)}</dd></div>
          <div><dt>Configured target arrival rate</dt><dd>{arrivalRates(review.originalDefinition)} → {arrivalRates(review.definition)}</dd></div>
          <div><dt>Measured TPS</dt><dd>Observed completed throughput from the original Job report; it is not used as the configured arrival rate.</dd></div>
        </dl>
        <details className="att-replay-advanced"><summary>View sanitized before and after definitions</summary><pre className="att-code-block" tabIndex={0} aria-label="Sanitized before and after execution definitions">{JSON.stringify({ before: review.originalDefinition, after: review.definition }, null, 2)}</pre></details>
      </section>}
      {review?.diagnostics?.length ? <ul className="att-replay-diagnostics" aria-label="Current Package validation diagnostics">{review.diagnostics.map((item,index)=><li key={`${item.code||item.field||'diagnostic'}-${index}`}>{[item.field||item.path,item.code].filter(Boolean).join(' · ')}{item.summary||item.message?`: ${item.summary||item.message}`:''}</li>)}</ul> : null}
      {review?.validationState === 'invalid' && <p role="alert">The retained definition is loaded for editing. Resolve these current Package errors and validate again before running.</p>}
      {review?.definition && <details className="att-replay-advanced"><summary>Advanced / View raw definition</summary><pre className="att-code-block" tabIndex={0} aria-label="Sanitized execution definition">{JSON.stringify(review.definition, null, 2)}</pre></details>}
      {review?.originatingScheduleId && onEditSchedule && <button type="button" onClick={() => { unregister.current?.(); onEditSchedule(review.originatingScheduleId!); close(); }}>View / Edit originating schedule</button>}
      {mode === 'run' && review?.available && review.definition && <button type="button" disabled={busy} onClick={() => void configure()}>Configure &amp; Rerun</button>}
      <footer><button type="button" disabled={busy || !review?.available || !review.intentId || Boolean(review?.missingFields?.length) || (mode === 'run' && review.sourcePolicy !== sourcePolicy)} onClick={() => void confirm()}>{mode === 'run' ? 'Rerun with same settings' : 'Continue to New Schedule'}</button>
      <button type="button" disabled={busy} onClick={close}>Cancel</button></footer>
    </dialog>, document.body)}
  </span>;
}
