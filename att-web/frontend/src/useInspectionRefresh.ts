import { useSyncExternalStore } from 'react';
import { inspectionCache } from './inspectionCache';
let revision = 0;
inspectionCache.subscribe(() => { revision++; });
export function useInspectionRefresh(shared = false) {
  return useSyncExternalStore(inspectionCache.subscribe, () => shared ? inspectionCache.sharedEpoch : revision);
}
