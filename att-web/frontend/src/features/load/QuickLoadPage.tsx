import { useEffect, useMemo, useRef, useState } from 'react';
import { TypedSidecarForm } from '../explorer/TypedSidecarForm';
import type { JsonObject as SidecarObject, JsonValue } from '../explorer/types';
import { CodeEditor } from '../code/CodeEditor';
import type { LoadDraftResponse, LoadJobsApi, LoadModel, LoadPolicyTemplate, LoadTargetType, ServerDiagnostic } from './types';
import { ApiError } from '../../types';
import './load.css';

interface QuickLoadPageProps {
  api: LoadJobsApi;
  packageId: string;
  target?: { type: string; resourceId: string; logicalId?: string };
  initialTemplateId?: string;
  initialModel?: LoadModel;
  backLabel?: string;
  environment: string;
  environments: string[];
  environmentState: 'loading' | 'ready' | 'error';
  environmentNotice?: string;
  inlineLoadEnabled: boolean;
  schedulingEnabled?: boolean;
  onRetryEnvironments?: () => void;
  onEnvironmentChange(environment: string): void;
  onJobAccepted(jobId: string): void;
  onSchedule?(input: { packageId: string; environment?: string; draftId?: string; previewYaml?: string; expiresAt?: string; scenario?: string }): boolean | void;
  onCancel(): void;
  registerUnsavedChanges(isDirty: boolean, description?: string): () => void;
}
type JsonObject = Record<string, unknown>;
function safeDiagnosticField(value: unknown): string | undefined {
  if (typeof value !== 'string' || value.length > 1000 || /[\u0000-\u001f\u007f]/.test(value)) return undefined;
  return value.trim() || undefined;
}
function safeDiagnosticPath(value: unknown): string | undefined {
  const path = safeDiagnosticField(value);
  if (!path || path.startsWith('/') || /^[A-Za-z]:[\\/]/.test(path) || path.startsWith('\\\\')) return undefined;
  return path;
}
function safeDiagnosticText(value: unknown): string | undefined {
  const text = safeDiagnosticField(value);
  if (!text) return undefined;
  return text.replace(/(password|passwd|token|secret|credential)(\s*[:=]\s*)[^\s,}]+/gi, '$1$2[REDACTED]')
    .replace(/(?:[A-Za-z]:)?[/\\][^\s:]+/g, '[package path]');
}
function safeServerDiagnostics(value: unknown): ServerDiagnostic[] {
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is Record<string, unknown> => Boolean(item && typeof item === 'object' && !Array.isArray(item)))
    .map(item => ({
      severity: safeDiagnosticField(item.severity), code: safeDiagnosticField(item.code),
      reason: safeDiagnosticField(item.reason), field: safeDiagnosticField(item.field), path: safeDiagnosticPath(item.path),
      message: safeDiagnosticText(item.message), summary: safeDiagnosticText(item.summary),
      detail: safeDiagnosticText(item.detail), suggestion: safeDiagnosticText(item.suggestion),
      file: safeDiagnosticPath(item.file), sourceFile: safeDiagnosticPath(item.sourceFile),
      resourceId: safeDiagnosticPath(item.resourceId), action: safeDiagnosticText(item.action),
    }));
}
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
  const scenario: JsonObject = { ...policy, schemaVersion: String(policy.schemaVersion || 'att-load/v1.8') };
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
  if (evidence.maxFailureSamples != null || evidence.maxSuccessSamples != null
      || evidence.quotaScope != null || evidence.defaults != null) scenario.schemaVersion = 'att-load/v1.8';
  if (advanced.seed.trim()) { const seed = Number(advanced.seed); if (!Number.isSafeInteger(seed)) throw new Error('Seed must be a safe integer.'); scenario.seed = seed; }
  else delete scenario.seed;
  return scenario;
}

