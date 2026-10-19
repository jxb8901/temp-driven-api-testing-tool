export function StatusBadge({ status, tone = status }: { status: string; tone?: string }) {
  return <span className={`att-status-badge att-status-${tone.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`}>{status}</span>;
}
