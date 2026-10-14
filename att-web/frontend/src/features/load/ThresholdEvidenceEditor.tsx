import { useEffect, useMemo, useRef, useState } from 'react';
import { CodeEditor } from '../code/CodeEditor';
import type { LoadModel } from './types';
import './load.css';

type JsonObject = Record<string, unknown>;
type Scope = 'aggregate' | 'workload';
interface WorkloadThreshold { id: string; thresholdsJson: string }
interface Props {
  model: LoadModel;
  aggregate: string;
  workloads: WorkloadThreshold[];
  evidence: string;
  onAggregateChange(value: string): void;
  onWorkloadChange(id: string, value: string): void;
  onEvidenceChange(value: string): void;
  onValidityChange(key: string, message: string): void;
}
const commonMetrics = [
  { key: 'errorRate', label: 'Error rate', units: ['%'], unit: '%' },
  { key: 'p95', label: 'P95 latency', units: ['ms'], unit: 'ms' },
  { key: 'p99', label: 'P99 latency', units: ['ms'], unit: 'ms' },
  { key: 'minThroughput', label: 'Minimum throughput', units: ['/s', '/m'], unit: '/s' },
];
const arrivalMetrics = [
  { key: 'droppedRate', label: 'Dropped rate', units: ['%'], unit: '%' },
  { key: 'achievedArrivalRate', label: 'Achieved arrival rate', units: ['%', '/s', '/m'], unit: '%' },
];
const operators = ['<', '<=', '>', '>=', '=='];
function parseObject(raw: string): JsonObject | null {
  try { const value: unknown = JSON.parse(raw || '{}'); return value && typeof value === 'object' && !Array.isArray(value) ? value as JsonObject : null; }
  catch { return null; }
}
function parseExpression(value: unknown): { operator: string; amount: string; unit: string } | null {
  const match = /^(<=|>=|==|<|>)\s*([0-9]+(?:\.[0-9]+)?)(%|ms|\/s|\/m)$/.exec(String(value ?? '').trim());
  return match ? { operator: match[1], amount: match[2], unit: match[3] } : null;
}
function defaultThreshold(key: string, unit: string): string {
  const defaults: Record<string, string> = {
    errorRate: '<= 1%',
    p95: '<= 500ms',
    p99: '<= 1000ms',
    minThroughput: '>= 1/s',
    droppedRate: '<= 0%',
    achievedArrivalRate: '>= 95%',
  };
  return defaults[key] || '<= 1' + unit;
}
function describeUnknown(object: JsonObject, supported: Set<string>): string[] { return Object.keys(object).filter(key => !supported.has(key)); }
function thresholdError(object: JsonObject, metrics: Array<{ key: string; label: string; units: string[]; unit: string }>, model: LoadModel): string {
  for (const [key, value] of Object.entries(object)) {
    if (model !== 'arrivalRate' && (key === 'droppedRate' || key === 'achievedArrivalRate'))
      return key + ' thresholds are only supported for Arrival Rate workloads.';
    const metric = metrics.find(item => item.key === key);
    if (!metric) continue;
    const parsed = parseExpression(value);
    if (!parsed) return metric.label + ' must use an operator, number and supported unit.';
    if (!metric.units.includes(parsed.unit)) return metric.label + ' must use ' + metric.units.join(' or ') + '.';
    const amount = Number(parsed.amount);
    if (!Number.isFinite(amount) || amount < 0 || parsed.unit === '%' && amount > 100)
      return metric.label + ' value must be a non-negative number' + (parsed.unit === '%' ? ' up to 100%.' : '.');
    if (key === 'minThroughput' && amount <= 0) return 'Minimum throughput must be greater than zero.';
  }
  return '';
}
function evidenceError(object: JsonObject): string {
  const allowed = {
    mode: ['metrics', 'failures', 'samples', 'all'],
    success: ['none', 'sample', 'full'],
    failure: ['none', 'full'],
  } as Record<string, string[]>;
  for (const [field, choices] of Object.entries(allowed))
    if (object[field] != null && !choices.includes(String(object[field]))) return 'Evidence ' + field + ' must be one of ' + choices.join(', ') + '.';
  if (object.maxSamples != null && (!Number.isInteger(Number(object.maxSamples)) || Number(object.maxSamples) < 0))
    return 'Maximum retained samples must be a non-negative integer.';
  if (object.sampleRate != null && (!Number.isFinite(Number(object.sampleRate)) || Number(object.sampleRate) < 0 || Number(object.sampleRate) > 1))
    return 'Success sample rate must be between 0 and 1.';
  const resources = parseObject(JSON.stringify(object.resources ?? {})) || {};
  if (resources.output != null && !['inherit', 'none'].includes(String(resources.output)))
    return 'Resource output must be inherit or none.';
  return '';
}


