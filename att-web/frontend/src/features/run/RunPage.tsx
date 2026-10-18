import './run.css';
import { useEffect, useRef, useState } from 'react';
import { api } from '../../api';
import { useNavigationGuard } from '../../router';
import type { ResourceRoute } from '../../router';
import { CodeEditor } from '../code/CodeEditor';
import { TypedSidecarForm } from '../explorer/TypedSidecarForm';
import type { DebugFormResponse, DebugResourceType, DraftResponse, JsonObject, ResourceIdentity } from '../explorer/types';
import type { ValidatedScheduleIntent } from '../schedules/types';

export interface RunDefinition {
  schemaVersion: 'att-execution/v1'; executionType: 'run'; packageId: string; environment?: string;
  target?: { type: string; id: string }; selection?: JsonObject; input?: JsonObject;
  options: { debug: boolean };
}
function isDebugTarget(type: string): type is DebugResourceType {
  return type === 'template' || type === 'flow' || type === 'tool';
}

export function RunPage({ packageId: initialPackage = '', initialTarget, initialBatchMode = false, initialEnvironment = '', onAccepted, onSchedule, schedulingEnabled }: {
  packageId?: string; onAccepted(job: { jobId: string; packageId: string }): void;
  initialTarget?: ResourceRoute; initialBatchMode?: boolean; initialEnvironment?: string;
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
  const [draft, setDraft] = useState<DraftResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [dirty, setDirty] = useState(false);
  const generation = useRef(0);
  const [definition, setDefinition] = useState<RunDefinition | null>(null);
  const releaseNavigationGuard = useRef<() => void>(() => {});
  const environmentRef = useRef(environment);
  environmentRef.current = environment;
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
        setInput(JSON.stringify(response.input || {}, null, 2));
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
  function changed(action: () => void) { generation.current++; action(); setDraft(null); setDefinition(null); setError(''); setDirty(true); }
  function object(text: string): JsonObject {
    const value: unknown = JSON.parse(text);
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Enter a JSON object.');
    return value as JsonObject;
  }
  async function validate() {
    const current = ++generation.current;
    setBusy(true); setError(''); setDraft(null);
    try {
      if (isDebugTarget(targetType) && debugFormState === 'loading') throw new Error('Wait for the typed target defaults to load before validating.');
      if (debugForm && !formValid) throw new Error('Correct the typed fields or Advanced JSON before validating.');
      const body: RunDefinition = { schemaVersion: 'att-execution/v1', executionType: 'run', packageId: packageId.trim(), options: { debug } };
      if (environment.trim()) body.environment = environment.trim();
      if (targetType === 'batch') body.selection = object(selection);
      else {
        body.target = debugForm?.target || { type: targetType, id: targetId.trim() };
        if (targetType !== 'case') body.input = object(businessInput);
      }
      const validated = await api.previewRun(body);
      if (current === generation.current) { setDraft(validated); setDefinition(body); }
    } catch (failure) { if (current === generation.current) setError(failure instanceof Error ? failure.message : 'RUN validation failed.'); }
    finally { setBusy(false); }
  }
  async function submit() {
    if (!draft || !definition || busy) return;
    setBusy(true); setError('');
    try {
      const job = await api.submitRun({ packageId: definition.packageId, draftId: draft.draftId });
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
    <h1 id="run-heading">Run</h1>
    <p>Validate a functional execution, review its selection, then run now or schedule the same definition.</p>
    <fieldset className="att-run-definition" disabled={busy}><legend>Execution definition</legend>
      {initialTarget ? <p><strong>Package:</strong> {packageId}<br /><strong>Target:</strong> {initialTarget.type} · {initialTarget.resourceId}</p>
        : <label>Package ID<input value={packageId} onChange={e => changed(() => setPackage(e.target.value))} /></label>}
      <label>Environment<select value={environment} onChange={e => { setEnvironmentNotice(''); changed(() => setEnvironment(e.target.value)); }} disabled={environmentState !== 'ready'}>
        <option value="">Package default</option>{environments.map(name => <option key={name} value={name}>{name}</option>)}
      </select></label>
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
      <button className="button button-primary" type="button" onClick={() => void validate()}>Validate</button>
    </fieldset>
    {error && <p role="alert">{error}</p>}
    {busy && <p role="status">Processing…</p>}
    {draft && definition && <section className="att-run-review" aria-label="Review RUN"><h2>Review</h2>
      <p>Validation passed. Package changes require validation again.</p>
      <CodeEditor label="Validated RUN preview" language={draft.previewYaml ? 'yaml' : 'json'} value={draft.previewYaml || JSON.stringify(draft.preview || draft, null, 2)} readOnly />
      <button className="button button-primary" type="button" disabled={busy} onClick={() => void submit()}>Run Now</button>
      {schedulingEnabled && <button className="button button-primary" type="button" disabled={busy} onClick={() => {
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
