// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { beforeEach, afterEach, it, expect, vi } from 'vitest';
import { JobsList } from './JobsList';
import type { JobSummary, JobsQueryResult, LoadJobsApi } from './types';
const job: JobSummary = {jobId:'J1234567890ABCDEF',command:'load',executionType:'load',packageId:'p',environment:'prod',status:'PASS',principal:'load-schedule:PRIVATE',createdAt:'2026-10-23T00:00:00Z',startedAt:'2026-10-23T00:00:05Z',finishedAt:'2026-10-23T00:00:15Z',targetGroup:'sig',targetIdentity:{type:'flow',id:'common.flow'}};
const response = (items: JobSummary[] = [job], extra: Partial<JobsQueryResult> = {}): JobsQueryResult => ({items,total:items.length,offset:0,limit:100,groups:[],principalSuggestions:['alice','bob'],executionPrincipalSuggestions:['alice','bob'],scheduleOwnerSuggestions:['alice','bob'],triggeredBySuggestions:['alice','bob'],packageSuggestions:['p'],...extra});
let host: HTMLDivElement, root: Root;
beforeEach(()=>{vi.useFakeTimers();localStorage.clear();sessionStorage.clear();host=document.createElement('div');document.body.append(host);root=createRoot(host);(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?:boolean}).IS_REACT_ACT_ENVIRONMENT=true;});
afterEach(async()=>{await act(async()=>root.unmount());host.remove();vi.useRealTimers();vi.restoreAllMocks();});
async function advance(){await act(async()=>{await vi.advanceTimersByTimeAsync(200);});}
async function click(element: Element | null){expect(element).not.toBeNull();await act(async()=>{element!.dispatchEvent(new MouseEvent('click',{bubbles:true}));});}
function button(text: string){return [...host.querySelectorAll('button')].find(button=>button.textContent===text)!;}
async function select(label: string,value: string){const field=[...host.querySelectorAll('label')].find(labelElement=>labelElement.firstChild?.textContent===label)?.querySelector('select')!;expect(field).toBeTruthy();await act(async()=>{field.value=value;field.dispatchEvent(new Event('change',{bubbles:true}));});}
async function render(api: Partial<LoadJobsApi>, onOpenJob=vi.fn(), schedulingEnabled=false){await act(async()=>root.render(<JobsList api={api as LoadJobsApi} schedulingEnabled={schedulingEnabled} onOpenJob={onOpenJob} onShowList={vi.fn()} />));await advance();}

