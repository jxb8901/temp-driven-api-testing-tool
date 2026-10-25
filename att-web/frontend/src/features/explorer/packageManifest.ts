import type { JsonValue } from './types';

export type ManifestView = 'source' | 'declared' | 'effective' | 'compare';
export interface ManifestField { value: JsonValue; origin: string; authoredPath: string; masked?: boolean; state?: string }
export interface ManifestProjection {
  packageId: string;
  manifestDigest: string;
  present: boolean;
  canEdit: boolean;
  environment?: string;
  environmentOrigin?: string;
  configurationIdentity?: string;
  declared?: JsonValue;
  fields?: Record<string, ManifestField>;
  globalFields?: Record<string, ManifestField>;
  available?: boolean;
  text?: string;
  redacted?: boolean;
  reason?: string;
  diagnostics?: string[];
}
export interface ManifestDraft {
  draftId: string;
  packageId: string;
  expectedManifestDigest: string;
  expiresAt: string;
  state: 'draft' | 'validated' | 'reviewed';
  validation?: ManifestProjection;
  resetToEmptyManifest?: boolean;
}
export interface ManifestHistory { manifestDigest: string; modifiedAt: string }
export interface PackageManifestApi {
  getPackageManifest(packageId: string, view: ManifestView, environment?: string, signal?: AbortSignal): Promise<ManifestProjection>;
  stagePackageManifest(packageId: string, source: string, expectedManifestDigest: string, signal?: AbortSignal): Promise<ManifestDraft>;
  manifestDraftAction(packageId: string, draftId: string, action: 'validate' | 'review' | 'discard', signal?: AbortSignal): Promise<ManifestDraft>;
  publishPackageManifest(packageId: string, draftId: string, signal?: AbortSignal): Promise<{ published: boolean; manifestDigest: string; auditConfirmed?: boolean }>;
  getPackageManifestHistory(packageId: string, signal?: AbortSignal): Promise<{ items: ManifestHistory[] }>;
  rollbackPackageManifest(packageId: string, revision: string, expectedManifestDigest: string, signal?: AbortSignal): Promise<ManifestDraft>;
}
