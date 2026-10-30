// @vitest-environment jsdom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { expect, it, vi } from 'vitest';
import { ScheduleExecutionEditor } from './ScheduleExecutionEditor';
import { NavigationGuardContext } from '../../router';
it('validates, reviews, and applies an execution update without changing schedule settings', async () => {
 (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT=true;
 const host=document.createElement('div');document.body.append(host);const root=createRoot(host);
 const schedule={id:'S1',name:'Nightly',packageId:'p',executionType:'load' as const,timezone:'UTC',maxConcurrentRuns:1,enabled:true,inlineSnapshot:true,definitionVersion:4,snapshotVersion:2,targetIdentity:{workloads:[{name:'payments',model:'arrivalRate',arrivalRate:'100/s',maxConcurrent:20,targets:[]}]}};
 const review={draftId:'A1',definitionVersion:4,snapshotVersion:2,revisionDigest:'abc',sourcePolicy:'strict',expiresAt:new Date(Date.now()+60000).toISOString(),originalPreview:{previewYaml:'100/s'},preview:{previewYaml:'150/s'}};
 const api={createScheduleExecutionDraft:vi.fn().mockResolvedValue(review),replaceScheduleExecution:vi.fn().mockResolvedValue({...schedule,snapshotVersion:3})};const onSaved=vi.fn();
 await act(async()=>root.render(<NavigationGuardContext.Provider value={{navigate:vi.fn(),registerUnsavedChanges:()=>vi.fn()}}><ScheduleExecutionEditor schedule={schedule} api={api as never} onClose={vi.fn()} onSaved={onSaved} /></NavigationGuardContext.Provider>));
 const rate=host.querySelector<HTMLInputElement>('input')!;
 await act(async()=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(rate,'150/s');rate.dispatchEvent(new Event('input',{bubbles:true}));});
 const click=async(text:string)=>act(async()=>[...host.querySelectorAll('button')].find(button=>button.textContent?.includes(text))!.click());
 await click('Validate changes');
 expect(api.createScheduleExecutionDraft).toHaveBeenCalledWith('S1',{expectedDefinitionVersion:4,overrides:{workloads:[{id:'payments',load:{arrivalRate:'150/s'}}]}});
 expect(host.textContent).toContain('100/s');expect(host.textContent).toContain('150/s');
 await click('Save as new snapshot');
 expect(api.replaceScheduleExecution).toHaveBeenCalledWith('S1',{draftId:'A1',expectedDefinitionVersion:4,confirm:true});expect(onSaved).toHaveBeenCalledOnce();
 await act(async()=>root.unmount());host.remove();
});
it('keeps package-backed schedules in package mode during review', async () => {
 (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT=true;
 const host=document.createElement('div');document.body.append(host);const root=createRoot(host);
 const schedule={id:'S2',name:'Package',packageId:'p',executionType:'load' as const,scenario:'load/scenario.yaml',load:{duration:'1s'},timezone:'UTC',maxConcurrentRuns:1,enabled:true,definitionVersion:0};
 const api={createScheduleExecutionDraft:vi.fn().mockResolvedValue({draftId:'P1',definitionVersion:0,snapshotVersion:1,revisionDigest:'abc',sourcePolicy:'strict',expiresAt:new Date(Date.now()+60000).toISOString(),originalPreview:{},preview:{}})};
 await act(async()=>root.render(<NavigationGuardContext.Provider value={{navigate:vi.fn(),registerUnsavedChanges:()=>vi.fn()}}><ScheduleExecutionEditor schedule={schedule} api={api as never} onClose={vi.fn()} onSaved={vi.fn()} /></NavigationGuardContext.Provider>));
 await act(async()=>[...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Validate changes'))!.click());
 expect(api.createScheduleExecutionDraft).toHaveBeenCalledWith('S2',{expectedDefinitionVersion:0,packageEdit:{scenario:'load/scenario.yaml',load:{duration:'1s'}}});
 expect(host.textContent).toContain('continue to resolve its package scenario');
 await act(async()=>root.unmount());host.remove();
});
it('reviews a changed RUN resource target', async () => {
 (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT=true;
 const host=document.createElement('div');document.body.append(host);const root=createRoot(host);
 const schedule={id:'S3',name:'Run',packageId:'p',executionType:'run' as const,timezone:'UTC',maxConcurrentRuns:1,enabled:true,inlineSnapshot:true,definitionVersion:1,debugTarget:{type:'template',id:'old'}};
 const api={createScheduleExecutionDraft:vi.fn().mockResolvedValue({draftId:'A2',definitionVersion:1,snapshotVersion:1,revisionDigest:'abc',sourcePolicy:'strict',expiresAt:new Date(Date.now()+60000).toISOString(),originalPreview:{},preview:{}})};
 await act(async()=>root.render(<NavigationGuardContext.Provider value={{navigate:vi.fn(),registerUnsavedChanges:()=>vi.fn()}}><ScheduleExecutionEditor schedule={schedule} api={api as never} onClose={vi.fn()} onSaved={vi.fn()} /></NavigationGuardContext.Provider>));
 expect([...host.querySelectorAll('select option')].map(option=>option.value)).toEqual(['template','flow','tool']);
 const input=host.querySelector<HTMLInputElement>('input')!;
 await act(async()=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(input,'new');input.dispatchEvent(new Event('input',{bubbles:true}));});
 await act(async()=>[...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Validate changes'))!.click());
 expect(api.createScheduleExecutionDraft).toHaveBeenCalledWith('S3',{expectedDefinitionVersion:1,overrides:{target:{type:'template',id:'new'}}});
 expect(host.textContent).toContain('clears its previous input bindings');
 await act(async()=>root.unmount());host.remove();
});
