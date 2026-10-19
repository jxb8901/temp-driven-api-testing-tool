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

export function ArchiveMetrics({ value }: { value?: ArtifactArchiveMetrics | null }) {
  return <section aria-label="Artifact archive metrics">
    <h3>Artifact archive</h3>
    <p>Server archive work only, separate from Worker execution. Stage timings describe the latest attempt; attempt counts persist across restarts. Unavailable measurements are not zero.</p>
    {!value ? <p>Archive metrics unavailable.</p> : <>
      <p>Outcome: {value.outcome}{value.failureReason && `; ${value.failurePhase}: ${value.failureReason}`}</p>
      <table><caption>Artifact archive publication metrics</caption><thead><tr><th>Metric</th><th>Value</th></tr></thead><tbody>
        {fields.map(([key, label]) => <tr key={key}><th scope="row">{label}</th><td>{typeof value[key] === 'number' && Number.isFinite(value[key]) && Number(value[key]) >= 0 ? Number(value[key]).toLocaleString(undefined, { maximumFractionDigits: 3 }) : 'Unavailable'}</td></tr>)}
      </tbody></table>
    </>}
  </section>;
}
