// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { LoadTemplateBuilder } from './LoadTemplateBuilder';
import { NavigationGuardContext } from '../../router';
import type { TargetDraft } from './LoadPage';
const endpoints = vi.hoisted(() => ({ listLoadTemplates: vi.fn(), createLoadTemplateDraft: vi.fn(), saveLoadScenario: vi.fn(), updateSavedLoadScenario: vi.fn() }));
vi.mock('../../api', () => ({ api: endpoints }));
vi.mock('../code/CodeEditor', () => ({ CodeEditor: ({ value }: { value: string }) => <pre>{value}</pre> }));
vi.mock('./LoadPage', async () => ({ ...await vi.importActual<typeof import('./LoadPage')>('./LoadPage'), TargetEditor: ({ target,onChange }: { target: TargetDraft; onChange(value: TargetDraft): void }) => <button type="button" onClick={() => onChange({ ...target,id: 'SAME',businessJson: '{"inputs":{"count":7}}' })}>Bind typed Target</button> }));
let host: HTMLDivElement, root: Root;
const navigate = vi.fn();
const definition = { schemaVersion: 'att-load-template/v1', name: 'Independent pools', mode: 'multi-workload-mix', policy: { load: { users: 10,duration: '10s' } },workloads: [1,2].map(i => ({ id: `workload-${i}`,mix: [{ id: 'slot-a',weight: 70 },{ id: 'slot-b',weight: 30 }] })) };
beforeEach(() => { host=document.createElement('div');document.body.append(host);root=createRoot(host);(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT=true; endpoints.listLoadTemplates.mockResolvedValue({ items: [{ id: 'builtin:multi-workload-mix',validation: 'valid',definition }],revisionDigest: 'source-revision' }); endpoints.createLoadTemplateDraft.mockReset(); endpoints.saveLoadScenario.mockReset(); endpoints.updateSavedLoadScenario.mockReset(); navigate.mockReset(); });
afterEach(async () => { await act(async () => root.unmount());host.remove(); });
async function click(text: string,within: Element = host) { await act(async () => { const button=[...within.querySelectorAll('button')].find(node => node.textContent?.includes(text));if(!button)throw new Error(`Missing ${text}`);button.click(); }); }
async function render(packageId = "p") { await act(async () => root.render(<NavigationGuardContext.Provider value={{ navigate,registerUnsavedChanges: () => vi.fn() }}><LoadTemplateBuilder api={{} as never} packages={[{packageId:'p'}]} selectedPackage={packageId} selectedEnvironment="SIT" onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()} inlineLoadEnabled schedulingEnabled templateId="builtin:multi-workload-mix" /></NavigationGuardContext.Provider>)); }
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

it('explains the inline duration cap and opens package scenarios with package and environment context',async () => {
 await render();
 expect(host.textContent).toContain('up to 10 days');
 await click('Open package LOAD scenarios');
 expect(navigate).toHaveBeenCalledWith({ kind: 'load', tab: 'scenarios', catalog: { packageFilter: 'p', environment: 'SIT' } });
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


it('round-trips workload timing, ramps and ranged think time while applying deliberate edits', async () => {
 const source = { ...definition, workloads: definition.workloads.map((row,index) => ({ ...row,
  load: { users: 10 + index, duration: `${20 + index}s`, warmup: `${index + 1}s`, rampUp: '2s', rampDown: '3s' },
  execution: { thinkTime: { min: '10ms', max: '50ms' } },
 })) };
 endpoints.listLoadTemplates.mockResolvedValue({ items: [{ id: 'builtin:multi-workload-mix', validation: 'valid', definition: source }], revisionDigest: 'source-revision' });
 await render(); await bindAllTargets();
 expect(host.textContent).toContain('Current effective envelope: 28s maximum across 2 workloads');
 endpoints.createLoadTemplateDraft.mockResolvedValue({ draftId:'A1', expiresAt:new Date(Date.now()+60000).toISOString(), previewYaml:'safe' });
 await click('Validate');
 let composed = endpoints.createLoadTemplateDraft.mock.calls[0][2].definition;
 source.workloads.forEach((row,index) => {
  expect(composed.workloads[index].load).toEqual(row.load);
  expect(composed.workloads[index].execution).toEqual(row.execution);
 });
 await click('Configure');
 const dialog = host.querySelector('dialog[aria-label="Configure Workload"]')!;
 const users = dialog.querySelector('input[type="number"]')!;
 await act(async () => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(users,'17'); users.dispatchEvent(new Event('input',{bubbles:true})); });
 await click('Save',dialog); await click('Validate');
 composed = endpoints.createLoadTemplateDraft.mock.calls[1][2].definition;
 expect(composed.workloads[0].load).toEqual({ ...source.workloads[0].load, users:17 });
 expect(composed.workloads[0].execution).toEqual(source.workloads[0].execution);
 expect(composed.workloads[1].load).toEqual(source.workloads[1].load);
});

it('saves a validated composition without submitting a Job or Schedule', async () => {
 await render(); await bindAllTargets();
 endpoints.createLoadTemplateDraft.mockResolvedValue({ draftId:'A1',expiresAt:new Date(Date.now()+60000).toISOString(),previewYaml:'safe' });
 endpoints.saveLoadScenario.mockResolvedValue({ id:'LS1',revision:1,name:'Nightly' });
 await click('Validate'); await click('Save Scenario');
 const dialog=host.querySelector('dialog[aria-label="Save Scenario"]')!;
 const input=dialog.querySelector('input[aria-label="Scenario name"]')!;
 await act(async () => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(input,'Nightly'); input.dispatchEvent(new Event('input',{bubbles:true})); });
 await click('Save',dialog);
 expect(endpoints.saveLoadScenario).toHaveBeenCalledWith({draftId:'A1',name:'Nightly',description:'',tags:[]});
 expect(host.textContent).toContain('Saved Nightly (revision 1)');
 expect(navigate).not.toHaveBeenCalled();
});

