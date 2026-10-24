import type { JobSummary } from './types';
export function JobStateIcons({ job, busy, onPin, onDelete }: { job: JobSummary; busy: boolean; onPin?: () => void; onDelete?: () => void }) {
  const terminal = !['QUEUED', 'PREPARING', 'RUNNING', 'CANCEL_REQUESTED'].includes(job.status);
  const pending = job.deletionState === 'DELETE_REQUESTED', claimed = job.deletionState === 'DELETING';
  return <span className="att-job-state-icons">
    {onPin && <button type="button" className="att-state-icon" aria-label={`${job.pinned ? 'Unpin' : 'Pin'} ${job.jobId}`} title={pending ? 'Unmark pending deletion before pinning' : job.pinned ? 'Unpin Job' : 'Pin Job'} aria-pressed={!!job.pinned} disabled={busy || !terminal || pending || claimed} onClick={onPin}>
      <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" fill={job.pinned ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.7"><path d="m8 3 8 0-1 6 4 4v2h-6v6l-2-3v-3H5v-2l4-4z" /></svg>
    </button>}
    {onDelete && <button type="button" className="att-state-icon att-delete-icon" aria-label={`${pending ? 'Unmark pending deletion for' : claimed ? 'Cleanup started for' : 'Mark for deletion'} ${job.jobId}`} title={job.pinned ? 'Unpin before deletion' : pending ? 'Cancel pending deletion before cleanup starts' : claimed ? 'Cleanup started; cannot undo' : 'Mark for background deletion'} {...(!claimed ? {'aria-pressed': pending} : {})} disabled={busy || !terminal || !!job.pinned || claimed} onClick={onDelete}>
      <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.7"><path d="M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 9v8m4-8v8" />{pending && <path d="m8 12 3 3 5-5" strokeWidth="2.8" />}</svg>{pending && <span className="att-pending-label">Pending</span>}
    </button>}
  </span>;
}
