import { useEffect, useMemo, useRef, useState } from 'react';
import { TypedSidecarForm } from '../explorer/TypedSidecarForm';
import type { JsonObject as SidecarObject, JsonValue } from '../explorer/types';
import { CodeEditor } from '../code/CodeEditor';
import type { LoadDraftResponse, LoadJobsApi, LoadModel, LoadTargetType, ServerDiagnostic } from './types';
import './load.css';

interface QuickLoadPageProps {
  api: LoadJobsApi;
  packageId: string;
  target: { type: string; resourceId: string };
  environment: string;
  environments: string[];
  environmentState: 'loading' | 'ready' | 'error';
  inlineLoadEnabled: boolean;
  onEnvironmentChange(environment: string): void;
  onJobAccepted(jobId: string): void;
  onCancel(): void;
  registerUnsavedChanges(isDirty: boolean, description?: string): () => void;
}
type JsonObject = Record<string, unknown>;
function asObject(value: unknown): JsonObject { return value && typeof value === 'object' && !Array.isArray(value) ? value as JsonObject : {}; }
function validTarget(type: string): type is LoadTargetType { return type === 'template' || type === 'flow' || type === 'tool'; }
function jsonObject(raw: string, label: string): JsonObject {
  let parsed: unknown;
  try { parsed = JSON.parse(raw || '{}'); } catch { throw new Error(`${label} must be valid JSON.`); }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error(`${label} must be a JSON object.`);
  return parsed as JsonObject;
}
function sidecarInput(input: Record<string, unknown>, type: LoadTargetType): SidecarObject {
  const hasGroups = ['inputs', 'vars', 'arguments'].some(key => Object.hasOwn(input, key));
  if (hasGroups) return JSON.parse(JSON.stringify(input)) as SidecarObject;
  return { inputs: input as Record<string, JsonValue>, ...(type === 'tool' ? { arguments: {} } : { vars: {} }) };
}
function makeScenario(policy: JsonObject, model: LoadModel, id: string, targetType: LoadTargetType,
  users: string, tps: string, maxConcurrent: string, duration: string, timing: { warmup: string; rampUp: string; rampDown: string },
  business: SidecarObject, advanced: { execution: string; testdata: string; thresholds: string; evidence: string; seed: string }): JsonObject {
  if (!duration.trim()) throw new Error('Measured duration is required.');
  const scenario: JsonObject = { ...policy, schemaVersion: String(policy.schemaVersion || 'att-load/v1.6') };
  const rootLoad: JsonObject = { ...asObject(policy.load), duration: duration.trim() };
  for (const key of ['warmup', 'rampUp', 'rampDown'] as const) {
    const value = timing[key].trim();
    if (value) rootLoad[key] = value; else delete rootLoad[key];
  }
  scenario.load = rootLoad;
  const workloadLoad: JsonObject = {};
  if (model === 'virtualUsers') {
    const amount = Number(users);
    if (!Number.isSafeInteger(amount) || amount < 1) throw new Error('Virtual Users must be a positive whole number.');
    workloadLoad.users = amount;
  } else {
    if (!tps.trim()) throw new Error('Target TPS is required.');
    const concurrency = Number(maxConcurrent);
    if (!Number.isSafeInteger(concurrency) || concurrency < 1) throw new Error('Maximum concurrency must be a positive whole number.');
    workloadLoad.arrivalRate = tps.trim(); workloadLoad.maxConcurrent = concurrency; workloadLoad.overloadPolicy = 'drop';
  }
  const target: JsonObject = { type: targetType, id };
  const workload: JsonObject = { id: 'quick-load', load: workloadLoad, target };
  if (Object.keys(asObject(business.inputs)).length) workload.inputs = asObject(business.inputs);
  if (targetType === 'tool') {
    if (Object.keys(asObject(business.arguments)).length) target.arguments = asObject(business.arguments);
  } else if (Object.keys(asObject(business.vars)).length) workload.vars = asObject(business.vars);
  scenario.workloads = [workload];
  const execution = jsonObject(advanced.execution, 'Execution defaults');
  const testdata = JSON.parse(advanced.testdata || '[]') as unknown;
  if (!Array.isArray(testdata) || testdata.some(item => typeof item !== 'string')) throw new Error('Testdata must be an array of package-relative paths.');
  const thresholds = jsonObject(advanced.thresholds, 'Thresholds');
  const evidence = jsonObject(advanced.evidence, 'Evidence');
  if (Object.keys(execution).length) scenario.execution = execution;
  if (testdata.length) scenario.testdata = testdata; else delete scenario.testdata;
  if (Object.keys(thresholds).length) scenario.thresholds = thresholds; else delete scenario.thresholds;
  if (Object.keys(evidence).length) scenario.evidence = evidence; else delete scenario.evidence;
  if (advanced.seed.trim()) { const seed = Number(advanced.seed); if (!Number.isSafeInteger(seed)) throw new Error('Seed must be a safe integer.'); scenario.seed = seed; }
  else delete scenario.seed;
  return scenario;
}