it('renders target-first rows, exact navigation, copyable ID, Schedule principal and execution-only elapsed',async()=>{
  const onOpenJob=vi.fn(),writeText=vi.fn(async()=>{});Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText}});
  const queryJobs=vi.fn(async()=>response());await render({queryJobs},onOpenJob);
  expect(queryJobs).toHaveBeenCalledWith(expect.objectContaining({offset:0,limit:100,sort:'newest'}));
  expect([...host.querySelectorAll('thead th')].map(th=>th.textContent)).toEqual(['Target / Package / Environment','Type / Status','Timing','Actions']);
  expect(host.querySelector('input[type="checkbox"]')).toBeNull();expect(host.textContent).not.toContain('PRIVATE');expect(host.textContent).toContain('Schedule');expect(host.textContent).toContain('Elapsed 0m 10s');
  await click(host.querySelector('.att-job-target'));expect(onOpenJob).toHaveBeenCalledWith(job.jobId);
  await click(host.querySelector('[aria-label="Copy full Job ID J1234567890ABCDEF"]'));expect(writeText).toHaveBeenCalledWith(job.jobId);
  expect(host.querySelector('code')?.textContent).toBe('J12345…CDEF');
});
it('links an available Schedule ID to history by keyboard and keeps unavailable IDs inert',async()=>{
  const available={...job,submissionSource:'scheduled',scheduleId:'S-AVAILABLE',originatingScheduleId:'S-AVAILABLE',scheduleAvailable:true};
  await render({queryJobs:vi.fn(async()=>response([available]))},vi.fn(),true);
  const link=host.querySelector<HTMLAnchorElement>('.att-job-provenance a');
  expect(link?.getAttribute('href')).toBe('#/schedules/S-AVAILABLE/history');
  expect(link?.getAttribute('aria-label')).toBe('View history for Schedule S-AVAILABLE');
  link?.focus();expect(document.activeElement).toBe(link);
  const enter=new KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true});link?.dispatchEvent(enter);expect(enter.defaultPrevented).toBe(false);
  await act(async()=>root.render(<JobsList api={{queryJobs:vi.fn(async()=>response([{...available,scheduleAvailable:false}]))} as unknown as LoadJobsApi} schedulingEnabled onOpenJob={vi.fn()} onShowList={vi.fn()} />));
  await advance();
  expect(host.querySelector('.att-job-provenance a')).toBeNull();
  expect(host.querySelector('.att-job-provenance')?.textContent).toContain('history unavailable');
});
it('shows the immutable LOAD pacing plan on the Job row and expands workload details on demand',async()=>{
  const planned={...job,targetIdentity:{type:'load',id:'2 workloads / 2 target bindings',workloadCount:2,bindingCount:2,workloads:[
    {name:'checkout',targets:[{type:'template',id:'PAYMENT'}],model:'arrivalRate',arrivalRate:'5/s',maxConcurrent:16,duration:'2h',warmup:'10m',rampUp:'5m',rampDown:'5m',totalDurationMs:8400000},
    {name:'account',targets:[{type:'flow',id:'ACCOUNT'}],model:'virtualUsers',users:12,duration:'1h',totalDurationMs:3600000},
  ]}};
  await render({queryJobs:vi.fn(async()=>response([planned]))});
  expect(host.textContent).toContain('Configured target rate 5/s · max concurrent 16 · Planned 2h 20m');
  expect(host.querySelector('.att-job-load-summary details')?.hasAttribute('open')).toBe(false);
  await click(host.querySelector('.att-job-load-summary summary'));
  expect(host.querySelector('.att-job-load-summary details')?.hasAttribute('open')).toBe(true);
  expect(host.textContent).toContain('ACCOUNT');
  expect(host.textContent).toContain('12 VUs · Planned 1h');
});
it('uses exact server page membership for footer mutations including collapsed groups and reports mixed outcomes',async()=>{
  const items=Array.from({length:100},(_,i)=>({...job,jobId:`J${i}`}));
  const queryJobs=vi.fn(async()=>response(items,{total:240,groups:[{packageId:'p',environment:'prod',targetGroup:'sig',total:240,lastCreatedAt:job.createdAt!,statuses:{PASS:240}}]}));
  const pinJobs=vi.fn(async(ids:string[])=>ids.map((jobId,index)=>({jobId,outcome:index===0?'PINNED':index===1?'ALREADY_IN_STATE':index===2?'FAILED':'NOT_ELIGIBLE'})));
  const setDeletionRequested=vi.fn(async(ids:string[])=>ids.map(jobId=>({jobId,outcome:'MARKED'})));
  await render({queryJobs,pinJobs,setDeletionRequested});await select('View','target');await advance();
  const header=host.querySelector('.att-job-group button')!;await click(header);expect(header.getAttribute('aria-expanded')).toBe('false');expect(host.querySelectorAll('tbody .att-job-target')).toHaveLength(0);
  expect(host.textContent).toContain('Apply to this page: 100 Jobs (including collapsed groups)');
  await click(button('Pin all ▾'));await click(button('Pin all on this page'));expect(pinJobs).toHaveBeenCalledWith(items.map(job=>job.jobId),true);
  expect(host.textContent).toContain('1 updated · 1 already in desired state / unavailable · 97 skipped · 1 failed');await advance();
  await click(button('Delete all ▾'));await click(button('Mark all on this page for deletion'));expect(setDeletionRequested).toHaveBeenCalledWith(items.map(job=>job.jobId),true);expect(host.querySelector('[role="dialog"]')).toBeNull();
});
it('queries subsequent server pages and persists page size with an explicit first-page reset',async()=>{
  const queryJobs=vi.fn(async(input)=>response([job],{total:245,offset:input.offset,limit:input.limit}));await render({queryJobs});
  await click(button('Next'));await advance();expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({offset:100,limit:100}));
  await select('Rows per page','50');await advance();expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({offset:0,limit:50}));expect(localStorage.getItem('att.jobs.pageSize')).toBe('50');
  await select('Sort','target-desc');await advance();expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({sort:'target-desc',offset:0}));
});
it('applies pending deletion immediately and reverses only the authoritative pending mark',async()=>{
  let pending=false;const queryJobs=vi.fn(async()=>response([{...job,deletionState:pending?'DELETE_REQUESTED':null}]));
  const setDeletionRequested=vi.fn(async(_ids:string[],requested:boolean)=>{pending=requested;return [{jobId:job.jobId,outcome:requested?'MARKED':'UNMARKED'}];});
  await render({queryJobs,setDeletionRequested});await click(host.querySelector(`[aria-label="Mark for deletion ${job.jobId}"]`));expect(setDeletionRequested).toHaveBeenCalledWith([job.jobId],true);await advance();
  const unmark=host.querySelector(`[aria-label="Unmark pending deletion for ${job.jobId}"]`);expect(unmark?.getAttribute('aria-pressed')).toBe('true');await click(unmark);expect(setDeletionRequested).toHaveBeenLastCalledWith([job.jobId],false);
});
it('pins on the first icon click, prevents overlapping actions, and refreshes authoritative state',async()=>{
  let resolve!: (value:Array<{jobId:string;outcome:string}>)=>void;
  const pinJobs=vi.fn(()=>new Promise<Array<{jobId:string;outcome:string}>>(yes=>{resolve=yes;}));const queryJobs=vi.fn().mockResolvedValueOnce(response()).mockResolvedValue(response([{...job,pinned:true}]));
  await render({queryJobs,pinJobs});await click(host.querySelector(`[aria-label="Pin ${job.jobId}"]`));expect(pinJobs).toHaveBeenCalledWith([job.jobId],true);expect((button('Pin all ▾') as HTMLButtonElement).disabled).toBe(true);expect(host.querySelector('[role="dialog"]')).toBeNull();
  await act(async()=>resolve([{jobId:job.jobId,outcome:'PINNED'}]));await advance();expect(host.querySelector(`[aria-label="Unpin ${job.jobId}"]`)?.getAttribute('aria-pressed')).toBe('true');
});
it('keeps legacy unknown targets openable and does not fabricate missing timestamps',async()=>{
  await render({queryJobs:vi.fn(async()=>response([{...job,targetIdentity:undefined,startedAt:null,finishedAt:null}]))});expect(host.textContent).toContain('Unknown target');expect(host.querySelector('.att-job-target')?.getAttribute('href')).toContain(job.jobId);expect(host.textContent).toContain('Elapsed —');
});
it('filters source and execution identity server-side, including Mine, suggestions, and literal text',async()=>{
  const queryJobs=vi.fn(async()=>response());await render({queryJobs});
  await select('Source','scheduled');await advance();
  expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({submissionSource:'scheduled',executionPrincipalMode:'all'}));
  const input=host.querySelector<HTMLInputElement>('[aria-label="Execution Principal"]')!;
  await act(async()=>{input.focus();});
  expect(input.getAttribute('aria-expanded')).toBe('true');
  await act(async()=>{input.dispatchEvent(new KeyboardEvent('keydown',{key:'End',bubbles:true}));input.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowUp',bubbles:true}));});
  await click([...host.querySelectorAll('[role="option"]')].find(option=>option.textContent==='alice')!);await advance();
  expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({executionPrincipal:'alice',executionPrincipalMode:'text',executionPrincipalExact:true}));
  await click(host.querySelector('[aria-label="Clear Execution Principal"]'));await advance();expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({executionPrincipalMode:'all'}));
  await act(async()=>input.focus());await click([...host.querySelectorAll('[role="option"]')].find(option=>option.textContent==='Mine')!);await advance();
  expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({executionPrincipalMode:'mine'}));
  await act(async()=>{const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')?.set;setter?.call(input,'ali');input.dispatchEvent(new Event('input',{bubbles:true}));});await advance();
  expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({executionPrincipal:'ali',executionPrincipalMode:'text',executionPrincipalExact:undefined}));
});

