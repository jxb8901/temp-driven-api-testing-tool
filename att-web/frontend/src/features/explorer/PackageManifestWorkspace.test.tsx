// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppShellContext } from '../../AppShell';
import { PackageManifestWorkspace } from './PackageManifestWorkspace';
import type { ManifestDraft, ManifestProjection, PackageManifestApi } from './packageManifest';

vi.mock('../code/CodeEditor', () => ({ CodeEditor: ({ value, label, readOnly, onChange }: { value: string; label: string; readOnly?: boolean; onChange?: (text: string) => void }) =>
  <textarea aria-label={label} value={value} readOnly={readOnly} onChange={event => onChange?.(event.target.value)} /> }));
let host: HTMLDivElement;
let root: Root;
beforeEach(() => { host = document.createElement('div');document.body.append(host);root = createRoot(host); });
afterEach(async () => { await act(async () => root.unmount());host.remove(); });
const digest = 'a'.repeat(64);
const authoringText = '# keep comments and ordering\nschemaVersion: att-package/v1\n\ndefaultEnvironment: SIT\n';
function fixture(masked = false): PackageManifestApi {
  const projection: ManifestProjection = { packageId: 'owner', present: true, manifestDigest: digest, canEdit: true,
    environment: 'SIT', environmentOrigin: 'package', declared: { schemaVersion: 'att-package/v1' }, available: true,
    text: 'schemaVersion: att-package/v1\n', redacted: masked,
    fields: { environment: { value: 'SIT', origin: 'package', authoredPath: 'package.yaml' } } };
  const draft: ManifestDraft = { draftId: 'M123', packageId: 'owner', expectedManifestDigest: digest, expiresAt: '2026-10-25T01:00:00Z', state: 'draft' };
  return {
    getPackageManifest: vi.fn(async () => projection),
    getPackageAuthoringSource: vi.fn(async () => {
      if (masked) throw { status: 404 };
      return { available: true, present: true, logicalPath: 'package.yaml', format: 'yaml', text: authoringText,
        sourceEtag: `\"sha256:${digest}\"`, revision: digest };
    }),
    getPackageManifestDraftSource: vi.fn(async (_packageId: string, draftId: string) => ({ draftId, source: authoringText,
      sourceDigest: digest, expectedManifestDigest: digest })),
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
function workflowStep(state: string, label: string): HTMLElement | undefined {
  return [...host.querySelectorAll<HTMLElement>(`[aria-label="Package Manifest publication workflow"] [data-state="${state}"]`)]
    .find(step => step.textContent?.includes(label));
}

describe('Package Manifest publication', () => {
  it('requires a staged, validated, reviewed candidate before publishing and refreshes afterward', async () => {
    const api = fixture();await render(api);
    expect(host.textContent).toContain('Resolved Environment: SIT');expect(host.textContent).toContain('Origin: package');
    await click('Source');
    expect((host.querySelector('textarea') as HTMLTextAreaElement).readOnly).toBe(false);
    await click('Create Manifest draft');
    expect(api.getPackageAuthoringSource).toHaveBeenCalledWith('owner', expect.any(AbortSignal));
    expect(api.stagePackageManifest).toHaveBeenCalledWith('owner', authoringText, digest, expect.any(AbortSignal));
    expect(host.querySelector('[aria-label="Package Manifest candidate diff"]')?.textContent).toContain('package.yaml');
    expect(button('Publish reviewed Manifest').disabled).toBe(true);expect(button('Review validated Manifest').disabled).toBe(true);
    expect(workflowStep('blocked', 'Review candidate')?.textContent).toContain('Validate the draft before reviewing the candidate.');
    await click('Validate Manifest draft');
    expect(button('Publish reviewed Manifest').disabled).toBe(true);
    expect(host.querySelector('[aria-label="Validated Manifest declaration"]')).not.toBeNull();
    expect(workflowStep('complete', 'Validate draft')).toBeTruthy();
    expect(workflowStep('current', 'Review candidate')).toBeTruthy();
    await click('Review validated Manifest');
    expect(button('Publish reviewed Manifest').disabled).toBe(false);
    expect(workflowStep('complete', 'Review candidate')).toBeTruthy();
    expect(workflowStep('current', 'Publish')).toBeTruthy();
    await click('Publish reviewed Manifest');
    expect(host.querySelector('[role="alertdialog"]')?.textContent).toContain('future configuration resolution');
    await click('Publish Manifest');
    expect(api.publishPackageManifest).toHaveBeenCalledTimes(1);expect(host.textContent).toContain('Manifest published');
    expect(host.querySelector('[aria-label="Manifest publication review"]')).toBeNull();
    expect(host.textContent).toContain('Published Manifest revision (advanced)');
  });
  it('retains edited source until explicit discard and registers draft navigation protection', async () => {
    const api=fixture();const release=vi.fn(),register=vi.fn(() => release);
    await act(async () => {root.render(<PackageManifestWorkspace api={api} packageId="owner" registerUnsavedChanges={register} />);});
    await click('Source');const editor=host.querySelector('textarea')!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')!.set!.call(editor,'schemaVersion: att-package/v1\ndefaultEnvironment: UAT\n');
      editor.dispatchEvent(new Event('input',{bubbles:true}));
    });
    expect(button('Effective').disabled).toBe(true);expect(button('Refresh Package Configuration').disabled).toBe(true);
    expect(register).toHaveBeenLastCalledWith(true,'unpublished Package Manifest changes');
    expect(editor.value).toContain('UAT');await click('Discard source changes');
    expect(host.querySelector('[role="alertdialog"]')?.textContent).toContain('Package owner');
    await click('Discard edits');
    expect(editor.value).not.toContain('UAT');expect(button('Effective').disabled).toBe(false);
    await click('Create Manifest draft');expect(register).toHaveBeenLastCalledWith(true,'unpublished Package Manifest changes');
    await click('Discard Manifest draft');expect(host.querySelector('[role="alertdialog"]')?.textContent).toContain('staged Manifest source');
    await click('Discard draft');expect(register).toHaveBeenLastCalledWith(false,'unpublished Package Manifest changes');
  });
  it('allows navigation in a clean source view and locks it only while local edits or a draft exist', async () => {
    const api = fixture();const lock = vi.fn();
    await act(async () => root.render(<AppShellContext.Provider value={{ uiLocale: 'en', setWorkspaceEditorLock: lock } as never}>
      <PackageManifestWorkspace api={api} packageId="owner" />
    </AppShellContext.Provider>));
    expect(lock).toHaveBeenLastCalledWith(false);
    await click('Source');expect(lock).toHaveBeenLastCalledWith(false);
    const editor = host.querySelector('textarea[aria-label="Package Manifest source"]')!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')!.set!.call(editor,'schemaVersion: att-package/v1\ndefaultEnvironment: UAT\n');
      editor.dispatchEvent(new Event('input',{bubbles:true}));
    });
    expect(lock).toHaveBeenLastCalledWith(true);
  });
  it('keeps masked source read only and never stages the projected text', async () => {
    const api = fixture(true);await render(api);await click('Source');
    expect((host.querySelector('textarea') as HTMLTextAreaElement).readOnly).toBe(true);
    expect(button('Create Manifest draft').disabled).toBe(true);
    expect(host.textContent).toContain('read only to preserve protected values');
    const blockedSteps = [...host.querySelectorAll('[aria-label="Package Manifest publication workflow"] [data-state="blocked"]')];
    expect(blockedSteps.filter(step => step.textContent?.includes('Edit package.yaml') || step.textContent?.includes('Stage draft')).length).toBe(2);
    expect(blockedSteps.map(step => step.textContent).join(' ')).toContain('complete authorized Manifest source is unavailable');
    await click('Create Manifest draft');expect(api.stagePackageManifest).not.toHaveBeenCalled();
  });
  it('uses the same lossless authoring source for View and Edit', async () => {
    const api=fixture();await render(api);await click('Source');
    const editor=host.querySelector('textarea') as HTMLTextAreaElement;
    expect(editor.value).toBe(authoringText);expect(editor.readOnly).toBe(false);
    expect(api.getPackageAuthoringSource).toHaveBeenCalledTimes(1);
  });
  it('retains a reviewed draft on revision conflict and requires an explicit discard', async () => {
    const api = fixture();api.publishPackageManifest = vi.fn(async () => { throw { status: 409 }; });
    await render(api);await click('Source');await click('Create Manifest draft');await click('Validate Manifest draft');await click('Review validated Manifest');
    await click('Publish reviewed Manifest');await click('Publish Manifest');expect(host.querySelector('[role="alert"]')?.textContent).toContain('Sources changed');
    expect(host.querySelector('[aria-label="Manifest publication review"]')).not.toBeNull();
    expect(button('Validate Manifest draft').disabled).toBe(true);
    expect(button('Review validated Manifest').disabled).toBe(true);
    expect(button('Publish reviewed Manifest').disabled).toBe(true);
    expect(button('Discard Manifest draft').disabled).toBe(false);
    expect(workflowStep('blocked', 'Publish')?.textContent).toContain('Discard it, refresh, and stage a new candidate.');
    await click('Discard Manifest draft');await click('Discard draft');expect(host.querySelector('[aria-label="Manifest publication review"]')).toBeNull();
    expect(api.publishPackageManifest).toHaveBeenCalledTimes(1);
  });

  it('shows the shared staged-to-published workflow for the Package source', async () => {
    const api = fixture();await render(api);
    const workflow = host.querySelector('[aria-label="Package Manifest publication workflow"]');
    expect(workflow?.textContent).toContain('Edit package.yaml');
    expect(workflow?.textContent).toContain('Stage draft');
    expect(workflow?.textContent).toContain('Validate draft');
    expect(workflow?.textContent).toContain('Review candidate');
    expect(workflow?.textContent).toContain('Status and history');
    await click('Source');
    expect(workflowStep('complete', 'Edit package.yaml')).toBeTruthy();
    expect(workflowStep('current', 'Stage draft')).toBeTruthy();
  });
  it('loads the exact authorized historical candidate before showing its rollback diff', async () => {
    const api = fixture();
    const historical = 'schemaVersion: att-package/v1\ndefaultEnvironment: UAT\n';
    api.getPackageManifestHistory = vi.fn(async () => ({ items: [{ manifestDigest: 'c'.repeat(64), modifiedAt: '2026-10-25T00:00:00Z' }] }));
    api.getPackageManifestDraftSource = vi.fn(async (_packageId: string, draftId: string) => ({ draftId, source: historical,
      sourceDigest: 'd'.repeat(64), expectedManifestDigest: digest }));
    await render(api);await click('Source');await click('Load Manifest history');
    await click('Stage rollback ' + 'c'.repeat(12));
    expect(host.querySelector('[role="alertdialog"]')?.textContent).toContain('must be validated, reviewed, and published');
    await click('Stage restore');
    expect(api.getPackageManifestDraftSource).toHaveBeenCalledWith('owner', 'M123', expect.any(AbortSignal));
    expect((host.querySelector('textarea[aria-label="Candidate Package Manifest source"]') as HTMLTextAreaElement).value).toBe(historical);
    expect(host.querySelector('[aria-label="Package Manifest candidate diff"]')?.textContent).toContain('Base Manifest revision');
  });
});
