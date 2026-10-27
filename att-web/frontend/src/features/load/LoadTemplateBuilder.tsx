import { EnvironmentResolutionNotice } from '../explorer/EnvironmentResolutionNotice';
import { useEffect, useRef, useState } from 'react';
import { api } from '../../api';
import { useNavigationGuard } from '../../router';
import { LoadDialog } from './LoadDialog';
import { CodeEditor } from '../code/CodeEditor';
import { DurationField, initialTiming, JsonArrayField, JsonObjectField, makeScenario, newTarget, newWorkload, TargetEditor, ThinkTimeField,
  type TargetDraft, type TimingDraft, type WorkloadDraft } from './LoadPage';
import type { LoadDraftResponse, LoadFeatureProps, LoadModel } from './types';
import type { LoadTemplateDefinition } from './templateTypes';
import { effectiveLoadEnvelopeMs, plannedWorkloadDuration } from './loadWorkloadSummary';

type Obj = Record<string, unknown>;
const object = (value: unknown): Obj => value && typeof value === 'object' && !Array.isArray(value) ? value as Obj : {};
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
type Edit = { kind: 'workload'; index: number; value: WorkloadDraft } | { kind: 'target'; index: number; slot?: number; value: TargetDraft }
  | { kind: 'mix'; index: number; value: WorkloadDraft } | { kind: 'settings'; timing: TimingDraft; advanced: Advanced } | { kind: 'review' };
type Advanced = { execution: string; testdata: string; thresholds: string; evidence: string; seed: string };