it('restores filters after navigating back to Jobs',async()=>{
  sessionStorage.setItem('att.jobs.filters',JSON.stringify({principal:'alice',principalMode:'text',status:'PASS',sort:'target-desc',type:'run',grouped:'true'}));
  const queryJobs=vi.fn(async()=>response());await render({queryJobs});
  expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({submissionSource:undefined,executionPrincipal:undefined,executionPrincipalMode:'all',status:'PASS',sort:'target-asc',executionType:'run',groupBy:'target'}));
});

it('maps the legacy Schedule filter to the new Scheduled source',async()=>{
  sessionStorage.setItem('att.jobs.filters',JSON.stringify({principalMode:'schedule'}));
  const queryJobs=vi.fn(async()=>response());await render({queryJobs});
  expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({submissionSource:'scheduled'}));
});

it('keeps advanced filters collapsed with restored active values visible and Clear next to Refresh',async()=>{
  sessionStorage.setItem('att.jobs.filters',JSON.stringify({from:'2026-10-01',deletion:'pending',pinned:'true'}));
  const queryJobs=vi.fn(async()=>response());await render({queryJobs});
  expect(host.querySelector<HTMLFieldSetElement>('#jobs-more-filters')?.hidden).toBe(true);
  expect(host.querySelector('.att-jobs-active-filters')?.textContent).toContain('From 2026-10-01 · Deletion: pending · Pinned only');
  expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({deletion:'pending',pinnedOnly:true,from:expect.any(String)}));
  await click(button('More filters (3)'));expect(host.querySelector<HTMLFieldSetElement>('#jobs-more-filters')?.hidden).toBe(false);
  await select('Deletion state','unmarked');await advance();expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({deletion:'unmarked',offset:0}));
  await click(button('More filters (3)'));await click(button('Clear filters'));await advance();
  expect(host.querySelector('.att-jobs-active-filters')).toBeNull();expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({deletion:'all',pinnedOnly:false,from:undefined}));
  expect(button('Clear filters').parentElement).toBe(button('Refresh jobs').parentElement);
  expect(host.querySelector('.att-jobs-toolbar')?.textContent).toContain('Showing 1–1 of 1 Jobs');
});

