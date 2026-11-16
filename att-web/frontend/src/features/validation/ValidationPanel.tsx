import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '../../api';
import type { ResourceRoute, ValidationScope } from '../../router';
import { useAppShellContext } from '../../AppShell';
import { diagnosticResourceKey, diagnosticResourceLookups, ValidationReport } from './ValidationReport';
import type { ValidationRecord, ValidationRequest, ValidationTransportState } from './types';
import './validation.css';

type ValidationPanelApi = Pick<typeof api, 'submitValidation' | 'getValidationRecord' | 'cancelValidation' | 'listResources' | 'listValidationRecords'>;
const ACTIVE = new Set(['QUEUED', 'RUNNING']);
type ValidationPanelError = 'lookup' | 'unavailable' | 'refresh' | 'cancel' | 'submit' | 'capacity';

function label(en: string, zh: string, locale: 'en' | 'zh-Hant'): string {
  return locale === 'zh-Hant' ? zh : en;
}

function dateTime(value?: string): string {
  if (!value) return '—';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? '—' : parsed.toLocaleString();
}

function requestForRecord(record: ValidationRecord): ValidationRequest | undefined {
  if (record.all) return { all: true };
  const context = { ...(record.packageId ? { packageId: record.packageId } : {}), ...(record.environment ? { environment: record.environment } : {}) };
  if (record.scope === 'config') return { targetKind: 'config', ...context };
  if (record.scope === 'package' && record.packageId) return { targetKind: 'package', packageId: record.packageId, ...(record.environment ? { environment: record.environment } : {}) };
  if (record.target && ['case', 'suite', 'template', 'flow', 'tool', 'load'].includes(record.scope) && record.packageId) {
    return { targetKind: record.scope as 'case' | 'suite' | 'template' | 'flow' | 'tool' | 'load', packageId: record.packageId,
      ...(record.environment ? { environment: record.environment } : {}), target: record.target };
  }
  return undefined;
}

function lookupForRequest(request: ValidationRequest) {
  if ('all' in request) return undefined;
  return {
    packageId: 'packageId' in request ? request.packageId : undefined,
    scope: request.targetKind,
    target: 'target' in request ? request.target : undefined,
    environment: 'environment' in request ? request.environment : undefined,
    matchContext: true,
    limit: 1,
  };
}

function errorMessage(error: ValidationPanelError, locale: 'en' | 'zh-Hant'): string {
  const messages: Record<ValidationPanelError, [string, string]> = {
    lookup: ['Could not look up a matching validation record.', '無法查詢相符的驗證記錄。'],
    unavailable: ['This validation record is unavailable or has expired.', '此驗證記錄無法使用或已過期。'],
    refresh: ['Could not refresh this validation record.', '無法重新整理此驗證記錄。'],
    cancel: ['The Server could not cancel this validation.', 'Server 無法取消此驗證。'],
    submit: ['The Server could not accept this validation request.', 'Server 無法接受此驗證請求。'],
    capacity: ['Validation capacity is full. Wait briefly and retry.', '驗證容量已滿。請稍候再試。'],
  };
  return label(...messages[error], locale);
}

