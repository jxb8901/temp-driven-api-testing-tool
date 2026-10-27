import { EnvironmentResolutionNotice } from '../explorer/EnvironmentResolutionNotice';
import React, { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import './load.css';
import { TypedSidecarForm, type SidecarGroup } from '../explorer/TypedSidecarForm';
import { ThresholdEvidenceEditor } from './ThresholdEvidenceEditor';
import { CodeEditor } from '../code/CodeEditor';
import type { JsonObject as SidecarJsonObject } from '../explorer/types';
import type {
  LoadFeatureProps,
  LoadModel,
  LoadTargetType,
  ServerDiagnostic,
  TargetResource,
} from './types';
import type { JobReplay, JobReplayEditorDraft } from './types';
import { useNavigationGuard } from '../../router';

type JsonObject = Record<string, unknown>;
type ThinkTime = string | { min: string; max: string };
export type TargetDraft = { type: LoadTargetType; id: string; businessJson: string; businessValid: boolean };
export type MixDraft = TargetDraft & { entryId: string; weight: string };
export type WorkloadDraft = {
  id: string;
  loadPolicy?: JsonObject;
  executionPolicy?: JsonObject;
  users: string;
  arrivalRate: string;
  maxConcurrent: string;
  overloadPolicy: string;
  thinkTime: ThinkTime;
  structure: 'single' | 'mix';
  testdataJson: string;
  thresholdsJson: string;
  evidenceJson: string;
  mixDefaultsJson: string;
  mixDefaultsValid: boolean;
  target: TargetDraft;
  mix: MixDraft[];
};
export type TimingDraft = { warmup: string; rampUp: string; duration: string; rampDown: string };

const emptyBusiness = (type: LoadTargetType): JsonObject => ({
  inputs: {},
  ...(type === 'tool' ? { arguments: {} } : { vars: {} }),
});

export function newTarget(type: LoadTargetType = 'template'): TargetDraft {
  return { type, id: '', businessJson: JSON.stringify(emptyBusiness(type), null, 2), businessValid: true };
}

export function newWorkload(id: number, policy: JsonObject = {}): WorkloadDraft {
  const load = asObject(policy.load);
  const execution = asObject(policy.execution);
  return {
    id: `workload-${id}`,
    users: String(load.users ?? 1),
    arrivalRate: String(load.arrivalRate ?? '1/s'),
    maxConcurrent: String(load.maxConcurrent ?? 1),
    overloadPolicy: String(load.overloadPolicy ?? 'drop'),
    thinkTime: typeof execution.thinkTime === 'string' ? execution.thinkTime : execution.thinkTime && typeof execution.thinkTime === 'object' ? { min: String(asObject(execution.thinkTime).min ?? ''), max: String(asObject(execution.thinkTime).max ?? '') } : '',
    structure: 'single',
    testdataJson: '{}',
    thresholdsJson: '{}',
    evidenceJson: '{}',
    mixDefaultsJson: '{\n  "inputs": {},\n  "vars": {}\n}',
    mixDefaultsValid: true,
    target: newTarget(),
    mix: [],
  };
}

function asObject(value: unknown): JsonObject {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as JsonObject : {};
}

function parseObjectJson(raw: string, label: string): JsonObject {
  let value: unknown;
  try { value = JSON.parse(raw || '{}'); }
  catch { throw new Error(`${label} must be valid JSON.`); }
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label} must be a JSON object.`);
  return value as JsonObject;
}

function parseThinkTime(value: ThinkTime): string | { min: string; max: string } | undefined {
  if (typeof value === 'string') return value.trim() || undefined;
  const min = value.min.trim();
  const max = value.max.trim();
  if (!min && !max) return undefined;
  return { min, max };
}

function mergeBusinessJson(raw: string, targetType: LoadTargetType, defaults: JsonObject): string {
  const current = parseObjectJson(raw, 'Target values');
  const incoming = asObject(defaults);
  const otherKey = targetType === 'tool' ? 'vars' : 'arguments';
  const activeKey = targetType === 'tool' ? 'arguments' : 'vars';
  const merged = {
    ...current,
    inputs: asObject(incoming.inputs),
    [activeKey]: asObject(incoming[activeKey]),
    [otherKey]: current[otherKey] ?? {},
  };
  return JSON.stringify(merged, null, 2);
}

function businessForTarget(raw: string, type: LoadTargetType): { inputs: JsonObject; vars: JsonObject; arguments: JsonObject } {
  const parsed = parseObjectJson(raw, 'Target inputs and variables');
  const inputs = asObject(parsed.inputs);
  const vars = asObject(parsed.vars);
  const args = asObject(parsed.arguments);
  return type === 'tool' ? { inputs, vars: {}, arguments: args } : { inputs, vars, arguments: {} };
}

export function makeScenario(
  policy: JsonObject,
  model: LoadModel,
  timing: TimingDraft,
  workloads: WorkloadDraft[],
  advanced: { execution: string; testdata: string; thresholds: string; evidence: string; seed: string },
): JsonObject {
  const scenario: JsonObject = { ...policy, schemaVersion: String(policy.schemaVersion || 'att-load/v1.9') };
  const rootLoad = { ...asObject(policy.load) };
  for (const field of ['warmup', 'rampUp', 'duration', 'rampDown'] as const) {
    const value = timing[field].trim();
    if (value) rootLoad[field] = value;
    else delete rootLoad[field];
  }
  if (!timing.duration.trim()) throw new Error('Measured duration is required.');
  scenario.load = rootLoad;
  scenario.workloads = workloads.map(workload => {
    if (!['case', 'template', 'flow', 'tool'].includes(workload.target.type))
      throw new Error(`Workload ${workload.id} has unsupported target type “${workload.target.type}”. Choose a supported target type before validating.`);
    const row: JsonObject = { id: workload.id.trim(), load: {} };
    if (!row.id) throw new Error('Every workload needs an ID.');
    const load: JsonObject = { ...workload.loadPolicy };
    const execution: JsonObject = { ...workload.executionPolicy };
    if (model === 'virtualUsers') {
      const users = Number(workload.users);
      if (!Number.isSafeInteger(users) || users < 1) throw new Error(`Workload ${row.id} needs a positive whole number of users.`);
      load.users = users;
      const thinkTime = parseThinkTime(workload.thinkTime);
      if (thinkTime) execution.thinkTime = thinkTime; else delete execution.thinkTime;
    } else {
      if (!workload.arrivalRate.trim()) throw new Error(`Workload ${row.id} needs an arrival rate.`);
      const maxConcurrent = Number(workload.maxConcurrent);
      if (!Number.isSafeInteger(maxConcurrent) || maxConcurrent < 1) throw new Error(`Workload ${row.id} needs positive maximum concurrency.`);
      if (!workload.overloadPolicy.trim()) throw new Error(`Workload ${row.id} needs an explicit overload policy.`);
      Object.assign(load, { arrivalRate: workload.arrivalRate.trim(), maxConcurrent, overloadPolicy: workload.overloadPolicy.trim() });
    }
    row.load = load;
    if (Object.keys(execution).length) row.execution = execution;
    const testdata = parseObjectJson(workload.testdataJson, `Workload ${row.id} Testdata`);
    const thresholds = parseObjectJson(workload.thresholdsJson, `Workload ${row.id} thresholds`);
    const evidence = parseObjectJson(workload.evidenceJson, `Workload ${row.id} evidence`);
    if (Object.keys(testdata).length) row.testdata = testdata;
    if (Object.keys(thresholds).length) row.thresholds = thresholds;
    if (Object.keys(evidence).length) row.evidence = evidence;
    if (model === 'virtualUsers' && workload.structure === 'mix') {
      if (!workload.mix.length) throw new Error(`Workload ${row.id} needs at least one mix target.`);
      if (!workload.mixDefaultsValid) throw new Error(`Workload ${row.id} has invalid mix defaults.`);
      const defaults = parseObjectJson(workload.mixDefaultsJson, `Workload ${row.id} mix defaults`);
      row.inputs = asObject(defaults.inputs);
      row.vars = asObject(defaults.vars);
      row.mix = workload.mix.map(entry => {
        if (!['case', 'template', 'flow', 'tool'].includes(entry.type))
          throw new Error(`Workload ${workload.id} mix target type “${entry.type}” is unsupported. Choose a supported target type before validating.`);
        if (!entry.businessValid) throw new Error(`Mix target ${entry.id} has invalid business values.`);
        const weight = Number(entry.weight);
        if (!Number.isSafeInteger(weight) || weight < 1) throw new Error(`Mix target ${entry.id} needs a positive whole-number weight.`);
        if (!entry.entryId.trim()) throw new Error('Every mix target needs an ID.');
        if (!entry.id.trim()) throw new Error('Every mix target needs a bound Target.');
        const values = businessForTarget(entry.businessJson, entry.type);
        const target: JsonObject = { type: entry.type, id: entry.id.trim() };
        if (entry.type === 'tool' && Object.keys(values.arguments).length) target.arguments = values.arguments;
        const mix: JsonObject = { id: entry.entryId.trim(), weight, target };
        if (Object.keys(values.inputs).length) mix.inputs = values.inputs;
        if (entry.type !== 'tool' && Object.keys(values.vars).length) mix.vars = values.vars;
        return mix;
      });
      if (workload.mix.some(entry => entry.type === 'tool') && Object.keys(asObject(row.vars)).length)
        throw new Error(`Workload ${row.id} cannot use workload variables with Tool mix entries.`);
    } else {
      if (!workload.target.businessValid) throw new Error(`Workload ${row.id} has invalid business values.`);
      const values = businessForTarget(workload.target.businessJson, workload.target.type);
      const target: JsonObject = { type: workload.target.type, id: workload.target.id.trim() };
      if (!target.id) throw new Error(`Workload ${row.id} needs a target logical ID.`);
      if (workload.target.type === 'tool' && Object.keys(values.arguments).length) target.arguments = values.arguments;
      row.target = target;
      if (Object.keys(values.inputs).length) row.inputs = values.inputs;
      if (workload.target.type !== 'tool' && Object.keys(values.vars).length) row.vars = values.vars;
    }
    return row;
  });

  const execution = parseObjectJson(advanced.execution, 'Root execution defaults');
  const testdata = JSON.parse(advanced.testdata || '[]') as unknown;
  if (!Array.isArray(testdata) || testdata.some(item => typeof item !== 'string')) throw new Error('Scenario Testdata must be an array of package-relative paths.');
  const thresholds = parseObjectJson(advanced.thresholds, 'Aggregate thresholds');
  const evidence = parseObjectJson(advanced.evidence, 'Evidence settings');
  if (Object.keys(execution).length) scenario.execution = execution; else delete scenario.execution;
  if (testdata.length) scenario.testdata = testdata; else delete scenario.testdata;
  if (Object.keys(thresholds).length) scenario.thresholds = thresholds; else delete scenario.thresholds;
  if (Object.keys(evidence).length) scenario.evidence = evidence; else delete scenario.evidence;
  if (evidence.maxFailureSamples != null || evidence.maxSuccessSamples != null
      || evidence.quotaScope != null || evidence.defaults != null) scenario.schemaVersion = 'att-load/v1.9';
  if (advanced.seed.trim()) {
    const seed = Number(advanced.seed);
    if (!Number.isSafeInteger(seed)) throw new Error('Seed must be a safe integer.');
    scenario.seed = seed;
  } else delete scenario.seed;
  return scenario;
}

export function initialTiming(policy: JsonObject): TimingDraft {
  const rootLoad = asObject(policy.load);
  const rows = Array.isArray(policy.workloads) ? policy.workloads : [];
  const load = { ...asObject(asObject(rows[0]).load), ...rootLoad };
  return {
    warmup: String(load.warmup ?? ''),
    rampUp: String(load.rampUp ?? ''),
    duration: String(load.duration ?? ''),
    rampDown: String(load.rampDown ?? ''),
  };
}

export function retainedScenarioToEditor(value: unknown): { model: LoadModel; timing: TimingDraft; workloads: WorkloadDraft[]; advanced: { execution: string; testdata: string; thresholds: string; evidence: string; seed: string }; diagnostics: ServerDiagnostic[] } {
  const scenario=asObject(value),rootLoad=asObject(scenario.load),rows=Array.isArray(scenario.workloads)?scenario.workloads:[];
  if(!rows.length)throw new Error('The retained LOAD draft has no workloads.');
  const targetDiagnostics:ServerDiagnostic[]=[];
  const firstLoad={...rootLoad,...asObject(asObject(rows[0]).load)};
  const isArrival=firstLoad.arrivalRate!=null;
  const model:LoadModel=isArrival?'arrivalRate':'virtualUsers';
  const phaseFields=['warmup','rampUp','duration','rampDown'] as const;
  const timing=Object.fromEntries(phaseFields.map(field=>[field,String(firstLoad[field]??'')])) as TimingDraft;
  for(const raw of rows){const load=asObject(asObject(raw).load);for(const field of phaseFields){
    const explicit=load[field];if(rootLoad[field]!=null&&explicit!=null&&String(rootLoad[field])!==String(explicit))throw new Error(`The retained LOAD has conflicting ${field} values. Review the source Job before editing.`);
    if(rootLoad[field]==null&&explicit!=null&&String(explicit)!==timing[field])throw new Error(`The retained LOAD workloads do not share one ${field} value. Review the source Job before editing.`);
  }}
  const targetDraft=(raw:unknown,inputs:unknown,vars:unknown,field:string):TargetDraft=>{
    const target=asObject(raw),rawType=String(target.type||'template');
    const supported=['case','template','flow','tool'].includes(rawType);
    if(!supported)targetDiagnostics.push({code:'ATT-LOAD-UNSUPPORTED-TARGET-TYPE',field,summary:`Retained target type “${rawType}” is unsupported. Choose a supported target type explicitly.`});
    const type=(supported?rawType:`unsupported:${rawType}`) as LoadTargetType;
    const business={inputs:asObject(inputs),...(rawType==='tool'?{arguments:asObject(target.arguments)}:{vars:asObject(vars)})};
    return {type,id:String(target.id||''),businessJson:JSON.stringify(business,null,2),businessValid:true};
  };
  const workloads=rows.map((raw,index)=>{
    const row=asObject(raw),load={...rootLoad,...asObject(row.load)},execution=asObject(row.execution),mixRows=Array.isArray(row.mix)?row.mix:[];
    const mix:MixDraft[]=mixRows.map((rawMix,mixIndex)=>{
      const item=asObject(rawMix),target=asObject(item.target),targetDraftValue=targetDraft(target,item.inputs,item.vars,`scenario.workloads[${index}].mix[${mixIndex}].target.type`);
      return {...targetDraftValue,entryId:String(item.id??''),weight:String(item.weight??'')};
    });
    const target=targetDraft(row.target,row.inputs,row.vars,`scenario.workloads[${index}].target.type`);
    if(row.target&&target.type==='tool')target.businessJson=JSON.stringify({inputs:asObject(row.inputs),arguments:asObject(asObject(row.target).arguments)},null,2);
    const testdata=asObject(row.testdata),thresholds=asObject(row.thresholds),evidence=asObject(row.evidence);
    const pacing={...load};for(const field of phaseFields)delete pacing[field];
    return {
      ...newWorkload(index+1,{load:pacing,execution}),id:String(row.id??''),loadPolicy:pacing,executionPolicy:{...execution},
      users:String(load.users??''),arrivalRate:String(load.arrivalRate??''),maxConcurrent:String(load.maxConcurrent??''),overloadPolicy:String(load.overloadPolicy??''),
      structure:(mix.length?'mix':'single') as WorkloadDraft['structure'],testdataJson:JSON.stringify(testdata,null,2),thresholdsJson:JSON.stringify(thresholds,null,2),evidenceJson:JSON.stringify(evidence,null,2),
      mixDefaultsJson:JSON.stringify({inputs:asObject(row.inputs),vars:asObject(row.vars)},null,2),mixDefaultsValid:true,target,mix,
    };
  });
  const advanced={execution:JSON.stringify(asObject(scenario.execution),null,2),testdata:JSON.stringify(Array.isArray(scenario.testdata)?scenario.testdata:[],null,2),
    thresholds:JSON.stringify(asObject(scenario.thresholds),null,2),evidence:JSON.stringify(asObject(scenario.evidence),null,2),seed:scenario.seed==null?'':String(scenario.seed)};
  return {model,timing,workloads,advanced,diagnostics:targetDiagnostics};
}

function effectiveDuration(timing:TimingDraft):string {
  const phases=[timing.warmup,timing.rampUp,timing.duration,timing.rampDown].filter(Boolean);
  let total=0;
  for(const phase of phases){const match=/^(\d+)(ms|s|m|h)$/.exec(phase.trim());if(!match)return 'Requires validation';const factor=match[2]==='ms'?1:match[2]==='s'?1000:match[2]==='m'?60000:3600000;total+=Number(match[1])*factor;}
  return `${total} ms (sum of warm-up, ramp-up, measured duration and ramp-down)`;
}

export function LoadPage(props: LoadFeatureProps): React.JSX.Element {
  const {
    api, packages, selectedPackage, selectedEnvironment, environments = [], environmentState, inlineLoadEnabled,
    schedulingEnabled = false, principal, replayDraftId, replaySourceJobId, onPackageChange, onEnvironmentChange, onJobAccepted, onSchedule, confirmStart, onRetryEnvironments,
  } = props;
  const [model, setModel] = useState<LoadModel>('virtualUsers');
  const [policy, setPolicy] = useState<JsonObject | null>(null);
  const [policyState, setPolicyState] = useState<'idle' | 'loading' | 'ready' | 'error' | 'disabled'>('idle');
  const [policyMessage, setPolicyMessage] = useState('Choose a package to load the Server policy.');
  const [timing, setTiming] = useState<TimingDraft>({ warmup: '', rampUp: '', duration: '', rampDown: '' });
  const [workloads, setWorkloads] = useState<WorkloadDraft[]>([]);
  const [advanced, setAdvanced] = useState({ execution: '{}', testdata: '[]', thresholds: '{}', evidence: '{}', seed: '' });
  const [draft, setDraft] = useState<import('./types').LoadDraftResponse | null>(null);
  const [draftState, setDraftState] = useState<'idle' | 'validating' | 'valid' | 'error' | 'submitting'>('idle');
  const [confirmationPending, setConfirmationPending] = useState(false);
  const [draftMessage, setDraftMessage] = useState('Validate to review the Server-normalized scenario.');
  const [diagnostics, setDiagnostics] = useState<ServerDiagnostic[]>([]);
  const [requestError, setRequestError] = useState('');
  const [requestErrorCode, setRequestErrorCode] = useState('');
  const [rawScenarioJson, setRawScenarioJson] = useState('');
  const [advancedJsonError, setAdvancedJsonError] = useState('');
  const [thresholdEditorErrors, setThresholdEditorErrors] = useState<Record<string, string>>({});
  const [replayEditorDefinition, setReplayEditorDefinition] = useState<JobReplayEditorDraft['definition'] | null>(null);
  const [replayOriginalDefinition, setReplayOriginalDefinition] = useState<JobReplayEditorDraft['definition'] | null>(null);
  const [replayReview, setReplayReview] = useState<JobReplay | null>(null);
  const [replayDraftLoading, setReplayDraftLoading] = useState(Boolean(replayDraftId&&replaySourceJobId));
  const [replayDraftError, setReplayDraftError] = useState('');
  const [replayDirty, setReplayDirty] = useState(false);
  const [missingReplayFields, setMissingReplayFields] = useState<string[]>([]);
  const navigation=useNavigationGuard();
  const requestSequence = useRef(0);
  const replayValidationGeneration = useRef(0);
  const packageRef = useRef(selectedPackage);
  const submitLock = useRef(false);
  const releaseReplayNavigationGuard = useRef<() => void>(() => {});
  const replaySeeded = useRef(false);
  const replayEnvironmentOrigin = useRef('');
  const replayPackageDefaultEnvironment = useRef('');
  useEffect(()=>{
    const release=navigation.registerUnsavedChanges(Boolean(replaySourceJobId&&replayDirty),'Edited Job replay settings');
    releaseReplayNavigationGuard.current=release;
    return()=>{release();if(releaseReplayNavigationGuard.current===release)releaseReplayNavigationGuard.current=()=>{};};
  },[navigation.registerUnsavedChanges,replayDirty,replaySourceJobId]);
  function markReplayDirty(){if(replaySourceJobId){replayValidationGeneration.current++;setReplayDirty(true);setReplayReview(null);setDraft(null);setDraftState('idle');}}

  useEffect(() => {
    if(!replayDraftId||!replaySourceJobId)return;
    if(!api.getJobReplayEditorDraft){setReplayDraftError('This Server client cannot load editable Job drafts.');setReplayDraftLoading(false);return;}
    const controller=new AbortController();replaySeeded.current=false;setReplayDraftLoading(true);setReplayDraftError('');
    api.getJobReplayEditorDraft(replaySourceJobId,replayDraftId).then(draft=>{
      if(controller.signal.aborted)return;
      if(draft.executionType!=='load')throw new Error('This Job contains a RUN definition, not a LOAD scenario.');
      setReplayEditorDefinition(draft.definition);setReplayOriginalDefinition(draft.originalDefinition||draft.definition);const scenario=asObject(draft.definition.scenario);
      replayEnvironmentOrigin.current=String(draft.configuration?.environmentOrigin||'');
      replayPackageDefaultEnvironment.current=replayEnvironmentOrigin.current==='package'?String(draft.configuration?.environment||''):'';
      const rows=Array.isArray(scenario.workloads)?scenario.workloads:[];
      setMissingReplayFields(draft.missingFields||[]);
      setModel(asObject(asObject(rows[0]).load).arrivalRate!=null||asObject(scenario.load).arrivalRate!=null?'arrivalRate':'virtualUsers');
      setReplayReview({jobId:draft.sourceJobId,available:true,...draft,definition:draft.definition,originalDefinition:draft.originalDefinition||draft.definition});
      setDraft(null);setDraftState(draft.validationState==='invalid'?'error':'idle');setDraftMessage(draft.validationState==='invalid'?'The retained settings are ready to edit. Resolve the current Package errors, then validate again.':'Job settings loaded. Edit the fields, then validate against the current Package.');
      setDiagnostics(draft.diagnostics||[]);setRequestError('');setRequestErrorCode('');setReplayDirty(false);
    }).catch(error=>{if(!controller.signal.aborted){setReplayDraftError(error instanceof Error?error.message:'The replay draft expired. Open the source Job again.');setReplayDraftLoading(false);}});
    return()=>controller.abort();
  },[api,replayDraftId,replaySourceJobId]);

  useEffect(() => {
    const controller = new AbortController();
    const sequence = ++requestSequence.current;
    const packageChanged = packageRef.current !== selectedPackage;
    packageRef.current = selectedPackage;
    setDraft(null);
    setDraftState('idle');
    setDiagnostics([]);
    setRequestError('');
    setRequestErrorCode('');
    if (packageChanged) {
      setPolicy(null);
      setWorkloads([]);
      setTiming({ warmup: '', rampUp: '', duration: '', rampDown: '' });
      setAdvanced({ execution: '{}', testdata: '[]', thresholds: '{}', evidence: '{}', seed: '' });
    }
    if (!selectedPackage) {
      setPolicyState('idle');
      setPolicyMessage('Choose a package to load the Server policy.');
      return () => controller.abort();
    }
    if (!inlineLoadEnabled && !replaySourceJobId) {
      setPolicyState('disabled');
      setPolicyMessage('Browser-created Load drafts are disabled by Server configuration.');
      return () => controller.abort();
    }
    setPolicyState('loading');
    setPolicyMessage('Loading safe model policy defaults…');
    api.getLoadPolicy(selectedPackage, model, selectedEnvironment || undefined, controller.signal)
      .then(response => {
        if (sequence !== requestSequence.current) return;
        const nextPolicy = asObject(response.policy);
        setPolicy(nextPolicy);
        setPolicyState('ready');
        setPolicyMessage(response.redacted
          ? 'Safe policy defaults loaded. Sensitive values remain hidden.'
          : 'Policy defaults loaded. Server validation is required before starting.');
        setWorkloads(current => packageChanged || current.length === 0 ? [newWorkload(1, nextPolicy)] : current);
        if (packageChanged || !policy) {
          setTiming(initialTiming(nextPolicy));
          setAdvanced({
            execution: JSON.stringify(asObject(nextPolicy.execution), null, 2),
            testdata: JSON.stringify(Array.isArray(nextPolicy.testdata) ? nextPolicy.testdata : [], null, 2),
            thresholds: JSON.stringify(asObject(nextPolicy.thresholds), null, 2),
            evidence: JSON.stringify(asObject(nextPolicy.evidence), null, 2),
            seed: nextPolicy.seed == null ? '' : String(nextPolicy.seed),
          });
        }
      })
      .catch(error => {
        if (controller.signal.aborted || sequence !== requestSequence.current) return;
        setPolicyState('error');
        setPolicyMessage(error instanceof Error ? error.message : 'Load policy is unavailable.');
        if(replaySourceJobId){setReplayDraftError('Current Package policy could not be loaded. Reopen the source Job or retry after the Package is available.');setReplayDraftLoading(false);}
      });
    return () => controller.abort();
  }, [api, selectedPackage, selectedEnvironment, model, inlineLoadEnabled, replaySourceJobId]);

  useEffect(()=>{
    if(!replayEditorDefinition||!policy||replaySeeded.current)return;
    try {
      const seeded=retainedScenarioToEditor(replayEditorDefinition.scenario);
      replaySeeded.current=true;setModel(seeded.model);setTiming(seeded.timing);setWorkloads(seeded.workloads);setAdvanced(seeded.advanced);
      if(seeded.diagnostics.length)setDiagnostics(current=>[...current,...seeded.diagnostics]);
      setDraft(null);setDraftState('idle');setDraftMessage('Job settings loaded. Edit the fields, then validate against the current Package.');
      setReplayDraftLoading(false);
    } catch(error) {setReplayDraftError(error instanceof Error?error.message:'The retained LOAD definition cannot be edited safely.');setReplayDraftLoading(false);}
  },[policy,replayEditorDefinition]);

  useEffect(() => {
    if (!policy) { setRawScenarioJson(''); return; }
    try {
      setRawScenarioJson(JSON.stringify(makeScenario(policy, model, timing, workloads, advanced), null, 2));
      setAdvancedJsonError('');
    } catch {
      // The structured fields remain editable until their values form a valid draft.
    }
  }, [policy, model, timing, workloads, advanced]);

  const packageName = useMemo(() => packages.find(item => item.packageId === selectedPackage)?.displayName || selectedPackage, [packages, selectedPackage]);
  const updateWorkload = (id: string, updater: (current: WorkloadDraft) => WorkloadDraft) => {
    markReplayDirty();setWorkloads(current => current.map(item => item.id === id ? updater(item) : item));
  };

  function readScenario(): JsonObject {
    if (!policy) throw new Error('Load policy is not ready.');
    if (advancedJsonError) throw new Error(advancedJsonError);
    const editorError = Object.values(thresholdEditorErrors).find(Boolean);
    if (editorError) throw new Error(editorError);
    return makeScenario(policy, model, timing, workloads, advanced);
  }

  async function validateScenario() {
    setRequestError('');
    setRequestErrorCode('');
    setDiagnostics([]);
    let scenario: JsonObject;
    try { scenario = readScenario(); }
    catch (error) {
      const message=error instanceof Error?error.message:'Correct the scenario fields.';
      if(message.includes('unsupported target type'))setDiagnostics([{code:'ATT-LOAD-UNSUPPORTED-TARGET-TYPE',field:'scenario.workloads.target.type',summary:message}]);
      setDraftState('error'); setDraftMessage(message); return;
    }
    const packageId = selectedPackage;
    const sequence = ++requestSequence.current;
    const validationGeneration = ++replayValidationGeneration.current;
    setDraft(null);
    setDraftState('validating');
    setDraftMessage('Validating the complete scenario on the Server…');
    try {
      let response: import('./types').LoadDraftResponse;
      if(replaySourceJobId) {
        if(!api.createJobReplayDraft)throw new Error('This Server client cannot validate an editable Job draft.');
        const scenarioLoad=asObject(scenario.load),scenarioWorkloads=Array.isArray(scenario.workloads)?scenario.workloads:[];
        const environmentOverride=replayEnvironmentOrigin.current==='package'&&selectedEnvironment===replayPackageDefaultEnvironment.current?'':selectedEnvironment||'';
        const overrides:Record<string,unknown>={environment:environmentOverride,
          load:Object.fromEntries(['warmup','rampUp','duration','rampDown'].filter(field=>scenarioLoad[field]!=null).map(field=>[field,scenarioLoad[field]])),
          workloads:scenarioWorkloads.map(raw=>{
            const row=asObject(raw),load=asObject(row.load);
            const pacing=model==='arrivalRate'
              ?{arrivalRate:load.arrivalRate,maxConcurrent:load.maxConcurrent,overloadPolicy:load.overloadPolicy}
              :{users:load.users};
            const patch:Record<string,unknown>={id:row.id,load:pacing};
            for(const field of ['execution','target','mix','inputs','vars','testdata','thresholds','evidence'])if(row[field]!=null)patch[field]=row[field];
            return patch;
          }),
        };
        for(const field of ['execution','testdata','thresholds','evidence','seed'])if(scenario[field]!=null)overrides[field]=scenario[field];
        const replay=await api.createJobReplayDraft(replaySourceJobId,{sourcePolicy:'current',overrides});
        if(validationGeneration!==replayValidationGeneration.current||sequence!==requestSequence.current||packageId!==selectedPackage)return;
        setMissingReplayFields(replay.missingFields||[]);
        setReplayReview(replay);
        if(!replay.intentId) {
          setDiagnostics(replay.diagnostics||[]);setDraftState('error');setDraftMessage('Current Package validation needs attention. See the error and field diagnostics below.');
          setRequestError(replay.reason||'Current Package validation failed.');setRequestErrorCode(replay.errorCode||'');setDraft(null);return;
        }
        if(replay.configuration?.environmentOrigin==='package')replayPackageDefaultEnvironment.current=String(replay.configuration.environment||'');
        setDiagnostics(replay.diagnostics||[]);response=(replay.preview||{}) as unknown as import('./types').LoadDraftResponse;
      } else {
        response = await api.createLoadDraft({
          packageId,
          ...(selectedEnvironment ? { environment: selectedEnvironment } : {}),
          scenario,
        });
      }
      if (validationGeneration!==replayValidationGeneration.current||sequence !== requestSequence.current || packageId !== selectedPackage) return;
      setDraft(response);
      setDiagnostics(response.diagnostics || []);
      setDraftState('valid');
      setDraftMessage(response.redacted
        ? 'Server validation passed. The preview hides sensitive fields; copy and export are disabled.'
        : 'Server validation passed. Review the complete normalized scenario before starting.');
    } catch (error) {
      if (validationGeneration!==replayValidationGeneration.current||sequence !== requestSequence.current) return;
      const details = error as Error & { diagnostics?: unknown };
      const serverDiagnostics = Array.isArray(details.diagnostics) ? details.diagnostics as ServerDiagnostic[] : [];
      setDiagnostics(serverDiagnostics);
      setDraftState('error');
      setDraftMessage('Validation needs attention. See the error and field diagnostics below.');
      setRequestError(details.message || 'Server validation failed.');
      setRequestErrorCode((details as Error & { code?: string }).code || '');
    }
  }

  async function startLoad() {
    if (!draft || draftState === 'submitting' || submitLock.current) return;
    if(replaySourceJobId&&(!replayReview?.intentId||replayReview.missingFields?.length))return;
    if (Date.parse(draft.expiresAt) <= Date.now()) {
      setDraft(null); setDraftState('error'); setDraftMessage('This validated draft expired. Validate the scenario again.'); return;
    }
    submitLock.current = true;
    setConfirmationPending(true);
    let accepted: boolean;
    try {
      accepted = await (confirmStart || (message => window.confirm(message)))(
        `Start ${model === 'arrivalRate' ? 'Arrival Rate' : 'Virtual Users'} Load with ${workloads.length} workload${workloads.length === 1 ? '' : 's'} for ${packageName}?`,
      );
    } catch (error) {
      submitLock.current = false;
      setDraftMessage('Load start could not be confirmed. See the error below.');
      setRequestError(error instanceof Error ? error.message : 'Load start confirmation failed.');
      setRequestErrorCode((error as Error & { code?: string }).code || '');
      setDraftState('error');
      setConfirmationPending(false);
      return;
    }
    setConfirmationPending(false);
    if (!accepted) { submitLock.current = false; return; }
    setDraftState('submitting');
    setDraftMessage('Submitting the Server-validated Load draft…');
    try {
      const job = replaySourceJobId&&replayReview?.intentId
        ? await api.rerunJob?.(replaySourceJobId,replayReview.intentId,replayReview.sourcePolicy||'current')
        : await api.submitLoadDraft({ packageId: selectedPackage, draftId: draft.draftId, ...(principal ? { principal } : {}) });
      if(!job)throw new Error('The Server client cannot submit this replay.');
      setDraft(null);
      releaseReplayNavigationGuard.current();
      setReplayDirty(false);
      onJobAccepted(job.jobId);
    } catch (error) {
      const failure = error as Error & { status?: number; code?: string; diagnostics?: unknown };
      setDiagnostics(Array.isArray(failure.diagnostics) ? failure.diagnostics as ServerDiagnostic[] : []);
      setDraftState('error');
      setDraftMessage(failure.code === 'ATT-SERVER-REPLAY-DRAFT-STALE'
        ? 'Your edits are retained. Validate again before running this LOAD.'
        : 'The LOAD was not started. See the error below.');
      setRequestError(failure.message || 'The Server did not accept this Load draft.');
      setRequestErrorCode(failure.code || '');
      if (failure.status === 404 || failure.status === 409) setDraft(null);
    } finally {
      submitLock.current = false;
    }
  }

  const startLoadRef = useRef(startLoad);
  startLoadRef.current = startLoad;

  async function copyYaml() {
    if (!draft?.previewYaml || draft.redacted) return;
    try { await navigator.clipboard.writeText(draft.previewYaml); setDraftMessage('Validated YAML copied.'); }
    catch { setDraftMessage('Clipboard access is unavailable in this browser.'); }
  }

  function exportYaml() {
    if (!draft?.previewYaml || draft.redacted) return;
    const blobUrl = URL.createObjectURL(new Blob([draft.previewYaml], { type: 'text/yaml;charset=utf-8' }));
    const anchor = document.createElement('a');
    anchor.href = blobUrl; anchor.download = 'att-load-v1.6.yaml'; anchor.click();
    URL.revokeObjectURL(blobUrl);
  }

  function updateAdvanced<K extends keyof typeof advanced>(key: K, value: (typeof advanced)[K]) {
    markReplayDirty();setAdvanced(current => ({ ...current, [key]: value }));
  }
  function updateThresholdEditorValidity(key: string, message: string) {
    setThresholdEditorErrors(current => { const next = { ...current }; if (message) next[key] = message; else delete next[key]; return next; });
  }

  const preview = draft?.previewYaml || (draft?.preview ? JSON.stringify(draft.preview, null, 2) : '');
  const hasLiveDraft = Boolean(draft && Date.parse(draft.expiresAt) > Date.now());
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key === 'Enter'
        && hasLiveDraft && draftState !== 'submitting' && !confirmationPending && (inlineLoadEnabled||Boolean(replaySourceJobId))) {
        event.preventDefault();
        void startLoadRef.current();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [confirmationPending, draftState, hasLiveDraft, inlineLoadEnabled,replaySourceJobId]);
  const navigateSection = (id: string) => {
    const section = document.getElementById(id);
    section?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
    section?.focus({ preventScroll: true });
  };

  return (
    <div className="page-content att-load-page" onChangeCapture={markReplayDirty} onClickCapture={event=>{
      const label=(event.target as HTMLElement).closest('button')?.textContent?.trim()||'';
      if(/^(Add|Remove)\b/.test(label))markReplayDirty();
    }}>
      <header className="att-feature-header">
        <div>
          <p className="att-eyebrow">Load testing</p>
          <h1>{replaySourceJobId?'Configure & Rerun LOAD':'New Load scenario'}</h1>
          <p>Build a shared timing plan, configure workloads, then validate the complete scenario on the Server.</p>
        </div>
        <span className={`att-feature-state att-state-${policyState}`} role="status" aria-live="polite">{policyMessage}</span>
      </header>
      {replayDraftLoading&&<p role="status">Loading the authorized Job draft…</p>}
      {replayDraftError&&<p role="alert">{replayDraftError}</p>}
      {replaySourceJobId&&<div role="note"><p>New LOAD from Job {replaySourceJobId}. Schedule package-change policy does not control this manual rerun.</p>
        <button type="button" onClick={()=>navigation.navigate({kind:'jobs',jobId:replaySourceJobId})}>Back to source Job</button></div>}
      {missingReplayFields.length>0&&<p role="note">These fields were not safely retained and need an explicit value before this LOAD can run: {missingReplayFields.join(', ')}.</p>}

      <div hidden={replayDraftLoading}>
      <nav className="att-section-nav" aria-label="Load scenario sections">
        <button type="button" aria-controls="load-model" onClick={() => navigateSection('load-model')}>Model</button>
        <button type="button" aria-controls="load-workloads" onClick={() => navigateSection('load-workloads')}>Workloads</button>
        <button type="button" aria-controls="load-thresholds" onClick={() => navigateSection('load-thresholds')}>Thresholds and evidence</button>
        <button type="button" aria-controls="load-review" onClick={() => navigateSection('load-review')}>Review</button>
      </nav>

      <section id="load-model" className="att-load-section" aria-labelledby="load-model-title" tabIndex={-1}>
        <h2 id="load-model-title">Scenario</h2>
        <div className="att-load-grid">
          <label>Package
            <select value={selectedPackage} onChange={event => onPackageChange(event.target.value)} disabled={!inlineLoadEnabled||Boolean(replaySourceJobId)}>
              <option value="">Choose a package</option>
              {packages.map(item => <option key={item.packageId} value={item.packageId}>{item.displayName || item.packageId}</option>)}
            </select>
          </label>
          <label>Environment
            <select value={selectedEnvironment} onChange={event => onEnvironmentChange(event.target.value)} disabled={!selectedPackage}>
              <option value="">Default environment</option>
              {environments.map(environment => <option key={environment} value={environment}>{environment}</option>)}
            </select>
          </label>
      <EnvironmentResolutionNotice packageId={selectedPackage} environment={selectedEnvironment} />
          <label>Load model
          <select value={model} onChange={event => {markReplayDirty();setModel(event.target.value as LoadModel);}} disabled={!inlineLoadEnabled||Boolean(replaySourceJobId)}>
              <option value="virtualUsers">Virtual Users</option>
              <option value="arrivalRate">Arrival Rate</option>
            </select>
          </label>
        </div>
        {environmentState === 'loading' && <p className="att-help" role="status" aria-busy="true">Loading declared package environments…</p>}
        {environmentState === 'error' && <p className="att-inline-error" role="status">Package environments could not be loaded. Only the Server default environment is available in this view.{onRetryEnvironments && <> <button type="button" onClick={onRetryEnvironments}>Retry</button></>}</p>}
        <fieldset className="att-timing-fieldset">
          <legend>Shared timing envelope</legend>
          <DurationField label="Warm-up" value={timing.warmup} onChange={value => setTiming(current => ({ ...current, warmup: value }))} />
          <DurationField label="Ramp-up" value={timing.rampUp} onChange={value => setTiming(current => ({ ...current, rampUp: value }))} />
          <DurationField label="Measured duration" required value={timing.duration} onChange={value => setTiming(current => ({ ...current, duration: value }))} />
          <DurationField label="Ramp-down" value={timing.rampDown} onChange={value => setTiming(current => ({ ...current, rampDown: value }))} />
          <p className="att-help">Use integer ms, s, m, or h values. The Server checks duration bounds and the shared timing envelope.</p>
        </fieldset>
      </section>

      <section id="load-workloads" className="att-load-section" aria-labelledby="load-workloads-title" tabIndex={-1}>
        <div className="att-section-heading">
          <div><h2 id="load-workloads-title">Workloads</h2><p>Every workload shares the selected model and timing envelope.</p></div>
          <button type="button" onClick={() => setWorkloads(current => [...current, newWorkload(current.length + 1, policy || {})])} disabled={!policy}>Add workload</button>
        </div>
        {workloads.map((workload, index) => (
          <WorkloadEditor
            key={workload.id}
            workload={workload}
            index={index}
            model={model}
            packageId={selectedPackage}
            environment={selectedEnvironment}
            api={api}
            inlineLoadEnabled={inlineLoadEnabled||Boolean(replaySourceJobId)}
            onChange={next => updateWorkload(workload.id, () => next)}
            onRemove={() => setWorkloads(current => current.filter(item => item.id !== workload.id))}
          />
        ))}
        {workloads.length === 0 && <p className="att-empty">Load a policy to begin; the Server defaults will populate the first workload.</p>}
      </section>

      <section id="load-thresholds" className="att-load-section" aria-labelledby="load-thresholds-title" tabIndex={-1}>
        <h2 id="load-thresholds-title">Thresholds and evidence</h2>
        <ThresholdEvidenceEditor
          model={model}
          aggregate={advanced.thresholds}
          workloads={workloads.map(item => ({ id: item.id, thresholdsJson: item.thresholdsJson, evidenceJson: item.evidenceJson }))}
          evidence={advanced.evidence}
          onAggregateChange={value => updateAdvanced('thresholds', value)}
          onWorkloadChange={(id, value) => updateWorkload(id, current => ({ ...current, thresholdsJson: value }))}
          onWorkloadEvidenceChange={(id, value) => updateWorkload(id, current => ({ ...current, evidenceJson: value }))}
          onEvidenceChange={value => updateAdvanced('evidence', value)}
          onValidityChange={updateThresholdEditorValidity}
        />
        <details className="att-advanced-settings">
          <summary>Advanced scenario settings</summary>
          <div className="att-load-grid">
            <label>Seed (optional)
              <input type="number" step="1" value={advanced.seed} onChange={event => updateAdvanced('seed', event.target.value)} />
            </label>
            <JsonObjectField label="Root execution defaults" value={advanced.execution} onChange={value => updateAdvanced('execution', value)} />
            <JsonArrayField label="Package-relative Testdata paths" value={advanced.testdata} onChange={value => updateAdvanced('testdata', value)} />
          </div>
          {rawScenarioJson && <details><summary>Current complete scenario JSON</summary><CodeEditor value={rawScenarioJson} language="json" label="Complete scenario JSON" readOnly /></details>}
        </details>
      </section>
      </div>

      <section id="load-review" className="att-load-section" aria-labelledby="load-review-title" tabIndex={-1}>
        <h2 id="load-review-title">Review and start</h2>
        {replayReview&&replayEditorDefinition&&<div className="att-replay-delta" role="region" aria-label="Review LOAD replay changes">
          <h3>Review changes from the source Job</h3>
          <p>Package revision: <code>{replayReview.originalRevision||replayReview.revision}</code> → <code>{replayReview.currentRevision||replayReview.revision}</code></p>
          <p>Configuration provenance: original environment {String(replayReview.originalConfiguration?.environment||replayEditorDefinition.environment||'Package default recorded for the Job')}; current environment {String(replayReview.configuration?.environment||selectedEnvironment||'Package default resolved now')}.</p>
          <p>Effective duration: {effectiveDuration(initialTiming(asObject((replayOriginalDefinition||replayEditorDefinition).scenario)))} → {effectiveDuration(timing)}</p>
          <p>Configured target arrival rate is the pacing request. Measured TPS is observed completed throughput and can differ.</p>
          {typeof (replayOriginalDefinition||replayEditorDefinition).sourcePath==='string'&&<p>Scenario source: <code>{String((replayOriginalDefinition||replayEditorDefinition).sourcePath)}</code></p>}
          <details><summary>Before → after scenario</summary><pre>{JSON.stringify({before:(replayOriginalDefinition||replayEditorDefinition).scenario,after:replayReview.definition?.scenario||(()=>{try{return readScenario();}catch{return 'Correct the fields, then validate.';}})()},null,2)}</pre></details>
        </div>}
        <div className={`att-validation-state att-state-${draftState}`} role="status" aria-live="polite" aria-busy={draftState === 'validating' || draftState === 'submitting'}>
          <p>{draftMessage}</p>
          {diagnostics.length > 0 && <ul aria-label="LOAD validation details">{diagnostics.map((item, index) => <li key={`${item.code || item.field || item.path || 'diagnostic'}-${index}`}>
            {[item.severity, item.field || item.path, item.code].filter(Boolean).join(' · ')}{item.message || item.summary ? `: ${item.message || item.summary}` : ''}
          </li>)}</ul>}
        </div>
        {requestError && <p className="att-inline-error" role="alert">{requestError}</p>}
        {requestErrorCode === 'ATT-SERVER-REPLAY-DRAFT-STALE' && <button type="button" onClick={() => void validateScenario()}>Validate again</button>}
        {draft && <p className="att-draft-expiry">Draft expires {new Date(draft.expiresAt).toLocaleString()}.</p>}
        {preview && <CodeEditor value={preview} language="yaml" label="Server-validated scenario preview" readOnly />}
        <div className="att-review-actions">
          <button type="button" onClick={() => void validateScenario()} disabled={replayDraftLoading || !policy || (!inlineLoadEnabled&&!replaySourceJobId) || draftState === 'validating' || draftState === 'submitting'}>Validate scenario</button>
          {!replaySourceJobId&&<button type="button" onClick={() => draft?.draftId && onSchedule?.({ packageId: selectedPackage, environment: selectedEnvironment || undefined, draftId: draft.draftId, previewYaml: draft.previewYaml })}
            disabled={!schedulingEnabled || !draft?.draftId || draftState !== 'valid' || !onSchedule}
            title={!schedulingEnabled ? 'Scheduled Load is disabled by Server configuration.' : !draft?.draftId ? 'Validate the scenario before scheduling.' : undefined}>Schedule…</button>
          }
          <button type="button" onClick={() => void copyYaml()} disabled={!draft?.previewYaml || Boolean(draft.redacted)}>Copy YAML</button>
          <button type="button" onClick={exportYaml} disabled={!draft?.previewYaml || Boolean(draft.redacted)}>Export YAML</button>
          <button type="button" className="att-primary-action" onClick={() => void startLoad()} disabled={!hasLiveDraft || draftState === 'submitting' || confirmationPending || Boolean(replayReview?.missingFields?.length) || (!inlineLoadEnabled&&!replaySourceJobId)}>
            {confirmationPending ? 'Waiting for confirmation…' : draftState === 'submitting' ? 'Starting…' : replaySourceJobId?'Run new LOAD Job':'Review and start Load'}
          </button>
        </div>
        {draftState === 'valid' && draft?.draftId && <p className="att-help">Scheduling creates a recurring job from this validated immutable scenario snapshot.</p>}
      </section>
    </div>
  );
}

export function DurationField({ label, value, onChange, required = false }: { label: string; value: string; onChange: (value: string) => void; required?: boolean }): React.JSX.Element {
  const match = /^(\d+)(ms|s|m|h)$/.exec(value.trim());
  const [amount, setAmount] = useState(match?.[1] || '');
  const [unit, setUnit] = useState(match?.[2] || 's');
  useEffect(() => {
    const current = /^(\d+)(ms|s|m|h)$/.exec(value.trim());
    if (current) { setAmount(current[1]); setUnit(current[2]); }
    else if (!value) setAmount('');
  }, [value]);
  function change(nextAmount: string, nextUnit: string) {
    setAmount(nextAmount); setUnit(nextUnit);
    onChange(nextAmount ? `${nextAmount}${nextUnit}` : '');
  }
  return <fieldset className="att-unit-field">
    <legend>{label}{required ? ' *' : ''}</legend>
    <div className="att-unit-row">
      <input aria-label={`${label} value`} type="number" min="0" step="1" inputMode="numeric" value={amount} onChange={event => change(event.target.value, unit)} required={required} />
      <select aria-label={`${label} unit`} value={unit} onChange={event => change(amount, event.target.value)}>
        <option value="ms">Milliseconds</option><option value="s">Seconds</option><option value="m">Minutes</option><option value="h">Hours</option>
      </select>
    </div>
    <details><summary>Advanced raw duration</summary><input aria-label={`${label} raw duration`} value={value} onChange={event => onChange(event.target.value)} placeholder="e.g. 500ms" /></details>
  </fieldset>;
}

export function ThinkTimeField({ value, onChange, disabled }: { value: ThinkTime; onChange: (value: ThinkTime) => void; disabled: boolean }): React.JSX.Element {
  const range = typeof value !== 'string';
  const fixedValue = typeof value === 'string' ? value : '';
  const [fixedAmount, setFixedAmount] = useState(/^(\d+)(ms|s|m|h)$/.exec(fixedValue)?.[1] || '');
  const [fixedUnit, setFixedUnit] = useState(/^(\d+)(ms|s|m|h)$/.exec(fixedValue)?.[2] || 'ms');
  useEffect(() => {
    if (typeof value === 'string') {
      const match = /^(\d+)(ms|s|m|h)$/.exec(value);
      setFixedAmount(match?.[1] || ''); setFixedUnit(match?.[2] || 'ms');
    }
  }, [value]);
  const rangeValue = typeof value === 'string' ? { min: '', max: '' } : value;
  return <fieldset className="att-think-time" disabled={disabled}>
    <legend>Think time (Virtual Users only)</legend>
    <label>Shape
      <select value={range ? 'range' : 'fixed'} onChange={event => onChange(event.target.value === 'range' ? { min: '', max: '' } : '')}>
        <option value="fixed">Fixed duration</option><option value="range">Random range</option>
      </select>
    </label>
    {!range ? <div className="att-unit-row">
      <input aria-label="Think time value" type="number" min="0" step="1" value={fixedAmount} onChange={event => { setFixedAmount(event.target.value); onChange(event.target.value ? `${event.target.value}${fixedUnit}` : ''); }} />
      <select aria-label="Think time unit" value={fixedUnit} onChange={event => { setFixedUnit(event.target.value); onChange(fixedAmount ? `${fixedAmount}${event.target.value}` : ''); }}>
        <option value="ms">Milliseconds</option><option value="s">Seconds</option><option value="m">Minutes</option><option value="h">Hours</option>
      </select>
    </div> : <div className="att-unit-row">
      <DurationField label="Minimum" value={rangeValue.min} onChange={min => onChange({ ...rangeValue, min })} />
      <DurationField label="Maximum" value={rangeValue.max} onChange={max => onChange({ ...rangeValue, max })} />
    </div>}
    <p className="att-help">Arrival-rate workloads do not support think time. The Server checks duration syntax and range order.</p>
  </fieldset>;
}

function WorkloadEditor({
  workload, index, model, packageId, environment, api, inlineLoadEnabled, onChange, onRemove,
}: {
  workload: WorkloadDraft;
  index: number;
  model: LoadModel;
  packageId: string;
  environment: string;
  api: LoadFeatureProps['api'];
  inlineLoadEnabled: boolean;
  onChange: (value: WorkloadDraft) => void;
  onRemove: () => void;
}): React.JSX.Element {
  return <article className="att-workload-card">
    <header className="att-workload-heading">
      <h3>Workload {index + 1}</h3>
      <button type="button" onClick={onRemove} disabled={index === 0}>Remove workload</button>
    </header>
    <label>Workload ID<input value={workload.id} onChange={event => onChange({ ...workload, id: event.target.value })} /></label>
    <div className="att-load-grid">
      {model === 'virtualUsers'
        ? <label>Virtual users<input type="number" min="1" step="1" value={workload.users} onChange={event => onChange({ ...workload, users: event.target.value })} /></label>
        : <><RateField value={workload.arrivalRate} onChange={arrivalRate => onChange({ ...workload, arrivalRate })} />
          <label>Maximum concurrent<input type="number" min="1" step="1" value={workload.maxConcurrent} onChange={event => onChange({ ...workload, maxConcurrent: event.target.value })} /></label>
          <label>Overload policy<select value={workload.overloadPolicy} onChange={event => onChange({ ...workload, overloadPolicy: event.target.value })}><option value="">Choose an overload policy</option><option value="drop">Drop excess arrivals</option></select></label></>}
      <ThinkTimeField value={workload.thinkTime} disabled={model !== 'virtualUsers'} onChange={thinkTime => onChange({ ...workload, thinkTime })} />
    </div>
    {model === 'virtualUsers' && <label>Target structure
      <select value={workload.structure} onChange={event => {
        const structure = event.target.value as WorkloadDraft['structure'];
        onChange({ ...workload, structure, mix: structure === 'mix' && workload.mix.length === 0 ? [newMixDraft(1)] : workload.mix });
      }}>
        <option value="single">Single target</option><option value="mix">Weighted mix (Virtual Users only)</option>
      </select>
    </label>}
    {model === 'virtualUsers' && workload.structure === 'mix' ? <>
      <BusinessValuesEditor label="Workload mix defaults" json={workload.mixDefaultsJson} groups={['inputs', 'vars']} onChange={mixDefaultsJson => onChange({ ...workload, mixDefaultsJson })}
        onValidityChange={mixDefaultsValid => { if (workload.mixDefaultsValid !== mixDefaultsValid) onChange({ ...workload, mixDefaultsValid }); }} />
      <div className="att-mix-target-list">
        {workload.mix.map((entry, mixIndex) => <fieldset className="att-mix-target" key={entry.entryId}>
          <legend>Mix target {mixIndex + 1}</legend>
          <label>Entry ID<input value={entry.entryId} onChange={event => onChange({ ...workload, mix: workload.mix.map(item => item.entryId === entry.entryId ? { ...item, entryId: event.target.value } : item) })} /></label>
          <label>Weight<input type="number" min="1" step="1" value={entry.weight} onChange={event => onChange({ ...workload, mix: workload.mix.map(item => item.entryId === entry.entryId ? { ...item, weight: event.target.value } : item) })} /></label>
          <TargetEditor
            target={entry}
            packageId={packageId}
            environment={environment}
            model={model}
            api={api}
            inlineLoadEnabled={inlineLoadEnabled}
            onChange={target => onChange({ ...workload, mix: workload.mix.map(item => item.entryId === entry.entryId ? { ...item, ...target } : item) })}
          />
          <button type="button" onClick={() => onChange({ ...workload, mix: workload.mix.filter(item => item.entryId !== entry.entryId) })}>Remove mix target</button>
        </fieldset>)}
      </div>
      <button type="button" onClick={() => onChange({ ...workload, mix: [...workload.mix, newMixDraft(workload.mix.length + 1)] })}>Add mix target</button>
    </> : <TargetEditor
      target={workload.target}
      packageId={packageId}
      environment={environment}
      model={model}
      api={api}
      inlineLoadEnabled={inlineLoadEnabled}
      onChange={target => onChange({ ...workload, target })}
    />}
    <details className="att-advanced-settings">
      <summary>Advanced workload settings</summary>
      <div className="att-load-grid">
        <JsonObjectField label="Workload Testdata policies" value={workload.testdataJson} onChange={testdataJson => onChange({ ...workload, testdataJson })} />
      </div>
    </details>
  </article>;
}

function newMixDraft(index: number): MixDraft {
  return { ...newTarget(), entryId: `mix-${index}`, weight: '1' };
}

function RateField({ value, onChange }: { value: string; onChange: (value: string) => void }): React.JSX.Element {
  const match = /^([1-9]\d*(?:\.\d+)?)\/(s|m)$/.exec(value.trim());
  const [amount, setAmount] = useState(match?.[1] || '');
  const [unit, setUnit] = useState(match?.[2] || 's');
  useEffect(() => {
    const current = /^([1-9]\d*(?:\.\d+)?)\/(s|m)$/.exec(value.trim());
    if (current) { setAmount(current[1]); setUnit(current[2]); }
    else if (!value) setAmount('');
  }, [value]);
  function change(nextAmount: string, nextUnit: string) {
    setAmount(nextAmount); setUnit(nextUnit);
    onChange(nextAmount ? `${nextAmount}/${nextUnit}` : '');
  }
  return <fieldset className="att-unit-field">
    <legend>Arrival rate</legend>
    <div className="att-unit-row">
      <input aria-label="Arrival rate value" type="number" min="0" step="any" value={amount} onChange={event => change(event.target.value, unit)} />
      <select aria-label="Arrival rate unit" value={unit} onChange={event => change(amount, event.target.value)}>
        <option value="s">Arrivals per second</option><option value="m">Arrivals per minute</option>
      </select>
    </div>
    <details><summary>Advanced raw rate</summary><input aria-label="Arrival rate raw value" value={value} onChange={event => onChange(event.target.value)} placeholder="e.g. 7/s" /></details>
  </fieldset>;
}

export function TargetEditor({ target, packageId, environment, model, api, inlineLoadEnabled, onChange }: {
  target: TargetDraft;
  packageId: string;
  environment: string;
  model: LoadModel;
  api: LoadFeatureProps['api'];
  inlineLoadEnabled: boolean;
  onChange: (target: TargetDraft) => void;
}): React.JSX.Element {
  const listId = useId();
  const [targets, setTargets] = useState<TargetResource[]>([]);
  const [targetState, setTargetState] = useState<'idle' | 'loading' | 'error'>('idle');
  const [targetMessage, setTargetMessage] = useState('');
  const [sidecarState, setSidecarState] = useState<'idle' | 'loading' | 'error'>('idle');
  const [sidecarMessage, setSidecarMessage] = useState('');
  const [sidecarRevision, setSidecarRevision] = useState(0);
  const targetSequence = useRef(0);
  const sidecarSequence = useRef(0);
  const sidecarRequest = useRef<AbortController | null>(null);
  const [businessError, setBusinessError] = useState('');

  useEffect(() => {
    if (!packageId) { setTargets([]); setTargetState('idle'); return; }
    const controller = new AbortController();
    const sequence = ++targetSequence.current;
    setTargetState('loading');
    api.listTargets(packageId, target.type, controller.signal)
      .then(items => {
        if (controller.signal.aborted || sequence !== targetSequence.current) return;
        const ready = items.filter(item => item.state === 'ready' && item.logicalId);
        setTargets(ready); setTargetState('idle');
        setTargetMessage(ready.length ? `${ready.length} ready ${target.type} suggestions loaded.` : 'No ready target suggestions. You can enter a logical ID.');
      })
      .catch(error => {
        if (controller.signal.aborted || sequence !== targetSequence.current) return;
        setTargets([]); setTargetState('error');
        setTargetMessage(error instanceof Error ? error.message : 'Target suggestions are unavailable; enter a logical ID.');
      });
    return () => controller.abort();
  }, [api, packageId, target.type]);

  useEffect(() => {
    sidecarRequest.current?.abort();
    sidecarRequest.current = null;
    sidecarSequence.current += 1;
    setSidecarState('idle');
    setSidecarMessage('');
    return () => sidecarRequest.current?.abort();
  }, [packageId, target.type, target.id, model, environment]);

  async function loadSidecar() {
    if (!packageId || !target.id.trim() || !inlineLoadEnabled) return;
    const selectedTarget = targets.find(item => item.type === target.type && item.logicalId === target.id.trim());
    if (!selectedTarget) {
      setSidecarState('error');
      setSidecarMessage('Select a ready package target to load its safe sidecar defaults.');
      return;
    }
    sidecarRequest.current?.abort();
    const controller = new AbortController();
    sidecarRequest.current = controller;
    const sequence = ++sidecarSequence.current;
    setSidecarState('loading'); setSidecarMessage('Loading safe sidecar defaults…');
    try {
      const form = await api.getTargetLoadForm(packageId, target.type, selectedTarget.resourceId, model, environment || undefined, controller.signal);
      if (controller.signal.aborted || sequence !== sidecarSequence.current) return;
      const next = mergeBusinessJson(target.businessJson, target.type, form.input);
      onChange({ ...target, id: String(form.target?.id || target.id), businessJson: next });
      setSidecarRevision(value => value + 1);
      setSidecarState('idle');
      setSidecarMessage(form.redacted ? 'Safe defaults loaded; hidden sensitive values remain protected.' : 'Safe target defaults loaded.');
    } catch (error) {
      if (controller.signal.aborted || sequence !== sidecarSequence.current) return;
      setSidecarState('error'); setSidecarMessage(error instanceof Error ? error.message : 'Sidecar defaults are unavailable.');
    }
  }

  return <section className="att-target-editor" aria-label="Load target">
    <div className="att-load-grid">
      <label>Target type
        <select value={target.type} onChange={event => onChange({ ...target, type: event.target.value as LoadTargetType })}>
          {target.type.startsWith('unsupported:')&&<option value={target.type}>Unsupported retained type: {target.type.slice('unsupported:'.length)}</option>}
          <option value="template">Template</option><option value="flow">Flow</option><option value="tool">Tool</option>
        </select>
      </label>
      <label>Target logical ID
        <input value={target.id} list={listId} onChange={event => onChange({ ...target, id: event.target.value })} />
        <datalist id={listId}>{targets.map(item => <option key={item.logicalId} value={item.logicalId} />)}</datalist>
      </label>
      <div className="att-target-actions">
        <button type="button" onClick={() => void loadSidecar()} disabled={!inlineLoadEnabled || !packageId || !target.id.trim() || targetState === 'loading' || sidecarState === 'loading'}>
          {sidecarState === 'loading' ? 'Loading defaults…' : 'Load sidecar defaults'}
        </button>
        {targetState === 'loading' && <span role="status">Loading targets…</span>}
        {targetMessage && <span role="status" aria-live="polite">{targetMessage}</span>}
        {sidecarMessage && <span role="status" aria-live="polite" data-state={sidecarState}>{sidecarMessage}</span>}
      </div>
    </div>
    <BusinessValuesEditor
      label={target.type === 'tool' ? 'Inputs and Tool arguments' : 'Inputs and Variables'}
      json={target.businessJson}
      groups={target.type === 'tool' ? ['inputs', 'arguments'] : ['inputs', 'vars']}
      resetKey={sidecarRevision}
      onChange={businessJson => {
        onChange({ ...target, businessJson, businessValid: true });
        try { parseObjectJson(businessJson, 'Target values'); setBusinessError(''); }
        catch (error) { setBusinessError(error instanceof Error ? error.message : 'Correct the JSON values.'); }
      }}
      onValidityChange={valid => {
        setBusinessError(valid ? '' : 'Correct the invalid scalar or Advanced JSON values.');
        if (target.businessValid !== valid) onChange({ ...target, businessValid: valid });
      }}
      error={businessError}
    />
  </section>;
}

function BusinessValuesEditor({ label, json, groups, onChange, onValidityChange, error = '', resetKey = 0 }: {
  label: string;
  json: string;
  groups: string[];
  onChange: (value: string) => void;
  onValidityChange?: (valid: boolean) => void;
  error?: string;
  resetKey?: number;
}): React.JSX.Element {
  const callbackRef = useRef(onValidityChange);
  const [newKey, setNewKey] = useState('');
  const [newGroup, setNewGroup] = useState(groups[0] || 'inputs');
  const [addError, setAddError] = useState('');
  callbackRef.current = onValidityChange;
  const reportValidity = useCallback((valid: boolean) => callbackRef.current?.(valid), []);
  let value: SidecarJsonObject = {};
  try { value = JSON.parse(json || '{}') as SidecarJsonObject; }
  catch { /* The controlled helper reports and keeps invalid advanced JSON local until corrected. */ }
  const formGroups = groups.map(key => ({
    key,
    label: key === 'inputs' ? 'Inputs' : key === 'vars' ? 'Variables' : 'Tool arguments',
  } satisfies SidecarGroup));
  function addScalar() {
    const key = newKey.trim();
    if (!key) { setAddError('Enter a field name.'); return; }
    const fields = value[newGroup];
    const nextFields: Record<string, unknown> = fields && typeof fields === 'object' && !Array.isArray(fields)
      ? { ...fields as Record<string, unknown> } : {};
    if (Object.prototype.hasOwnProperty.call(nextFields, key)) { setAddError('That field already exists.'); return; }
    Object.defineProperty(nextFields, key, { configurable: true, enumerable: true, writable: true, value: '' });
    onChange(JSON.stringify({ ...value, [newGroup]: nextFields }, null, 2));
    setNewKey(''); setAddError('');
  }
  return <section className="att-business-editor" aria-label={label}>
    <h4>{label}</h4>
    {error && <p className="att-inline-error" role="alert">{error}</p>}
    <div className="att-add-business-field">
      <label>Add a value
        <select value={newGroup} onChange={event => setNewGroup(event.currentTarget.value)}>{formGroups.map(group => <option key={group.key} value={group.key}>{group.label}</option>)}</select>
      </label>
      <label>Field name<input value={newKey} onChange={event => setNewKey(event.currentTarget.value)} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); addScalar(); } }} /></label>
      <button type="button" onClick={addScalar}>Add field</button>
    </div>
    {addError && <p className="att-inline-error" role="alert">{addError}</p>}
    <TypedSidecarForm
      value={value}
      groups={formGroups}
      resetKey={resetKey}
      onChange={next => onChange(JSON.stringify(next, null, 2))}
      onValidityChange={reportValidity}
    />
    <p className="att-help">Nested maps and lists remain in Advanced JSON. The Server validates the complete business values map.</p>
  </section>;
}

export function JsonObjectField({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }): React.JSX.Element {
  return <div className="att-json-field"><span>{label}</span><CodeEditor value={value} language="json" label={label} readOnly={false} onChange={onChange} />
    <span className="att-help">JSON object; final validation is performed by the Server.</span>
  </div>;
}

export function JsonArrayField({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }): React.JSX.Element {
  return <div className="att-json-field"><span>{label}</span><CodeEditor value={value} language="json" label={label} readOnly={false} onChange={onChange} />
    <span className="att-help">JSON array of package-relative paths.</span>
  </div>;
}
