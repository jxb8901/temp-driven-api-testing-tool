import { useEffect, useState } from 'react';
import { api } from '../../api';

/** Resolve the current selection through the same Engine projection used by draft validation. */
export function EnvironmentResolutionNotice({ packageId, environment = '' }: { packageId: string; environment?: string }) {
  const key = `${packageId}:${environment}`;
  const [result, setResult] = useState<{ key: string; environment?: string; origin?: string; failed?: boolean }>();
  useEffect(() => {
    if (!packageId) return;
    const controller = new AbortController();
    Promise.resolve().then(() => api.getPackageManifest(packageId, 'effective', environment || undefined, controller.signal))
      .then(value => {
        if (!controller.signal.aborted) setResult({ key, environment: value.environment, origin: value.environmentOrigin, failed: !value.environment });
      }).catch(() => { if (!controller.signal.aborted) setResult({ key, failed: true }); });
    return () => controller.abort();
  }, [key, packageId, environment]);
  if (!packageId) return null;
  if (result?.key !== key) return <p className="att-help" role="status">Resolving Environment…</p>;
  if (result.failed) return <p className="att-help">Environment could not be resolved. Validate the execution to check its configuration.</p>;
  const origins: Record<string, string> = { request: 'Request', context: 'CLI Context', package: 'Package Manifest', global: 'Global configuration', 'runtime-default': 'Runtime default' };
  return <p className="att-help" role="status">Resolved Environment: <strong>{result.environment}</strong> · Origin: {origins[result.origin || ''] || result.origin || 'Unavailable'}</p>;
}
