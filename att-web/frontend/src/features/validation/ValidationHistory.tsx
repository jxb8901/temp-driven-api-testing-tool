import { useEffect, useState } from 'react';
import { api } from '../../api';
import { useAppShellContext } from '../../AppShell';
import type { ValidationRecord } from './types';
import './validation.css';

const ACTIVE = new Set(['QUEUED', 'RUNNING']);
const SCOPES = ['config', 'package', 'case', 'suite', 'template', 'flow', 'tool', 'load'];
const STATUSES = ['QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'INTERRUPTED'];
const PAGE_SIZE = 50;
const ACTIVE_REFRESH_MS = 5000;
const IDLE_REFRESH_MS = 30_000;
const MAX_IDLE_REFRESHES = 20;
const EMPTY_FILTERS = { packageId: '', environment: '', scope: '', status: '' };

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
  const { uiLocale: locale, authContextKey, inspectionSessionEpoch } = useAppShellContext();
  const identityKey = JSON.stringify([authContextKey || '', inspectionSessionEpoch ?? 0]);
  const [filters, setFilters] = useState({ identity: identityKey, ...EMPTY_FILTERS });
  const activeFilters = filters.identity === identityKey ? filters : { identity: identityKey, ...EMPTY_FILTERS };
  const { packageId, environment, scope, status } = activeFilters;
  const [pagination, setPagination] = useState<{ identity: string; stack: Array<string | undefined> }>({ identity: identityKey, stack: [undefined] });
  const cursorStack = pagination.identity === identityKey ? pagination.stack : [undefined];
  const cursor = cursorStack[cursorStack.length - 1];
  const [page, setPage] = useState<{ identity: string; records: ValidationRecord[]; nextCursor: string | null; loading: boolean; error: string }>(
    { identity: identityKey, records: [], nextCursor: null, loading: true, error: '' },
  );
  const pageMatchesIdentity = page.identity === identityKey;
  const records = pageMatchesIdentity ? page.records : [];
  const nextCursor = pageMatchesIdentity ? page.nextCursor : null;
  const loading = !pageMatchesIdentity || page.loading;
  const error = pageMatchesIdentity ? page.error : '';
  const [refreshSequence, setRefreshSequence] = useState(0);
  const [cancelling, setCancelling] = useState({ identity: identityKey, id: '' });
  const cancellingId = cancelling.identity === identityKey ? cancelling.id : '';
  const [idleRefreshCount, setIdleRefreshCount] = useState(0);
  const [visible, setVisible] = useState(() => document.visibilityState === 'visible');

  useEffect(() => {
    const updateVisibility = () => setVisible(document.visibilityState === 'visible');
    document.addEventListener('visibilitychange', updateVisibility);
    return () => document.removeEventListener('visibilitychange', updateVisibility);
  }, []);

  useEffect(() => {
    setFilters(current => current.identity === identityKey ? current : { identity: identityKey, ...EMPTY_FILTERS });
    setPagination(current => current.identity === identityKey ? current : { identity: identityKey, stack: [undefined] });
    setCancelling(current => current.identity === identityKey ? current : { identity: identityKey, id: '' });
    setIdleRefreshCount(0);
  }, [identityKey]);

  useEffect(() => {
    if (!supported) {
      setPage({ identity: identityKey, records: [], nextCursor: null, loading: false, error: '' });
      return;
    }
    const controller = new AbortController();
    setPage({ identity: identityKey, records: [], nextCursor: null, loading: true, error: '' });
    api.listValidationRecords({ packageId: packageId.trim() || undefined, environment: environment.trim() || undefined,
      scope: scope || undefined, status: status || undefined, limit: PAGE_SIZE, cursor }, controller.signal)
      .then(page => {
        if (controller.signal.aborted) return;
        setPage({ identity: identityKey, records: page.items, nextCursor: page.nextCursor || null, loading: false, error: '' });
      })
      .catch(reason => {
        if (controller.signal.aborted) return;
        setPage({ identity: identityKey, records: [], nextCursor: null, loading: false,
          error: reason instanceof Error ? reason.message : label('Validation history could not be loaded.', '無法載入驗證記錄。', locale) });
      });
    return () => controller.abort();
  }, [identityKey, packageId, environment, scope, status, cursor, refreshSequence, supported, locale]);

  const hasActiveRecords = records.some(record => ACTIVE.has(record.status));
  const idleRefreshPaused = !hasActiveRecords && cursorStack.length === 1 && idleRefreshCount >= MAX_IDLE_REFRESHES;
  const refreshDelay = hasActiveRecords ? ACTIVE_REFRESH_MS
    : cursorStack.length === 1 && !idleRefreshPaused ? IDLE_REFRESH_MS : null;
  useEffect(() => {
    if (!supported || !active || !visible || loading || refreshDelay === null) return;
    const timer = window.setTimeout(() => {
      if (!hasActiveRecords) setIdleRefreshCount(value => value + 1);
      setRefreshSequence(value => value + 1);
    }, refreshDelay);
    return () => window.clearTimeout(timer);
  }, [active, hasActiveRecords, identityKey, loading, refreshDelay, supported, visible]);

  const updateFilters = (patch: Partial<typeof EMPTY_FILTERS>) => {
    setFilters(current => ({ identity: identityKey, ...(current.identity === identityKey ? current : { ...EMPTY_FILTERS }), ...patch }));
    setPagination({ identity: identityKey, stack: [undefined] });
    setIdleRefreshCount(0);
  };
  const refresh = () => { setIdleRefreshCount(0); setRefreshSequence(value => value + 1); };
  const cancel = async (record: ValidationRecord) => {
    if (cancellingId || !ACTIVE.has(record.status)) return;
    setCancelling({ identity: identityKey, id: record.validationId });
    setPage(current => current.identity === identityKey ? { ...current, error: '' } : current);
    try {
      const updated = await api.cancelValidation(record.validationId);
      setPage(current => current.identity === identityKey
        ? { ...current, records: current.records.map(item => item.validationId === updated.validationId ? updated : item) } : current);
    } catch (reason) {
      setPage(current => current.identity === identityKey ? { ...current,
        error: reason instanceof Error ? reason.message : label('The Server could not cancel this validation.', 'Server 無法取消此驗證。', locale) } : current);
    } finally { setCancelling(current => current.identity === identityKey ? { identity: identityKey, id: '' } : current); }
  };

  return <div className="att-validation-history-content">
    <p className="att-validation-history-description">{label('Durable read-only validation records for your authenticated account.', '顯示目前已驗證帳戶的持久唯讀驗證記錄。', locale)}</p>
    {!supported ? <section className="att-query-results-panel" aria-label={label('Validation history', '驗證記錄', locale)}>
      <p role="status">{label('This Server does not support asynchronous validation records. Contextual validation remains available where supported.', '此 Server 不支援非同步驗證記錄。支援此功能的頁面仍可使用情境驗證。', locale)}</p>
    </section> : <>
      <section className="att-query-filter-panel att-validation-history-filters" aria-label={label('Validation filters', '驗證篩選', locale)}>
        <label>{label('Package', '套件', locale)}<input type="search" value={packageId} onChange={event => updateFilters({ packageId: event.currentTarget.value })} /></label>
        <label>{label('Environment', '環境', locale)}<input type="search" value={environment} onChange={event => updateFilters({ environment: event.currentTarget.value })} placeholder={label('All environments', '所有環境', locale)} /></label>
        <label>{label('Scope', '範圍', locale)}<select value={scope} onChange={event => updateFilters({ scope: event.currentTarget.value })}>
          <option value="">{label('All scopes', '所有範圍', locale)}</option>{SCOPES.map(value => <option key={value} value={value}>{scopeLabel(value, locale)}</option>)}
        </select></label>
        <label>{label('Status', '狀態', locale)}<select value={status} onChange={event => updateFilters({ status: event.currentTarget.value })}>
          <option value="">{label('All statuses', '所有狀態', locale)}</option>{STATUSES.map(value => <option key={value}>{value}</option>)}
        </select></label>
        <div className="att-query-filter-actions"><button type="button" disabled={loading} onClick={refresh}>{label('Refresh validations', '重新整理驗證', locale)}</button></div>
      </section>
      <section className="att-query-results-panel" aria-label={label('Validation records', '驗證記錄', locale)}>
        <p role="status" aria-live="polite">{loading ? label('Loading validations…', '正在載入驗證記錄…', locale)
          : error ? label('Validation history unavailable.', '無法使用驗證記錄。', locale)
            : label(`${records.length} validation records on this page`, `此頁有 ${records.length} 筆驗證記錄`, locale)}</p>
        {idleRefreshPaused && <p role="note">{label('Automatic history refresh paused after 10 minutes. Refresh validations to check again.', '自動更新已暫停 10 分鐘。請手動重新整理驗證記錄。', locale)}</p>}
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
          <div><button type="button" disabled={loading || cursorStack.length <= 1} onClick={() => setPagination(current => ({
            identity: identityKey, stack: (current.identity === identityKey ? current.stack : [undefined]).slice(0, -1),
          }))}>{label('Previous', '上一頁', locale)}</button>
            <button type="button" disabled={loading || !nextCursor} onClick={() => setPagination(current => ({
              identity: identityKey, stack: [...(current.identity === identityKey ? current.stack : [undefined]), nextCursor || undefined],
            }))}>{label('Next', '下一頁', locale)}</button></div>
        </footer>
      </section>
    </>}
  </div>;
}
