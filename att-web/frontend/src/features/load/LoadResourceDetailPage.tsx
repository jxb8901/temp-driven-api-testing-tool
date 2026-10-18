import { useEffect, useState } from 'react';
import { ApiError } from '../../types';
import type { LoadCatalogDetail, ServerDiagnostic } from './types';
import { CodeEditor } from '../code/CodeEditor';
import './load.css';

type DetailApi = {
  getLoadCatalogDetail(packageId: string, resourceId: string, signal?: AbortSignal): Promise<LoadCatalogDetail>;
  getLoadSource(packageId: string, resourceId: string, signal?: AbortSignal): Promise<{ available: boolean; text?: string; reason?: string; redacted?: boolean }>;
  validateLoadCatalogScenario(packageId: string, resourceId: string, environment?: string, signal?: AbortSignal): Promise<{ validation: string; model?: string }>;
};

function safeField(value: unknown): string | undefined {
  return typeof value === 'string' && value.length <= 1000 && !/[\u0000-\u001f\u007f]/.test(value) ? value.trim() || undefined : undefined;
}

function safeMessage(value: unknown): string {
  const message = safeField(value) || 'The Load resource request could not be completed.';
  return message
    .replace(/(password|passwd|token|secret|credential)(\s*[:=]\s*)[^\s,}]+/gi, '$1$2[REDACTED]')
    .replace(/(?:[A-Za-z]:)?[/\\][^\s:]+/g, '[package path]');
}

function safeDiagnosticPath(value: unknown): string | undefined {
  const path = safeField(value);
  if (!path || path.startsWith('/') || /^[A-Za-z]:[\\/]/.test(path) || path.startsWith('\\\\')) return undefined;
  return path;
}
function safeOptionalMessage(value: unknown): string | undefined {
  return typeof value === 'string' ? safeMessage(value) : undefined;
}

function safeDiagnostics(value: unknown): ServerDiagnostic[] {
  if (!Array.isArray(value)) return [];
  return value.filter((item): item is Record<string, unknown> => Boolean(item && typeof item === 'object' && !Array.isArray(item)))
    .map(item => ({
      code: safeField(item.code), reason: safeOptionalMessage(item.reason), severity: safeField(item.severity),
      field: safeField(item.field), path: safeDiagnosticPath(item.path), message: safeOptionalMessage(item.message),
      summary: safeOptionalMessage(item.summary), suggestion: safeOptionalMessage(item.suggestion),
      line: typeof item.line === 'number' ? item.line : undefined,
      column: typeof item.column === 'number' ? item.column : undefined,
    }));
}

function StructuredValue({ value, depth = 0 }: { value: unknown; depth?: number }) {
  if (value === null || value === undefined) return <span className="att-load-detail-empty">Not specified</span>;
  if (typeof value !== 'object') return <code>{String(value)}</code>;
  if (Array.isArray(value)) return value.length
    ? <ol className="att-load-detail-list">{value.map((item, index) => <li key={index}><StructuredValue value={item} depth={depth + 1} /></li>)}</ol>
    : <span className="att-load-detail-empty">None</span>;
  const entries = Object.entries(value as Record<string, unknown>);
  if (!entries.length) return <span className="att-load-detail-empty">None</span>;
  return <dl className={`att-load-detail-values depth-${Math.min(depth, 2)}`}>
    {entries.map(([key, item]) => <div key={key}><dt>{key}</dt><dd><StructuredValue value={item} depth={depth + 1} /></dd></div>)}
  </dl>;
}

function DetailSection({ title, value }: { title: string; value: unknown }) {
  if (value === undefined || value === null) return null;
  return <section className="att-load-detail-section"><h2>{title}</h2><StructuredValue value={value} /></section>;
}