export function QuickLoadPage({ api, packageId, target, environment, environments, environmentState, inlineLoadEnabled, onEnvironmentChange, onJobAccepted, onCancel, registerUnsavedChanges }: QuickLoadPageProps) {
  const targetType = validTarget(target.type) ? target.type : null;
  const [model, setModel] = useState<LoadModel>('virtualUsers');
  const [preset, setPreset] = useState<'package-policy' | 'quick-profile' | 'custom'>('package-policy');
  const [policy, setPolicy] = useState<JsonObject | null>(null);
  const [policyState, setPolicyState] = useState<'loading' | 'ready' | 'error' | 'disabled'>('loading');
  const [policyMessage, setPolicyMessage] = useState('Loading Server Load policy…');
  const [users, setUsers] = useState('1');
  const [tps, setTps] = useState('1/s');
  const [maxConcurrent, setMaxConcurrent] = useState('1');
  const [duration, setDuration] = useState('');
  const [timing, setTiming] = useState({ warmup: '', rampUp: '', rampDown: '' });
  const [business, setBusiness] = useState<SidecarObject>({ inputs: {}, vars: {} });
  const [businessValid, setBusinessValid] = useState(true);
  const [advanced, setAdvanced] = useState({ execution: '{}', testdata: '[]', thresholds: '{}', evidence: '{}', seed: '' });
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [draft, setDraft] = useState<LoadDraftResponse | null>(null);
  const [draftState, setDraftState] = useState<'idle' | 'validating' | 'valid' | 'error' | 'submitting'>('idle');
  const [message, setMessage] = useState('Validate this single target before running it.');
  const [error, setError] = useState('');
  const [diagnostics, setDiagnostics] = useState<ServerDiagnostic[]>([]);
  const [confirming, setConfirming] = useState(false);
  const [resetKey, setResetKey] = useState(0);
  const identityKey = `${packageId}:${target.type}:${target.resourceId}`;
  const previousIdentity = useRef(identityKey);
  const dirtyRef = useRef(false);
  const templateBusiness = useRef<SidecarObject>({ inputs: {}, vars: {} });
  const policySequence = useRef(0);
  const sidecarSequence = useRef(0);
  const submitLock = useRef(false);
  const scenario = useMemo(() => {
    if (!policy || !targetType) return null;
    try { return makeScenario(policy, model, target.resourceId, targetType, users, tps, maxConcurrent, duration, timing, business, advanced); }
    catch { return null; }
  }, [policy, model, target.resourceId, targetType, environment, users, tps, maxConcurrent, duration, timing, business, advanced]);
  const rawJson = scenario ? JSON.stringify(scenario, null, 2) : '';

  useEffect(() => registerUnsavedChanges(dirty, 'Quick Load draft'), [dirty, registerUnsavedChanges]);
  useEffect(() => {
    const identityChanged = previousIdentity.current !== identityKey;
    if (identityChanged) { previousIdentity.current = identityKey; dirtyRef.current = false; setDirty(false); setBusiness({ inputs: {}, vars: {} }); setDraft(null); setDraftState('idle'); }
    const preserveDraft = dirtyRef.current && !identityChanged;
    const controller = new AbortController(); const request = ++policySequence.current;
    setPolicy(null); setDraft(null); setDraftState('idle'); setError(''); setDiagnostics([]); setPolicyState(inlineLoadEnabled ? 'loading' : 'disabled');
    setPolicyMessage(inlineLoadEnabled ? 'Loading safe policy defaults…' : 'Browser-created Load drafts are disabled by Server configuration.');
    if (!packageId || !targetType || !inlineLoadEnabled) return () => controller.abort();
    api.getLoadPolicy(packageId, model, environment || undefined, controller.signal).then(response => {
      if (controller.signal.aborted || request !== policySequence.current) return;
      const loaded = asObject(response.policy); setPolicy(loaded); setPolicyState('ready');
      setPolicyMessage(response.redacted ? 'Safe policy defaults loaded. Sensitive values remain protected.' : 'Server policy defaults loaded. Validation is required before running.');
      if (!preserveDraft) {
        const load = asObject(loaded.load);
        setPreset('package-policy');
        setDuration(String(load.duration || ''));
        setUsers(String(load.users ?? 1)); setTps(String(load.arrivalRate ?? '1/s')); setMaxConcurrent(String(load.maxConcurrent ?? 1));
        setTiming({ warmup: String(load.warmup || ''), rampUp: String(load.rampUp || ''), rampDown: String(load.rampDown || '') });
        setAdvanced({
          execution: JSON.stringify(asObject(loaded.execution), null, 2),
          testdata: JSON.stringify(Array.isArray(loaded.testdata) ? loaded.testdata : [], null, 2),
          thresholds: JSON.stringify(asObject(loaded.thresholds), null, 2),
          evidence: JSON.stringify(asObject(loaded.evidence), null, 2),
          seed: loaded.seed == null ? '' : String(loaded.seed),
        });
      }
    }).catch(reason => {
      if (controller.signal.aborted || request !== policySequence.current) return;
      setPolicyState('error'); setPolicyMessage(reason instanceof Error ? reason.message : 'Load policy is unavailable.');
    });
    return () => controller.abort();
  }, [api, packageId, targetType, identityKey, model, environment, inlineLoadEnabled]);

  useEffect(() => {
    if (!packageId || !targetType || !inlineLoadEnabled) return;
    const controller = new AbortController(); const request = ++sidecarSequence.current;
    api.getTargetLoadForm(packageId, targetType, target.resourceId, model, environment || undefined, controller.signal).then(form => {
      if (controller.signal.aborted || request !== sidecarSequence.current) return;
      const defaults = sidecarInput(form.input || {}, targetType);
      templateBusiness.current = defaults;
      if (!dirtyRef.current) setBusiness(defaults);
      setResetKey(value => value + 1);
      setMessage(form.redacted ? 'Safe target defaults loaded. Sensitive values remain protected by the Server.' : 'Target defaults loaded from the package debug sidecar.');
    }).catch(() => { if (!controller.signal.aborted && request === sidecarSequence.current) setMessage('No safe debug sidecar defaults were available. You can add inputs manually.'); });
    return () => controller.abort();
  }, [api, packageId, targetType, target.resourceId, identityKey, model, environment, inlineLoadEnabled]);

  function change<T>(setter: (value: T) => void, value: T) { setter(value); setPreset('custom'); dirtyRef.current = true; setDirty(true); setDraft(null); setDraftState('idle'); }
  function applyPreset(value: 'package-policy' | 'quick-profile' | 'custom') {
    setPreset(value);
    if (value === 'custom') { dirtyRef.current = true; setDirty(true); setDraft(null); setDraftState('idle'); return; }
    if (!policy) return;
    const load = asObject(policy.load);
    const execution = asObject(policy.execution);
    const testdata = Array.isArray(policy.testdata) ? policy.testdata : [];
    const thresholds = asObject(policy.thresholds);
    const evidence = asObject(policy.evidence);
    if (value === 'quick-profile') {
      setModel('virtualUsers'); setUsers('2'); setDuration('10s');
      setTiming({ warmup: '', rampUp: '', rampDown: '' });
      setAdvanced({
        execution: JSON.stringify({ ...execution, thinkTime: '250ms' }, null, 2),
        testdata: JSON.stringify(testdata, null, 2),
        thresholds: JSON.stringify(thresholds, null, 2),
        evidence: JSON.stringify({ ...evidence, mode: 'failures' }, null, 2),
        seed: policy.seed == null ? '' : String(policy.seed),
      });
    } else {
      setDuration(String(load.duration || ''));
      setUsers(String(load.users ?? 1)); setTps(String(load.arrivalRate ?? '1/s')); setMaxConcurrent(String(load.maxConcurrent ?? 1));
      setTiming({ warmup: String(load.warmup || ''), rampUp: String(load.rampUp || ''), rampDown: String(load.rampDown || '') });
      setAdvanced({
        execution: JSON.stringify(execution, null, 2),
        testdata: JSON.stringify(testdata, null, 2),
        thresholds: JSON.stringify(thresholds, null, 2),
        evidence: JSON.stringify(evidence, null, 2),
        seed: policy.seed == null ? '' : String(policy.seed),
      });
    }
    dirtyRef.current = true; setDirty(true); setDraft(null); setDraftState('idle');
  }
  async function validate() {
    setError(''); setDiagnostics([]); setDraft(null);
    if (!inlineLoadEnabled) { setDraftState('error'); setError('Quick Load is disabled by Server policy.'); return; }
    if (!businessValid) { setDraftState('error'); setError('Correct the invalid target inputs before validation.'); return; }
    let nextScenario: JsonObject;
    try { nextScenario = makeScenario(policy || {}, model, target.resourceId, targetType || 'template', users, tps, maxConcurrent, duration, timing, business, advanced); }
    catch (reason) { setDraftState('error'); setError(reason instanceof Error ? reason.message : 'Correct the Quick Load fields.'); return; }
    setDraftState('validating'); setMessage('Validating the single-target scenario on the Server…');
    try {
      const result = await api.createLoadDraft({ packageId, ...(environment ? { environment } : {}), scenario: nextScenario });
      setDraft(result); setDiagnostics(result.diagnostics || []); setDraftState('valid'); dirtyRef.current = false; setDirty(false);
      setMessage(result.diagnostics?.length ? 'The Server returned validation diagnostics. Review them before running.' : 'The Server accepted this draft for review.');
    } catch (reason) { setDraftState('error'); setError(reason instanceof Error ? reason.message : 'Quick Load validation failed.'); setMessage('Correct the fields or retry validation.'); }
  }
  async function runNow() {
    if (!draft || draftState !== 'valid' || submitLock.current) return;
    const accepted = window.confirm('Run this validated single-target Quick Load now?');
    if (!accepted) return;
    submitLock.current = true; setConfirming(true); setDraftState('submitting'); setError('');
    try { const job = await api.submitLoadDraft({ packageId, draftId: draft.draftId }); onJobAccepted(job.jobId); }
    catch (reason) { setDraftState('error'); setError(reason instanceof Error ? reason.message : 'Quick Load could not be started.'); setMessage('The validated draft could not be submitted. Retry validation if it expired.'); }
    finally { submitLock.current = false; setConfirming(false); }
  }
  const updateAdvanced = (key: keyof typeof advanced, value: string) => change(setAdvanced, { ...advanced, [key]: value });
  const groups = targetType === 'tool' ? [{ key: 'inputs', label: 'Inputs' }, { key: 'arguments', label: 'Tool arguments' }] : [{ key: 'inputs', label: 'Inputs' }, { key: 'vars', label: 'Variables' }];
  const targetLabel = targetType ? `${targetType} · ${target.resourceId}` : `Unsupported target · ${target.type}`;

  return <div className="att-load-page att-quick-load-page">
    <header className="att-feature-header"><div><p className="att-eyebrow">Single-target workload</p><h1>Quick Load</h1><p>Run a focused load check against one selected resource. Advanced Load supports multi-workload scenarios.</p></div>
      <div className="att-quick-load-actions"><button type="button" onClick={onCancel}>Back to Explorer</button><button type="button" disabled title="The Server does not yet support immutable inline Quick Load schedule definitions.">Schedule…</button></div>
    </header>
    {!targetType && <p className="att-inline-error" role="alert">Quick Load supports one Template, Flow, or Tool target. This route has an unsupported target type.</p>}
    {!inlineLoadEnabled && <p className="att-inline-error" role="alert">Browser-created Load drafts are disabled by Server configuration.</p>}
    <section className="att-load-section" aria-labelledby="quick-load-target">
      <div className="att-section-heading"><div><h2 id="quick-load-target">Target</h2><p>The selected resource is fixed for this Quick Load.</p></div><strong>{targetLabel}</strong></div>
      <div className="att-load-grid">
        <label>Load template / preset<select aria-label="Load template or preset" value={preset} onChange={event => applyPreset(event.currentTarget.value as 'package-policy' | 'quick-profile' | 'custom')} disabled={!policy || !inlineLoadEnabled}>
          <option value="package-policy">Package / Server policy</option><option value="quick-profile">Quick smoke · 2 users · 10s</option>
          {preset === 'custom' && <option value="custom">Custom edits</option>}
        </select><small className="att-help">{preset === 'quick-profile' ? 'Based on examples/load/quick-profile.yaml; target inputs stay unchanged.' : 'Selecting a preset only edits this draft. Validate before running.'}</small></label>
        <label>Load model<select value={model} onChange={event => { setModel(event.currentTarget.value as LoadModel); setPreset('custom'); dirtyRef.current = true; setDirty(true); setDraft(null); setDraftState('idle'); }} disabled={!inlineLoadEnabled}><option value="virtualUsers">Virtual Users</option><option value="arrivalRate">Arrival Rate</option></select></label>
        <label>Environment<select value={environment} onChange={event => { onEnvironmentChange(event.currentTarget.value); setPreset('custom'); dirtyRef.current = true; setDirty(true); setDraft(null); }}><option value="">Package default</option>{environments.map(item => <option key={item} value={item}>{item}</option>)}</select></label>
        {model === 'virtualUsers' ? <label>Users<input type="number" min="1" step="1" value={users} onChange={event => change(setUsers, event.currentTarget.value)} /></label>
          : <label>Target TPS<input value={tps} onChange={event => change(setTps, event.currentTarget.value)} placeholder="e.g. 5/s" /></label>}
        {model === 'arrivalRate' && <label>Maximum concurrent<input type="number" min="1" step="1" value={maxConcurrent} onChange={event => change(setMaxConcurrent, event.currentTarget.value)} /></label>}
        <label>Duration<input value={duration} onChange={event => change(setDuration, event.currentTarget.value)} placeholder="e.g. 30s or 5m" /></label>
      </div>
      {environmentState === 'loading' && <p role="status">Loading package environments…</p>}{environmentState === 'error' && <p className="att-help">Environment profiles are unavailable; the package default remains selectable.</p>}
      <p className="att-help">{policyMessage}</p>
    </section>
    <section className="att-load-section" aria-labelledby="quick-load-inputs">
      <div className="att-section-heading"><div><h2 id="quick-load-inputs">Inputs and variables</h2><p>Values from the safe debug sidecar stay editable as ATT-authored literals.</p></div>
        <button type="button" onClick={() => { setBusiness(templateBusiness.current); setResetKey(value => value + 1); setPreset('custom'); dirtyRef.current = true; setDirty(true); setDraft(null); setDraftState('idle'); }}>Reset inputs to sidecar defaults</button></div>
      <TypedSidecarForm value={business} groups={groups} resetKey={resetKey} onChange={next => { setBusiness(next); setBusinessValid(true); setPreset('custom'); dirtyRef.current = true; setDirty(true); setDraft(null); }} onValidityChange={setBusinessValid} />
    </section>
    <section className="att-load-section" aria-labelledby="quick-load-advanced">
      <button className="att-quick-load-disclosure" type="button" aria-expanded={advancedOpen} onClick={() => setAdvancedOpen(value => !value)}>Advanced Settings {advancedOpen ? '−' : '+'}</button>
      {advancedOpen && <div className="att-load-grid">
        <label>Warm-up<input value={timing.warmup} onChange={event => change(setTiming, { ...timing, warmup: event.currentTarget.value })} placeholder="Optional duration" /></label>
        <label>Ramp-up<input value={timing.rampUp} onChange={event => change(setTiming, { ...timing, rampUp: event.currentTarget.value })} placeholder="Optional duration" /></label>
        <label>Ramp-down<input value={timing.rampDown} onChange={event => change(setTiming, { ...timing, rampDown: event.currentTarget.value })} placeholder="Optional duration" /></label>
        <label>Seed<input type="number" step="1" value={advanced.seed} onChange={event => updateAdvanced('seed', event.currentTarget.value)} /></label>
        {(['thresholds', 'evidence', 'execution'] as const).map(key => <label className="att-json-field" key={key}>{key === 'thresholds' ? 'Thresholds JSON' : key === 'evidence' ? 'Evidence settings JSON' : 'Execution defaults JSON'}
          <CodeEditor value={advanced[key]} language="json" label={`${key} advanced JSON`} readOnly={false} onChange={value => updateAdvanced(key, value)} />
        </label>)}
        <label className="att-json-field">Testdata paths JSON<CodeEditor value={advanced.testdata} language="json" label="Testdata paths JSON" readOnly={false} onChange={value => updateAdvanced('testdata', value)} /></label>
        {rawJson && <details><summary>Complete scenario JSON preview</summary><CodeEditor value={rawJson} language="json" label="Complete single-target scenario JSON" readOnly /></details>}
      </div>}
    </section>
    <section className="att-load-section" aria-labelledby="quick-load-review">
      <div className="att-section-heading"><div><h2 id="quick-load-review">Validate and run</h2><p>Changing a preset or model never starts a job.</p></div>
        <div className="att-quick-load-actions"><button type="button" onClick={() => void validate()} disabled={!policy || policyState !== 'ready' || !inlineLoadEnabled || draftState === 'validating' || draftState === 'submitting'}>Validate</button>
          <button type="button" className="att-primary-action" onClick={() => void runNow()} disabled={!draft || draftState !== 'valid' || confirming}>{confirming ? 'Starting…' : 'Run Now'}</button></div>
      </div>
      <div className={`att-validation-state att-state-${draftState}`} role="status" aria-live="polite" aria-busy={draftState === 'validating' || draftState === 'submitting'}><p>{message}</p>{error && <p className="att-inline-error" role="alert">{error}</p>}
        {policyState === 'loading' && <p>Loading Load policy…</p>}{policyState === 'error' && <p className="att-inline-error">{policyMessage}</p>}
        {diagnostics.length > 0 && <ul>{diagnostics.map((item, index) => <li key={`${item.code || item.path || 'diagnostic'}-${index}`}>{[item.severity, item.path, item.code].filter(Boolean).join(' · ')}{item.message || item.summary ? `: ${item.message || item.summary}` : ''}</li>)}</ul>}
      </div>
      {draft?.previewYaml && <CodeEditor value={draft.previewYaml} language="yaml" label="Server validated Quick Load preview" readOnly />}
      {draft?.expiresAt && <p className="att-help">Validated draft expires {new Date(draft.expiresAt).toLocaleString()}.</p>}
    </section>
    <details className="att-quick-load-schedule-note"><summary>Scheduling availability</summary><p>Scheduling is unavailable for this inline draft until the Server supports immutable inline Quick Load schedule snapshots. No test is scheduled.</p></details>
  </div>;
}