function ThresholdRow({ metric, expression, disabled, onChange, onRemove }: {
  metric: (typeof commonMetrics)[number] | (typeof arrivalMetrics)[number];
  expression: unknown;
  disabled: boolean;
  onChange(value: string): void;
  onRemove(): void;
}) {
  const parsed = parseExpression(expression);
  const [operator, setOperator] = useState(parsed?.operator || '<');
  const [amount, setAmount] = useState(parsed?.amount || '');
  const [unit, setUnit] = useState(parsed?.unit || metric.unit);
  useEffect(() => { const next = parseExpression(expression); setOperator(next?.operator || '<'); setAmount(next?.amount || ''); setUnit(next?.unit || metric.unit); }, [expression, metric.unit]);
  const update = (next: { operator?: string; amount?: string; unit?: string }) => {
    const op = next.operator ?? operator; const value = next.amount ?? amount; const suffix = next.unit ?? unit;
    if (next.operator !== undefined) setOperator(next.operator);
    if (next.amount !== undefined) setAmount(next.amount);
    if (next.unit !== undefined) setUnit(next.unit);
    onChange(`${op} ${value}${suffix}`);
  };
  return <div className="att-threshold-row">
    <strong>{metric.label}</strong>
    <label>Condition<select disabled={disabled} value={operator} onChange={event => update({ operator: event.currentTarget.value })}>{operators.map(item => <option key={item} value={item}>{item}</option>)}</select></label>
    <label>Target value<input disabled={disabled} type="number" min="0" step="any" value={amount} onChange={event => update({ amount: event.currentTarget.value })} aria-invalid={!parsed && Boolean(expression)} /></label>
    <label>Unit<select disabled={disabled} value={unit} onChange={event => update({ unit: event.currentTarget.value })}>{metric.units.map(item => <option key={item} value={item}>{item}</option>)}</select></label>
    <button type="button" disabled={disabled} onClick={onRemove}>Remove</button>
  </div>;
}

function ThresholdScopeEditor({ scope, raw, model, onChange, onValidityChange }: {
  scope: string; raw: string; model: LoadModel; onChange(value: string): void; onValidityChange(message: string): void;
}) {
  const [advancedDraft, setAdvancedDraft] = useState(raw);
  const object = useMemo(() => parseObject(raw) || {}, [raw]);
  const metrics = useMemo(() => model === 'arrivalRate' ? [...commonMetrics, ...arrivalMetrics] : commonMetrics, [model]);
  const supported = useMemo(() => new Set(metrics.map(item => item.key)), [metrics]);
  const rows = Object.keys(object).filter(key => supported.has(key));
  const unknown = describeUnknown(object, supported);
  const advancedObject = useMemo(() => parseObject(advancedDraft || '{}'), [advancedDraft]);
  const validationMessage = advancedObject ? thresholdError(advancedObject, metrics, model) : scope + ' thresholds must be a JSON object.';
  const validityRef = useRef(onValidityChange);
  useEffect(() => { validityRef.current = onValidityChange; }, [onValidityChange]);
  useEffect(() => { validityRef.current(validationMessage); }, [validationMessage]);
  useEffect(() => { setAdvancedDraft(raw); }, [raw]);
  const setMetric = (key: string, value: string | undefined) => {
    const next = { ...object };
    if (value === undefined) delete next[key]; else next[key] = value;
    const serialized = JSON.stringify(next, null, 2); onChange(serialized); setAdvancedDraft(serialized); onValidityChange('');
  };
  function advancedChange(text: string) {
    setAdvancedDraft(text);
    const parsed = parseObject(text || '{}');
    if (!parsed) { onValidityChange(`${scope} thresholds must be a JSON object.`); return; }
    const error = thresholdError(parsed, metrics, model);
    if (error) { onValidityChange(error); return; }
    onValidityChange(''); onChange(JSON.stringify(parsed, null, 2));
  }
  const nextMetric = metrics.find(metric => !Object.hasOwn(object, metric.key));
  return <section className="att-threshold-scope" aria-label={`${scope} thresholds`}>
    <div className="att-threshold-heading"><div><h3>{scope === 'aggregate' ? 'Aggregate thresholds' : 'Workload thresholds'}</h3><p>ATT accepts only documented metric/operator/unit combinations. Server validation remains authoritative.</p></div>
      <button type="button" onClick={() => nextMetric && setMetric(nextMetric.key, defaultThreshold(nextMetric.key, nextMetric.unit))} disabled={!nextMetric || Boolean(validationMessage)}>Add threshold</button>
    </div>
    {rows.map(key => {
      const metric = metrics.find(item => item.key === key)!;
      return <ThresholdRow key={key} metric={metric} expression={object[key]} disabled={Boolean(validationMessage)} onChange={value => setMetric(key, value)} onRemove={() => setMetric(key, undefined)} />;
    })}
    {!rows.length && <p className="att-threshold-empty">No {scope} thresholds. Empty <code>{'{}'}</code> means no thresholds.</p>}
    {unknown.length > 0 && <p className="att-help">Unrepresented keys stay in Advanced JSON: {unknown.join(', ')}.</p>}
    <details className="att-advanced-settings"><summary>Advanced JSON</summary>
      <label>Complete {scope.toLowerCase()} threshold object</label>
      <CodeEditor value={advancedDraft} language="json" label={`${scope} thresholds advanced JSON`} readOnly={false} onChange={advancedChange} />
    </details>
  </section>;
}

