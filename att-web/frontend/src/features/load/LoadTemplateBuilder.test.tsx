// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { LoadTemplateBuilder } from './LoadTemplateBuilder';
import { NavigationGuardContext } from '../../router';
import type { TargetDraft } from './LoadPage';
const endpoints = vi.hoisted(() => ({ listLoadTemplates: vi.fn(), createLoadTemplateDraft: vi.fn() }));
vi.mock('../../api', () => ({ api: endpoints }));
vi.mock('../code/CodeEditor', () => ({ CodeEditor: ({ value }: { value: string }) => <pre>{value}</pre> }));
vi.mock('./LoadPage', async () => ({ ...await vi.importActual<typeof import('./LoadPage')>('./LoadPage'), TargetEditor: ({ target,onChange }: { target: TargetDraft; onChange(value: TargetDraft): void }) => <button type="button" onClick={() => onChange({ ...target,id: 'SAME',businessJson: '{"inputs":{"count":7}}' })}>Bind typed Target</button> }));
let host: HTMLDivElement, root: Root;
const definition = { schemaVersion: 'att-load-template/v1', name: 'Independent pools', mode: 'multi-workload-mix', policy: { load: { users: 10,duration: '10s' } },workloads: [1,2].map(i => ({ id: `workload-${i}`,mix: [{ id: 'slot-a',weight: 70 },{ id: 'slot-b',weight: 30 }] })) };
beforeEach(() => { host=document.createElement('div');document.body.append(host);root=createRoot(host);(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT=true; endpoints.listLoadTemplates.mockResolvedValue({ items: [{ id: 'builtin:multi-workload-mix',validation: 'valid',definition }],revisionDigest: 'source-revision' }); endpoints.createLoadTemplateDraft.mockReset(); });
afterEach(async () => { await act(async () => root.unmount());host.remove(); });
async function click(text: string,within: Element = host) { await act(async () => { const button=[...within.querySelectorAll('button')].find(node => node.textContent?.includes(text));if(!button)throw new Error(`Missing ${text}`);button.click(); }); }
async function render(packageId = "p") { await act(async () => root.render(<NavigationGuardContext.Provider value={{ navigate: vi.fn(),registerUnsavedChanges: () => vi.fn() }}><LoadTemplateBuilder api={{} as never} packages={[{packageId:'p'}]} selectedPackage={packageId} selectedEnvironment="SIT" onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()} inlineLoadEnabled schedulingEnabled templateId="builtin:multi-workload-mix" /></NavigationGuardContext.Provider>)); }
async function bindAllTargets() {
 for(let row=0;row<2;row++) {
  await act(async () => ([...host.querySelectorAll('button')].filter(button => button.textContent?.includes('Edit Mix'))[row]).click());
  for(let slot=0;slot<2;slot++) {
   const dialog=host.querySelector('dialog[aria-label="Edit Mix"]')!;
   await act(async () => ([...dialog.querySelectorAll('button')].filter(button => button.textContent?.includes('Choose / Configure'))[slot]).click());
   await click('Bind typed Target');await click('Save',host.querySelector('dialog[aria-label="Configure Mix Target"]')!);
  }
  await click('Save',host.querySelector('dialog[aria-label="Edit Mix"]')!);
 }
}
it('isolates nested mix edits until Save and composes stable IDs separately from repeated typed Targets',async () => {
 await render();
 await click('Edit Mix');
 await click('Choose / Configure',host.querySelector('dialog')!);await click('Bind typed Target');
 await click('Save',host.querySelector('dialog[aria-label="Configure Mix Target"]')!);
 await click('Cancel',host.querySelector('dialog[aria-label="Edit Mix"]')!);
 expect(host.textContent).not.toContain('SAME');
 await bindAllTargets();
 expect(host.textContent).toContain('2 Workloads · 4 Target bindings · 1 Unique targets · 20 total VU');
 endpoints.createLoadTemplateDraft.mockResolvedValue({ draftId: 'A1',expiresAt:new Date(Date.now()+60000).toISOString(),previewYaml:'safe' });
 await click('Validate');
 expect(endpoints.createLoadTemplateDraft).toHaveBeenCalledOnce();
 const composition=endpoints.createLoadTemplateDraft.mock.calls[0][2];
 expect(composition.definition.workloads[0].mix[0]).toEqual({id:'slot-a',weight:70});
 expect(composition.bindings.bindings['workload-2'].mix['slot-a']).toEqual({type:'template',id:'SAME',inputs:{count:7}});
 expect(composition.definition.workloads[0].target).toBeUndefined();
 expect([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Run Now'))!.disabled).toBe(false);
 await click('Duplicate');
 expect([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Run Now'))!.disabled).toBe(true);
});

it('ignores a previous package validation response after changing package',async () => {
 await render();
 await bindAllTargets();
 let resolve!: (value: unknown) => void;
 endpoints.createLoadTemplateDraft.mockImplementation(() => new Promise(done => { resolve=done; }));
 await click('Validate');
 await render('other');
 await act(async () => resolve({ draftId:'OLD',expiresAt:new Date(Date.now()+60000).toISOString(),previewYaml:'old package' }));
 expect([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Run Now'))!.disabled).toBe(true);
 expect(host.textContent).not.toContain('Validation passed.');
});
