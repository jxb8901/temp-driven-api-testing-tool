import { StatusBadge } from './StatusBadge';
import type { ArtifactArchiveMetrics } from './types';

const fields: Array<[keyof ArtifactArchiveMetrics, string]> = [
  ['queueWaitMs', 'Queue wait (ms)'], ['publicationWallMs', 'Writer wall time, including cleanup (ms)'],
  ['scanClassificationRedactionMs', 'Scan / classification / redaction (ms)'], ['zipWriteMs', 'ZIP compression / write (ms)'],
  ['verificationFsyncMs', 'Verification / file fsync (ms)'], ['cleanupMs', 'Private / staging cleanup (ms)'],
  ['writerCpuMs', 'Server writer-thread CPU (ms)'], ['writerAllocatedBytes', 'Server writer-thread allocated bytes (not retained heap / peak)'],
  ['sourceFiles', 'Source files'], ['sourceBytes', 'Source bytes'], ['includedEntries', 'Included entries'],
  ['omittedEntries', 'Omitted entries'], ['includedBytes', 'Included uncompressed bytes'], ['zipBytes', 'ZIP bytes'],
  ['compressionRatio', 'Included bytes / ZIP bytes'], ['attempts', 'Attempts'], ['retries', 'Retries'],
];

function metricValue(key: keyof ArtifactArchiveMetrics, value: unknown): string {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return 'Unavailable';
  if (String(key).endsWith('Bytes')) {
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const index = value === 0 ? 0 : Math.max(0, Math.min(4, Math.floor(Math.log(value) / Math.log(1024))));
    return `${(value / 1024 ** index).toLocaleString(undefined, { maximumFractionDigits: 2 })} ${units[index]}`;
  }
  return value.toLocaleString(undefined, { maximumFractionDigits: 3 });
}
const groups: Array<[string, typeof fields]> = [
  ['Timing', fields.slice(0, 6)], ['CPU / memory allocation', fields.slice(6, 8)],
  ['Files / size / compression', fields.slice(8, 15)], ['Retries / outcome', fields.slice(15)],
];

export function ArchiveMetrics({ value }: { value?: ArtifactArchiveMetrics | null }) {
  return <section className="att-metric-section att-archive-metrics" aria-label="Artifact archive metrics">
    <h3>Artifact archive</h3>
    <p className="att-archive-metrics-help">Server archive work only, separate from Worker execution. Stage timings describe the latest attempt; attempt counts persist across restarts. Allocated bytes are not retained or peak heap. Unavailable measurements are not zero.</p>
    {!value ? <p>Archive metrics unavailable.</p> : <>
      <p>Outcome: <StatusBadge status={value.outcome} tone={value.outcome === 'READY' ? 'pass' : value.outcome === 'FAILED' ? 'error' : undefined} /></p>
      {value.failureReason && <p className="att-archive-metrics-help">{value.failurePhase}: {value.failureReason}</p>}
      <div className="att-table-scroll"><table className="att-data-table"><caption>Artifact archive publication metrics</caption><thead><tr><th scope="col">Metric</th><th scope="col">Value</th></tr></thead>
        {groups.map(([title, rows]) => <tbody key={title}>
          <tr className="att-archive-metric-group"><th scope="rowgroup" colSpan={2}>{title}</th></tr>
          {rows.map(([key, label]) => <tr key={key}><th scope="row">{label}</th><td title={String(key).endsWith('Bytes') && typeof value[key] === 'number' ? `${value[key]} bytes` : undefined}>{metricValue(key, value[key])}</td></tr>)}
        </tbody>)}
      </table></div>
    </>}
  </section>;
}