function EvidenceEditor({ raw, onChange, onValidityChange }: { raw: string; onChange(value: string): void; onValidityChange(message: string): void }) {
  const [advancedDraft, setAdvancedDraft] = useState(raw);
  const object = useMemo(() => parseObject(raw) || {}, [raw]);
  const advancedObject = useMemo(() => parseObject(advancedDraft || '{}'), [advancedDraft]);
  const validationMessage = advancedObject ? evidenceError(advancedObject) : 'Evidence settings must be a JSON object.';
  const validityRef = useRef(onValidityChange);
  useEffect(() => { validityRef.current = onValidityChange; }, [onValidityChange]);
  useEffect(() => { validityRef.current(validationMessage); }, [validationMessage]);
  const mode = String(object.mode ?? '');
  const success = String(object.success ?? '');
  const failure = String(object.failure ?? '');
  const resources = parseObject(JSON.stringify(object.resources ?? {})) || {};
  useEffect(() => { setAdvancedDraft(raw); }, [raw]);
  function update(key: string, value: unknown, nested = false) {
    const next = { ...object };
    if (nested) {
      const current = { ...resources };
      if (value === '') delete current[key]; else current[key] = value;
      if (Object.keys(current).length) next.resources = current; else delete next.resources;
    } else if (value === '') delete next[key]; else next[key] = value;
    const serialized = JSON.stringify(next, null, 2); onChange(serialized); setAdvancedDraft(serialized); onValidityChange('');
  }
  function advancedChange(text: string) {
    setAdvancedDraft(text);
    const parsed = parseObject(text || '{}');
    if (!parsed) { onValidityChange('Evidence settings must be a JSON object.'); return; }
    const error = evidenceError(parsed);
    if (error) { onValidityChange(error); return; }
    onValidityChange(''); onChange(JSON.stringify(parsed, null, 2));
  }
  const samplingActive = mode === 'samples' || success === 'sample';
  const fullCapture = mode === 'all' || success === 'full';
  return <section className="att-evidence-form" aria-label="Evidence settings">
    <fieldset className="att-evidence-controls" disabled={Boolean(validationMessage)}>
    <legend><h3>Evidence capture</h3></legend>
    <div className="att-load-grid">
      <label>Capture mode<select value={mode} onChange={event => update('mode', event.currentTarget.value)}>
        <option value="">Server default (failures)</option><option value="metrics">Metrics only</option><option value="failures">Failures</option><option value="samples">Sample successes and failures</option><option value="all">All iterations</option>
      </select></label>
      <label>Successful iterations<select value={success} onChange={event => update('success', event.currentTarget.value)}>
        <option value="">Use mode default</option><option value="none">None</option><option value="sample">Sample</option><option value="full">Full</option>
      </select></label>
      <label>Failed iterations<select value={failure} onChange={event => update('failure', event.currentTarget.value)}>
        <option value="">Use mode default</option><option value="none">None</option><option value="full">Full</option>
      </select></label>
      <label>Maximum retained samples<input type="number" min="0" step="1" value={object.maxSamples == null ? '' : String(object.maxSamples)} placeholder="Server default (1,000)" onChange={event => update('maxSamples', event.currentTarget.value === '' ? '' : Number(event.currentTarget.value))} /></label>
      {samplingActive && <label>Success sample rate<input type="number" min="0" max="1" step="0.01" value={object.sampleRate == null ? '' : String(object.sampleRate)} placeholder="Server default (0.01)" onChange={event => update('sampleRate', event.currentTarget.value === '' ? '' : Number(event.currentTarget.value))} /></label>}
      <label>Resource output<select value={String(resources.output ?? '')} onChange={event => update('output', event.currentTarget.value, true)}>
        <option value="">Inherit default</option><option value="inherit">Include when evidence is retained</option><option value="none">Skip resource-output snapshots</option>
      </select></label>
    </div>
    {fullCapture && <p className="att-evidence-impact" role="note">Full capture can increase disk use and report size. Set Maximum retained samples to a finite limit for bounded storage.</p>}
    {samplingActive && <p className="att-help">Sampling retains selected successful iterations; failures follow the selected failure policy. Dropped arrivals do not create iteration evidence.</p>}
    </fieldset>
    {validationMessage && <p className="att-inline-error" role="alert">{validationMessage} Fix the Advanced JSON to re-enable the form.</p>}
    <details className="att-advanced-settings"><summary>Advanced JSON</summary>
      <label>Complete evidence settings object</label>
      <CodeEditor value={advancedDraft} language="json" label="Evidence settings advanced JSON" readOnly={false} onChange={advancedChange} />
    </details>
  </section>;
}

