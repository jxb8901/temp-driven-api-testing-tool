import { useEffect, useRef, useState } from 'react';
import { useNavigationGuard } from '../../router';
import { LoadDialog } from '../load/LoadDialog';
import type { ScheduleDefinition, ScheduleExecutionReview, SchedulesApi } from './types';

type MixEntry = { id: string; weight: string; targetType: string; targetId: string };
type Workload = { id: string; model: string; rate: string; users: string; maxConcurrent: string; targetType: string; targetId: string; mix: boolean; mixEntries: MixEntry[]; rampUp: string; warmup: string; duration: string; rampDown: string };
type Preview = ScheduleExecutionReview['preview'];
type AdvancedJson = { thresholds: string; evidence: string; testdata: string; execution: string; seed: string };
type RunOptions = { debug: boolean; failFast: boolean };

function previewText(preview: Preview): string {
  const value=preview.previewYaml || JSON.stringify(preview,null,2);
  try { return JSON.stringify(JSON.parse(value),null,2); } catch { return value; }
}
function jsonField(value: unknown, fallback = ''): string {
  if(value==null)return fallback;
  try { return JSON.stringify(value,null,2); } catch { return fallback; }
}
function initialAdvanced(schedule: ScheduleDefinition): AdvancedJson {
  const settings=schedule.executionSettings || {};
  return {
    thresholds:jsonField(settings.thresholds),
    evidence:jsonField(settings.evidence),
    testdata:jsonField(settings.testdata),
    execution:jsonField(settings.execution),
    seed:settings.seed==null?'':String(settings.seed),
  };
}
function initialRunOptions(schedule: ScheduleDefinition): RunOptions {
  return { debug:Boolean(schedule.runOptions?.debug), failFast:Boolean(schedule.runOptions?.failFast) };
}
/** Compare only Server-sanitized previews and bound the quadratic line comparison. */
export function previewChanges(before: string, after: string): {kind:'removed'|'added'; line:string}[] | null {
  if(before.length>50000||after.length>50000)return null;
  const left=before.split('\n'),right=after.split('\n');
  if(left.length>300||right.length>300)return null;
  const lengths=Array.from({length:left.length+1},()=>new Uint16Array(right.length+1));
  for(let i=left.length-1;i>=0;i--)for(let j=right.length-1;j>=0;j--)
    lengths[i][j]=left[i]===right[j]?lengths[i+1][j+1]+1:Math.max(lengths[i+1][j],lengths[i][j+1]);
  const changes: {kind:'removed'|'added';line:string}[]=[];let i=0,j=0;
  while(i<left.length||j<right.length) {
    if(i<left.length&&j<right.length&&left[i]===right[j]) {i++;j++;}
    else if(j<right.length&&(i===left.length||lengths[i][j+1]>lengths[i+1][j])) changes.push({kind:'added',line:right[j++]});
    else changes.push({kind:'removed',line:left[i++]});
  }
  return changes;
}
function parseObjectField(label: string, value: string): Record<string, unknown> {
  const trimmed=value.trim(); if(!trimmed)throw new Error(`${label} must be a JSON object.`);
  const parsed: unknown=JSON.parse(trimmed);
  if(!parsed || typeof parsed!=='object' || Array.isArray(parsed))throw new Error(`${label} must be a JSON object.`);
  return parsed as Record<string, unknown>;
}
function parseArrayOrObject(label: string, value: string): unknown {
  const trimmed=value.trim(); if(!trimmed)throw new Error(`${label} must be JSON.`);
  const parsed: unknown=JSON.parse(trimmed);
  if(parsed==null || (typeof parsed!=='object'))throw new Error(`${label} must be JSON.`);
  return parsed;
}
function changedAdvanced(label: string, current: string, initial: string, parse: (label: string, value: string) => unknown): unknown | undefined {
  if(current===initial)return undefined;
  if(!current.trim())throw new Error(`${label} cannot be cleared here; restore the retained JSON or keep the previous value.`);
  return parse(label, current);
}
/** Reviewable execution editor covering the override fields accepted by schedule execution drafts. */
export function ScheduleExecutionEditor({ schedule, api, onClose, onSaved }: {
  schedule: ScheduleDefinition; api: SchedulesApi; onClose(): void; onSaved(value: ScheduleDefinition): void;
}) {
  const navigation=useNavigationGuard();
  const [workloads,setWorkloads]=useState<Workload[]>(() => (schedule.targetIdentity?.workloads || []).map(row => ({ id:row.name,model:row.model,rate:String(row.arrivalRate ?? ''),users:String(row.users ?? ''),maxConcurrent:String(row.maxConcurrent ?? ''), targetType:row.targets[0]?.type || '', targetId:row.targets[0]?.id || '', mix:row.mix !== false, mixEntries:(row.mixEntries || []).map(item=>({id:item.id,weight:String(item.weight ?? ''),targetType:item.target.type || '',targetId:item.target.id || ''})), rampUp:String(row.rampUp ?? ''),warmup:String(row.warmup ?? ''),duration:String(row.duration ?? ''),rampDown:String(row.rampDown ?? '') })));
  const initialWorkloads=useRef(workloads);
  const [scenario,setScenario]=useState(schedule.scenario || '');
  const [packageLoad,setPackageLoad]=useState(JSON.stringify(schedule.load || {},null,2));
  const [environment,setEnvironment]=useState(schedule.environment || '');
  const initialEnvironment=useRef(environment);
  const [cases,setCases]=useState((schedule.selectedCaseIds || []).join(', '));
  const initialCaseIds=useRef([...(schedule.selectedCaseIds || [])]);
  const [runTarget,setRunTarget]=useState(schedule.debugTarget || {type:'',id:''});
  const [runOptions,setRunOptions]=useState<RunOptions>(() => initialRunOptions(schedule));
  const initialRunOptionsRef=useRef(runOptions);
  const [runInput,setRunInput]=useState('');
  const [phases,setPhases]=useState({rampUp:'',warmup:'',duration:'',rampDown:''});
  const [advanced,setAdvanced]=useState<AdvancedJson>(() => initialAdvanced(schedule));
  const initialAdvancedRef=useRef(advanced);
  const [changed,setChanged]=useState(false),[busy,setBusy]=useState(false);
  const [review,setReview]=useState<ScheduleExecutionReview | null>(null);
  const [error,setError]=useState('');
  useEffect(() => navigation.registerUnsavedChanges(changed, 'Schedule execution settings'), [changed,navigation.registerUnsavedChanges]);
  function edit() { setChanged(true); setReview(null); setError(''); }
  function close() { if (!changed || window.confirm('Discard execution settings changes?')) onClose(); }
  function setAdvancedField(field: keyof AdvancedJson, value: string) { edit(); setAdvanced(current=>({...current,[field]:value})); }
  async function validate() {
    if (!api.createScheduleExecutionDraft) return;
    setBusy(true);setError('');setReview(null);
    try {
      const overrides: Record<string,unknown>={};
      if(!schedule.inlineSnapshot) {
        const parsed: unknown=JSON.parse(packageLoad); if(!parsed || typeof parsed!=='object' || Array.isArray(parsed))throw new Error('Load overrides must be a JSON object.');
        const packageEdit: Record<string,unknown>={scenario,load:parsed};
        if(environment!==initialEnvironment.current)packageEdit.environment=environment;
        setReview(await api.createScheduleExecutionDraft(schedule.id,{expectedDefinitionVersion:schedule.definitionVersion ?? 0,packageEdit}));return;
      }
      if(environment!==initialEnvironment.current)overrides.environment=environment;
      if(schedule.executionType==='run') {
        if(schedule.debugTarget) {
          if(runTarget.type!==schedule.debugTarget.type || runTarget.id!==schedule.debugTarget.id) overrides.target=runTarget;
          if(runInput.trim())overrides.input=parseObjectField('RUN input',runInput);
        } else if(schedule.selectedCaseIds?.length) {
          const next=cases.split(',').map(value=>value.trim()).filter(Boolean);
          const initial=initialCaseIds.current;
          if(next.length!==initial.length || next.some((id,index)=>id!==initial[index])) overrides.selection={caseIds:next};
        }
        const options: Record<string,boolean>={};
        if(runOptions.debug!==initialRunOptionsRef.current.debug)options.debug=runOptions.debug;
        if(runOptions.failFast!==initialRunOptionsRef.current.failFast)options.failFast=runOptions.failFast;
        if(Object.keys(options).length)overrides.options=options;
      }
      else {
        const load=Object.fromEntries(Object.entries(phases).filter(([,value])=>value.trim()));if(Object.keys(load).length)overrides.load=load;
        const edits=workloads.flatMap((row,index)=>{ const before=initialWorkloads.current[index]; const load: Record<string,unknown>={};
          if(row.model==='arrivalRate') { if(row.rate!==before.rate)load.arrivalRate=row.rate; if(row.maxConcurrent!==before.maxConcurrent)load.maxConcurrent=Number(row.maxConcurrent); }
          else if(row.users!==before.users)load.users=Number(row.users);
          for(const phase of ['rampUp','warmup','duration','rampDown'] as const)if(row[phase]!==before[phase])load[phase]=row[phase];
          const target=!row.mix&&(row.targetType!==before.targetType||row.targetId!==before.targetId)?{type:row.targetType,id:row.targetId}:undefined;
          const mixEdits=row.mixEntries.flatMap((item,mixIndex)=>{const original=before.mixEntries[mixIndex];
            const weight=item.weight!==original.weight?Number(item.weight):undefined;
            const mixTarget=item.targetType!==original.targetType||item.targetId!==original.targetId?{type:item.targetType,id:item.targetId}:undefined;
            return weight!==undefined||mixTarget?[{id:item.id,...(weight!==undefined?{weight}:{}),...(mixTarget?{target:mixTarget}:{})}]:[];
          });
          return Object.keys(load).length||target||mixEdits.length?[{id:row.id,...(Object.keys(load).length?{load}:{}),...(target?{target}:{}),...(mixEdits.length?{mixEdits}:{})}]:[];
        });
        if(edits.length)overrides.workloads=edits;
        const advancedAvailable=Boolean(schedule.executionSettings);
        if(!advancedAvailable) {
          const touched=(['thresholds','evidence','testdata','execution','seed'] as const).some(field => advanced[field].trim());
          if(touched) throw new Error('Advanced settings cannot be replaced here because retained values are unavailable for safe prefill. Leave those fields empty and edit pacing, targets, or environment instead.');
        } else {
          const thresholds=changedAdvanced('Thresholds',advanced.thresholds,initialAdvancedRef.current.thresholds,parseObjectField); if(thresholds!==undefined)overrides.thresholds=thresholds;
          const evidence=changedAdvanced('Evidence',advanced.evidence,initialAdvancedRef.current.evidence,parseObjectField); if(evidence!==undefined)overrides.evidence=evidence;
          const testdata=changedAdvanced('Testdata',advanced.testdata,initialAdvancedRef.current.testdata,parseArrayOrObject); if(testdata!==undefined)overrides.testdata=testdata;
          const execution=changedAdvanced('Execution',advanced.execution,initialAdvancedRef.current.execution,parseObjectField); if(execution!==undefined)overrides.execution=execution;
          if(advanced.seed!==initialAdvancedRef.current.seed) {
            if(!advanced.seed.trim())throw new Error('Seed cannot be cleared here; restore the retained value or keep the previous seed.');
            overrides.seed=Number(advanced.seed);
          }
        }
      }
      setReview(await api.createScheduleExecutionDraft(schedule.id,{expectedDefinitionVersion:schedule.definitionVersion ?? 0,overrides}));
    } catch(failure) {setError(failure instanceof Error?failure.message:'Validation failed.');} finally {setBusy(false);}
  }
  async function apply() {
    if(!review||!api.replaceScheduleExecution||Date.parse(review.expiresAt)<=Date.now()) {setError('The validated review expired. Validate again.');return;}
    setBusy(true);setError('');
    try {const saved=await api.replaceScheduleExecution(schedule.id,{draftId:review.draftId,expectedDefinitionVersion:review.definitionVersion,confirm:true});setChanged(false);onSaved(saved);}
    catch(failure){setError(failure instanceof Error?failure.message:'The schedule could not be updated.');setReview(null);}finally{setBusy(false);}
  }
  const advancedAvailable=Boolean(schedule.executionSettings);
  return <LoadDialog title="Edit execution settings" onCancel={close}>
    <p>Schedule {schedule.name} · snapshot {schedule.snapshotVersion ?? 1}. These fields change future executions only.</p>
    <label>Environment <input value={environment} onChange={event=>{edit();setEnvironment(event.target.value);}} placeholder="Package default" /></label>
    {!schedule.inlineSnapshot ? <><p>The schedule will continue to resolve its package scenario when it fires.</p><label>Package scenario path <input value={scenario} onChange={event=>{edit();setScenario(event.target.value);}} /></label><label>Load overrides (JSON) <textarea value={packageLoad} onChange={event=>{edit();setPackageLoad(event.target.value);}} /></label></> : schedule.executionType==='load' ? <>
      {workloads.map((row,index)=><fieldset key={row.id}><legend>{row.id}</legend>
        {row.model==='arrivalRate'?<><label>Arrival rate <input value={row.rate} onChange={event=>{edit();setWorkloads(current=>current.map((item,i)=>i===index?{...item,rate:event.target.value}:item));}} /></label>
        <label>Maximum concurrent <input type="number" min="1" value={row.maxConcurrent} onChange={event=>{edit();setWorkloads(current=>current.map((item,i)=>i===index?{...item,maxConcurrent:event.target.value}:item));}} /></label></>:<label>Users <input type="number" min="1" value={row.users} onChange={event=>{edit();setWorkloads(current=>current.map((item,i)=>i===index?{...item,users:event.target.value}:item));}} /></label>}
        {!row.mix && <><p>Changing the target clears its previous inputs and variables.</p><label>Target type <select value={row.targetType} onChange={event=>{edit();setWorkloads(current=>current.map((item,i)=>i===index?{...item,targetType:event.target.value}:item));}}>{['case','template','flow','tool'].map(type=><option key={type}>{type}</option>)}</select></label><label>Target ID <input value={row.targetId} onChange={event=>{edit();setWorkloads(current=>current.map((item,i)=>i===index?{...item,targetId:event.target.value}:item));}} /></label></>}
        {row.mix && row.mixEntries.length>0 && <fieldset><legend>Mix bindings</legend><p>Changing a target clears that binding's previous inputs and variables.</p>{row.mixEntries.map((item,mixIndex)=><div key={item.id}>
          <strong>{item.id}</strong><label>Weight <input type="number" min="1" value={item.weight} onChange={event=>{edit();setWorkloads(current=>current.map((entry,i)=>i===index?{...entry,mixEntries:entry.mixEntries.map((member,j)=>j===mixIndex?{...member,weight:event.target.value}:member)}:entry));}} /></label>
          <label>Target type <select value={item.targetType} onChange={event=>{edit();setWorkloads(current=>current.map((entry,i)=>i===index?{...entry,mixEntries:entry.mixEntries.map((member,j)=>j===mixIndex?{...member,targetType:event.target.value}:member)}:entry));}}>{['case','template','flow','tool'].map(type=><option key={type}>{type}</option>)}</select></label>
          <label>Target ID <input value={item.targetId} onChange={event=>{edit();setWorkloads(current=>current.map((entry,i)=>i===index?{...entry,mixEntries:entry.mixEntries.map((member,j)=>j===mixIndex?{...member,targetId:event.target.value}:member)}:entry));}} /></label>
        </div>)}</fieldset>}
        {(['rampUp','warmup','duration','rampDown'] as const).map(field=><label key={field}>{field} <input value={row[field]} onChange={event=>{edit();setWorkloads(current=>current.map((item,i)=>i===index?{...item,[field]:event.target.value}:item));}} /></label>)}
      </fieldset>)}
      <p>Leave phase fields empty to keep the existing timing.</p>{(['rampUp','warmup','duration','rampDown'] as const).map(field=><label key={field}>{field} <input value={phases[field]} onChange={event=>{edit();setPhases(current=>({...current,[field]:event.target.value}));}} /></label>)}
      <fieldset><legend>Common advanced settings</legend>
        {advancedAvailable
          ? <p>These fields are prefilled with the retained complete values. Saving a changed field replaces that whole object; leave a field unchanged to keep it.</p>
          : <p>Retained advanced settings are unavailable for safe prefill on this schedule. Root-object replacement is disabled so undisclosed settings cannot be erased; edit pacing, targets, or environment instead.</p>}
        <label>Thresholds (JSON) <textarea aria-label="Thresholds JSON" value={advanced.thresholds} disabled={!advancedAvailable} onChange={event=>setAdvancedField('thresholds',event.target.value)} /></label>
        <label>Evidence (JSON) <textarea aria-label="Evidence JSON" value={advanced.evidence} disabled={!advancedAvailable} onChange={event=>setAdvancedField('evidence',event.target.value)} /></label>
        <label>Testdata (JSON) <textarea aria-label="Testdata JSON" value={advanced.testdata} disabled={!advancedAvailable} onChange={event=>setAdvancedField('testdata',event.target.value)} /></label>
        <label>Execution defaults (JSON) <textarea aria-label="Execution JSON" value={advanced.execution} disabled={!advancedAvailable} onChange={event=>setAdvancedField('execution',event.target.value)} /></label>
        <label>Seed <input type="number" aria-label="Seed" value={advanced.seed} disabled={!advancedAvailable} onChange={event=>setAdvancedField('seed',event.target.value)} /></label>
      </fieldset>
    </>: schedule.debugTarget ? <><p>Changing the target clears its previous input bindings; validate the new target before saving.</p>
      <label>Target type <select value={runTarget.type} onChange={event=>{edit();setRunTarget(current=>({...current,type:event.target.value}));}}>{['template','flow','tool'].map(type=><option key={type}>{type}</option>)}</select></label>
      <label>Target ID <input value={runTarget.id} onChange={event=>{edit();setRunTarget(current=>({...current,id:event.target.value}));}} /></label>
      <label>Input bindings (JSON) <textarea aria-label="RUN input JSON" value={runInput} onChange={event=>{edit();setRunInput(event.target.value);}} /></label>
      <label><input type="checkbox" checked={runOptions.debug} onChange={event=>{edit();setRunOptions(current=>({...current,debug:event.target.checked}));}} /> Debug</label>
      <label><input type="checkbox" checked={runOptions.failFast} onChange={event=>{edit();setRunOptions(current=>({...current,failFast:event.target.checked}));}} /> Fail fast</label>
    </> : schedule.selectedCaseIds?.length ? <><label>Selected case IDs <input value={cases} onChange={event=>{edit();setCases(event.target.value);}} /></label>
      <label><input type="checkbox" checked={runOptions.debug} onChange={event=>{edit();setRunOptions(current=>({...current,debug:event.target.checked}));}} /> Debug</label>
      <label><input type="checkbox" checked={runOptions.failFast} onChange={event=>{edit();setRunOptions(current=>({...current,failFast:event.target.checked}));}} /> Fail fast</label>
    </> : <p>This RUN target needs the full RUN editor.</p>}
    {error&&<p role="alert">{error}</p>}
    <button type="button" className="button button-quiet" disabled={busy || (schedule.inlineSnapshot && schedule.executionType==='load' && (!workloads.length || Boolean(schedule.targetIdentity?.truncated))) || (schedule.inlineSnapshot && schedule.executionType==='run'&&!schedule.selectedCaseIds?.length&&!schedule.debugTarget)} onClick={() => void validate()}>Validate changes</button>
    {review && <section aria-label="Review execution changes"><h3>Review future snapshot {review.snapshotVersion+1}</h3>
      <p>Package revision {review.revisionDigest} · policy {review.sourcePolicy} · next occurrence {review.nextRunAt || 'none'}</p>
      <ReviewChanges before={previewText(review.originalPreview)} after={previewText(review.preview)} />
      <details><summary>View complete safe previews</summary><h4>Before</h4><pre>{previewText(review.originalPreview)}</pre><h4>After</h4><pre>{previewText(review.preview)}</pre></details>
      <button type="button" className="button button-primary" disabled={busy} onClick={() => void apply()}>Save as new snapshot</button>
    </section>}
  </LoadDialog>;
}

function ReviewChanges({before,after}:{before:string;after:string}) {
  const changes=previewChanges(before,after);
  return <div><h4>Changes in the safe preview</h4>
    <p>Only values visible in the Server's safe preview are compared. Redacted or withheld values may not appear.</p>
    {changes===null?<p>The preview is too large for an inline comparison. Review the complete safe previews below.</p>:
      changes.length===0?<p>No visible changes.</p>:<pre aria-label="Changed preview lines">{changes.map((change,index)=><span key={index}>{change.kind==='removed'?'− Before: ':'+ After: '}{change.line}{'\n'}</span>)}</pre>}
  </div>;
}
