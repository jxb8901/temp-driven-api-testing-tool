import { useSyncExternalStore } from 'react';
import { inspectionCache } from './inspectionCache';
let revision = 0;
inspectionCache.subscribe(() => { revision++; });
export function useInspectionRefresh() {
  return useSyncExternalStore(inspectionCache.subscribe, () => revision);
}
