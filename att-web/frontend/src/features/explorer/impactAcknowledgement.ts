import type { SourceImpactAcknowledgement, SourceImpactReport, SourceRevisionIdentity } from './sourceManagement';

/** Matches #335 ConfigImpactAnalyzer's att-config-impact-ack/v1 canonical representation. */
type Freshness = NonNullable<SourceImpactReport['freshness']>;

function identity(value: SourceRevisionIdentity): string {
  return [value.kind, value.scopeId, value.qualifier ?? 'null', value.revision].join('\t');
}

export function canonicalImpactFreshness(value: Freshness): string {
  const lines = [
    'att-config-impact-ack/v1', value.draftId, value.draftFingerprint,
    identity(value.baseConfig), identity(value.candidateConfig), identity(value.packageRegistry),
    identity(value.engineSchema),
    ...value.packageSources.map(identity).sort(),
    ...value.manifests.map(identity).sort(),
    ...value.scheduleSnapshots.map(identity).sort(),
  ];
  return lines.join('\n') + '\n';
}

async function sha256Hex(value: string): Promise<string> {
  if (!globalThis.crypto?.subtle) throw new Error('Secure Web Crypto is required for Config impact acknowledgement.');
  const digest = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('');
}

/** Server independently validates actor, freshness, decision, approval and ADMIN permission. */
export async function createImpactAcknowledgement(freshness: Freshness, actor: string): Promise<SourceImpactAcknowledgement> {
  const normalizedActor = actor.trim();
  if (!normalizedActor || normalizedActor.length > 160) throw new Error('Enter the exact authenticated administrator ID before publishing.');
  const freshnessFingerprint = await sha256Hex(canonicalImpactFreshness(freshness));
  return {
    freshnessFingerprint,
    actor: normalizedActor,
    acknowledgementDigest: await sha256Hex('att-config-impact-acknowledgement/v1\n' + freshnessFingerprint + '\n' + normalizedActor),
  };
}
