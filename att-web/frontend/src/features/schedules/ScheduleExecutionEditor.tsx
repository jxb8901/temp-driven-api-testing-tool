import { useEffect, useRef, useState } from 'react';
import { useNavigationGuard } from '../../router';
import { LoadDialog } from '../load/LoadDialog';
import type { ScheduleDefinition, ScheduleExecutionReview, SchedulesApi } from './types';

type MixEntry = { id: string; weight: string; targetType: string; targetId: string };
type Workload = { id: string; model: string; rate: string; users: string; maxConcurrent: string; targetType: string; targetId: string; mix: boolean; mixEntries: MixEntry[]; rampUp: string; warmup: string; duration: string; rampDown: string };
/** A bounded editor for the safe schedule summary; validation retains all unedited values server-side. */
export function ScheduleExecutionEditor({ schedule, api, onClose, onSaved }: {
  schedule: ScheduleDefinition; api: SchedulesApi; onClose(): void; onSaved(value: ScheduleDefinition): void;
}) {
  const navigation=useNavigationGuard();
  const [workloads,setWorkloads]=useState<Workload[]>(() => (schedule.targetIdentity?.workloads || []).map(row => ({ id:row.name,model:row.model,rate:String(row.arrivalRate ?? ''),users:String(row.users ?? ''),maxConcurrent:String(row.maxConcurrent ?? ''), targetType:row.targets[0]?.type || '', targetId:row.targets[0]?.id || '', mix:row.mix !== false, mixEntries:(row.mixEntries || []).map(item=>({id:item.id,weight:String(item.weight ?? ''),targetType:item.target.type || '',targetId:item.target.id || ''})), rampUp:String(row.rampUp ?? ''),warmup:String(row.warmup ?? ''),duration:String(row.duration ?? ''),rampDown:String(row.rampDown ?? '') })));
  const initialWorkloads=useRef(workloads);
  const [scenario,setScenario]=useState(schedule.scenario || '');
  const [packageLoad,setPackageLoad]=useState(JSON.stringify(schedule.load || {},null,2));
  const [cases,setCases]=useState((schedule.selectedCaseIds || []).join(', '));
  const [runTarget,setRunTarget]=useState(schedule.debugTarget || {type:'',id:''});
  const [phases,setPhases]=useState({rampUp:'',warmup:'',duration:'',rampDown:''});
  const [changed,setChanged]=useState(false),[busy,setBusy]=useState(false);
  const [review,setReview]=useState<ScheduleExecutionReview | null>(null);
  const [error,setError]=useState('');
  useEffect(() => navigation.registerUnsavedChanges(changed, 'Schedule execution settings'), [changed,navigation.registerUnsavedChanges]);
  function edit() { setChanged(true); setReview(null); setError(''); }
  function close() { if (!changed || window.confirm('Discard execution settings changes?')) onClose(); }
  async function validate() {
    if (!api.createScheduleExecutionDraft) return;
    setBusy(true);setError('');setReview(null);
    try {
      const overrides: Record<string,unknown>={};
      if(!schedule.inlineSnapshot) {
        const parsed: unknown=JSON.parse(packageLoad); if(!parsed || typeof parsed!=='object' || Array.isArray(parsed))throw new Error('Load overrides must be a JSON object.');
        setReview(await api.createScheduleExecutionDraft(schedule.id,{expectedDefinitionVersion:schedule.definitionVersion ?? 0,packageEdit:{scenario,load:parsed}}));return;
      }
      if(schedule.executionType==='run') {
        if(schedule.debugTarget) {
          if(runTarget.type!==schedule.debugTarget.type || runTarget.id!==schedule.debugTarget.id) overrides.target=runTarget;
        } else if(cases.trim()) overrides.selection={caseIds:cases.split(',').map(value=>value.trim()).filter(Boolean)};
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
  return <LoadDialog title="Edit execution settings" onCancel={close}>
    <p>Schedule {schedule.name} · snapshot {schedule.snapshotVersion ?? 1}. These fields change future executions only.</p>
    {!schedule.inlineSnapshot ? <><p>The schedule will continue to resolve its package scenario when it fires.</p><label>Package scenario path <input value={scenario} onChange={event=>{edit();setScenario(event.target.value);}} /></label><label>Load overrides (JSON) <textarea value={packageLoad} onChange={event=>{edit();setPackageLoad(event.target.value);}} /></label></> : schedule.executionType==='load' ? <>
      {workloads.map((row,index)=><fieldset key={row.id}><legend>{row.id}</legend>
        {row.model==='arrivalRate'?<><label>Arrival rate <input value={row.rate} onChange={event=>{edit();setWorkloads(current=>current.map((item,i)=>i===index?{...item,rate:event.target.value}:item));}} /></label>
        <label>Maximum concurrent <input type="number" min="1" value={row.maxConcurrent} onChange={event=>{edit();setWorkloads(current=>current.map((item,i)=>i===index?{...item,maxConcurrent:event.target.value}:item));}} /></label></>:<label>Users <input type="number" min="1" value={row.users} onChange={event=>{edit();setWorkloads(current=>current.map((item,i)=>i===index?{...item,users:event.target.value}:item));}} /></label>}
        {!row.mix && <><label>Target type <select value={row.targetType} onChange={event=>{edit();setWorkloads(current=>current.map((item,i)=>i===index?{...item,targetType:event.target.value}:item));}}>{['case','template','flow','tool'].map(type=><option key={type}>{type}</option>)}</select></label><label>Target ID <input value={row.targetId} onChange={event=>{edit();setWorkloads(current=>current.map((item,i)=>i===index?{...item,targetId:event.target.value}:item));}} /></label></>}
        {row.mix && row.mixEntries.length>0 && <fieldset><legend>Mix bindings</legend><p>Changing a target clears that binding's previous inputs and variables.</p>{row.mixEntries.map((item,mixIndex)=><div key={item.id}>
          <strong>{item.id}</strong><label>Weight <input type="number" min="1" value={item.weight} onChange={event=>{edit();setWorkloads(current=>current.map((entry,i)=>i===index?{...entry,mixEntries:entry.mixEntries.map((member,j)=>j===mixIndex?{...member,weight:event.target.value}:member)}:entry));}} /></label>
          <label>Target type <select value={item.targetType} onChange={event=>{edit();setWorkloads(current=>current.map((entry,i)=>i===index?{...entry,mixEntries:entry.mixEntries.map((member,j)=>j===mixIndex?{...member,targetType:event.target.value}:member)}:entry));}}>{['case','template','flow','tool'].map(type=><option key={type}>{type}</option>)}</select></label>
          <label>Target ID <input value={item.targetId} onChange={event=>{edit();setWorkloads(current=>current.map((entry,i)=>i===index?{...entry,mixEntries:entry.mixEntries.map((member,j)=>j===mixIndex?{...member,targetId:event.target.value}:member)}:entry));}} /></label>
        </div>)}</fieldset>}
        {(['rampUp','warmup','duration','rampDown'] as const).map(field=><label key={field}>{field} <input value={row[field]} onChange={event=>{edit();setWorkloads(current=>current.map((item,i)=>i===index?{...item,[field]:event.target.value}:item));}} /></label>)}
      </fieldset>)}
      <p>Leave phase fields empty to keep the existing timing.</p>{(['rampUp','warmup','duration','rampDown'] as const).map(field=><label key={field}>{field} <input value={phases[field]} onChange={event=>{edit();setPhases(current=>({...current,[field]:event.target.value}));}} /></label>)}
    </>: schedule.debugTarget ? <><p>Changing the target clears its previous input bindings; validate the new target before saving.</p>
      <label>Target type <select value={runTarget.type} onChange={event=>{edit();setRunTarget(current=>({...current,type:event.target.value}));}}>{['template','flow','tool'].map(type=><option key={type}>{type}</option>)}</select></label>
      <label>Target ID <input value={runTarget.id} onChange={event=>{edit();setRunTarget(current=>({...current,id:event.target.value}));}} /></label></> : schedule.selectedCaseIds?.length ? <label>Selected case IDs <input value={cases} onChange={event=>{edit();setCases(event.target.value);}} /></label> : <p>This RUN target needs the full RUN editor.</p>}
    {error&&<p role="alert">{error}</p>}
    <button type="button" className="button button-quiet" disabled={busy || (schedule.inlineSnapshot && schedule.executionType==='load' && (!workloads.length || Boolean(schedule.targetIdentity?.truncated))) || (schedule.inlineSnapshot && schedule.executionType==='run'&&!schedule.selectedCaseIds?.length&&!schedule.debugTarget)} onClick={() => void validate()}>Validate changes</button>
    {review && <section aria-label="Review execution changes"><h3>Review future snapshot {review.snapshotVersion+1}</h3>
      <p>Package revision {review.revisionDigest} · policy {review.sourcePolicy} · next occurrence {review.nextRunAt || 'none'}</p>
      <h4>Before</h4><pre>{review.originalPreview.previewYaml || JSON.stringify(review.originalPreview,null,2)}</pre><h4>After</h4><pre>{review.preview.previewYaml || JSON.stringify(review.preview,null,2)}</pre>
      <button type="button" className="button button-primary" disabled={busy} onClick={() => void apply()}>Save as new snapshot</button>
    </section>}
  </LoadDialog>;
}
