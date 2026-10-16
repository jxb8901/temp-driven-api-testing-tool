import './run.css';
import { useEffect, useRef, useState } from 'react';
import { api } from '../../api';
import { useNavigationGuard } from '../../router';
import { CodeEditor } from '../code/CodeEditor';
import type { DraftResponse, JsonObject } from '../explorer/types';
import type { ValidatedLoadDraftIntent } from '../schedules/types';

export interface RunDefinition {
  schemaVersion: 'att-execution/v1'; executionType: 'run'; packageId: string; environment?: string;
  target?: { type: string; id: string }; selection?: JsonObject; input?: JsonObject;
  options: { debug: boolean };
}
export function RunPage({ packageId: initialPackage = '', onAccepted, onSchedule, schedulingEnabled }: {
  packageId?: string; onAccepted(job: { jobId: string; packageId: string }): void;
  onSchedule(intent: ValidatedLoadDraftIntent): void; schedulingEnabled: boolean;
}) {
  const navigation = useNavigationGuard();
  const [packageId, setPackage] = useState(initialPackage);
  const [environment, setEnvironment] = useState('');
  const [targetType, setType] = useState('case');
  const [targetId, setId] = useState('');
  const [businessInput, setInput] = useState('{}');
  const [selection, setSelection] = useState('{"all": true}');
  const [debug, setDebug] = useState(false);
  const [draft, setDraft] = useState<DraftResponse | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [dirty, setDirty] = useState(false);
  const generation = useRef(0);
  const [definition, setDefinition] = useState<RunDefinition | null>(null);
  const releaseNavigationGuard = useRef<() => void>(() => {});
  useEffect(() => {
    const release = navigation.registerUnsavedChanges(dirty, 'Run definition changes');
    releaseNavigationGuard.current = release;
    return release;
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
      const body: RunDefinition = { schemaVersion: 'att-execution/v1', executionType: 'run', packageId: packageId.trim(), options: { debug } };
      if (environment.trim()) body.environment = environment.trim();
      if (targetType === 'batch') body.selection = object(selection);
      else { body.target = { type: targetType, id: targetId.trim() }; if (targetType !== 'case') body.input = object(businessInput); }
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
  return <section className="page-content att-run-page" aria-labelledby="run-heading">
    <h1 id="run-heading">Run</h1>
    <p>Validate a functional execution, review its selection, then run now or schedule the same definition.</p>
    <fieldset className="att-run-definition" disabled={busy}><legend>Execution definition</legend>
      <label>Package ID<input value={packageId} onChange={e => changed(() => setPackage(e.target.value))} /></label>
      <label>Environment<input value={environment} placeholder="Package default" onChange={e => changed(() => setEnvironment(e.target.value))} /></label>
      <label>Target type<select value={targetType} onChange={e => changed(() => setType(e.target.value))}>
        <option value="case">Case</option><option value="template">Template</option><option value="flow">Flow</option><option value="tool">Tool</option><option value="batch">Batch selection</option>
      </select></label>
      {targetType !== 'batch' && <label>Target ID<input value={targetId} onChange={e => changed(() => setId(e.target.value))} /></label>}
      {targetType === 'batch' ? <><p>Use suites, suiteDirectory, caseIds, tags, excludeTags, all or rerunFailed. Combined filters retain the existing CLI selection rules.</p>
        <CodeEditor label="Case selection" readOnly={false} value={selection} language="json" onChange={value => changed(() => setSelection(value))} /></>
        : targetType !== 'case' ? <><p>Use the target's Debug sidecar fields: inputs, vars or Tool arguments. Null values and expressions retain their existing meanings.</p>
          <CodeEditor label="Target input" readOnly={false} value={businessInput} language="json" onChange={value => changed(() => setInput(value))} /></>
        : <p>Case inputs come from the package Excel and sidecar definitions.</p>}
      <label className="att-run-diagnostics"><input type="checkbox" checked={debug} onChange={e => changed(() => setDebug(e.target.checked))} />Debug diagnostics</label>
      <button className="button button-primary" type="button" onClick={() => void validate()}>Validate</button>
    </fieldset>
    {error && <p role="alert">{error}</p>}
    {busy && <p role="status">Processing…</p>}
    {draft && definition && <section className="att-run-review" aria-label="Review RUN"><h2>Review</h2>
      <p>Validation passed. Package changes require validation again.</p>
      <CodeEditor label="Validated RUN preview" language={draft.previewYaml ? 'yaml' : 'json'} value={draft.previewYaml || JSON.stringify(draft.preview || draft, null, 2)} readOnly />
      <button className="button button-primary" type="button" disabled={busy} onClick={() => void submit()}>Run Now</button>
      {schedulingEnabled && <button className="button button-primary" type="button" disabled={busy} onClick={() => onSchedule({ packageId: definition.packageId, environment: definition.environment, draftId: draft.draftId, jobType: 'run', previewYaml: draft.previewYaml, target: definition.target })}>Schedule…</button>}
    </section>}
  </section>;
}
