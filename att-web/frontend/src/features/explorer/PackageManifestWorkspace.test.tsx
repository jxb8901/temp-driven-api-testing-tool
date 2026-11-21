// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppShellContext } from '../../AppShell';
import { PackageManifestWorkspace } from './PackageManifestWorkspace';
import type { ManifestDraft, ManifestProjection, ManifestView, PackageManifestApi } from './packageManifest';

vi.mock('../code/CodeEditor', () => ({ CodeEditor: ({ value, label, readOnly, onChange }: { value: string; label: string; readOnly?: boolean; onChange?: (text: string) => void }) =>
  <textarea aria-label={label} value={value} readOnly={readOnly} onChange={event => onChange?.(event.target.value)} /> }));
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
let host: HTMLDivElement;
let root: Root;
beforeEach(() => { host = document.createElement('div');document.body.append(host);root = createRoot(host); });
afterEach(async () => { await act(async () => root.unmount());host.remove(); });
const digest = 'a'.repeat(64);
const authoringText = '# keep comments and ordering\nschemaVersion: att-package/v1\n\ndefaultEnvironment: SIT\n';
function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  const promise = new Promise<T>(resolvePromise => { resolve = resolvePromise; });
  return { promise, resolve };
}
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
  it('allows navigation while a clean Manifest projection read is pending', async () => {
    const api=fixture();const pending=deferred<ManifestProjection>();
    api.getPackageManifest=vi.fn(async () => pending.promise);
    const register=vi.fn(() => () => {});
    await act(async () => {root.render(<PackageManifestWorkspace api={api} packageId="owner" registerUnsavedChanges={register} />);});
    expect(register).toHaveBeenCalledWith(false,'unpublished Package Manifest changes');
    expect(register).not.toHaveBeenCalledWith(true,'unpublished Package Manifest changes',expect.any(Function));
  });
  it('blocks navigation while Manifest staging is in flight, then offers draft discard', async () => {
    const api=fixture();const pending=deferred<ManifestDraft>();
    api.stagePackageManifest=vi.fn(() => pending.promise);
    let confirmDiscard: (() => boolean | Promise<boolean>) | undefined;
    const register=vi.fn((_dirty: boolean,_description?: string,onConfirmDiscard?: () => boolean | Promise<boolean>) => {
      confirmDiscard=onConfirmDiscard;return () => {};
    });
    await act(async () => {root.render(<PackageManifestWorkspace api={api} packageId="owner" registerUnsavedChanges={register} />);});
    await click('Source');
    await click('Create Manifest draft');
    expect(api.stagePackageManifest).toHaveBeenCalledOnce();
    expect(register).toHaveBeenLastCalledWith(true,'unpublished Package Manifest changes',expect.any(Function));
    let allowed=true;
    await act(async () => {allowed=await confirmDiscard?.() ?? true;});
    expect(allowed).toBe(false);
    await act(async () => {pending.resolve({draftId:'M123',packageId:'owner',expectedManifestDigest:digest,
      expiresAt:'2026-10-25T01:00:00Z',state:'draft'});await pending.promise;});
    expect(host.querySelector('[aria-label="Manifest publication review"]')).not.toBeNull();
  });
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
    expect(register).toHaveBeenLastCalledWith(true,'unpublished Package Manifest changes',expect.any(Function));
    expect(editor.value).toContain('UAT');await click('Discard source changes');
    expect(host.querySelector('[role="alertdialog"]')?.textContent).toContain('Package owner');
    await click('Discard edits');
    expect(editor.value).not.toContain('UAT');expect(button('Effective').disabled).toBe(false);
    await click('Create Manifest draft');expect(register).toHaveBeenLastCalledWith(true,'unpublished Package Manifest changes',expect.any(Function));
    await click('Discard Manifest draft');expect(host.querySelector('[role="alertdialog"]')?.textContent).toContain('staged Manifest source');
    await click('Discard draft');expect(register).toHaveBeenLastCalledWith(false,'unpublished Package Manifest changes');
  });
  it('discards a staged Manifest draft before allowing guarded navigation', async () => {
    const api=fixture();
    let confirmDiscard: (() => boolean | Promise<boolean>) | undefined;
    const register=vi.fn((_dirty: boolean,_description?: string,onConfirmDiscard?: () => boolean | Promise<boolean>) => {
      confirmDiscard=onConfirmDiscard;return () => {};
    });
    await act(async () => {root.render(<PackageManifestWorkspace api={api} packageId="owner" registerUnsavedChanges={register} />);});
    await click('Source');await click('Create Manifest draft');
    expect(host.querySelector('[aria-label="Manifest publication review"]')).not.toBeNull();
    let allowed=false;
    await act(async () => {allowed=await confirmDiscard?.() ?? false;});
    expect(allowed).toBe(true);
    expect(api.manifestDraftAction).toHaveBeenCalledWith('owner','M123','discard',expect.any(AbortSignal));
    expect(host.querySelector('[aria-label="Manifest publication review"]')).toBeNull();
  });
  it('blocks navigation and clears the Manifest editor when navigation discard is revoked', async () => {
    const api=fixture();let revoked=false;
    const getProjection=api.getPackageManifest;
    api.getPackageManifest=vi.fn(async (_packageId: string,view: ManifestView,environment?: string,signal?: AbortSignal) => {
      const value=await getProjection('owner',view,environment,signal);
      return revoked?{...value,canEdit:false,text:'redacted projection',redacted:true,authoringSourceUnavailable:true}:value;
    });
    api.manifestDraftAction=vi.fn(async () => {revoked=true;throw {status:403};});
    let confirmDiscard: (() => boolean | Promise<boolean>) | undefined;
    const register=vi.fn((_dirty: boolean,_description?: string,onConfirmDiscard?: () => boolean | Promise<boolean>) => {
      confirmDiscard=onConfirmDiscard;return () => {};
    });
    await act(async () => {root.render(<PackageManifestWorkspace api={api} packageId="owner" registerUnsavedChanges={register} />);});
    await click('Source');await click('Create Manifest draft');
    expect(host.textContent).toContain('keep comments and ordering');
    let allowed=true;
    await act(async () => {allowed=await confirmDiscard?.() ?? true;});
    expect(allowed).toBe(false);
    expect(api.manifestDraftAction).toHaveBeenCalledWith('owner','M123','discard',expect.any(AbortSignal));
    expect(host.querySelector('[aria-label="Manifest publication review"]')).toBeNull();
    expect(host.textContent).not.toContain('keep comments and ordering');
    expect((host.querySelector('textarea[aria-label="Package Manifest source"]') as HTMLTextAreaElement)?.readOnly).toBe(true);
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
  it('shows Manifest mutation controls to a non-owner trusted editor granted by the server', async () => {
    const api=fixture();
    const ownerProjection=await api.getPackageManifest('owner','effective');
    api.getPackageManifest=vi.fn(async () => ({...ownerProjection,packageId:'common-one',canEdit:true}));
    await act(async () => {root.render(<PackageManifestWorkspace api={api} packageId="common-one" />);});

    await click('Source');
    const editor=host.querySelector('textarea[aria-label="Package Manifest source"]') as HTMLTextAreaElement;
    expect(editor.readOnly).toBe(false);
    expect(button('Create Manifest draft').disabled).toBe(false);
    await click('Create Manifest draft');
    expect(api.getPackageManifest).toHaveBeenCalledWith('common-one','effective',undefined,expect.any(AbortSignal));
    expect(api.getPackageAuthoringSource).toHaveBeenCalledWith('common-one',expect.any(AbortSignal));
    expect(api.stagePackageManifest).toHaveBeenCalledWith('common-one',authoringText,digest,expect.any(AbortSignal));
    await click('Validate Manifest draft');
    await click('Review validated Manifest');
    expect(api.manifestDraftAction).toHaveBeenCalledWith('common-one','M123','validate',expect.any(AbortSignal));
    expect(api.manifestDraftAction).toHaveBeenCalledWith('common-one','M123','review',expect.any(AbortSignal));
    expect(button('Publish reviewed Manifest').disabled).toBe(false);
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

  it('clears loaded source, draft, and history when a trusted editor grant is revoked', async () => {
    const api=fixture();let revoked=false;
    const getProjection=api.getPackageManifest;
    api.getPackageManifest=vi.fn(async (_packageId: string, _view: ManifestView, _environment?: string, _signal?: AbortSignal) => {
      const value=await getProjection('owner','effective');
      return revoked?{...value,canEdit:false,text:'redacted projection',redacted:true,authoringSourceUnavailable:true}:value;
    });
    api.getPackageManifestHistory=vi.fn(async () => ({items:[{manifestDigest:'c'.repeat(64),modifiedAt:'2026-10-25T00:00:00Z'}]}));
    api.manifestDraftAction=vi.fn(async () => {revoked=true;throw {status:403};});
    await render(api);await click('Source');await click('Load Manifest history');
    expect(host.textContent).toContain('c'.repeat(12));
    await click('Create Manifest draft');
    expect((host.querySelector('textarea[aria-label="Candidate Package Manifest source"]') as HTMLTextAreaElement)?.value)
      .toContain('keep comments');
    await click('Validate Manifest draft');
    expect(host.querySelector('[aria-label="Manifest publication review"]')).toBeNull();
    expect(host.querySelector('[aria-label="Manifest revision history"]')).toBeNull();
    expect(host.textContent).not.toContain('keep comments and ordering');
    expect(host.textContent).not.toContain(authoringText);
    expect([...host.querySelectorAll('textarea')].some(editor=>editor.value.includes('keep comments'))).toBe(false);
    expect(button('Create Manifest draft').disabled).toBe(true);
    expect(host.textContent).toContain('Package source is read only for this account.');
  });

  it('clears privileged Manifest state when a refreshed projection no longer grants editing', async () => {
    const api=fixture();let revoked=false;
    const getProjection=api.getPackageManifest;
    api.getPackageManifest=vi.fn(async (_packageId: string, view: ManifestView, environment?: string, signal?: AbortSignal) => {
      const value=await getProjection('owner',view,environment,signal);
      return revoked?{...value,canEdit:false,text:'redacted projection',redacted:true,authoringSourceUnavailable:true}:value;
    });
    api.getPackageManifestHistory=vi.fn(async () => ({items:[{manifestDigest:'c'.repeat(64),modifiedAt:'2026-10-25T00:00:00Z'}]}));
    await render(api);await click('Source');await click('Load Manifest history');await click('Create Manifest draft');
    expect(host.querySelector('[aria-label="Manifest publication review"]')).not.toBeNull();
    expect(host.querySelector('[aria-label="Manifest revision history"]')).not.toBeNull();
    expect(host.textContent).toContain(authoringText);

    revoked=true;
    await act(async () => {root.render(<PackageManifestWorkspace api={{...api}} packageId="owner" />);});
    expect(host.querySelector('[aria-label="Manifest publication review"]')).toBeNull();
    expect(host.querySelector('[aria-label="Manifest revision history"]')).toBeNull();
    expect(host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Package Manifest source"]')?.value).toBe('redacted projection');
    expect(host.textContent).not.toContain(authoringText);
    expect(host.textContent).not.toContain('keep comments and ordering');
    expect(button('Create Manifest draft').disabled).toBe(true);
  });

  it('clears Manifest state and refreshes the projection after raw authoring source returns 403', async () => {
    const api=fixture();let revoked=false,rawSourceDenied=false;
    const getProjection=api.getPackageManifest;
    const getManifest=vi.fn(async (_packageId: string, view: ManifestView, environment?: string, signal?: AbortSignal) => {
      const value=await getProjection('owner',view,environment,signal);
      return revoked&&rawSourceDenied
        ? {...value,canEdit:false,text:'redacted projection',redacted:true,authoringSourceUnavailable:true}
        : value;
    });
    api.getPackageManifest=getManifest;
    api.getPackageAuthoringSource=vi.fn(async () => {
      if(revoked){rawSourceDenied=true;throw {status:403};}
      return {available:true,present:true,logicalPath:'package.yaml',format:'yaml',text:authoringText,
        sourceEtag:`\"sha256:${digest}\"`,revision:digest};
    });
    api.getPackageManifestHistory=vi.fn(async () => ({items:[{manifestDigest:'c'.repeat(64),modifiedAt:'2026-10-25T00:00:00Z'}]}));
    await render(api);await click('Source');await click('Load Manifest history');await click('Create Manifest draft');
    expect(api.getPackageAuthoringSource).toHaveBeenCalledTimes(1);
    revoked=true;
    const projectionCallsBeforeRevoke=getManifest.mock.calls.length;
    await act(async () => {root.render(<PackageManifestWorkspace api={{...api}} packageId="owner" />);});
    expect(api.getPackageAuthoringSource).toHaveBeenCalledTimes(2);
    expect(getManifest.mock.calls.length).toBeGreaterThan(projectionCallsBeforeRevoke+2);
    expect(host.querySelector('[aria-label="Manifest publication review"]')).toBeNull();
    expect(host.querySelector('[aria-label="Manifest revision history"]')).toBeNull();
    expect(host.textContent).not.toContain(authoringText);
    expect(host.textContent).not.toContain('keep comments and ordering');
    expect(host.querySelector<HTMLTextAreaElement>('textarea[aria-label="Package Manifest source"]')?.value).toBe('redacted projection');
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
