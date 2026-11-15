import { useCallback, useEffect, useState } from 'react';
import { api } from '../../api';
import { useAppShellContext } from '../../AppShell';
import type { ValidationRecord } from './types';
import './validation.css';

const ACTIVE = new Set(['QUEUED', 'RUNNING']);
const SCOPES = ['config', 'package', 'case', 'suite', 'template', 'flow', 'tool', 'load'];
const STATUSES = ['QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'INTERRUPTED'];
const PAGE_SIZE = 50;

function label(en: string, zh: string, locale: 'en' | 'zh-Hant') { return locale === 'zh-Hant' ? zh : en; }
function scopeLabel(scope: string, locale: 'en' | 'zh-Hant') {
  const labels: Record<string, [string, string]> = {
    config: ['Config', '設定'], package: ['Package', '套件'], case: ['Case', '案例'], suite: ['Suite', '套件集'],
    template: ['Template', '範本'], flow: ['Flow', '流程'], tool: ['Tool', '工具'], load: ['LOAD Scenario', 'LOAD 情境'],
  };
  const values = labels[scope];
  return values ? label(values[0], values[1], locale) : scope;
}
function dateTime(value?: string) {
  if (!value || !Number.isFinite(Date.parse(value))) return '—';
  return new Date(value).toLocaleString();
}
function targetLabel(record: ValidationRecord, locale: 'en' | 'zh-Hant') {
  if (record.all) return label('All packages', '所有套件', locale);
  if (record.scope === 'config') return record.packageId
    ? `${record.packageId} · ${label('Effective Config', '生效設定', locale)}`
    : label('Shared Runtime Config', '共用 Runtime 設定', locale);
  const scope = record.scope === 'case' ? label('Case', '案例', locale)
    : record.scope === 'suite' ? label('Suite', '套件集', locale)
      : record.scope === 'template' ? label('Template', '範本', locale)
        : record.scope === 'flow' ? label('Flow', '流程', locale)
          : record.scope === 'tool' ? label('Tool', '工具', locale)
            : record.scope === 'load' ? label('LOAD Scenario', 'LOAD 情境', locale) : label('Package', '套件', locale);
  return record.target ? `${scope} · ${record.target}` : scope;
}
function resultLabel(record: ValidationRecord) {
  return record.overallStatus || (record.status === 'FAILED' || record.status === 'TIMED_OUT' || record.status === 'INTERRUPTED'
    ? record.errorCode || record.status : '—');
}

export function ValidationHistory({ active, supported, onOpenRecord }: {
  active: boolean;
  supported: boolean;
  onOpenRecord(validationId: string): void;
}) {
  const locale = useAppShellContext().uiLocale;
  const [packageId, setPackageId] = useState('');
  const [environment, setEnvironment] = useState('');
  const [scope, setScope] = useState('');
  const [status, setStatus] = useState('');
  const [records, setRecords] = useState<ValidationRecord[]>([]);
  const [cursorStack, setCursorStack] = useState<Array<string | undefined>>([undefined]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [refreshSequence, setRefreshSequence] = useState(0);
  const [cancellingId, setCancellingId] = useState('');
  const [visible, setVisible] = useState(() => document.visibilityState === 'visible');
  const cursor = cursorStack[cursorStack.length - 1];

  useEffect(() => {
    const updateVisibility = () => setVisible(document.visibilityState === 'visible');
    document.addEventListener('visibilitychange', updateVisibility);
    return () => document.removeEventListener('visibilitychange', updateVisibility);
  }, []);

  useEffect(() => {
    if (!supported) { setLoading(false); setRecords([]); setNextCursor(null); return; }
    const controller = new AbortController();
    setLoading(true); setError('');
    api.listValidationRecords({ packageId: packageId.trim() || undefined, environment: environment.trim() || undefined,
      scope: scope || undefined, status: status || undefined, limit: PAGE_SIZE, cursor }, controller.signal)
      .then(page => {
        if (controller.signal.aborted) return;
        setRecords(page.items);
        setNextCursor(page.nextCursor || null);
      })
      .catch(reason => {
        if (controller.signal.aborted) return;
        setRecords([]); setNextCursor(null);
        setError(reason instanceof Error ? reason.message : label('Validation history could not be loaded.', '無法載入驗證記錄。', locale));
      })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [packageId, environment, scope, status, cursor, refreshSequence, supported, locale]);

  const hasActiveRecords = records.some(record => ACTIVE.has(record.status));
  useEffect(() => {
    if (!supported || !active || !visible || !hasActiveRecords) return;
    const timer = window.setInterval(() => setRefreshSequence(value => value + 1), 5000);
    return () => window.clearInterval(timer);
  }, [active, hasActiveRecords, supported, visible]);

  const resetFilterPage = useCallback(() => setCursorStack([undefined]), []);
  const cancel = async (record: ValidationRecord) => {
    if (cancellingId || !ACTIVE.has(record.status)) return;
    setCancellingId(record.validationId); setError('');
    try {
      const updated = await api.cancelValidation(record.validationId);
      setRecords(current => current.map(item => item.validationId === updated.validationId ? updated : item));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : label('The Server could not cancel this validation.', 'Server 無法取消此驗證。', locale));
    } finally { setCancellingId(''); }
  };

  return <div className="att-validation-history-content">
    <p className="att-validation-history-description">{label('Durable read-only validation records for your authenticated account.', '顯示目前已驗證帳戶的持久唯讀驗證記錄。', locale)}</p>
    {!supported ? <section className="att-query-results-panel" aria-label={label('Validation history', '驗證記錄', locale)}>
      <p role="status">{label('This Server does not support asynchronous validation records. Contextual validation remains available where supported.', '此 Server 不支援非同步驗證記錄。支援此功能的頁面仍可使用情境驗證。', locale)}</p>
    </section> : <>
      <section className="att-query-filter-panel att-validation-history-filters" aria-label={label('Validation filters', '驗證篩選', locale)}>
        <label>{label('Package', '套件', locale)}<input type="search" value={packageId} onChange={event => { setPackageId(event.currentTarget.value); resetFilterPage(); }} /></label>
        <label>{label('Environment', '環境', locale)}<input type="search" value={environment} onChange={event => { setEnvironment(event.currentTarget.value); resetFilterPage(); }} placeholder={label('All environments', '所有環境', locale)} /></label>
        <label>{label('Scope', '範圍', locale)}<select value={scope} onChange={event => { setScope(event.currentTarget.value); resetFilterPage(); }}>
          <option value="">{label('All scopes', '所有範圍', locale)}</option>{SCOPES.map(value => <option key={value} value={value}>{scopeLabel(value, locale)}</option>)}
        </select></label>
        <label>{label('Status', '狀態', locale)}<select value={status} onChange={event => { setStatus(event.currentTarget.value); resetFilterPage(); }}>
          <option value="">{label('All statuses', '所有狀態', locale)}</option>{STATUSES.map(value => <option key={value}>{value}</option>)}
        </select></label>
        <div className="att-query-filter-actions"><button type="button" disabled={loading} onClick={() => setRefreshSequence(value => value + 1)}>{label('Refresh validations', '重新整理驗證', locale)}</button></div>
      </section>
      <section className="att-query-results-panel" aria-label={label('Validation records', '驗證記錄', locale)}>
        <p role="status" aria-live="polite">{loading ? label('Loading validations…', '正在載入驗證記錄…', locale)
          : error ? label('Validation history unavailable.', '無法使用驗證記錄。', locale)
            : label(`${records.length} validation records on this page`, `此頁有 ${records.length} 筆驗證記錄`, locale)}</p>
        {error && <p role="alert">{error}</p>}
        {!loading && !error && records.length === 0 && <div className="att-empty-state"><h2>{label('No matching validations', '沒有符合的驗證記錄', locale)}</h2>
          <p>{label('Change the filters or clear them to review other validation records.', '請調整篩選條件或清除篩選以檢視其他驗證記錄。', locale)}</p></div>}
        {records.length > 0 && <div className="att-table-scroll"><table className="att-validation-history-table att-data-table">
          <caption>{label('Validation records owned by the authenticated account', '目前已驗證帳戶擁有的驗證記錄', locale)}</caption>
          <thead><tr><th scope="col">{label('Submitted', '提交時間', locale)}</th><th scope="col">{label('Scope / target', '範圍／目標', locale)}</th>
            <th scope="col">{label('Package / Environment', '套件／環境', locale)}</th><th scope="col">{label('Status / result', '狀態／結果', locale)}</th><th scope="col">{label('Actions', '操作', locale)}</th></tr></thead>
          <tbody>{records.map(record => <tr key={record.validationId}>
            <td data-label={label('Submitted', '提交時間', locale)}><time dateTime={record.submittedAt}>{dateTime(record.submittedAt)}</time></td>
            <td data-label={label('Scope / target', '範圍／目標', locale)}><strong>{targetLabel(record, locale)}</strong>
              {record.phase && ACTIVE.has(record.status) && <span className="att-validation-history-secondary">{label('Phase', '階段', locale)}: {record.phase}</span>}
              <span className="att-validation-history-secondary"><code>{record.validationId}</code></span></td>
            <td data-label={label('Package / Environment', '套件／環境', locale)}>{record.packageId || 'Runtime'}
              <span className="att-validation-history-secondary">{record.environment || label('Package default', '套件預設值', locale)}</span></td>
            <td data-label={label('Status / result', '狀態／結果', locale)}><span className={`att-validation-history-status is-${record.status.toLowerCase()}`}>{record.status}</span>
              <span className="att-validation-history-secondary">{label('Result', '結果', locale)}: {resultLabel(record)}</span>
              <span className="att-validation-history-secondary">{label('Source', '來源', locale)}: {record.applicability || 'UNVERIFIED'}</span>
              {record.error && <span className="att-validation-history-secondary att-validation-history-error">{record.error}</span>}</td>
            <td data-label={label('Actions', '操作', locale)}><div className="att-validation-history-actions">
              <button type="button" className="button button-quiet" onClick={() => onOpenRecord(record.validationId)}>{label('View details', '檢視詳細資料', locale)}</button>
              {ACTIVE.has(record.status) && <button type="button" className="button button-quiet" disabled={Boolean(cancellingId)} onClick={() => void cancel(record)}>
                {cancellingId === record.validationId ? label('Cancelling…', '正在取消…', locale) : label('Cancel', '取消', locale)}</button>}
            </div></td>
          </tr>)}</tbody>
        </table></div>}
        <footer className="att-validation-history-footer">
          <span>{label(`Page ${cursorStack.length}`, `第 ${cursorStack.length} 頁`, locale)}</span>
          <div><button type="button" disabled={loading || cursorStack.length <= 1} onClick={() => setCursorStack(current => current.slice(0, -1))}>{label('Previous', '上一頁', locale)}</button>
            <button type="button" disabled={loading || !nextCursor} onClick={() => setCursorStack(current => [...current, nextCursor || undefined])}>{label('Next', '下一頁', locale)}</button></div>
        </footer>
      </section>
    </>}
  </div>;
}