export function ThresholdEvidenceEditor({ model, aggregate, workloads, evidence, onAggregateChange, onWorkloadChange, onEvidenceChange, onValidityChange }: Props) {
  const [scope, setScope] = useState<Scope>('aggregate');
  const [workloadId, setWorkloadId] = useState(workloads[0]?.id || '');
  const [editorErrors, setEditorErrors] = useState<Record<string, string>>({});
  const hasInvalidDraft = Object.values(editorErrors).some(Boolean);
  const reportValidity = (key: string, message: string) => {
    setEditorErrors(current => { const next = { ...current }; if (message) next[key] = message; else delete next[key]; return next; });
    onValidityChange(key, message);
  };
  useEffect(() => { if (!workloads.some(item => item.id === workloadId)) setWorkloadId(workloads[0]?.id || ''); }, [workloads, workloadId]);
  const selectedWorkload = workloads.find(item => item.id === workloadId);
  return <div className="att-threshold-evidence-editor">
    <div className="att-threshold-scope-picker"><label>Threshold scope<select value={scope} disabled={hasInvalidDraft} onChange={event => setScope(event.currentTarget.value as Scope)}>
      <option value="aggregate">Aggregate run</option><option value="workload">Per-workload</option>
    </select></label>
    {scope === 'workload' && <label>Workload<select value={workloadId} disabled={hasInvalidDraft} onChange={event => setWorkloadId(event.currentTarget.value)}>
      {workloads.map(item => <option key={item.id} value={item.id}>{item.id}</option>)}
    </select></label>}</div>
    {scope === 'aggregate' ? <ThresholdScopeEditor key="aggregate" scope="aggregate" raw={aggregate} model={model} onChange={onAggregateChange} onValidityChange={message => reportValidity('threshold-aggregate', message)} />
      : selectedWorkload ? <ThresholdScopeEditor key={selectedWorkload.id} scope={`Workload ${selectedWorkload.id}`} raw={selectedWorkload.thresholdsJson} model={model}
        onChange={value => onWorkloadChange(selectedWorkload.id, value)} onValidityChange={message => reportValidity(`threshold-workload-${selectedWorkload.id}`, message)} />
        : <p className="att-threshold-empty">Add a workload before configuring per-workload thresholds.</p>}
    <EvidenceEditor raw={evidence} onChange={onEvidenceChange} onValidityChange={message => reportValidity('evidence', message)} />
  </div>;
}