export function LoadResourceDetailPage({ api, packageId, resourceId, environment, environments, environmentState = 'ready',
  environmentNotice, inlineLoadEnabled, onEnvironmentChange, onRetryEnvironments, onBack, onQuickLoad }: {
  api: DetailApi;
  packageId: string;
  resourceId: string;
  environment: string;
  environments: string[];
  environmentState?: 'loading' | 'ready' | 'error';
  environmentNotice?: string;
  inlineLoadEnabled: boolean;
  onEnvironmentChange(value: string): void;
  onRetryEnvironments?(): void;
  onBack(): void;
  onQuickLoad(packageId: string, detail: LoadCatalogDetail): void;
}) {
  const [detail, setDetail] = useState<LoadCatalogDetail | null>(null);
  const [detailState, setDetailState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [detailError, setDetailError] = useState('');
  const [sourceState, setSourceState] = useState<'idle' | 'loading' | 'ready' | 'unavailable' | 'error'>('idle');
  const [source, setSource] = useState<{ text?: string; reason?: string; redacted?: boolean }>({});
  const [validationState, setValidationState] = useState<'idle' | 'loading' | 'valid' | 'invalid' | 'error'>('idle');
  const [validationDiagnostics, setValidationDiagnostics] = useState<ServerDiagnostic[]>([]);
  const [validationMessage, setValidationMessage] = useState('Validate this Scenario for the selected Environment to refresh target dependencies.');
  const [requestId, setRequestId] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    setDetailState('loading'); setDetailError(''); setDetail(null);
    api.getLoadCatalogDetail(packageId, resourceId, controller.signal).then(result => {
      if (controller.signal.aborted) return;
      setDetail(result); setDetailState('ready');
    }).catch(error => {
      if (controller.signal.aborted) return;
      setDetailError(safeMessage(error instanceof Error ? error.message : 'This Load resource could not be opened.'));
      setDetailState('error');
    });
    return () => controller.abort();
  }, [api, packageId, resourceId]);

  useEffect(() => {
    setValidationState('idle'); setValidationDiagnostics([]); setRequestId('');
    setValidationMessage(`Validate this Scenario for ${environment || 'Package default'} to refresh target dependencies.`);
  }, [environment, resourceId]);

  async function showYamlSource() {
    setSourceState('loading'); setSource({});
    try {
      const result = await api.getLoadSource(packageId, resourceId);
      setSource({ text: result.text, reason: result.reason, redacted: result.redacted });
      setSourceState(result.available ? 'ready' : 'unavailable');
    } catch (error) {
      setSource({ reason: safeMessage(error instanceof Error ? error.message : 'YAML Source is unavailable under the current visibility policy.') });
      setSourceState('error');
    }
  }

  async function validateScenario() {
    setValidationState('loading'); setValidationDiagnostics([]); setRequestId('');
    setValidationMessage(`Validating for ${environment || 'Package default'}…`);
    try {
      const result = await api.validateLoadCatalogScenario(packageId, resourceId, environment || undefined);
      setValidationState('valid'); setValidationMessage(`Valid for ${environment || 'Package default'}.`);
      if (result.model && detail) setDetail({ ...detail, model: result.model as LoadCatalogDetail['model'] });
    } catch (error) {
      setValidationState('invalid');
      setValidationMessage(safeMessage(error instanceof Error ? error.message : 'Scenario validation failed.'));
      if (error instanceof ApiError) {
        setRequestId(error.requestId || '');
        setValidationDiagnostics(safeDiagnostics(error.diagnostics));
      }
    }
  }

  const definition = detail?.definition || {};
  const workloads = Array.isArray(definition.workloads) ? definition.workloads as Array<Record<string, unknown>> : [];
  const diagnosticList = detail?.diagnostics || [];

  return <main className="page-content att-load-resource-detail">
    <header className="att-feature-header att-load-detail-header">
      <div><p className="att-eyebrow">{packageId} · {detail?.kind === 'template' ? 'Load Template' : detail?.kind === 'scenario' ? 'Executable Scenario' : 'Load resource'}</p>
        <h1>{detail?.path || resourceId}</h1>
        <p>Read-only structured Load resource details. Business values follow the Server redaction policy.</p></div>
      <div className="att-load-detail-header-actions">
        {detail?.kind === 'template' && <button className="button button-primary" type="button" disabled={!inlineLoadEnabled || !detail.model}
          title={!inlineLoadEnabled ? 'Quick LOAD composition is disabled by Server policy.' : undefined}
          onClick={() => onQuickLoad(packageId, detail)}>Create Quick LOAD</button>}
        <button className="button button-quiet" type="button" onClick={onBack}>Back to LOAD Catalog</button>
      </div>
    </header>
    {detailState === 'loading' && <p role="status" aria-busy="true">Loading Load resource details…</p>}
    {detailState === 'error' && <p className="att-inline-error" role="alert">{detailError}</p>}
    {detailState === 'ready' && detail && <>
      <section className="att-load-detail-meta" aria-label="Resource metadata">
        <dl><div><dt>Kind</dt><dd>{detail.kind}</dd></div><div><dt>Package</dt><dd>{packageId}</dd></div>
          <div><dt>Path</dt><dd><code>{detail.path}</code></dd></div><div><dt>Model</dt><dd>{detail.model || 'Unavailable'}</dd></div>
          <div><dt>Schema version</dt><dd>{detail.schemaVersion || 'Unavailable'}</dd></div>
          <div><dt>Validation</dt><dd>{detail.validation}{detail.kind === 'scenario' && validationState === 'valid' ? ` · ${environment || 'Package default'}` : ''}</dd></div>
        </dl>
      </section>
      {detail.kind === 'invalid' && <section className="att-load-detail-section" aria-labelledby="load-detail-validation">
        <h2 id="load-detail-validation">Validation &amp; Diagnostics</h2>
        <p className="att-inline-error" role="alert">{detail.diagnostic || 'Load YAML is invalid or unavailable.'}</p>
        <DiagnosticList items={diagnosticList} />
      </section>}
      {detail.kind === 'scenario' && <section className="att-load-detail-section" aria-labelledby="load-detail-validation">
        <div className="att-section-heading"><div><h2 id="load-detail-validation">Validation &amp; Dependencies</h2><p>Validation is specific to the selected Environment.</p></div>
          <label>Environment<select aria-label="Load detail Environment" value={environment} disabled={environmentState === 'loading'} onChange={event => onEnvironmentChange(event.currentTarget.value)}>
            <option value="">Package default</option>{environments.map(name => <option key={name} value={name}>{name}</option>)}
          </select></label>
        </div>
        {environmentState === 'loading' && <p role="status" aria-busy="true">Loading declared Environment profiles…</p>}
        {environmentState === 'error' && <p className="att-inline-error" role="alert">Environment profiles could not be loaded. Package default remains available. {onRetryEnvironments && <button className="button button-quiet" type="button" onClick={onRetryEnvironments}>Retry profiles</button>}</p>}
        {environmentNotice && <p role="status">{environmentNotice}</p>}
        <p role={validationState === 'invalid' ? 'alert' : 'status'}>{validationMessage}</p>
        {validationState === 'invalid' && <DiagnosticList items={validationDiagnostics} />}
        {requestId && <p className="att-help">Request ID: <code>{requestId}</code></p>}
        <button className="button button-quiet" type="button" aria-label={`Validate for ${environment || 'Package default'}`}
          disabled={validationState === 'loading' || (environmentState !== 'ready' && Boolean(environment))} onClick={() => void validateScenario()}>
          {validationState === 'loading' ? 'Validating…' : 'Validate'}
        </button>
      </section>}
      {detail.kind === 'scenario' && <>
        <DetailSection title="Load controls" value={definition.load} />
        <DetailSection title="Execution settings" value={definition.execution} />
        <section className="att-load-detail-section"><h2>Workloads &amp; Targets</h2>
          {workloads.length ? workloads.map((workload, index) => <article className="att-load-detail-workload" key={String(workload.id || index)}>
            <h3>{String(workload.id || `Workload ${index + 1}`)}</h3>
            <DetailSection title="Target" value={workload.target} />
            <DetailSection title="Load" value={workload.load} />
            <DetailSection title="Inputs and variables" value={{ inputs: workload.inputs, vars: workload.vars }} />
            <DetailSection title="Execution and evidence" value={{ execution: workload.execution, thresholds: workload.thresholds, evidence: workload.evidence }} />
            <DetailSection title="Mix" value={workload.mix} />
          </article>) : <p className="att-load-detail-empty">No workload definition is available.</p>}
        </section>
        <DetailSection title="Scenario thresholds" value={definition.thresholds} />
        <DetailSection title="Evidence" value={definition.evidence} />
      </>}
      {detail.kind === 'template' && <>
        <DetailSection title="Policy defaults" value={definition.load} />
        <DetailSection title="Execution defaults" value={definition.execution} />
        <p className="att-help">This policy template does not contain runnable workloads. Create Quick LOAD to compose one.</p>
      </>}
      {detail.redacted && <p className="att-help">Some business values are protected by the Server and shown as keep-default markers.</p>}
      <section className="att-load-detail-section" aria-labelledby="load-detail-source">
        <div className="att-section-heading"><div><h2 id="load-detail-source">YAML Source</h2><p>Source access follows package visibility policy and is fetched only when requested.</p></div>
          <button className="button button-quiet" type="button" disabled={sourceState === 'loading'} onClick={() => void showYamlSource()}>
            {sourceState === 'loading' ? 'Loading Source…' : sourceState === 'idle' ? 'Show YAML Source' : 'Refresh YAML Source'}
          </button>
        </div>
        {sourceState === 'unavailable' && <p role="status">YAML Source is unavailable: {source.reason || 'visibility policy'}.</p>}
        {sourceState === 'error' && <p role="alert" className="att-inline-error">{source.reason}</p>}
        {sourceState === 'ready' && <>{source.redacted && <p role="status">Sensitive values are redacted in this source view.</p>}
          <CodeEditor value={source.text || ''} language="yaml" label="Load YAML Source" readOnly /></>}
      </section>
    </>}
  </main>;
}

function DiagnosticList({ items }: { items: ServerDiagnostic[] }) {
  if (!items.length) return null;
  return <ul className="att-load-detail-diagnostics">{items.map((item, index) => <li key={`${item.code || item.field || 'diagnostic'}-${index}`}>
    <strong>{[item.severity, item.code].filter(Boolean).join(' · ') || 'Diagnostic'}</strong>
    {item.summary || item.message ? <span>{item.summary || item.message}</span> : null}
    {item.field || item.path ? <span>Field: {item.field || item.path}</span> : null}
    {item.line ? <span>Line {item.line}{item.column ? `, column ${item.column}` : ''}</span> : null}
    {item.suggestion ? <span>Suggestion: {item.suggestion}</span> : null}
  </li>)}</ul>;
}