export function QuickLoadPage({ api, packageId, target, initialTemplateId = '', initialModel = 'virtualUsers', backLabel, environment, environments, environmentState, environmentNotice, inlineLoadEnabled, schedulingEnabled = false, onEnvironmentChange, onJobAccepted, onSchedule, onCancel, onRetryEnvironments, registerUnsavedChanges }: QuickLoadPageProps) {
  const [selectedTarget, setSelectedTarget] = useState<{ type: LoadTargetType; resourceId: string; logicalId: string } | null>(target && validTarget(target.type) ? { type: target.type, resourceId: target.resourceId, logicalId: target.logicalId || '' } : null);
  const activeTarget = target ?? selectedTarget;
  const targetType = activeTarget && validTarget(activeTarget.type) ? activeTarget.type : null;
  const [targetOptions, setTargetOptions] = useState<Array<{ type: LoadTargetType; resourceId: string; logicalId: string; state: string }>>([]);
  const [targetListState, setTargetListState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [model, setModel] = useState<LoadModel>(initialModel);
  const [templateId, setTemplateId] = useState(initialTemplateId);
  const [preset, setPreset] = useState('package-policy');
  const [policy, setPolicy] = useState<JsonObject | null>(null);
  const [templates, setTemplates] = useState<LoadPolicyTemplate[]>([]);
  const [templateCatalogTruncated, setTemplateCatalogTruncated] = useState(false);
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
  const [requestId, setRequestId] = useState('');
  const [logicalTargetId, setLogicalTargetId] = useState(target?.logicalId || '');
  const [sidecarState, setSidecarState] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const [sidecarError, setSidecarError] = useState('');
  const [sidecarDiagnostics, setSidecarDiagnostics] = useState<ServerDiagnostic[]>([]);
  const [sidecarRequestId, setSidecarRequestId] = useState('');
  const [sidecarRefresh, setSidecarRefresh] = useState(0);
  const [confirming, setConfirming] = useState(false);
  const [resetKey, setResetKey] = useState(0);
  const identityKey = activeTarget ? `${packageId}:${activeTarget.type}:${activeTarget.resourceId}:${activeTarget.logicalId || ''}` : `${packageId}:select-target`;
  const availableTemplates = templates;
  const previousIdentity = useRef(identityKey);
  const previousEnvironment = useRef(environment);
  const dirtyRef = useRef(false);
  const templateBusiness = useRef<SidecarObject>({ inputs: {}, vars: {} });
  const policySequence = useRef(0);
  const pendingModelPolicySelection = useRef(false);
  const pendingTemplatePolicySelection = useRef(false);
  const sidecarSequence = useRef(0);
  const validationSequence = useRef(0);
  const validationController = useRef<AbortController | null>(null);
  const submitLock = useRef(false);
  const scenario = useMemo(() => {
    if (!policy || !targetType) return null;
    try { return logicalTargetId ? makeScenario(policy, model, logicalTargetId, targetType, users, tps, maxConcurrent, duration, timing, business, advanced) : null; }
    catch { return null; }
  }, [policy, model, logicalTargetId, targetType, environment, users, tps, maxConcurrent, duration, timing, business, advanced]);
  const rawJson = scenario ? JSON.stringify(scenario, null, 2) : '';
  const scenarioFingerprint = JSON.stringify([identityKey, environment, model, templateId, preset, rawJson]);
  const scenarioFingerprintRef = useRef(scenarioFingerprint);
  scenarioFingerprintRef.current = scenarioFingerprint;
  const [validatedFingerprint, setValidatedFingerprint] = useState<string | null>(null);

  useEffect(() => {
    if (target || !inlineLoadEnabled) return;
    const controller = new AbortController(); setTargetListState('loading');
    Promise.all((['template', 'flow', 'tool'] as const).map(async type =>
      (await api.listTargets(packageId, type, controller.signal)).map(item => ({ ...item, type }))))
      .then(groups => { if (!controller.signal.aborted) { setTargetOptions(groups.flat()); setTargetListState('ready'); } })
      .catch(() => { if (!controller.signal.aborted) { setTargetOptions([]); setTargetListState('error'); } });
    return () => controller.abort();
  }, [api, packageId, target, inlineLoadEnabled]);

  function invalidateDraft() {
    validationSequence.current++;
    validationController.current?.abort();
    validationController.current = null;
    setDraft(null); setValidatedFingerprint(null); setDraftState('idle'); setError(''); setDiagnostics([]); setRequestId('');
    setMessage('The scenario changed. Validate it again before running or scheduling.');
  }

  useEffect(() => registerUnsavedChanges(dirty, 'Quick Load draft'), [dirty, registerUnsavedChanges]);
  useEffect(() => () => { validationController.current?.abort(); }, []);
  useEffect(() => {
    if (previousEnvironment.current === environment) return;
    previousEnvironment.current = environment;
    invalidateDraft();
    dirtyRef.current = false;
    setDirty(false);
    setBusiness({ inputs: {}, vars: {} });
    setBusinessValid(true);
    templateBusiness.current = { inputs: {}, vars: {} };
    setModel(initialModel);
    setTemplateId(initialTemplateId);
    setPreset('package-policy');
    setUsers('1'); setTps('1/s'); setMaxConcurrent('1'); setDuration('');
    setTiming({ warmup: '', rampUp: '', rampDown: '' });
    setAdvanced({ execution: '{}', testdata: '[]', thresholds: '{}', evidence: '{}', seed: '' });
    setLogicalTargetId(target?.logicalId || '');
    pendingModelPolicySelection.current = false;
    pendingTemplatePolicySelection.current = false;
    setResetKey(value => value + 1);
  }, [environment, initialModel, initialTemplateId, target?.logicalId]);
  useEffect(() => {
    const identityChanged = previousIdentity.current !== identityKey;
    if (identityChanged) { previousIdentity.current = identityKey; invalidateDraft(); dirtyRef.current = false; setDirty(false); setBusiness({ inputs: {}, vars: {} }); setLogicalTargetId(activeTarget?.logicalId || ''); }
    const resetForPolicySelection = pendingModelPolicySelection.current || pendingTemplatePolicySelection.current;
    const preserveDraft = dirtyRef.current && !identityChanged && !resetForPolicySelection;
    const controller = new AbortController(); const request = ++policySequence.current;
    setPolicy(null); setDraft(null); setDraftState('idle'); setError(''); setDiagnostics([]); setPolicyState(inlineLoadEnabled ? 'loading' : 'disabled');
    setPolicyMessage(inlineLoadEnabled ? 'Loading safe policy defaults…' : 'Browser-created Load drafts are disabled by Server configuration.');
    if (!packageId || !targetType || !inlineLoadEnabled) return () => controller.abort();
    api.getLoadPolicy(packageId, model, environment || undefined, controller.signal, templateId || undefined).then(response => {
      if (controller.signal.aborted || request !== policySequence.current) return;
      pendingModelPolicySelection.current = false;
      pendingTemplatePolicySelection.current = false;
      const loaded = asObject(response.policy); setPolicy(loaded); setTemplates(response.templates || []);
      setTemplateCatalogTruncated(Boolean(response.templateCatalogTruncated)); setPolicyState('ready');
      setPolicyMessage(response.redacted ? 'Safe model-specific policy defaults loaded. Sensitive values remain protected.' : 'The selected model’s package policy or Server fallback is loaded. Validate before running.');
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
  }, [api, packageId, targetType, identityKey, model, environment, templateId, inlineLoadEnabled]);

  useEffect(() => {
    if (!packageId || !targetType || !inlineLoadEnabled) return;
    const controller = new AbortController(); const request = ++sidecarSequence.current;
    if (!activeTarget) return () => controller.abort();
    setSidecarState('loading'); setSidecarError(''); setSidecarDiagnostics([]); setSidecarRequestId('');
    api.getTargetLoadForm(packageId, targetType, activeTarget.resourceId, model, environment || undefined, controller.signal).then(form => {
      if (controller.signal.aborted || request !== sidecarSequence.current) return;
      const resolvedId = typeof form.target?.id === 'string' ? form.target.id : '';
      if (!resolvedId) throw new Error('The Server did not return a logical target ID.');
      setLogicalTargetId(resolvedId);
      const defaults = sidecarInput(form.input || {}, targetType);
      templateBusiness.current = defaults;
      if (!dirtyRef.current) setBusiness(defaults);
      setResetKey(value => value + 1);
      setSidecarState('ready');
      setMessage(form.redacted ? 'Safe target defaults are ready. Sensitive values remain protected by the Server.' : 'Target defaults are ready. Add inputs manually if needed.');
    }).catch(reason => {
      if (controller.signal.aborted || request !== sidecarSequence.current) return;
      setSidecarState('error');
      setSidecarError(reason instanceof Error ? reason.message : 'Safe target defaults are unavailable.');
      setSidecarDiagnostics(reason instanceof ApiError ? safeServerDiagnostics(reason.diagnostics) : []);
      setSidecarRequestId(reason instanceof ApiError ? reason.requestId || '' : '');
    });
    return () => controller.abort();
  }, [api, packageId, targetType, activeTarget?.resourceId, identityKey, model, environment, inlineLoadEnabled, sidecarRefresh]);

  function change<T>(setter: (value: T) => void, value: T) { invalidateDraft(); setter(value); setPreset('custom'); dirtyRef.current = true; setDirty(true); }
  function selectTemplate(nextTemplateId: string) {
    if (nextTemplateId === templateId) return;
    if (dirtyRef.current && !window.confirm('Replace the current Quick Load settings with the selected template?')) return;
    const nextTemplate = availableTemplates.find(item => item.id === nextTemplateId);
    invalidateDraft();
    pendingTemplatePolicySelection.current = true;
    pendingModelPolicySelection.current = Boolean(nextTemplate && nextTemplate.model !== model);
    if (nextTemplate && nextTemplate.model !== model) setModel(nextTemplate.model);
    setTemplateId(nextTemplateId);
    setPreset(nextTemplateId ? `template:${nextTemplateId}` : 'package-policy');
    dirtyRef.current = true; setDirty(true);
  }

  function selectModel(nextModel: LoadModel) {
    if (nextModel === model) return;
    if (dirtyRef.current && !window.confirm('Replace the current Quick Load settings with this model’s defaults?')) return;
    invalidateDraft();
    pendingModelPolicySelection.current = true;
    pendingTemplatePolicySelection.current = false;
    setModel(nextModel); setTemplateId(''); setPreset('package-policy');
    dirtyRef.current = true; setDirty(true);
  }
  async function validate() {
    const request = ++validationSequence.current;
    validationController.current?.abort();
    validationController.current = null;
    const fingerprint = scenarioFingerprint;
    setError(''); setDiagnostics([]); setRequestId(''); setDraft(null); setValidatedFingerprint(null);
    if (!inlineLoadEnabled) { setDraftState('error'); setError('Quick Load is disabled by Server policy.'); return; }
    if (!businessValid) { setDraftState('error'); setError('Correct the invalid target inputs before validation.'); return; }
    let nextScenario: JsonObject;
    try {
      if (!logicalTargetId) throw new Error('Wait for the Server to resolve this resource before validating.');
      nextScenario = makeScenario(policy || {}, model, logicalTargetId, targetType || 'template', users, tps, maxConcurrent, duration, timing, business, advanced);
    }
    catch (reason) { setDraftState('error'); setError(reason instanceof Error ? reason.message : 'Correct the Quick Load fields.'); return; }
    const controller = new AbortController();
    validationController.current = controller;
    setDraftState('validating'); setMessage('Validating the single-target scenario on the Server…');
    try {
      const result = await api.createLoadDraft({ packageId, ...(environment ? { environment } : {}), scenario: nextScenario }, controller.signal);
      if (controller.signal.aborted || request !== validationSequence.current || fingerprint !== scenarioFingerprintRef.current) return;
      setDraft(result); setDiagnostics(result.diagnostics || []); setDraftState('valid'); dirtyRef.current = false; setDirty(false);
      setRequestId(result.requestId || '');
      setValidatedFingerprint(fingerprint);
      setMessage(result.diagnostics?.length ? 'The Server returned validation diagnostics. Review them before running.' : 'The Server accepted this draft for review.');
    } catch (reason) {
      if (controller.signal.aborted || request !== validationSequence.current || fingerprint !== scenarioFingerprintRef.current) return;
      setDraftState('error'); setError(reason instanceof Error ? reason.message : 'Quick Load validation failed.');
      if (reason instanceof ApiError) {
        setRequestId(reason.requestId || '');
        setDiagnostics(safeServerDiagnostics(reason.diagnostics));
      }
      setMessage('Review the validation details below, then correct the indicated input or package dependency before retrying.');
    } finally { if (validationController.current === controller) validationController.current = null; }
  }
  async function runNow() {
    if (!draft || draftState !== 'valid' || validatedFingerprint !== scenarioFingerprint || submitLock.current) return;
    const accepted = window.confirm('Run this validated single-target Quick Load now?');
    if (!accepted) return;
    submitLock.current = true; setConfirming(true); setDraftState('submitting'); setError('');
    try { const job = await api.submitLoadDraft({ packageId, draftId: draft.draftId }); onJobAccepted(job.jobId); }
    catch (reason) { setDraftState('error'); setError(reason instanceof Error ? reason.message : 'Quick Load could not be started.'); setMessage('The validated draft could not be submitted. Retry validation if it expired.'); }
    finally { submitLock.current = false; setConfirming(false); }
  }
  const updateAdvanced = (key: keyof typeof advanced, value: string) => change(setAdvanced, { ...advanced, [key]: value });
  const groups = targetType === 'tool' ? [{ key: 'inputs', label: 'Inputs' }, { key: 'arguments', label: 'Tool arguments' }] : [{ key: 'inputs', label: 'Inputs' }, { key: 'vars', label: 'Variables' }];
  const targetLabel = targetType ? `${targetType} · ${logicalTargetId || 'Resolving…'}` : activeTarget ? `Unsupported target · ${activeTarget.type}` : 'Choose a Template, Flow, or Tool';

  function diagnosticControl(item: ServerDiagnostic): HTMLElement | undefined {
    const page = document.querySelector('.att-quick-load-page');
    const field = item.path || item.field;
    if (field === 'scenario.load.duration') return page?.querySelector<HTMLElement>('input[placeholder="e.g. 30s or 5m"]') || undefined;
    if (field === 'scenario.load.users') return page?.querySelector<HTMLElement>('input[type="number"]') || undefined;
    return undefined;
  }
  function reviewDiagnostic(item: ServerDiagnostic) {
    diagnosticControl(item)?.focus();
  }

  return <div className="page-content att-load-page att-quick-load-page">
    <header className="att-feature-header"><div><p className="att-eyebrow">Single-target workload</p><h1>Quick Load</h1><p>Run a focused load check against one selected resource. Advanced Load supports multi-workload scenarios.</p></div>
      <div className="att-quick-load-actions"><button type="button" onClick={onCancel}>{backLabel || 'Back to Explorer'}</button><button type="button"
        onClick={() => draft?.draftId && onSchedule?.({ packageId, environment: environment || undefined, draftId: draft.draftId,
          previewYaml: draft.previewYaml, expiresAt: draft.expiresAt })}
        disabled={!schedulingEnabled || !draft?.draftId || draftState !== 'valid' || validatedFingerprint !== scenarioFingerprint || !onSchedule}
        title={!schedulingEnabled ? 'Scheduled Load is disabled by Server configuration.' : !draft?.draftId ? 'Validate the scenario before scheduling.' : undefined}>Schedule…</button></div>
    </header>
    {activeTarget && !targetType && <p className="att-inline-error" role="alert">Quick Load supports one Template, Flow, or Tool target. This route has an unsupported target type.</p>}
    {!inlineLoadEnabled && <p className="att-inline-error" role="alert">Browser-created Load drafts are disabled by Server configuration.</p>}
    <section className="att-load-section" aria-labelledby="quick-load-target">
      <div className="att-section-heading"><div><h2 id="quick-load-target">Target</h2><p>{activeTarget ? 'The selected resource is fixed for this Quick Load.' : 'Choose a target from this package to compose one workload.'}</p></div><strong>{targetLabel}</strong></div>
      {!target && <label>Package target<select aria-label="Quick Load target" value={selectedTarget ? `${selectedTarget.type}:${selectedTarget.resourceId}` : ''}
        onChange={event => {
          const selected = targetOptions.find(item => `${item.type}:${item.resourceId}` === event.currentTarget.value);
          const next = selected ? { type: selected.type, resourceId: selected.resourceId, logicalId: selected.logicalId } : null;
          if (next?.type === selectedTarget?.type && next?.resourceId === selectedTarget?.resourceId) return;
          if (dirtyRef.current && !window.confirm('Replace the current Quick Load settings and inputs with a new target?')) return;
          invalidateDraft(); dirtyRef.current = false; setDirty(false);
          setSelectedTarget(next); setBusiness({ inputs: {}, vars: {} }); setLogicalTargetId(next?.logicalId || '');
        }}>
        <option value="">Select a Template, Flow, or Tool</option>{targetOptions.map(item => <option key={`${item.type}:${item.resourceId}`} value={`${item.type}:${item.resourceId}`}>{item.type} · {item.logicalId} · {item.state}</option>)}
      </select>{targetListState === 'loading' && <small role="status">Loading package targets…</small>}{targetListState === 'error' && <small role="alert">Package targets could not be loaded. Retry from the Explorer.</small>}</label>}
      <div className="att-load-grid">
        <label>Load template / preset<select aria-label="Load template or preset" value={templateId} onChange={event => selectTemplate(event.currentTarget.value)} disabled={!policy || !inlineLoadEnabled}>
          <option value="">Model default</option>
          {templateId && !availableTemplates.some(item => item.id === templateId) && <option value={templateId}>Selected package template · {templateId}</option>}
          {availableTemplates.map(item => <option key={item.id} value={item.id}>{item.name} · {item.model === 'virtualUsers' ? 'Virtual Users' : 'Arrival Rate'}{item.bundled ? ' · bundled' : ''}</option>)}
        </select><small className="att-help">Selecting a template loads its supported settings into this single-target draft. It never starts a job; validate before running.{templateCatalogTruncated ? ' The package template catalog reached its display limit.' : ''}</small></label>
        <label>Load model<select value={model} onChange={event => selectModel(event.currentTarget.value as LoadModel)} disabled={!inlineLoadEnabled}><option value="virtualUsers">Virtual Users</option><option value="arrivalRate">Arrival Rate</option></select></label>
        <label>Environment<select value={environment} onChange={event => onEnvironmentChange(event.currentTarget.value)}><option value="">Package default</option>{environments.map(item => <option key={item} value={item}>{item}</option>)}</select></label>
        {model === 'virtualUsers' ? <label>Users<input type="number" min="1" step="1" value={users} onChange={event => change(setUsers, event.currentTarget.value)} /></label>
          : <label>Target TPS<input value={tps} onChange={event => change(setTps, event.currentTarget.value)} placeholder="e.g. 5/s" /></label>}
        {model === 'arrivalRate' && <label>Maximum concurrent<input type="number" min="1" step="1" value={maxConcurrent} onChange={event => change(setMaxConcurrent, event.currentTarget.value)} /></label>}
        <label>Duration<input value={duration} onChange={event => change(setDuration, event.currentTarget.value)} placeholder="e.g. 30s or 5m" /></label>
      </div>
      {environmentState === 'loading' && <p role="status">Loading package environments…</p>}{environmentState === 'error' && <p className="att-help" role="alert">Environment profiles are unavailable; the package default remains selectable.{onRetryEnvironments && <> <button className="button button-quiet" type="button" onClick={onRetryEnvironments}>Retry profiles</button></>}</p>}
      {environmentNotice && <p role="status">{environmentNotice}</p>}
      <p className="att-help">{policyMessage}</p>
    </section>
    <section className="att-load-section" aria-labelledby="quick-load-inputs">
      <div className="att-section-heading"><div><h2 id="quick-load-inputs">Inputs and variables</h2><p>Values from the safe debug sidecar stay editable as ATT-authored literals.</p></div>
        <button type="button" onClick={() => { invalidateDraft(); setBusiness(templateBusiness.current); setResetKey(value => value + 1); setPreset('custom'); dirtyRef.current = true; setDirty(true); }}>Reset inputs to sidecar defaults</button></div>
      <TypedSidecarForm value={business} groups={groups} resetKey={resetKey} onChange={next => { invalidateDraft(); setBusiness(next); setBusinessValid(true); setPreset('custom'); dirtyRef.current = true; setDirty(true); }} onValidityChange={setBusinessValid} />
      {sidecarState === 'loading' && <p role="status">Loading safe target defaults…</p>}
      {sidecarState === 'ready' && <p role="status">Target defaults are ready.</p>}
      {sidecarState === 'error' && <div className="att-inline-error" role="alert">
        <p>{sidecarError}</p>
        {sidecarRequestId && <p className="att-help">Request ID: <code>{sidecarRequestId}</code></p>}
        {sidecarDiagnostics.length > 0 && <ul>{sidecarDiagnostics.map((item, index) => <li key={`${item.code || item.path || 'sidecar-diagnostic'}-${index}`}>
          {[item.severity, item.code, item.reason, item.field, item.path].filter(Boolean).join(' · ')}{item.message || item.summary ? `: ${item.message || item.summary}` : ''}
        </li>)}</ul>}
        <button type="button" onClick={() => setSidecarRefresh(value => value + 1)}>Retry target defaults</button>
      </div>}
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
      <div className="att-section-heading"><div><h2 id="quick-load-review" tabIndex={-1}>Validate and run</h2><p>Changing a preset or model never starts a job.</p></div>
        <div className="att-quick-load-actions"><button type="button" onClick={() => void validate()} disabled={!policy || policyState !== 'ready' || !inlineLoadEnabled || draftState === 'validating' || draftState === 'submitting'}>Validate</button>
          <button type="button" className="att-primary-action" onClick={() => void runNow()} disabled={!draft || draftState !== 'valid' || validatedFingerprint !== scenarioFingerprint || confirming}>{confirming ? 'Starting…' : 'Run Now'}</button></div>
      </div>
      <div className={`att-validation-state att-state-${draftState}`} role="status" aria-live="polite" aria-busy={draftState === 'validating' || draftState === 'submitting'}><p>{message}</p>{error && <p className="att-inline-error" role="alert">{error}</p>}
        {policyState === 'loading' && <p>Loading Load policy…</p>}{policyState === 'error' && <p className="att-inline-error">{policyMessage}</p>}
        {requestId && <p className="att-help">Request ID: <code>{requestId}</code></p>}
        {diagnostics.length > 0 && <ul>{diagnostics.map((item, index) => <li key={`${item.code || item.path || 'diagnostic'}-${index}`}>
          {[item.severity, item.code, item.reason, item.field, item.path].filter(Boolean).join(' · ')}{item.message || item.summary ? `: ${item.message || item.summary}` : ''}
          {item.detail && <p>{item.detail}</p>}
          {item.resourceId && <p>Resource: <code>{item.resourceId}</code>{item.action ? ` · ${item.action}` : ''}</p>}
          {item.suggestion && <p>Suggestion: {item.suggestion}</p>}
          {diagnosticControl(item) && <button type="button" className="button button-quiet" onClick={() => reviewDiagnostic(item)}>Review field</button>}
        </li>)}</ul>}
      </div>
      {draft?.previewYaml && <CodeEditor value={draft.previewYaml} language="yaml" label="Server validated Quick Load preview" readOnly />}
      {draft?.expiresAt && <p className="att-help">Validated draft expires {new Date(draft.expiresAt).toLocaleString()}.</p>}
    </section>
    {draftState === 'valid' && draft?.draftId && <p className="att-help">Scheduling creates a recurring job from this validated immutable scenario snapshot.</p>}
  </div>;
}