it('reopens a saved mix with its bindings and saves an optimistic new revision', async () => {
 const savedDefinition = { ...definition, workloads: definition.workloads.map(row => ({ ...row, load:{users:10} })) };
 const bindings = Object.fromEntries(savedDefinition.workloads.map(row => [row.id, { mix: { 'slot-a': {type:'template',id:'PAY',inputs:{amount:7}}, 'slot-b': {type:'flow',id:'REFUND',vars:{region:'HK'}} } }]));
 const saved = { id:'LS1',packageId:'p',name:'Saved',description:'',tags:[],model:'virtualUsers',revision:3,updatedAt:'',definition:{packageId:'p',environment:'SIT',templateComposition:{templateId:'builtin:multi-workload-mix',revision:'source-revision',definition:savedDefinition,bindings:{schemaVersion:'att-load-bindings/v1',bindings}}} };
 endpoints.createLoadTemplateDraft.mockResolvedValue({draftId:'A2',expiresAt:new Date(Date.now()+60000).toISOString(),previewYaml:'safe'});
 endpoints.updateSavedLoadScenario.mockResolvedValue({...saved,revision:4});
 await act(async () => root.render(<NavigationGuardContext.Provider value={{navigate,registerUnsavedChanges:()=>vi.fn()}}><LoadTemplateBuilder api={{} as never} packages={[{packageId:'p'}]} selectedPackage="p" selectedEnvironment="SIT" onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()} inlineLoadEnabled schedulingEnabled templateId="builtin:multi-workload-mix" savedScenario={saved} /></NavigationGuardContext.Provider>));
 await click('Validate');
 const composed=endpoints.createLoadTemplateDraft.mock.calls[0][2];
 expect(composed.bindings.bindings['workload-1'].mix['slot-a']).toEqual({type:'template',id:'PAY',inputs:{amount:7}});
 expect(composed.bindings.bindings['workload-2'].mix['slot-b']).toEqual({type:'flow',id:'REFUND',vars:{region:'HK'}});
 await click('Save Scenario'); await click('Save', host.querySelector('dialog[aria-label="Save Scenario"]')!);
 expect(endpoints.updateSavedLoadScenario).toHaveBeenCalledWith('LS1',expect.objectContaining({draftId:'A2',expectedRevision:3,name:'Saved'}));
});

