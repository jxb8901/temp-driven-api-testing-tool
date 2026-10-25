// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { PackageManifestWorkspace } from './PackageManifestWorkspace';
import type { ManifestDraft, ManifestProjection, PackageManifestApi } from './packageManifest';

vi.mock('../code/CodeEditor', () => ({ CodeEditor: ({ value, label, readOnly, onChange }: { value: string; label: string; readOnly?: boolean; onChange?: (text: string) => void }) =>
  <textarea aria-label={label} value={value} readOnly={readOnly} onChange={event => onChange?.(event.target.value)} /> }));
let host: HTMLDivElement;
let root: Root;
beforeEach(() => { host = document.createElement('div');document.body.append(host);root = createRoot(host); });
afterEach(async () => { await act(async () => root.unmount());host.remove(); });
const digest = 'a'.repeat(64);
function fixture(masked = false): PackageManifestApi {
  const projection: ManifestProjection = { packageId: 'owner', present: true, manifestDigest: digest, canEdit: true,
    environment: 'SIT', environmentOrigin: 'package', declared: { schemaVersion: 'att-package/v1' }, available: true,
    text: 'schemaVersion: att-package/v1\n', redacted: masked,
    fields: { environment: { value: 'SIT', origin: 'package', authoredPath: 'package.yaml' } } };
  const draft: ManifestDraft = { draftId: 'M123', packageId: 'owner', expectedManifestDigest: digest, expiresAt: '2026-10-25T01:00:00Z', state: 'draft' };
  return {
    getPackageManifest: vi.fn(async () => projection),
    stagePackageManifest: vi.fn(async () => draft),
    manifestDraftAction: vi.fn(async (_packageId: string, _id: string, action: 'validate' | 'review' | 'discard'): Promise<ManifestDraft> => ({ ...draft, state: action === 'review' ? 'reviewed' : 'validated', validation: projection })),
    publishPackageManifest: vi.fn(async () => ({ published: true, manifestDigest: 'b'.repeat(64), auditConfirmed: true })),
    getPackageManifestHistory: vi.fn(async () => ({ items: [] })),
    rollbackPackageManifest: vi.fn(async () => draft),
  };
}
function button(label: string): HTMLButtonElement {
  const found = [...host.querySelectorAll('button')].find(item => item.textContent === label);
  expect(found, `button ${label}`).toBeTruthy();return found!;
}
async function click(label: string) { await act(async () => { button(label).click(); }); }
async function render(api: PackageManifestApi) { await act(async () => { root.render(<PackageManifestWorkspace api={api} packageId="owner" />); }); }

describe('Package Manifest publication', () => {
  it('requires a staged, validated, reviewed candidate before publishing and refreshes afterward', async () => {
    const api = fixture();await render(api);
    expect(host.textContent).toContain('Resolved Environment: SIT');expect(host.textContent).toContain('Origin: package');
    await click('Source');
    expect((host.querySelector('textarea') as HTMLTextAreaElement).readOnly).toBe(false);
    await click('Create Manifest draft');
    expect(api.stagePackageManifest).toHaveBeenCalledWith('owner', 'schemaVersion: att-package/v1\n', digest, expect.any(AbortSignal));
    expect(button('Publish reviewed Manifest').disabled).toBe(true);expect(button('Review validated Manifest').disabled).toBe(true);
    await click('Validate Manifest draft');
    expect(button('Publish reviewed Manifest').disabled).toBe(true);
    expect(host.querySelector('[aria-label="Validated Manifest declaration"]')).not.toBeNull();
    await click('Review validated Manifest');
    expect(button('Publish reviewed Manifest').disabled).toBe(false);
    await click('Publish reviewed Manifest');
    expect(api.publishPackageManifest).toHaveBeenCalledTimes(1);expect(host.textContent).toContain('Manifest published');
    expect(host.querySelector('[aria-label="Manifest publication review"]')).toBeNull();
  });
  it('keeps masked source read only and never stages the projected text', async () => {
    const api = fixture(true);await render(api);await click('Source');
    expect((host.querySelector('textarea') as HTMLTextAreaElement).readOnly).toBe(true);
    expect(button('Create Manifest draft').disabled).toBe(true);
    expect(host.textContent).toContain('read only to preserve protected values');
    await click('Create Manifest draft');expect(api.stagePackageManifest).not.toHaveBeenCalled();
  });
  it('retains a reviewed draft on revision conflict and requires an explicit discard', async () => {
    const api = fixture();api.publishPackageManifest = vi.fn(async () => { throw { status: 409 }; });
    await render(api);await click('Source');await click('Create Manifest draft');await click('Validate Manifest draft');await click('Review validated Manifest');
    await click('Publish reviewed Manifest');expect(host.querySelector('[role="alert"]')?.textContent).toContain('Sources changed');
    expect(host.querySelector('[aria-label="Manifest publication review"]')).not.toBeNull();
    await click('Discard Manifest draft');expect(host.querySelector('[aria-label="Manifest publication review"]')).toBeNull();
    expect(api.publishPackageManifest).toHaveBeenCalledTimes(1);
  });
});
