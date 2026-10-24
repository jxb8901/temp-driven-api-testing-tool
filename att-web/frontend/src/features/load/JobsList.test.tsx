// @vitest-environment jsdom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { beforeEach, afterEach, it, expect, vi } from 'vitest';
import { JobsList } from './JobsList';
import type { JobSummary, JobsQueryResult, LoadJobsApi } from './types';
const job: JobSummary = {jobId:'J1234567890ABCDEF',command:'load',executionType:'load',packageId:'p',environment:'prod',status:'PASS',principal:'load-schedule:PRIVATE',createdAt:'2026-10-23T00:00:00Z',startedAt:'2026-10-23T00:00:05Z',finishedAt:'2026-10-23T00:00:15Z',targetGroup:'sig',targetIdentity:{type:'flow',id:'common.flow'}};
const response = (items: JobSummary[] = [job], extra: Partial<JobsQueryResult> = {}): JobsQueryResult => ({items,total:items.length,offset:0,limit:100,groups:[],principalSuggestions:['alice','bob'],packageSuggestions:['p'],...extra});
let host: HTMLDivElement, root: Root;
beforeEach(()=>{vi.useFakeTimers();localStorage.clear();sessionStorage.clear();host=document.createElement('div');document.body.append(host);root=createRoot(host);(globalThis as typeof globalThis & {IS_REACT_ACT_ENVIRONMENT?:boolean}).IS_REACT_ACT_ENVIRONMENT=true;});
afterEach(async()=>{await act(async()=>root.unmount());host.remove();vi.useRealTimers();vi.restoreAllMocks();});
async function advance(){await act(async()=>{await vi.advanceTimersByTimeAsync(200);});}
async function click(element: Element | null){expect(element).not.toBeNull();await act(async()=>{element!.dispatchEvent(new MouseEvent('click',{bubbles:true}));});}
function button(text: string){return [...host.querySelectorAll('button')].find(button=>button.textContent===text)!;}
async function select(label: string,value: string){const field=[...host.querySelectorAll('label')].find(labelElement=>labelElement.firstChild?.textContent===label)?.querySelector('select')!;expect(field).toBeTruthy();await act(async()=>{field.value=value;field.dispatchEvent(new Event('change',{bubbles:true}));});}
async function render(api: Partial<LoadJobsApi>, onOpenJob=vi.fn()){await act(async()=>root.render(<JobsList api={api as LoadJobsApi} onOpenJob={onOpenJob} onShowList={vi.fn()} />));await advance();}

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
it('selects Schedule with keyboard and sends a typed server predicate, then supports Mine and literal free text',async()=>{
  const queryJobs=vi.fn(async()=>response());await render({queryJobs});
  const input=host.querySelector<HTMLInputElement>('[role="combobox"]')!;
  await act(async()=>{input.focus();});
  expect(input.getAttribute('aria-expanded')).toBe('true');
  await act(async()=>{input.dispatchEvent(new KeyboardEvent('keydown',{key:'End',bubbles:true}));input.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowUp',bubbles:true}));});
  const schedule=[...host.querySelectorAll('[role="option"]')].find(option=>option.textContent==='Schedule')!;await click(schedule);await advance();
  expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({principalMode:'schedule',principal:undefined,offset:0}));
  await click(host.querySelector('[aria-label="Clear Principal"]'));await advance();expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({principalMode:'all'}));
  await act(async()=>input.focus());await click([...host.querySelectorAll('[role="option"]')].find(option=>option.textContent==='Mine')!);await advance();expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({principalMode:'mine'}));
});

it('restores filters after navigating back to Jobs',async()=>{
  sessionStorage.setItem('att.jobs.filters',JSON.stringify({principal:'alice',principalMode:'text',status:'PASS',sort:'target-desc',type:'run',grouped:'true'}));
  const queryJobs=vi.fn(async()=>response());await render({queryJobs});
  expect(queryJobs).toHaveBeenLastCalledWith(expect.objectContaining({principal:'alice',principalMode:'text',status:'PASS',sort:'target-desc',executionType:'run',groupBy:'target'}));
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