export function ValidationPanel({ request, validationId, title, submitOnOpen = true, onRecordLoaded,
  onRecordSubmitted, onClose, onOpenResource, onOpenRecord, onOpenOrigin, api: apiOverride = api }: {
  request?: ValidationRequest;
  validationId?: string;
  title?: string;
  submitOnOpen?: boolean;
  onRecordLoaded?(record: ValidationRecord): void;
  onRecordSubmitted?(record: ValidationRecord): void;
  onClose?: () => void;
  onOpenResource?(packageId: string, resource: ResourceRoute, environment?: string): void;
  onOpenRecord?(validationId: string): void;
  onOpenOrigin?(record: ValidationRecord): void;
  api?: ValidationPanelApi;
}) {
  const workspace = useAppShellContext();
  const locale = workspace.uiLocale;
  const [record, setRecord] = useState<ValidationRecord | null>(null);
  const [error, setError] = useState<ValidationPanelError | null>(null);
  const [busy, setBusy] = useState(false);
  const [initializing, setInitializing] = useState(false);
  const [diagnosticRoutes, setDiagnosticRoutes] = useState<Record<string, ResourceRoute>>({});
  const requestKey = JSON.stringify(request || null);
  const contextualPanel = Boolean(onClose);
  const panelRef = useRef<HTMLElement | null>(null);
  const headingRef = useRef<HTMLHeadingElement | null>(null);

  useEffect(() => {
    if (!contextualPanel || !submitOnOpen) return;
    headingRef.current?.focus({ preventScroll: true });
    panelRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' });
  }, [contextualPanel, requestKey, submitOnOpen]);

  const refresh = useCallback(async (signal?: AbortSignal) => {
    if (!record?.validationId) return;
    try {
      const updated = await apiOverride.getValidationRecord(record.validationId, signal);
      if (!signal?.aborted) {
        setRecord(updated);
        setError(null);
      }
    } catch {
      if (!signal?.aborted) setError('refresh');
    }
  }, [apiOverride, record?.validationId]);

  useEffect(() => {
    let alive = true;
    const controller = new AbortController();
    setRecord(null);
    setError(null);
    setInitializing(true);
    const load = async () => {
      try {
        if (validationId) {
          const value = await apiOverride.getValidationRecord(validationId, controller.signal);
          if (alive) { setRecord(value); onRecordLoaded?.(value); }
        } else if (requestKey !== 'null') {
          const request = JSON.parse(requestKey) as ValidationRequest;
          const lookup = lookupForRequest(request);
          if (lookup) {
            const page = await apiOverride.listValidationRecords(lookup, controller.signal);
            const match = page.items[0];
            if (match) {
              const value = await apiOverride.getValidationRecord(match.validationId, controller.signal);
              if (alive) { setRecord(value); onRecordLoaded?.(value); }
              return;
            }
          }
          if (!submitOnOpen) return;
          // Admission is intentionally not tied to this AbortSignal:
          // navigating away must not turn into a server-side cancellation.
          const value = await apiOverride.submitValidation(request);
          if (alive) { setRecord(value); onRecordLoaded?.(value); }
        }
      } catch (reason: unknown) {
        if (alive) {
          const status = typeof reason === 'object' && reason !== null && 'status' in reason
            ? Number((reason as { status?: unknown }).status) : 0;
          setError(validationId ? 'unavailable' : status === 503 ? 'capacity' : 'lookup');
        }
      } finally {
        if (alive) setInitializing(false);
      }
    };
    void load();
    return () => { alive = false; controller.abort(); };
  }, [apiOverride, onRecordLoaded, requestKey, submitOnOpen, validationId]);

  useEffect(() => {
    if (!record?.validationId || !ACTIVE.has(record.status) || document.visibilityState !== 'visible') return;
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void refresh();
    }, 2500);
    return () => window.clearInterval(timer);
  }, [record?.validationId, record?.status, refresh]);

  useEffect(() => {
    const controller = new AbortController();
    setDiagnosticRoutes({});
    if (!record?.report || !record.packageId) return () => controller.abort();
    const identities = new Map<string, ReturnType<typeof diagnosticResourceLookups>[number]>();
    for (const diagnostic of record.report.diagnostics || []) {
      for (const lookup of diagnosticResourceLookups(diagnostic)) {
        identities.set(diagnosticResourceKey(lookup), lookup);
        if (identities.size >= 32) break;
      }
      if (identities.size >= 32) break;
    }
    const resolve = async () => {
      const routes: Record<string, ResourceRoute> = {};
      for (const [key, identity] of identities) {
        if (controller.signal.aborted) return;
        try {
          const page = await apiOverride.listResources(record.packageId!, { type: identity.type, query: identity.value, limit: 100 }, controller.signal);
          const matches = page.items.filter(item => (identity.type === 'all' || item.type === identity.type)
            && (identity.match === 'resourceId' ? item.resourceId === identity.value
              : identity.match === 'logicalIdOrName' ? item.logicalId === identity.value || item.name === identity.value
                : item.sourcePath === identity.value));
          const match = page.total === page.items.length && matches.length === 1 ? matches[0] : undefined;
          if (match) routes[key] = { type: match.type, resourceId: match.resourceId, ...(match.logicalId ? { logicalId: match.logicalId } : {}) };
        } catch { /* Unauthorized or ambiguous diagnostics remain plain text. */ }
      }
      if (!controller.signal.aborted) setDiagnosticRoutes(routes);
    };
    void resolve();
    return () => controller.abort();
  }, [apiOverride, record?.packageId, record?.report]);

  const cancel = async () => {
    if (!record || !ACTIVE.has(record.status)) return;
    setBusy(true);
    setError(null);
    try {
      setRecord(await apiOverride.cancelValidation(record.validationId));
    } catch {
      setError('cancel');
      await refresh();
    } finally {
      setBusy(false);
    }
  };

  const revalidationRequest = request || (record ? requestForRecord(record) : undefined);
  const submitNewValidation = async () => {
    if (!request || busy) return;
    setBusy(true); setError(null);
    try {
      const submitted = await apiOverride.submitValidation(request);
      setRecord(submitted);onRecordLoaded?.(submitted);
    }
    catch { setError('submit'); }
    finally { setBusy(false); }
  };
  const revalidate = async () => {
    if (!revalidationRequest || busy) return;
    setBusy(true); setError(null);
    try {
      const submitted=await apiOverride.submitValidation(revalidationRequest);
      setRecord(submitted);onRecordLoaded?.(submitted);onRecordSubmitted?.(submitted);
    }
    catch { setError('submit'); }
    finally { setBusy(false); }
  };

  const scopeName = record?.all ? label('All packages', '所有套件', locale)
    : record?.scope || (request && 'targetKind' in request ? request.targetKind : 'config');
  const packageName = record?.packageId || (request && 'packageId' in request ? request.packageId : undefined)
    || label('Runtime', 'Runtime', locale);
  const environmentName = record?.environment || (request && 'environment' in request ? request.environment : undefined)
    || label('Package default', '套件預設值', locale);
  const targetName = record?.target || (request && 'target' in request ? request.target : undefined);
  const resultLabel = record?.overallStatus;
  const counts = record?.report?.counts;
  const reportSummary = record?.error || (counts
    ? `${resultLabel || label('Report available', '報告已提供', locale)} · ${counts.errors} ${label('errors', '個錯誤', locale)} · ${counts.warnings} ${label('warnings', '個警告', locale)}`
    : resultLabel || '');
  const status = record?.status;
  const transportState = initializing && !record ? label('Looking for a matching validation…', '正在尋找相符的驗證…', locale)
    : busy && !record ? label('Submitting…', '正在提交…', locale)
      : !record ? label('No matching validation record.', '沒有相符的驗證記錄。', locale)
        : status ? label(status.replace('_', ' ').toLowerCase(), ({
    QUEUED: '排隊中', RUNNING: '執行中', COMPLETED: '已完成', FAILED: '失敗',
    TIMED_OUT: '逾時', CANCELLED: '已取消', INTERRUPTED: '已中斷',
  } as Record<ValidationTransportState, string>)[status], locale) : label('Submitting…', '正在提交…', locale);
  const scope = (record?.scope || (request && 'targetKind' in request ? request.targetKind : 'config')) as ValidationScope;

  return <section ref={panelRef} className={`att-validation-panel att-validation-page${onClose ? ' att-validation-context-panel' : ''}`} aria-labelledby="att-validation-panel-title">
    <header className="att-feature-header">
      <div><p className="att-eyebrow">{label('Read-only validation', '唯讀驗證', locale)}</p>
        <h2 ref={headingRef} id="att-validation-panel-title" tabIndex={contextualPanel ? -1 : undefined}>{title || label('Validation record', '驗證記錄', locale)}</h2>
        <p>{label('This read-only Server check does not run tests or create a Job. Closing or hiding this panel does not cancel it.', '此 Server 唯讀檢查不會執行測試或建立 Job。關閉或隱藏此面板不會取消驗證。', locale)}</p></div>
      {onClose && <button className="button button-quiet" type="button" onClick={onClose}>{label('Close', '關閉', locale)}</button>}
    </header>
    <div className="att-validation-form">
      <div className={`att-validation-status${record?.overallStatus ? ` is-${record.overallStatus.toLowerCase()}` : ''}`} role="status" aria-live="polite">
        <strong>{transportState}</strong>
        {record?.phase && status === 'RUNNING' && <span>{label('Phase', '階段', locale)}: {record.phase}</span>}
        {resultLabel && <span>{label('Result', '結果', locale)}: {resultLabel}</span>}
        {record?.applicability && <span>{label('Source applicability', '來源適用性', locale)}: {record.applicability}</span>}
        {ACTIVE.has(status || '') && <span>{status === 'QUEUED'
          ? label('Waiting for an inspection slot.', '正在等候檢查時段。', locale)
          : label('Running; the current phase is not available.', '正在執行；目前階段無法取得。', locale)}</span>}
      </div>
      {(record || request) && <dl className="att-validation-context-summary">
        <div><dt>{label('Scope', '範圍', locale)}</dt><dd>{scopeName}</dd></div>
        <div><dt>{label('Package', '套件', locale)}</dt><dd>{packageName}</dd></div>
        <div><dt>{label('Environment', '環境', locale)}</dt><dd>{environmentName}</dd></div>
        {targetName && <div><dt>{label('Target', '目標', locale)}</dt><dd>{targetName}</dd></div>}
        {record && <div><dt>{label('Validation ID', '驗證 ID', locale)}</dt><dd>{record.validationId}</dd></div>}
        {reportSummary && <div><dt>{label('Summary', '摘要', locale)}</dt><dd>{reportSummary}</dd></div>}
      </dl>}
      {record && <details className="att-validation-record-details">
        <summary>{label('Record timings', '記錄時間', locale)}</summary>
        <dl className="att-validation-summary">
          <div><dt>{label('Submitted', '提交時間', locale)}</dt><dd>{dateTime(record.submittedAt)}</dd></div>
          <div><dt>{label('Started', '開始時間', locale)}</dt><dd>{dateTime(record.startedAt)}</dd></div>
          <div><dt>{label('Finished', '完成時間', locale)}</dt><dd>{dateTime(record.finishedAt)}</dd></div>
          <div><dt>{label('Queue time', '排隊時間', locale)}</dt><dd>{record.queueElapsedMs == null ? '—' : `${record.queueElapsedMs} ms`}</dd></div>
          <div><dt>{label('Execution time', '執行時間', locale)}</dt><dd>{record.executionElapsedMs == null ? '—' : `${record.executionElapsedMs} ms`}</dd></div>
        </dl>
      </details>}
      {record?.error && <p className="att-validation-error" role="alert">{record.error}</p>}
      {error && <p className="att-validation-error" role="alert">{errorMessage(error, locale)}</p>}
      <div className="att-validation-submit-row">
        <button className="button button-quiet" type="button" disabled={!record || busy} onClick={() => void refresh()}>{label('Refresh', '重新整理', locale)}</button>
        {record && onOpenRecord && <button className="button button-quiet" type="button" onClick={() => onOpenRecord(record.validationId)}>{label('Open full record', '開啟完整記錄', locale)}</button>}
        {record && onOpenOrigin && <button className="button button-quiet" type="button" onClick={() => onOpenOrigin(record)}>{label('Open matching context', '開啟相符情境', locale)}</button>}
        {ACTIVE.has(status || '') && <button className="button button-quiet" type="button" disabled={busy} onClick={() => void cancel()}>{label('Cancel validation', '取消驗證', locale)}</button>}
        {request && !record && <button className="button button-primary" type="button" disabled={busy || initializing} onClick={() => void submitNewValidation()}>{label('Validate', '驗證', locale)}</button>}
        {record && !ACTIVE.has(status || '') && revalidationRequest
          && <button className="button button-primary" type="button" disabled={busy} onClick={() => void revalidate()}>{label('Revalidate', '重新驗證', locale)}</button>}
      </div>
    </div>
    {record?.report && <details className="att-validation-report-details">
      <summary>{label('View full report and diagnostics', '檢視完整報告與診斷', locale)}</summary>
      <ValidationReport report={record.report} scope={scope} packageId={record.packageId}
        environment={record.environment} target={record.target}
        diagnosticResourceRoutes={diagnosticRoutes}
        resourceRoute={record.target && ['case', 'template', 'flow', 'tool'].includes(scope)
          ? { type: scope, resourceId: record.target } : undefined}
        onOpenResource={record.packageId && onOpenResource
          ? resource => onOpenResource(record.packageId!, resource, record.environment) : undefined}
        onRevalidate={() => void revalidate()} locale={locale} />
    </details>}
  </section>;
}
