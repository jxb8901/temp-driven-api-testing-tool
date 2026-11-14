import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api';
import type { ResourceRoute, ValidationScope } from '../../router';
import { useAppShellContext } from '../../AppShell';
import { diagnosticResourceKey, diagnosticResourceLookups, ValidationReport } from './ValidationReport';
import type { ValidationRecord, ValidationRequest, ValidationTransportState } from './types';
import './validation.css';

type ValidationPanelApi = Pick<typeof api, 'submitValidation' | 'getValidationRecord' | 'cancelValidation' | 'listResources'>;
const ACTIVE = new Set(['QUEUED', 'RUNNING']);

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

export function ValidationPanel({ request, validationId, title, onClose, onOpenResource, onOpenRecord, api: apiOverride = api }: {
  request?: ValidationRequest;
  validationId?: string;
  title?: string;
  onClose?: () => void;
  onOpenResource?(packageId: string, resource: ResourceRoute, environment?: string): void;
  onOpenRecord?(validationId: string): void;
  api?: ValidationPanelApi;
}) {
  const workspace = useAppShellContext();
  const locale = workspace.uiLocale;
  const [record, setRecord] = useState<ValidationRecord | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [diagnosticRoutes, setDiagnosticRoutes] = useState<Record<string, ResourceRoute>>({});
  const requestKey = JSON.stringify(request || null);

  const refresh = useCallback(async (signal?: AbortSignal) => {
    if (!record?.validationId) return;
    try {
      const updated = await apiOverride.getValidationRecord(record.validationId, signal);
      if (!signal?.aborted) {
        setRecord(updated);
        setError('');
      }
    } catch {
      if (!signal?.aborted) setError(label('Could not refresh this validation record.', '無法重新整理此驗證記錄。', locale));
    }
  }, [apiOverride, locale, record?.validationId]);

  useEffect(() => {
    let alive = true;
    const controller = new AbortController();
    setRecord(null);
    setError('');
    if (validationId) {
      apiOverride.getValidationRecord(validationId, controller.signal).then(value => {
        if (alive) setRecord(value);
      }).catch(() => {
        if (alive) setError(label('This validation record is unavailable or has expired.', '此驗證記錄無法使用或已過期。', locale));
      });
    } else if (requestKey !== 'null') {
      // Admission is intentionally not tied to this component's AbortSignal:
      // navigating away must not turn into a server-side cancellation.
      apiOverride.submitValidation(JSON.parse(requestKey) as ValidationRequest).then(value => {
        if (alive) setRecord(value);
      }).catch((reason: unknown) => {
        if (alive) {
          const status = typeof reason === 'object' && reason !== null && 'status' in reason
            ? Number((reason as { status?: unknown }).status) : 0;
          setError(status === 503
            ? label('Validation capacity is full. Wait briefly and retry.', '驗證容量已滿。請稍候再試。', locale)
            : label('The Server could not accept this validation request.', 'Server 無法接受此驗證請求。', locale));
        }
      });
    }
    return () => { alive = false; controller.abort(); };
  }, [apiOverride, locale, requestKey, validationId]);

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
    setError('');
    try {
      setRecord(await apiOverride.cancelValidation(record.validationId));
    } catch {
      setError(label('The Server could not cancel this validation.', 'Server 無法取消此驗證。', locale));
      await refresh();
    } finally {
      setBusy(false);
    }
  };

  const revalidationRequest = request || (record ? requestForRecord(record) : undefined);
  const revalidate = async () => {
    if (!revalidationRequest || busy) return;
    setBusy(true); setError('');
    try { setRecord(await apiOverride.submitValidation(revalidationRequest)); }
    catch { setError(label('The Server could not accept a new validation.', 'Server 無法接受新的驗證。', locale)); }
    finally { setBusy(false); }
  };

  const status = record?.status;
  const transportState = status ? label(status.replace('_', ' ').toLowerCase(), ({
    QUEUED: '排隊中', RUNNING: '執行中', COMPLETED: '已完成', FAILED: '失敗',
    TIMED_OUT: '逾時', CANCELLED: '已取消', INTERRUPTED: '已中斷',
  } as Record<ValidationTransportState, string>)[status], locale) : label('Submitting…', '正在提交…', locale);
  const scope = (record?.scope || (request && 'targetKind' in request ? request.targetKind : 'config')) as ValidationScope;

  return <section className="att-validation-panel att-validation-page" aria-labelledby="att-validation-panel-title">
    <header className="att-feature-header">
      <div><p className="att-eyebrow">{label('Read-only contextual validation', '唯讀情境驗證', locale)}</p>
        <h2 id="att-validation-panel-title">{title || label('Validation record', '驗證記錄', locale)}</h2>
        <p>{label('This is a durable Server validation record. Closing this view does not cancel it.', '此為 Server 持久驗證記錄。關閉此檢視不會取消驗證。', locale)}</p></div>
      {onClose && <button className="button button-quiet" type="button" onClick={onClose}>{label('Close', '關閉', locale)}</button>}
    </header>
    <div className="att-validation-form">
      <div className={`att-validation-status${record?.overallStatus ? ` is-${record.overallStatus.toLowerCase()}` : ''}`} role="status" aria-live="polite">
        <strong>{transportState}</strong>
        {record?.phase && status === 'RUNNING' && <span>{label('Phase', '階段', locale)}: {record.phase}</span>}
        {record?.overallStatus && <span>{label('Report', '報告', locale)}: {record.overallStatus}</span>}
        {record?.applicability && <span>{label('Source applicability', '來源適用性', locale)}: {record.applicability}</span>}
        {ACTIVE.has(status || '') && <span>{status === 'QUEUED'
          ? label('Waiting for an inspection slot.', '正在等候檢查時段。', locale)
          : label('Running; the current phase is not available.', '正在執行；目前階段無法取得。', locale)}</span>}
      </div>
      {record && <dl className="att-validation-summary">
        <div><dt>{label('Validation ID', '驗證 ID', locale)}</dt><dd>{record.validationId}</dd></div>
        <div><dt>{label('Submitted', '提交時間', locale)}</dt><dd>{dateTime(record.submittedAt)}</dd></div>
        <div><dt>{label('Started', '開始時間', locale)}</dt><dd>{dateTime(record.startedAt)}</dd></div>
        <div><dt>{label('Finished', '完成時間', locale)}</dt><dd>{dateTime(record.finishedAt)}</dd></div>
        <div><dt>{label('Queue time', '排隊時間', locale)}</dt><dd>{record.queueElapsedMs == null ? '—' : `${record.queueElapsedMs} ms`}</dd></div>
        <div><dt>{label('Execution time', '執行時間', locale)}</dt><dd>{record.executionElapsedMs == null ? '—' : `${record.executionElapsedMs} ms`}</dd></div>
      </dl>}
      {record?.error && <p className="att-validation-error" role="alert">{record.error}</p>}
      {error && <p className="att-validation-error" role="alert">{error}</p>}
      <div className="att-validation-submit-row">
        <button className="button button-quiet" type="button" disabled={!record || busy} onClick={() => void refresh()}>{label('Refresh', '重新整理', locale)}</button>
        {record && onOpenRecord && <button className="button button-quiet" type="button" onClick={() => onOpenRecord(record.validationId)}>{label('Open full record', '開啟完整記錄', locale)}</button>}
        {ACTIVE.has(status || '') && <button className="button button-quiet" type="button" disabled={busy} onClick={() => void cancel()}>{label('Cancel validation', '取消驗證', locale)}</button>}
      </div>
    </div>
    {record?.report && <ValidationReport report={record.report} scope={scope} packageId={record.packageId}
      environment={record.environment} target={record.target}
      diagnosticResourceRoutes={diagnosticRoutes}
      resourceRoute={record.target && ['case', 'template', 'flow', 'tool'].includes(scope)
        ? { type: scope, resourceId: record.target } : undefined}
      onOpenResource={record.packageId && onOpenResource
        ? resource => onOpenResource(record.packageId!, resource, record.environment) : undefined}
      onRevalidate={() => void revalidate()} locale={locale} />}
  </section>;
}