/** UI projection of the existing Advanced Load draft, using the shared Engine template composer on validation. */
export function LoadTemplateBuilder(props: LoadFeatureProps & { templateId: string }) {
  const { templateId, selectedPackage: packageId, selectedEnvironment: environment, onEnvironmentChange, environments = [] } = props;
  const navigation = useNavigationGuard();
  const [definition, setDefinition] = useState<LoadTemplateDefinition | null>(null);
  const [limits, setLimits] = useState({ maxWorkloads: 128, maxTargets: 256, maxTotalUsers: 0, maxAggregateArrivalRatePerSecond: 0, maxConcurrentPerWorkload: 0, maxTotalConcurrent: 0, maxDurationSeconds: 0 });
  const [revision, setRevision] = useState('');
  const [workloads, setWorkloads] = useState<WorkloadDraft[]>([]);
  const [timing, setTiming] = useState<TimingDraft>({ duration: '', warmup: '', rampUp: '', rampDown: '' });
  const [advanced, setAdvanced] = useState<Advanced>({ execution: '{}', testdata: '[]', thresholds: '{}', evidence: '{}', seed: '' });
  const [edit, setEdit] = useState<Edit | null>(null);
  const [draft, setDraft] = useState<LoadDraftResponse | null>(null);
  const [message, setMessage] = useState('Loading template…');
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [reload, setReload] = useState(0);
  const sequence = useRef(0), loadSequence = useRef(0), slots = useRef(100);
  const [mixTarget, setMixTarget] = useState<{ slot: number; value: TargetDraft } | null>(null);
  const model: LoadModel = definition?.mode.endsWith('tps') ? 'arrivalRate' : 'virtualUsers';
  const effectiveWorkloadEnvelopes=workloads.map(workload=>{
    const phases=['warmup','rampUp','duration','rampDown'].map(field=>String(workload.loadPolicy?.[field]??timing[field as keyof TimingDraft]??''));
    return {id:workload.id,milliseconds:phases[2]?.trim()?effectiveLoadEnvelopeMs(phases):null};
  });
  const effectiveEnvelopeMs=effectiveWorkloadEnvelopes.length&&effectiveWorkloadEnvelopes.every(item=>item.milliseconds!=null)
    ?Math.max(...effectiveWorkloadEnvelopes.map(item=>item.milliseconds!)):null;
  const mixed = Boolean(definition?.mode.endsWith('mix'));
  const multiple = Boolean(definition?.mode.startsWith('multi-'));
  const unregisterDirty = useRef<(() => void) | null>(null);
  useEffect(() => { const unregister = navigation.registerUnsavedChanges(dirty || Boolean(edit && edit.kind !== 'review'), 'Load Template draft'); unregisterDirty.current = unregister; return unregister; }, [dirty, edit, navigation.registerUnsavedChanges]);
  useEffect(() => {
    const controller = new AbortController(); const version = ++loadSequence.current;
    ++sequence.current; setBusy(false);
    setDefinition(null); setDraft(null); setDirty(false); setEdit(null); setMessage('Loading template…');
    (async () => {
      let offset = 0;
      do {
        const page = await api.listLoadTemplates(packageId, templateId, offset, controller.signal);
        if (controller.signal.aborted || version !== loadSequence.current) return;
        const selected = page.items.find(item => item.id === templateId);
        if (selected) {
          if (!selected.definition || selected.validation !== 'valid') throw new Error('The selected template is invalid. Refresh the catalog.');
          const value = selected.definition;
          setDefinition(value); setRevision(page.revisionDigest); if (page.limits) setLimits(page.limits); setTiming(initialTiming(value.policy));
          setAdvanced({ execution: JSON.stringify(value.policy.execution || {}), testdata: JSON.stringify(value.policy.testdata || []), thresholds: JSON.stringify(value.policy.thresholds || {}), evidence: JSON.stringify(value.policy.evidence || {}), seed: String(value.policy.seed ?? '') });
          setWorkloads(value.workloads.map((row, index) => {
            const policy = { ...value.policy, load: { ...object(value.policy.load), ...object(row.load) }, execution: { ...object(value.policy.execution), ...object(row.execution) } };
            return { ...newWorkload(index + 1, policy), loadPolicy: clone(object(row.load)), executionPolicy: clone(object(row.execution)), id: row.id, structure: value.mode.endsWith('mix') ? 'mix' : 'single',
              thresholdsJson: JSON.stringify(row.thresholds || {}), evidenceJson: JSON.stringify(row.evidence || {}),
              mix: (row.mix || []).map(slot => ({ ...newTarget(), entryId: slot.id, weight: String(slot.weight) })) };
          }));
          setMessage('Bind every Target, then validate.'); return;
        }
        if (page.nextOffset == null) throw new Error('Template is missing or no longer permitted. Refresh the catalog.');
        offset = page.nextOffset;
      } while (true);
    })().catch(error => { if (!controller.signal.aborted) setMessage(error.message || 'Template could not be loaded.'); });
    return () => { controller.abort(); ++loadSequence.current; };
  }, [packageId, templateId, reload]);
  useEffect(() => { ++sequence.current; setDraft(null); }, [environment, workloads, timing, advanced]);
  useEffect(() => {
    if (!draft) return;
    const timer = setTimeout(() => { setDraft(null); setMessage('Validated draft expired. Validate again.'); }, Math.max(0,Date.parse(draft.expiresAt)-Date.now()));
    return () => clearTimeout(timer);
  }, [draft]);
  function change(next: WorkloadDraft[]) { setWorkloads(next); setDirty(true); setDraft(null); }
  function update(index: number, value: WorkloadDraft) { change(workloads.map((row, i) => i === index ? value : row)); }
  function close() { setMixTarget(null); setEdit(null); }
  function save() {
    if (!edit) return;
    if (edit.kind === 'workload' || edit.kind === 'mix') update(edit.index, edit.value);
    if (edit.kind === 'target') {
      const row = clone(workloads[edit.index]);
      if (edit.slot == null) row.target = edit.value;
      else row.mix[edit.slot] = { ...row.mix[edit.slot], ...edit.value };
      update(edit.index, row);
    }
    if (edit.kind === 'settings') { setTiming(edit.timing); setAdvanced(edit.advanced); setDirty(true); }
    close();
  }
  function composition() {
    if (!definition) throw new Error('Template is not ready.');
    const scenario = makeScenario(definition.policy, model, timing, workloads, advanced);
    const policy = { ...scenario }; delete policy.workloads; delete policy.schemaVersion;
    const slots: Obj[] = [], bindings: Obj = {};
    for (const raw of scenario.workloads as Obj[]) {
      const row = { ...raw }; const id = String(row.id); const bound: Obj = {};
      if (Array.isArray(row.mix)) {
        const mix: Obj = {};
        row.mix = row.mix.map(value => { const entry = object(value); const selection = { ...object(entry.target), ...(entry.inputs ? { inputs: entry.inputs } : {}), ...(entry.vars ? { vars: entry.vars } : {}) }; mix[String(entry.id)] = selection; return { id: entry.id, weight: entry.weight }; });
        bound.mix = mix;
      } else { bound.target = { ...object(row.target), ...(row.inputs ? { inputs: row.inputs } : {}), ...(row.vars ? { vars: row.vars } : {}) }; delete row.target; }
      for (const key of ['inputs','vars','testdata']) { if (row[key]) bound[key] = row[key]; delete row[key]; }
      slots.push(row); bindings[id] = bound;
    }
    return { templateId, revision, definition: { ...definition, policy, workloads: slots }, bindings: { schemaVersion: 'att-load-bindings/v1', bindings } };
  }
  async function validate() {
    const version = ++sequence.current; setBusy(true); setDraft(null);
    try { const result = await api.createLoadTemplateDraft(packageId, environment, composition()); if (version === sequence.current) { setDraft(result); setMessage('Validation passed. Review YAML before Run or Schedule.'); } }
    catch (error) { if (version === sequence.current) setMessage(error instanceof Error ? error.message : 'Validation failed.'); }
    finally { setBusy(false); }
  }
  async function run() {
    if (!draft || Date.parse(draft.expiresAt) <= Date.now() || busy || !window.confirm(`Start LOAD in ${packageId} / ${environment || 'default'}: ${workloads.length} workloads, ${targets.length} bindings (${unique} unique targets), ${model === 'virtualUsers' ? workloads.reduce((sum,row) => sum + Number(row.users),0) + ' users' : workloads.map(row => row.arrivalRate + ' (max ' + row.maxConcurrent + ' concurrent)').join(', ')}, effective duration ${effectiveEnvelopeMs==null?'requires validation':plannedWorkloadDuration(effectiveEnvelopeMs)}?`)) return;
    setBusy(true);
    try { const accepted = await props.api.submitLoadDraft({ packageId, draftId: draft.draftId }); setDirty(false); unregisterDirty.current?.(); props.onJobAccepted(accepted.jobId); }
    catch (error) { setDraft(null); setMessage(error instanceof Error ? error.message : 'Run was not accepted.'); }
    finally { setBusy(false); }
  }
  function schedule() {
    if (!draft || Date.parse(draft.expiresAt) <= Date.now() || busy || !props.onSchedule) return;
    unregisterDirty.current?.();
    const accepted = props.onSchedule({ packageId, environment, draftId: draft.draftId, previewYaml: draft.previewYaml, expiresAt: draft.expiresAt });
    if (accepted === false) unregisterDirty.current = navigation.registerUnsavedChanges(dirty, 'Load Template draft');
    else setDirty(false);
  }
  const targets = workloads.flatMap(row => row.structure === 'mix' ? row.mix : [row.target]);
  const unique = new Set(targets.filter(target => target.id).map(target => `${target.type}:${target.id}`)).size;
  const valid = Boolean(draft && Date.parse(draft.expiresAt) > Date.now());
  const mixTable = (row: WorkloadDraft, onChange: (value: WorkloadDraft) => void, configure: (slot: number) => void) => {
    const total = row.mix.reduce((sum, slot) => sum + Number(slot.weight), 0);
    return <><div className="att-mix-distribution" role="img" aria-label={row.mix.map(slot => `${slot.entryId}: ${total > 0 ? (100 * Number(slot.weight) / total).toFixed(1) : 0}%`).join(", ")}>{row.mix.map((slot,index) => <span key={slot.entryId} style={{ flexGrow: Math.max(0, Number(slot.weight) || 0), background: `hsl(${index * 83 % 360} 55% 45%)` }} />)}</div><div className="att-table-scroll"><table className="att-data-table"><thead><tr><th>Target</th><th>Weight / probability</th><th>Readiness</th><th>Actions</th></tr></thead><tbody>{row.mix.map((slot,index) => <tr key={slot.entryId}>
      <td>{slot.entryId}: {slot.id || 'Unbound'}</td><td><input aria-label={`${slot.entryId} weight`} type="number" min="1" value={slot.weight} onChange={event => onChange({ ...row, mix: row.mix.map((entry,i) => i === index ? { ...entry, weight: event.target.value } : entry) })} /> {total > 0 ? (100 * Number(slot.weight) / total).toFixed(1) : '0'}%</td>
      <td>{slot.id ? 'Bound; validate' : 'Choose Target'}</td><td><button className="button button-quiet" type="button" onClick={() => configure(index)}>⚙ Choose / Configure</button><button className="button button-quiet" type="button" disabled={row.mix.length <= 2} onClick={() => onChange({ ...row, mix: row.mix.filter((_,i) => i !== index) })}>− Remove</button><button className="button button-quiet" type="button" disabled={index === 0} onClick={() => { const mix = [...row.mix]; [mix[index-1],mix[index]] = [mix[index],mix[index-1]]; onChange({ ...row,mix }); }}>↑ Move up</button><button className="button button-quiet" type="button" disabled={row.mix.length >= 128 || targets.length - (workloads.find(item => item.id === row.id)?.mix.length || 0) + row.mix.length >= limits.maxTargets} onClick={() => onChange({ ...row,mix: [...row.mix,{ ...clone(slot),entryId: `slot-${++slots.current}` }] })}>⧉ Duplicate</button></td>
    </tr>)}</tbody></table></div><button className="button button-quiet" type="button" disabled={row.mix.length >= 128 || targets.length - (workloads.find(item => item.id === row.id)?.mix.length || 0) + row.mix.length >= limits.maxTargets} onClick={() => onChange({ ...row, mix: [...row.mix, { ...newTarget(), entryId: `slot-${++slots.current}`, weight: '1' }] })}>＋ Add Target</button><p>Weights are iteration selection probabilities within this workload.</p></>;
  };
  return <div className="page-content att-query-workspace att-template-builder">
    <h1>{definition?.name || 'Load Template'}</h1><p>{packageId} · {definition?.mode} · {message}</p>
    {!definition && <button className="button button-quiet" type="button" onClick={() => setReload(value => value + 1)}>Retry</button>}
    <label>Environment <select value={environment} onChange={event => onEnvironmentChange(event.target.value)}><option value="">Default environment</option>{environments.map(value => <option key={value}>{value}</option>)}</select></label>
      <EnvironmentResolutionNotice packageId={packageId} environment={environment} />
    <aside className="att-help" role="note">
      <p>Browser-created and package-backed LOAD scenarios follow the Server duration limit shown below (up to 10 days). The limit applies to warmup, ramp-up, steady duration, and ramp-down combined.</p>
      <button className="button button-quiet" type="button" onClick={() => navigation.navigate({ kind: 'load', tab: 'scenarios', catalog: { packageFilter: packageId, environment } })}>Open package LOAD scenarios</button>
    </aside>
    <p>{workloads.length} Workloads · {targets.length} Target bindings · {unique} Unique targets · {model === 'virtualUsers' ? `${workloads.reduce((sum,row) => sum + Number(row.users),0)} total VU` : `${workloads.reduce((sum,row) => { const [rate,unit] = row.arrivalRate.split('/'); return sum + Number(rate) / (unit === 'm' ? 60 : 1); },0)} configured arrivals/sec`}</p>
    {limits.maxTotalUsers > 0 && <p className="att-help">Server limits: {limits.maxWorkloads} workloads · {limits.maxTargets} bindings · {limits.maxTotalUsers} users · {limits.maxAggregateArrivalRatePerSecond} arrivals/sec · {limits.maxTotalConcurrent} total concurrent · {plannedWorkloadDuration(limits.maxDurationSeconds*1000)} ({limits.maxDurationSeconds}s) effective duration</p>}
    {definition && <p className="att-help">Current effective envelope: {effectiveEnvelopeMs==null?'requires valid phase values':`${plannedWorkloadDuration(effectiveEnvelopeMs)} maximum across ${effectiveWorkloadEnvelopes.length} workloads`} (warmup + ramp-up + steady duration + ramp-down).</p>}
    {definition && <>
      <button className="button button-quiet" type="button" onClick={() => setEdit({ kind: 'settings', timing: clone(timing), advanced: clone(advanced) })}>⚙ Common Settings</button>
      {!multiple && mixed ? <><label>Shared users <input type="number" min="1" value={workloads[0]?.users || ''} onChange={event => update(0,{ ...workloads[0], users: event.target.value })} /></label>{workloads[0] && mixTable(workloads[0], value => update(0,value), slot => setEdit({ kind: 'target', index: 0, slot, value: clone(workloads[0].mix[slot]) }))}</> :
        <div className="att-table-scroll"><table className="att-data-table"><thead><tr><th>Workload</th><th>{mixed ? 'Mix / bindings' : 'Target'}</th><th>{model === 'virtualUsers' ? 'Users' : 'Target TPS / maximum concurrent'}</th><th>Readiness</th><th>Actions</th></tr></thead><tbody>{workloads.map((row,index) => <tr key={row.id}>
          <td>{row.id}</td><td>{mixed ? <>{row.mix.length} bindings · {row.mix.map(slot => slot.weight).join(' / ')}<div className="att-mix-distribution" role="img" aria-label={`Weights for ${row.id}: ${row.mix.map(slot => slot.weight).join(' / ')}`}>{row.mix.map((slot,index) => <span key={slot.entryId} style={{ flexGrow: Math.max(0,Number(slot.weight) || 0),background: `hsl(${index * 83 % 360} 55% 45%)` }} />)}</div></> : row.target.id || 'Unbound'}</td><td>{model === 'virtualUsers' ? row.users : `${row.arrivalRate} / ${row.maxConcurrent}`}</td>
          <td>{(mixed ? row.mix.every(slot => slot.id) : row.target.id) ? (valid ? 'Validated' : 'Bound; validate') : 'Choose Target'}</td>
          <td><button className="button button-quiet" type="button" onClick={() => setEdit({ kind: mixed ? 'mix' : 'target', index, value: clone(mixed ? row : row.target) } as Edit)}>{mixed ? '⤨ Edit Mix' : '⚙ Choose Target'}</button><button className="button button-quiet" type="button" onClick={() => setEdit({ kind: 'workload', index, value: clone(row) })}>⚙ Configure</button>
            <button className="button button-quiet" type="button" disabled={workloads.length <= 2} onClick={() => change(workloads.filter((_,i) => i !== index))}>− Remove</button><button className="button button-quiet" type="button" disabled={index === 0} onClick={() => { const next = [...workloads]; [next[index-1],next[index]] = [next[index],next[index-1]]; change(next); }}>↑ Move up</button><button className="button button-quiet" type="button" disabled={workloads.length >= Math.min(128,limits.maxWorkloads) || targets.length + (mixed ? workloads[0]?.mix.length || 2 : 1) > limits.maxTargets} onClick={() => change([...workloads,{ ...clone(row),id: `workload-${++slots.current}` }])}>⧉ Duplicate</button></td>
        </tr>)}</tbody></table></div>}
      {multiple && <button className="button button-quiet" type="button" disabled={workloads.length >= Math.min(128,limits.maxWorkloads) || targets.length + (mixed ? workloads[0]?.mix.length || 2 : 1) > limits.maxTargets} onClick={() => { const row = clone(workloads[0]); row.id = `workload-${++slots.current}`; row.target = newTarget(); row.mix = row.mix.map(slot => ({ ...slot, ...newTarget() })); change([...workloads,row]); }}>＋ Add Workload</button>}
      <footer className="att-load-actions"><button className="button button-quiet" type="button" onClick={() => void validate()} disabled={busy || !props.inlineLoadEnabled}>✓ Validate</button><button className="button button-quiet" type="button" disabled={!valid} onClick={() => setEdit({ kind: 'review' })}>▤ Review YAML</button><button className="button button-quiet" type="button" disabled={!valid || busy} onClick={() => void run()}>▶ Run Now</button><button className="button button-quiet" type="button" disabled={!valid || !props.schedulingEnabled || !props.onSchedule || busy} onClick={schedule}>◷ Schedule…</button></footer>
    </>}
    {edit && <LoadDialog title={edit.kind === 'review' ? 'Review Scenario' : edit.kind === 'settings' ? 'Common Settings' : edit.kind === 'mix' ? 'Edit Mix' : edit.kind === 'target' ? 'Configure Target' : 'Configure Workload'} onCancel={close} onSave={edit.kind === 'review' ? undefined : save}>
      {edit.kind === 'review' && <CodeEditor label="Validated scenario YAML" language="yaml" value={draft?.previewYaml || ''} readOnly />}
      {edit.kind === 'target' && <TargetEditor api={props.api} target={edit.value} packageId={packageId} environment={environment} model={model} inlineLoadEnabled={props.inlineLoadEnabled} onChange={value => setEdit({ ...edit, value })} />}
      {edit.kind === 'workload' && <>
        {model === 'virtualUsers' ? <><label>Users <input type="number" min="1" value={edit.value.users} onChange={event => setEdit({ ...edit, value: { ...edit.value, users: event.target.value } })} /></label><ThinkTimeField value={edit.value.thinkTime} disabled={false} onChange={thinkTime => setEdit({ ...edit, value: { ...edit.value, thinkTime } })} /></> : <><label>Target arrivals/sec or /m <input value={edit.value.arrivalRate} onChange={event => setEdit({ ...edit, value: { ...edit.value, arrivalRate: event.target.value } })} /></label><label>Max Concurrent <input type="number" min="1" value={edit.value.maxConcurrent} onChange={event => setEdit({ ...edit, value: { ...edit.value, maxConcurrent: event.target.value } })} /></label><p>Configured arrivals are targets; actual throughput and generator drops are reported separately.</p></>}
        {(['testdataJson','thresholdsJson','evidenceJson', ...(mixed ? ['mixDefaultsJson'] : [])] as Array<keyof WorkloadDraft>).map(key => <JsonObjectField key={key} label={key.replace('Json','')} value={String(edit.value[key])} onChange={value => setEdit({ ...edit, value: { ...edit.value, [key]: value } })} />)}
      </>}
      {edit.kind === 'mix' && mixTable(edit.value, value => setEdit({ ...edit,value }), slot => setMixTarget({ slot, value: clone(edit.value.mix[slot]) }))}
      {edit.kind === 'settings' && <>
        {(Object.keys(edit.timing) as Array<keyof TimingDraft>).map(key => <DurationField key={key} label={key} value={edit.timing[key]} onChange={value => setEdit({ ...edit, timing: { ...edit.timing, [key]: value } })} />)}
        {(['execution','thresholds','evidence'] as const).map(key => <JsonObjectField key={key} label={key} value={edit.advanced[key]} onChange={value => setEdit({ ...edit, advanced: { ...edit.advanced, [key]: value } })} />)}
        <JsonArrayField label="Package Testdata paths" value={edit.advanced.testdata} onChange={value => setEdit({ ...edit, advanced: { ...edit.advanced, testdata: value } })} />
        <label>Seed <input type="number" value={edit.advanced.seed} onChange={event => setEdit({ ...edit, advanced: { ...edit.advanced, seed: event.target.value } })} /></label>
      </>}
    </LoadDialog>}
    {mixTarget && edit?.kind === 'mix' && <LoadDialog title="Configure Mix Target" onCancel={() => setMixTarget(null)} onSave={() => {
      setEdit({ ...edit, value: { ...edit.value, mix: edit.value.mix.map((slot,index) => index === mixTarget.slot ? { ...slot, ...mixTarget.value } : slot) } }); setMixTarget(null);
    }}><TargetEditor api={props.api} target={mixTarget.value} packageId={packageId} environment={environment} model={model} inlineLoadEnabled={props.inlineLoadEnabled} onChange={value => setMixTarget({ ...mixTarget,value })} /></LoadDialog>}
  </div>;
}