it('reflects enforced A–Z grouping after descending Recent sort and retains the flat preference',async()=>{
  const queryJobs=vi.fn(async()=>response());await render({queryJobs});
  await select('Sort','target-desc');await advance();
  expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({sort:'target-desc',groupBy:undefined}));
  await select('View','target');await advance();
  const sort=host.querySelector<HTMLSelectElement>('[aria-label="Sort"]')!;
  expect(sort.value).toBe('target-asc');expect(sort.disabled).toBe(true);
  expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({sort:'target-asc',groupBy:'target',offset:0}));
  await select('View','recent');await advance();
  expect(sort.value).toBe('target-desc');expect(sort.disabled).toBe(false);
  expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({sort:'target-desc',groupBy:undefined}));
});

it('clears stale results when target search is pending and allows an explicit refresh retry',async()=>{
  const message='Historical target search is being prepared. No partial results were returned. Retry Refresh jobs shortly.';
  const queryJobs=vi.fn().mockResolvedValueOnce(response()).mockRejectedValueOnce(new Error(message)).mockResolvedValue(response());
  await render({queryJobs});expect(host.querySelector('.att-job-target')).not.toBeNull();
  await click(button('Refresh jobs'));await advance();
  expect(host.querySelector('[role="alert"]')?.textContent).toBe(message);expect(host.querySelector('.att-job-target')).toBeNull();expect(host.textContent).not.toContain('No matching Jobs');
  await click(button('Refresh jobs'));await advance();expect(host.querySelector('[role="alert"]')).toBeNull();expect(host.querySelector('.att-job-target')).not.toBeNull();
});

it('can replace pending substring search with explicit exact Job ID without clearing other filters',async()=>{
  sessionStorage.setItem('att.jobs.filters',JSON.stringify({search:'common',packageId:'p',status:'PASS'}));
  const queryJobs=vi.fn(async(input)=>{if(input.search?.startsWith('job:'))return response();throw new Error('Historical target search is being prepared.');});
  await render({queryJobs});expect(host.querySelector('[role="alert"]')).not.toBeNull();
  const input=host.querySelector<HTMLInputElement>('input[type="search"]')!;
  expect(input.title).toContain('job:');
  await act(async()=>{Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(input,`job:${job.jobId}`);input.dispatchEvent(new Event('input',{bubbles:true}));});
  await advance();
  expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({search:`job:${job.jobId}`,packageId:'p',status:'PASS',offset:0}));
  expect(host.querySelector('[role="alert"]')).toBeNull();expect(host.querySelector('.att-job-target')).not.toBeNull();
});
