export interface LoadWorkloadIdentity {
  model: string;
  users?: number;
  arrivalRate?: number | string;
  maxConcurrent?: number;
  duration?: string;
  totalDurationMs?: number;
  warmup?: string;
  rampUp?: string;
  rampDown?: string;
}

const DURATION_FACTORS: Record<string, number> = { ms: 1, s: 1000, m: 60_000, h: 3_600_000 };
export function parseLoadDuration(value: string): number | null {
  const match = /^(\d+)(ms|s|m|h)$/.exec(value.trim());
  if (!match) return null;
  const milliseconds = Number(match[1]) * DURATION_FACTORS[match[2]!];
  return Number.isFinite(milliseconds) && milliseconds <= Number.MAX_SAFE_INTEGER ? milliseconds : null;
}

export function semanticLoadDurationMs(value: unknown): number | null {
  if (value == null) return 0;
  if (typeof value === 'number') return Number.isSafeInteger(value) && value >= 0 ? value : null;
  if (typeof value !== 'string') return null;
  if (/^\d+$/.test(value.trim())) {
    const milliseconds = Number(value.trim());
    return Number.isSafeInteger(milliseconds) ? milliseconds : null;
  }
  return parseLoadDuration(value);
}

export function sameLoadTiming(left: unknown, right: unknown): boolean {
  const leftMs = semanticLoadDurationMs(left);
  const rightMs = semanticLoadDurationMs(right);
  return leftMs != null && rightMs != null && leftMs === rightMs;
}

export function effectiveLoadEnvelopeMs(phases: Array<string | undefined>): number | null {
  let total = 0;
  for (const phase of phases) {
    if (!phase?.trim()) continue;
    const value = parseLoadDuration(phase);
    if (value == null || !Number.isFinite(total + value) || total + value > Number.MAX_SAFE_INTEGER) return null;
    total += value;
  }
  return total;
}

export function workloadPacing(workload: LoadWorkloadIdentity): string {
  const target = workload.arrivalRate != null
    ? `Configured target rate ${workload.arrivalRate}`
    : workload.users != null ? `${workload.users} VUs` : 'Pacing not available';
  return workload.maxConcurrent == null ? target : `${target} · max concurrent ${workload.maxConcurrent}`;
}

export function plannedWorkloadDuration(milliseconds?: number): string {
  if (milliseconds == null || !Number.isFinite(milliseconds) || milliseconds < 0) return 'Not available';
  let seconds = Math.floor(milliseconds / 1000);
  const days = Math.floor(seconds / 86400); seconds %= 86400;
  const hours = Math.floor(seconds / 3600); seconds %= 3600;
  const minutes = Math.floor(seconds / 60); seconds %= 60;
  const parts = [days && `${days}d`, hours && `${hours}h`, minutes && `${minutes}m`, seconds && `${seconds}s`].filter(Boolean);
  return parts.length ? parts.join(' ') : '0s';
}

export function workloadPhaseSummary(workload: LoadWorkloadIdentity): string {
  return [
    workload.rampUp != null && `Ramp-up ${workload.rampUp}`,
    workload.warmup != null && `Warmup ${workload.warmup}`,
    `Steady ${workload.duration || 'Not available'}`,
    workload.rampDown != null && `Ramp-down ${workload.rampDown}`,
  ].filter((value): value is string => typeof value === 'string').join(' · ');
}
