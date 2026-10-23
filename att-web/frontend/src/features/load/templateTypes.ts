export type TemplateMode = 'single-vu' | 'single-tps' | 'single-workload-mix' | 'multi-vu' | 'multi-tps' | 'multi-workload-mix';
export interface LoadTemplateDefinition {
  schemaVersion: 'att-load-template/v1'; name: string; mode: TemplateMode;
  policy: Record<string, unknown>; workloads: Array<Record<string, unknown> & { id: string; mix?: Array<{ id: string; weight: number }> }>;
}
export interface LoadTemplateItem {
  id: string; name: string; mode?: TemplateMode; builtin: boolean; validation: string; schemaVersion?: string; definition?: LoadTemplateDefinition;
}
export interface LoadTemplatePage { items: LoadTemplateItem[]; total: number; nextOffset?: number | null; revisionDigest: string; limits?: { maxWorkloads: number; maxTargets: number; maxTotalUsers: number; maxAggregateArrivalRatePerSecond: number; maxConcurrentPerWorkload: number; maxTotalConcurrent: number; maxDurationSeconds: number }; truncated?: boolean }