it.each(['single-vu','single-tps','single-workload-mix','multi-vu','multi-tps','multi-workload-mix'])('preserves bound Targets when reopening %s', async mode => {
 const mixed=mode.endsWith('mix'), arrival=mode.endsWith('tps');
 const policy={load:arrival?{arrivalRate:'100/s',maxConcurrent:10,overloadPolicy:'drop',duration:'30s'}:{users:2,duration:'30s'}};
 const row=mixed?{id:'w',mix:[{id:'a',weight:1},{id:'b',weight:2}]}:{id:'w'};
 const bound=mixed?{mix:{a:{type:'template',id:'PAY'},b:{type:'flow',id:'REFUND'}}}:{target:{type:'template',id:'PAY'}};
 const composition={templateId:'builtin:'+mode,revision:'source-revision',definition:{schemaVersion:'att-load-template/v1',name:'Saved',mode,policy,workloads:[row]},bindings:{schemaVersion:'att-load-bindings/v1',bindings:{w:bound}}};
 const saved={id:'LS1',packageId:'p',name:'Saved',description:'',tags:[],model:arrival?'arrivalRate':'virtualUsers',revision:1,updatedAt:'',definition:{templateComposition:composition}};
 endpoints.createLoadTemplateDraft.mockResolvedValue({draftId:'A1',expiresAt:new Date(Date.now()+60000).toISOString(),previewYaml:'safe'});
 await act(async () => root.render(<NavigationGuardContext.Provider value={{navigate,registerUnsavedChanges:()=>vi.fn()}}><LoadTemplateBuilder api={{} as never} packages={[{packageId:'p'}]} selectedPackage="p" selectedEnvironment="" onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()} inlineLoadEnabled templateId={'builtin:'+mode} savedScenario={saved} /></NavigationGuardContext.Provider>));
 await click('Validate');
 expect(endpoints.createLoadTemplateDraft.mock.calls[0][2].bindings.bindings.w).toMatchObject(bound);
});

it('avoids reusing workload and mix IDs retained in a saved scenario', async () => {
 const savedDefinition={...definition,workloads:[{id:'workload-101',mix:[{id:'slot-103',weight:1},{id:'b',weight:1}]},{id:'w2',mix:[{id:'a',weight:1},{id:'b',weight:1}]}]};
 const bindings=Object.fromEntries(savedDefinition.workloads.map(row=>[row.id,{mix:Object.fromEntries(row.mix.map(slot=>[slot.id,{type:'template',id:'PAY'}]))}]));
 const saved={id:'LS1',packageId:'p',name:'Saved',description:'',tags:[],model:'virtualUsers',revision:1,updatedAt:'',definition:{templateComposition:{templateId:'builtin:multi-workload-mix',revision:'source-revision',definition:savedDefinition,bindings:{schemaVersion:'att-load-bindings/v1',bindings}}}};
 await act(async () => root.render(<NavigationGuardContext.Provider value={{navigate,registerUnsavedChanges:()=>vi.fn()}}><LoadTemplateBuilder api={{} as never} packages={[{packageId:'p'}]} selectedPackage="p" selectedEnvironment="" onPackageChange={vi.fn()} onEnvironmentChange={vi.fn()} onJobAccepted={vi.fn()} inlineLoadEnabled templateId="builtin:multi-workload-mix" savedScenario={saved} /></NavigationGuardContext.Provider>));
 await click('Add Workload');
 expect(host.textContent).toContain('workload-102');
 await click('Edit Mix');
 const dialog=host.querySelector('dialog[aria-label="Edit Mix"]')!;
 await click('Add Target',dialog);
 expect(dialog.textContent).toContain('slot-104');
 expect([...dialog.querySelectorAll('input[aria-label]')].filter(input=>input.getAttribute('aria-label')==='slot-103 weight')).toHaveLength(1);
});

it('updates the same saved scenario on subsequent saves and requires revision-bound validation to run', async () => {
 endpoints.createLoadTemplateDraft.mockResolvedValue({draftId:'A1',expiresAt:new Date(Date.now()+60000).toISOString(),previewYaml:'safe'});
 endpoints.saveLoadScenario.mockResolvedValue({id:'LS1',name:'Traffic',revision:1});
 endpoints.updateSavedLoadScenario.mockResolvedValue({id:'LS1',name:'Traffic',revision:2});
 await render();await bindAllTargets();await click('Validate');await click('Save Scenario');
 const name=host.querySelector<HTMLInputElement>('input[aria-label="Scenario name"]')!;
 await act(async()=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(name,'Traffic');name.dispatchEvent(new Event('input',{bubbles:true}));});
 await click('Save',host.querySelector('dialog[aria-label="Save Scenario"]')!);
 expect(endpoints.saveLoadScenario).toHaveBeenCalledTimes(1);
 expect([...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Run Now'))?.disabled).toBe(true);
 expect(host.textContent).toContain('Open Saved Scenarios');
 await click('Save Scenario');await click('Save',host.querySelector('dialog[aria-label="Save Scenario"]')!);
 expect(endpoints.saveLoadScenario).toHaveBeenCalledTimes(1);
 expect(endpoints.updateSavedLoadScenario).toHaveBeenCalledWith('LS1',expect.objectContaining({expectedRevision:1,draftId:'A1'}));
});
