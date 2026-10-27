import { EnvironmentResolutionNotice } from '../explorer/EnvironmentResolutionNotice';
import './run.css';
import { useEffect, useRef, useState } from 'react';
import { api } from '../../api';
import { useNavigationGuard } from '../../router';
import type { ResourceRoute } from '../../router';
import { CodeEditor } from '../code/CodeEditor';
import { TypedSidecarForm } from '../explorer/TypedSidecarForm';
import type { DebugFormResponse, DebugResourceType, DraftResponse, JsonObject, ResourceIdentity } from '../explorer/types';
import type { ValidatedScheduleIntent } from '../schedules/types';
import type { JobReplay, JobReplayEditorDraft } from '../load/types';

export interface RunDefinition {
  schemaVersion: 'att-execution/v1'; executionType: 'run'; packageId: string; environment?: string;
  target?: { type: string; id: string }; selection?: JsonObject; input?: JsonObject;
  options: { debug: boolean; failFast?: boolean };
}
function isDebugTarget(type: string): type is DebugResourceType {
  return type === 'template' || type === 'flow' || type === 'tool';
}

export function RunPage({ packageId: initialPackage = '', initialTarget, initialBatchMode = false, initialEnvironment = '', replayDraftId, replaySourceJobId, onAccepted, onSchedule, schedulingEnabled }: {
  packageId?: string; onAccepted(job: { jobId: string; packageId: string }): void;
  initialTarget?: ResourceRoute; initialBatchMode?: boolean; initialEnvironment?: string;
  replayDraftId?: string; replaySourceJobId?: string;
  onSchedule(intent: ValidatedScheduleIntent): boolean; schedulingEnabled: boolean;
}) {
  const navigation = useNavigationGuard();
  const [packageId, setPackage] = useState(initialPackage);
  const [environment, setEnvironment] = useState(initialEnvironment);
  const [environments, setEnvironments] = useState<string[]>([]);
  const [environmentState, setEnvironmentState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [environmentReload, setEnvironmentReload] = useState(0);
  const [environmentNotice, setEnvironmentNotice] = useState('');
  const [targetType, setType] = useState(initialBatchMode ? 'batch' : initialTarget?.type || 'case');
  const [targetId, setId] = useState(initialTarget?.logicalId || initialTarget?.resourceId || '');
  const [businessInput, setInput] = useState('{}');
  const [debugForm, setDebugForm] = useState<DebugFormResponse | null>(null);
  const [debugFormState, setDebugFormState] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const [formValid, setFormValid] = useState(true);
  const [formResetKey, setFormResetKey] = useState(0);
  const [selection, setSelection] = useState('{"all": true}');
  const [debug, setDebug] = useState(false);
  const [failFast, setFailFast] = useState(false);
  const [draft, setDraft] = useState<DraftResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [validationDiagnostics, setValidationDiagnostics] = useState<Array<{code?: string; field?: string; path?: string; summary?: string; detail?: string}>>([]);
  const [dirty, setDirty] = useState(false);
  const generation = useRef(0);
  const [definition, setDefinition] = useState<RunDefinition | null>(null);
  const [replayReview, setReplayReview] = useState<JobReplay | null>(null);
  const [replaySourceDefinition, setReplaySourceDefinition] = useState<RunDefinition | null>(null);
  const [replayDraftLoading, setReplayDraftLoading] = useState(Boolean(replayDraftId&&replaySourceJobId));
  const [replayDraftError, setReplayDraftError] = useState('');
  const [missingReplayFields,setMissingReplayFields]=useState<string[]>([]);
  const replayInputRef = useRef<JsonObject | undefined>(undefined);
  const releaseNavigationGuard = useRef<() => void>(() => {});
  const environmentRef = useRef(environment);
  environmentRef.current = environment;
  const replayEnvironmentOrigin = useRef('');
  const replayPackageDefaultEnvironment = useRef('');
  useEffect(() => {
    if (!replayDraftId || !replaySourceJobId) return;
    const controller=new AbortController();setReplayDraftLoading(true);setReplayDraftError('');
    api.getJobReplayEditorDraft(replaySourceJobId,replayDraftId).then((draft:JobReplayEditorDraft)=>{
      if(controller.signal.aborted)return;
      const retained=draft.definition as unknown as RunDefinition;
      replayEnvironmentOrigin.current=String(draft.configuration?.environmentOrigin||'');
      replayPackageDefaultEnvironment.current=replayEnvironmentOrigin.current==='package'?String(draft.configuration?.environment||''):'';
      setPackage(retained.packageId);setEnvironment(replayEnvironmentOrigin.current==='package'?'':retained.environment||String(draft.configuration?.environment||''));
      setMissingReplayFields(draft.missingFields||[]);
      if(retained.target){setType(retained.target.type);setId(retained.target.id);}else if(retained.selection){setType('batch');setId('');}else{setType('case');setId('');}
      setSelection(JSON.stringify(retained.selection||{all:true},null,2));replayInputRef.current=retained.input;
      setInput(JSON.stringify(retained.input||{},null,2));setDebug(Boolean(retained.options?.debug));setFailFast(Boolean(retained.options?.failFast));
      setReplaySourceDefinition((draft.originalDefinition||retained) as unknown as RunDefinition);
      setReplayReview({jobId:draft.sourceJobId,available:true,...draft,definition:draft.definition,originalDefinition:draft.originalDefinition||draft.definition});
      setValidationDiagnostics(draft.diagnostics||[]);setMissingReplayFields(draft.missingFields||[]);
      setDirty(false);setDraft(null);setDefinition(null);setError('');
    }).catch(failure=>{if(!controller.signal.aborted)setReplayDraftError(failure instanceof Error?failure.message:'The replay draft expired. Open the source Job again.');})
      .finally(()=>{if(!controller.signal.aborted)setReplayDraftLoading(false);});
    return()=>controller.abort();
  },[replayDraftId,replaySourceJobId]);
  useEffect(() => {
    const controller = new AbortController();
    if (!packageId.trim()) {
      setEnvironments([]);
      setEnvironmentState('ready');
      return () => controller.abort();
    }
    setEnvironmentNotice('');
    setEnvironmentState('loading');
    api.getDeclaredConfiguration(packageId, controller.signal).then(configuration => {
      if (controller.signal.aborted) return;
      const names = (configuration.environments || []).filter(item => item.name && item.state === 'active').map(item => item.name);
      setEnvironments(names);
      setEnvironmentState('ready');
      if (environmentRef.current && !names.includes(environmentRef.current)) {
        setEnvironmentNotice(`Environment ${environmentRef.current} is not an active profile in ${packageId}; Package default is selected.`);
        setEnvironment('');
      }
    }).catch(() => {
      if (!controller.signal.aborted) { setEnvironments([]); setEnvironmentState('error'); }
    });
    return () => controller.abort();
  }, [packageId, environmentReload]);
  useEffect(() => {
    if (!isDebugTarget(targetType) || !packageId.trim()) {
      setDebugForm(null);
      setDebugFormState('idle');
      setFormValid(true);
      return;
    }
    const resourceId = initialTarget?.type === targetType ? initialTarget.resourceId : targetId.trim();
    if (!resourceId) {
      setDebugForm(null);
      setDebugFormState('idle');
      setFormValid(true);
      return;
    }
    const identity: ResourceIdentity = { packageId: packageId.trim(), type: targetType, resourceId };
    const controller = new AbortController();
    setDebugForm(null);
    setDebugFormState('loading');
    setFormValid(true);
    const timer = window.setTimeout(() => {
      api.getDebugForm(identity, environment.trim() || undefined, controller.signal).then(response => {
        if (controller.signal.aborted) return;
        setDebugForm(response);
        setInput(JSON.stringify(replayInputRef.current !== undefined ? replayInputRef.current : response.input || {}, null, 2));
        setFormResetKey(value => value + 1);
        setFormValid(true);
        setDebugFormState('ready');
      }).catch(() => {
        if (!controller.signal.aborted) { setDebugForm(null); setFormValid(true); setDebugFormState('error'); }
      });
    }, 200);
    return () => { window.clearTimeout(timer); controller.abort(); };
  }, [environment, initialTarget?.resourceId, initialTarget?.type, packageId, targetId, targetType]);
  useEffect(() => {
    const release = navigation.registerUnsavedChanges(dirty, 'Run definition changes');
    releaseNavigationGuard.current = release;
    return () => releaseNavigationGuard.current();
  }, [dirty, navigation.registerUnsavedChanges]);
  function changed(action: () => void) { generation.current++; action(); setDraft(null); setDefinition(null); setReplayReview(null); setError(''); setDirty(true); }
  function object(text: string): JsonObject {
    const value: unknown = JSON.parse(text);
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Enter a JSON object.');
    return value as JsonObject;
  }
  async function validate() {
    const current = ++generation.current;
    setBusy(true); setError(''); setValidationDiagnostics([]); setDraft(null);
    try {
      if (isDebugTarget(targetType) && debugFormState === 'loading') throw new Error('Wait for the typed target defaults to load before validating.');
      if (debugForm && !formValid) throw new Error('Correct the typed fields or Advanced JSON before validating.');
      const body: RunDefinition = { schemaVersion: 'att-execution/v1', executionType: 'run', packageId: packageId.trim(), options: { debug, ...(failFast?{failFast:true}:{}) } };
      if (environment.trim()) body.environment = environment.trim();
      if (targetType === 'batch') body.selection = object(selection);
      else {
        body.target = debugForm?.target || { type: targetType, id: targetId.trim() };
        if (targetType !== 'case') body.input = object(businessInput);
      }
      if(replaySourceJobId) {
        const selectedEnvironment=body.environment||'';
        const environmentOverride=replayEnvironmentOrigin.current==='package'&&selectedEnvironment===replayPackageDefaultEnvironment.current?'':selectedEnvironment;
        const overrides:Record<string,unknown>={environment:environmentOverride,options:body.options,
          ...(body.target?{target:body.target}:{}),...(body.selection?{selection:body.selection}:{}),...(body.input?{input:body.input}:{})};
        const replay=await api.createJobReplayDraft(replaySourceJobId,{sourcePolicy:'current',overrides});
        if(current===generation.current){setReplayReview(replay);setValidationDiagnostics(replay.diagnostics||[]);setMissingReplayFields(replay.missingFields||[]);
          if(replay.intentId){setDraft((replay.preview as unknown as DraftResponse)||null);setDefinition(body);}
          else {setDraft(null);setDefinition(null);setError(replay.reason||'Resolve the current Package validation errors, then validate again.');}}
      } else {
        const validated = await api.previewRun(body);
        if (current === generation.current) { setDraft(validated); setDefinition(body); }
      }
    } catch (failure) { if (current === generation.current) {
      setError(failure instanceof Error ? failure.message : 'RUN validation failed.');
      const details=failure as Error&{diagnostics?:unknown};
      setValidationDiagnostics(Array.isArray(details.diagnostics)?details.diagnostics.filter(item=>item&&typeof item==='object') as Array<{code?: string; field?: string; summary?: string; detail?: string}>:[]);
    }}
    finally { setBusy(false); }
  }
  async function submit() {
    if (!draft || !definition || busy) return;
    setBusy(true); setError('');
    try {
      const job = replaySourceJobId&&replayReview?.intentId
        ? await api.rerunJob(replaySourceJobId,replayReview.intentId,replayReview.sourcePolicy||'current')
        : await api.submitRun({ packageId: definition.packageId, draftId: draft.draftId });
      releaseNavigationGuard.current();
      setDraft(null); setDirty(false); onAccepted({ jobId: job.jobId, packageId: definition.packageId });
    } catch (failure) { setDraft(null); setError(failure instanceof Error ? failure.message : 'RUN submission failed.'); }
    finally { setBusy(false); }
  }
  const backToExplorer = () => navigation.navigate(initialTarget
    ? { kind: 'package', packageId: packageId.trim(), resource: initialTarget }
    : { kind: 'package', packageId: packageId.trim() });
  const sidecarGroups = targetType === 'tool'
    ? [{ key: 'inputs', label: 'Inputs' }, { key: 'arguments', label: 'Tool arguments' }]
    : [{ key: 'inputs', label: 'Inputs' }, { key: 'vars', label: 'Variables' }];
  return <section className="page-content att-run-page" aria-labelledby="run-heading">
    {(initialTarget || initialBatchMode) && <button className="button button-quiet" type="button" onClick={backToExplorer}>Back to Explorer</button>}
    {replaySourceJobId&&<button className="button button-quiet" type="button" onClick={()=>navigation.navigate({kind:'jobs',jobId:replaySourceJobId})}>Back to source Job</button>}
    <h1 id="run-heading">Run</h1>
    <p>Validate a functional execution, review its selection, then run now or schedule the same definition.</p>
    {replayDraftLoading&&<p role="status">Loading the authorized Job draft…</p>}
    {replayDraftError&&<p role="alert">{replayDraftError}</p>}
    {replaySourceDefinition&&<><p role="note">New RUN from Job {replaySourceJobId}. The current Package and environment are revalidated.</p><p role="status">Job settings loaded. Review the retained fields, then validate against the current Package.</p></>}
    {missingReplayFields.length>0&&<p role="note">These fields were not safely retained and need an explicit value before this RUN can start: {missingReplayFields.join(', ')}.</p>}
    <fieldset className="att-run-definition" disabled={busy||replayDraftLoading}><legend>Execution definition</legend>
      {initialTarget ? <p><strong>Package:</strong> {packageId}<br /><strong>Target:</strong> {initialTarget.type} · {initialTarget.resourceId}</p>
        : <label>Package ID<input value={packageId} onChange={e => changed(() => setPackage(e.target.value))} /></label>}
      <label>Environment<select value={environment} onChange={e => { setEnvironmentNotice(''); changed(() => setEnvironment(e.target.value)); }} disabled={environmentState !== 'ready'}>
        <option value="">Package default</option>{environments.map(name => <option key={name} value={name}>{name}</option>)}
      </select></label>
      <EnvironmentResolutionNotice packageId={packageId} environment={environment} />
      {!initialTarget && <label>Target type<select value={targetType} onChange={e => changed(() => setType(e.target.value))}>
        <option value="case">Case</option><option value="template">Template</option><option value="flow">Flow</option><option value="tool">Tool</option><option value="batch">Batch selection</option>
      </select></label>}
      {!initialTarget && targetType !== 'batch' && <label>Target ID<input value={targetId} onChange={e => changed(() => setId(e.target.value))} /></label>}
      {environmentState === 'error' && <p role="alert">Environment profiles could not be loaded. Package default remains available. <button className="button button-quiet" type="button" onClick={() => setEnvironmentReload(value => value + 1)}>Retry profiles</button></p>}
      {environmentNotice && <p role="status">{environmentNotice}</p>}
      {targetType === 'batch' ? <><p>Use suites, suiteDirectory, caseIds, tags, excludeTags, all or rerunFailed. Combined filters retain the existing CLI selection rules.</p>
        <CodeEditor label="Case selection" readOnly={false} value={selection} language="json" onChange={value => changed(() => setSelection(value))} /></>
        : isDebugTarget(targetType) ? <>
          {debugFormState === 'loading' && <p role="status">Loading typed target defaults…</p>}
          {debugFormState === 'error' && <p role="status">Safe defaults are unavailable. Enter the target input as JSON below.</p>}
          {debugFormState === 'ready' && debugForm
            ? <>
              <p>Typed fields use the selected target's safe Debug defaults. Advanced JSON remains available for nested inputs.</p>
              {debugForm.redacted && <p role="note">Hidden package defaults remain protected until you choose a typed override.</p>}
              <TypedSidecarForm value={object(businessInput)} groups={sidecarGroups} resetKey={formResetKey}
                onChange={value => changed(() => setInput(JSON.stringify(value, null, 2)))}
                onValidityChange={valid => { setFormValid(valid); if (!valid) { setDraft(null); setDefinition(null); } }} />
            </>
            : <CodeEditor label="Target input" readOnly={false} value={businessInput} language="json" onChange={value => changed(() => setInput(value))} />}
        </>
        : targetType === 'case' && !initialTarget ? <p>Case inputs come from the package Excel and sidecar definitions.</p> : null}
      <label className="att-run-diagnostics"><input type="checkbox" checked={debug} onChange={e => changed(() => setDebug(e.target.checked))} />Debug diagnostics</label>
      <label><input type="checkbox" checked={failFast} onChange={e => changed(() => setFailFast(e.target.checked))} />Stop after the first failed Case</label>
      <button className="button button-primary" type="button" onClick={() => void validate()}>Validate</button>
    </fieldset>
    {error && <p role="alert">{error}</p>}
    {validationDiagnostics.length>0&&<ul className="att-run-validation-diagnostics" aria-label="RUN validation details">{validationDiagnostics.map((item,index)=><li key={`${item.code||item.field||item.path||'diagnostic'}-${index}`}>{[item.field||item.path,item.code].filter(Boolean).join(' · ')}{item.summary||item.detail?`: ${item.summary||item.detail}`:''}</li>)}</ul>}
    {busy && <p role="status">Processing…</p>}
    {draft && definition && <section className="att-run-review" aria-label="Review RUN"><h2>Review</h2>
      <p>Validation passed. Package changes require validation again.</p>
      {replayReview&&replaySourceDefinition&&<><p>Package revision: <code>{replayReview.originalRevision||replayReview.revision}</code> → <code>{replayReview.currentRevision||replayReview.revision}</code></p>
        {replayReview.missingFields?.length?<p role="alert">Fields needing explicit values: {replayReview.missingFields.join(', ')}.</p>:null}
        <p>Configuration provenance: original environment {String(replayReview.originalConfiguration?.environment||replaySourceDefinition.environment||'Package default recorded for the Job')}; current environment {String(replayReview.configuration?.environment||definition.environment||'Package default resolved now')}.</p>
        <details><summary>Review changes from the source Job</summary><p>Before → after</p><pre>{JSON.stringify({before:replaySourceDefinition,after:replayReview.definition||definition},null,2)}</pre></details></>}
      <CodeEditor label="Validated RUN preview" language={draft.previewYaml ? 'yaml' : 'json'} value={draft.previewYaml || JSON.stringify(draft.preview || draft, null, 2)} readOnly />
      <button className="button button-primary" type="button" disabled={busy||Boolean(replaySourceJobId&&(!replayReview?.intentId||replayReview.missingFields?.length))} onClick={() => void submit()}>{replaySourceJobId?'Run new Job':'Run Now'}</button>
      {schedulingEnabled&&!replaySourceJobId && <button className="button button-primary" type="button" disabled={busy} onClick={() => {
        releaseNavigationGuard.current();
        releaseNavigationGuard.current = () => {};
        const accepted = onSchedule({ packageId: definition.packageId, environment: definition.environment, draftId: draft.draftId,
          expiresAt: draft.expiresAt, executionType: 'run', legacyDebug: draft.draftId.startsWith('D'),
          previewYaml: draft.previewYaml || JSON.stringify(draft.preview || draft, null, 2), target: definition.target });
        if (accepted === false) {
          releaseNavigationGuard.current = navigation.registerUnsavedChanges(true, 'Run definition changes');
          setDirty(true);
        } else setDirty(false);
      }}>Schedule…</button>}
    </section>}
  </section>;
}
