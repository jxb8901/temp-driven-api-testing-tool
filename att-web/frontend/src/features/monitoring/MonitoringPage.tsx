import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../../api';
import type { MonitoringJobJournal, MonitoringSnapshot } from '../../types';
import { useAppShellContext } from '../../AppShell';
import './monitoring.css';

const POLL_OPTIONS = [5, 15, 30, 60] as const;
const HISTORY_LIMIT = 96;
type GroupName = 'health' | 'process' | 'workers' | 'http' | 'journal' | 'storage' | 'inspection' | 'scheduledLoad';
type Point = { at: number; value: number | null };

function formatBytes(value: number | null | undefined, unavailable = 'Not available'): string {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return unavailable;
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  let amount = value, unit = 0;
  while (amount >= 1024 && unit < units.length - 1) { amount /= 1024; unit++; }
  return `${amount.toLocaleString(undefined, { maximumFractionDigits: 1 })} ${units[unit]}`;
}

function formatNumber(value: number | null | undefined, suffix = '', unavailable = 'Not available'): string {
  return typeof value === 'number' && Number.isFinite(value) ? `${value.toLocaleString(undefined, { maximumFractionDigits: 2 })}${suffix}` : unavailable;
}

function deltaLabel(current: number, previous: number | undefined, zh: boolean): string {
  if (previous === undefined) return zh ? '等待下一個快照' : 'Awaiting previous snapshot';
  if (current < previous) return zh ? '計數器已重設' : 'Counter reset';
  return `+${formatNumber(current - previous, '', zh ? '無資料' : 'Not available')}`;
}

function requestRate(current: number, previous: number | undefined, capturedAt: string, previousAt: string | undefined, zh: boolean): string {
  if (previous === undefined || !previousAt) return zh ? '等待下一個快照' : 'Awaiting previous snapshot';
  const seconds = (Date.parse(capturedAt) - Date.parse(previousAt)) / 1000;
  if (!Number.isFinite(seconds) || seconds <= 0 || current < previous) return zh ? '無資料' : 'Not available';
  return formatNumber((current - previous) / seconds, ' req/s', zh ? '無資料' : 'Not available');
}

function Chart({ title, points, color = '#2563eb', emptyText }: { title: string; points: Point[]; color?: string; emptyText: string }) {
  const valid = points.filter((point): point is { at: number; value: number } => point.value !== null && Number.isFinite(point.value));
  if (valid.length < 2) return <section className="att-monitor-chart"><h3>{title}</h3><p>{emptyText}</p></section>;
  const max = Math.max(1, ...valid.map(point => point.value));
  const minAt = valid[0].at, span = Math.max(1, valid[valid.length - 1].at - minAt);
  const coords = valid.map(point => `${40 + (point.at - minAt) / span * 540},${132 - point.value / max * 106}`).join(' ');
  return <section className="att-monitor-chart"><h3>{title}</h3><svg viewBox="0 0 600 160" role="img" aria-label={title}>
    <path d="M40 20V132H580" fill="none" stroke="currentColor" opacity=".35" />
    <polyline points={coords} fill="none" stroke={color} strokeWidth="3" strokeLinejoin="round" strokeLinecap="round" />
    <text x="40" y="151">{new Date(valid[0].at).toLocaleTimeString()}</text><text x="580" y="151" textAnchor="end">{new Date(valid[valid.length - 1].at).toLocaleTimeString()}</text>
    <text x="40" y="14">0</text><text x="580" y="14" textAnchor="end">{formatNumber(max)}</text>
  </svg></section>;
}

export function MonitoringPage() {
  const { uiLocale } = useAppShellContext();
  const zh = uiLocale === 'zh-Hant';
  const unavailable = zh ? '無資料' : 'Not available';
  const bytes = (value: number | null | undefined) => formatBytes(value, unavailable);
  const number = (value: number | null | undefined, suffix = '') => formatNumber(value, suffix, unavailable);
  const [period, setPeriod] = useState<number>(15);
  const [refreshKey, setRefreshKey] = useState(0);
  const [snapshot, setSnapshot] = useState<MonitoringSnapshot | null>(null);
  const [history, setHistory] = useState<MonitoringSnapshot[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<number | null>(null);
  const [jobId, setJobId] = useState('');
  const [jobJournal, setJobJournal] = useState<MonitoringJobJournal | null>(null);
  const [jobError, setJobError] = useState('');
  const [jobBusy, setJobBusy] = useState(false);
  const jobController = useRef<AbortController | null>(null);
  const previous = history.length > 1 ? history[history.length - 2] : null;
  const alertReasons: Array<{ level: 'warning' | 'critical'; message: string }> = [];
  if (snapshot) {
    if (snapshot.health.status !== 'UP') alertReasons.push({ level: 'critical', message: zh ? '伺服器健康端點未回報 UP。' : 'The Server health endpoint is not reporting UP.' });
    if (snapshot.journal.storageHeadroomLow) alertReasons.push({ level: 'critical', message: zh ? '事件日誌回報磁碟剩餘空間偏低。' : 'The event journal reports low disk headroom.' });
    if (snapshot.journal.degradedActiveJobs > 0) alertReasons.push({ level: 'critical', message: zh ? '有作用中工作日誌處於降級狀態。' : 'One or more active Job journals are degraded.' });
    if (snapshot.process.processCpuLoad !== null && snapshot.process.processCpuLoad > 0.9) alertReasons.push({ level: 'warning', message: zh ? '程序 CPU 高於 90%。' : 'Process CPU is above 90%.' });
    if (snapshot.process.heapMaxBytes && snapshot.process.heapUsedBytes !== null && snapshot.process.heapUsedBytes / snapshot.process.heapMaxBytes > 0.9) alertReasons.push({ level: 'warning', message: zh ? 'Heap 使用量高於 90%。' : 'Heap use is above 90%.' });
    if (snapshot.workers.queueCapacity > 0 && snapshot.workers.queued / snapshot.workers.queueCapacity > 0.8) alertReasons.push({ level: 'warning', message: zh ? '工作佇列超過容量的 80%。' : 'The work queue is above 80% of capacity.' });
    if (previous && snapshot.http.serverErrors > previous.http.serverErrors) alertReasons.push({ level: 'warning', message: zh ? '近期取樣期間新增 HTTP 5xx 錯誤。' : 'HTTP 5xx errors increased since the previous snapshot.' });
    if (previous && snapshot.journal.compactionFailuresSinceStart > previous.journal.compactionFailuresSinceStart) alertReasons.push({ level: 'warning', message: zh ? '近期取樣期間新增日誌壓縮失敗。' : 'Journal compaction failures increased since the previous snapshot.' });
    if (previous && (snapshot.workers.loadCapacityRejections > previous.workers.loadCapacityRejections || snapshot.workers.queueCapacityRejections > previous.workers.queueCapacityRejections)) alertReasons.push({ level: 'warning', message: zh ? '近期取樣期間有工作因准入容量而遭拒。' : 'Work submissions were rejected for capacity during the last sample interval.' });
  }
  const lastSnapshotAgeMs = updatedAt === null ? null : Math.max(0, Date.now() - updatedAt);
  const stale = Boolean(snapshot && error && lastSnapshotAgeMs !== null && lastSnapshotAgeMs >= period * 1000);
  const unavailableStatus = !snapshot && Boolean(error);
  const snapshotState = alertReasons.some(reason => reason.level === 'critical') ? (zh ? '嚴重' : 'Critical')
    : alertReasons.length ? (zh ? '警告' : 'Warning') : (zh ? '正常' : 'Healthy');
  const overallState = stale ? (zh ? '資料過期' : 'Stale')
    : unavailableStatus ? (zh ? '無法取得' : 'Unavailable')
      : snapshotState;

  useEffect(() => {
    let disposed = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let running = false;
    let active: AbortController | null = null;
    const schedule = () => {
      if (!disposed && !document.hidden) timer = window.setTimeout(() => void load(), period * 1000);
    };
    const load = async () => {
      if (disposed || document.hidden || running) return;
      running = true;
      const controller = new AbortController(); active = controller;
      try {
        const value = await api.getServerMonitoring(controller.signal);
        if (controller.signal.aborted || disposed) return;
        if (value.schemaVersion !== 'att-monitoring/v1' || !value.capturedAt || !value.health) throw new Error('The Server returned an unsupported Monitoring snapshot.');
        setSnapshot(value); setHistory(previous => [...previous, value].slice(-HISTORY_LIMIT));
        setUpdatedAt(Date.now()); setError('');
      } catch (reason) {
        if (!controller.signal.aborted && !disposed) setError(reason instanceof Error && reason.message ? reason.message : 'Monitoring data is unavailable.');
      } finally {
        if (active === controller) active = null;
        running = false;
        if (!disposed && !document.hidden) schedule();
        if (!disposed) setLoading(false);
      }
    };
    const visibility = () => {
      if (document.hidden) { if (timer) clearTimeout(timer); active?.abort(); }
      else { if (timer) clearTimeout(timer); void load(); }
    };
    document.addEventListener('visibilitychange', visibility);
    void load();
    return () => { disposed = true; if (timer) clearTimeout(timer); active?.abort(); document.removeEventListener('visibilitychange', visibility); };
  }, [period, refreshKey]);

  useEffect(() => () => jobController.current?.abort(), []);
  const requestJobJournal = useCallback(async () => {
    jobController.current?.abort();
    const controller = new AbortController(); jobController.current = controller;
    setJobBusy(true); setJobError(''); setJobJournal(null);
    try {
      if (!/^J[0-9A-F]{16}$/.test(jobId.trim())) throw new Error(zh ? '請輸入有效的工作 ID。' : 'Enter a valid Job ID.');
      setJobJournal(await api.getMonitoringJobJournal(jobId.trim(), controller.signal));
    } catch (reason) {
      if (!controller.signal.aborted) setJobError(reason instanceof Error ? reason.message : (zh ? '無法取得工作日誌。' : 'The Job journal could not be loaded.'));
    } finally { if (!controller.signal.aborted) setJobBusy(false); }
  }, [jobId, zh]);

  const titles: Record<GroupName, string> = {
    health: zh ? '健康狀態' : 'Health', process: zh ? 'JVM 與程序' : 'JVM and process',
    workers: zh ? 'Worker 與工作佇列' : 'Workers and admission', http: zh ? 'HTTP API 與 SSE' : 'HTTP API and SSE',
    journal: zh ? '事件日誌與壓縮' : 'Journal and compaction', storage: zh ? '儲存與產物' : 'Storage and artifacts',
    inspection: zh ? '檢查與執行池' : 'Inspection and execution pool', scheduledLoad: zh ? '排程負載測試' : 'Scheduled Load',
  };
  const sections = useMemo(() => {
    if (!snapshot) return [] as Array<{ name: GroupName; rows: Array<[string, string]> }>;
    const p = snapshot.process, w = snapshot.workers, h = snapshot.http, j = snapshot.journal, s = snapshot.storage, i = snapshot.inspection, ep = snapshot.executionPool, sl = snapshot.scheduledLoad;
    const t = (en: string, zhText: string) => zh ? zhText : en;
    return [
      { name: 'health' as const, rows: [[t('Monitoring state','監控狀態'), overallState], [t('Server state','伺服器狀態'), snapshot.health.status], [t('Server version','伺服器版本'), snapshot.health.version], [t('Uptime','運行時間'), number(snapshot.health.uptimeMs / 1000, ' s')], [t('Snapshot source','資料來源'), snapshot.origin], [t('Request ID','請求 ID'), snapshot.requestId || t('Not available','無資料')]] },
      { name: 'process' as const, rows: [[t('Heap used / committed / max','Heap 已用 / 已配置 / 上限'), `${bytes(p.heapUsedBytes)} / ${bytes(p.heapCommittedBytes)} / ${bytes(p.heapMaxBytes)}`], [t('Heap utilization','Heap 使用率'), p.heapMaxBytes && p.heapUsedBytes !== null ? number(p.heapUsedBytes / p.heapMaxBytes * 100, '%') : t('Not available','無資料')], [t('Non-heap used / committed','Non-heap 已用 / 已配置'), `${bytes(p.nonHeapUsedBytes)} / ${bytes(p.nonHeapCommittedBytes)}`], [t('Threads: live / daemon / peak','執行緒：存活 / 背景 / 峰值'), `${number(p.threadCount)} / ${number(p.daemonThreadCount)} / ${number(p.peakThreadCount)}`], [t('GC count / time','GC 次數 / 時間'), `${number(p.gcCount)} / ${number(p.gcTimeMs, ' ms')}`], [t('Available processors','可用處理器'), number(p.availableProcessors)], [t('Process CPU load / time','程序 CPU 負載 / 時間'), `${number(p.processCpuLoad === null ? null : p.processCpuLoad * 100, '%')} / ${number(p.processCpuTimeMs, ' ms')}`], [t('Process RSS','程序 RSS'), bytes(p.processRssBytes)]] },
      { name: 'workers' as const, rows: [[t('Active worker slots / maxConcurrent','作用中 Worker 槽 / maxConcurrent'), `${number(w.active)} / ${number(w.maxConcurrent)}`], [t('Waiting queue / capacity','等待佇列 / 容量'), `${number(w.queued)} / ${number(w.queueCapacity)}`], [t('Admission slots used / total capacity','已用准入槽 / 總容量'), `${number(w.admissionSlotsUsed)} / ${number(w.admissionCapacity)}`], [t('Active LOAD jobs / admitted LOAD slots / maxConcurrentLoad','作用中 LOAD 工作 / 已准入 LOAD 槽 / maxConcurrentLoad'), `${number(w.activeLoadJobs)} / ${number(w.loadSlotsUsed)} / ${number(w.maxConcurrentLoad)}`], [t('Queued / running / finished Jobs','佇列中 / 執行中 / 已完成工作'), `${number(snapshot.jobs.QUEUED)} / ${number(snapshot.jobs.RUNNING)} / ${number((snapshot.jobs.PASS ?? 0)+(snapshot.jobs.FAIL ?? 0)+(snapshot.jobs.ERROR ?? 0)+(snapshot.jobs.INVALID ?? 0)+(snapshot.jobs.CANCELLED ?? 0))}`], [t('Completed Jobs since start','啟動後完成工作數'), number(w.completedSinceStart)], [t('LOAD / queue capacity rejections since start','啟動後 LOAD / 佇列容量拒絕數'), `${number(w.loadCapacityRejections)} / ${number(w.queueCapacityRejections)}`], [t('Worker abnormal exits / timeouts','Worker 異常結束 / 逾時'), `${number(w.workerAbnormalExits)} / ${number(w.workerTimeouts)}`], [t('Active SSE observers / limit','作用中 SSE 連線 / 上限'), `${number(w.activeSseConnections)} / ${number(w.sseCapacity)}`], [t('SSE errors / reconnects','SSE 錯誤 / 重新連線'), `${number(w.sseErrors)} / ${number(w.sseReconnects)}`], [t('Jobs by status','依狀態統計工作'), Object.entries(snapshot.jobs).sort(([left],[right]) => left.localeCompare(right)).map(([key,value]) => `${key}: ${number(value)}`).join(' · ') || t('Not available','無資料')]] },
      { name: 'http' as const, rows: [[t('Requests since start','啟動後請求數'), number(h.requests)], [t('2xx / 3xx / 4xx / 5xx totals','2xx / 3xx / 4xx / 5xx 總數'), `${number(h.successfulRequests)} / ${number(h.redirectRequests)} / ${number(h.clientErrors)} / ${number(h.serverErrors)}`], [t('Recent request rate','近期請求速率'), requestRate(h.requests, previous?.http.requests, snapshot.capturedAt, previous?.capturedAt, zh)], [t('Recent 2xx / 4xx / 5xx rates','近期 2xx / 4xx / 5xx 速率'), `${requestRate(h.successfulRequests, previous?.http.successfulRequests, snapshot.capturedAt, previous?.capturedAt, zh)} · ${requestRate(h.clientErrors, previous?.http.clientErrors, snapshot.capturedAt, previous?.capturedAt, zh)} · ${requestRate(h.serverErrors, previous?.http.serverErrors, snapshot.capturedAt, previous?.capturedAt, zh)}`], [t('Recent 4xx / 5xx change','近期 4xx / 5xx 變化'), `${deltaLabel(h.clientErrors, previous?.http.clientErrors, zh)} / ${deltaLabel(h.serverErrors, previous?.http.serverErrors, zh)}`], [t('Recent p50 / p95 latency','近期 p50 / p95 延遲'), `${number(h.recentP50Ms, ' ms')} / ${number(h.recentP95Ms, ' ms')}`]] },
      { name: 'journal' as const, rows: [[t('Active / degraded jobs','作用中 / 降級工作'), `${number(j.activeJobs)} / ${number(j.degradedActiveJobs)}`], [t('Active journal bytes / retained events','作用中日誌大小 / 保留事件'), `${bytes(j.activeJournalBytes)} / ${number(j.activeRetainedEvents)}`], [t('Configured cap per Job','每個工作的設定上限'), bytes(j.maxJournalBytesPerJob)], [t('Retained memory / active budget','保留記憶體 / 作用中預算'), `${bytes(j.activeRetainedMemoryBytes)} / ${bytes(j.memoryBudgetBytesPerJob * j.activeJobs)}`], [t('Memory over budget','超出記憶體預算'), bytes(j.activeMemoryOverBudgetBytes)], [t('Compaction attempts / failures (active jobs)','壓縮次數 / 失敗（作用中工作）'), `${number(j.activeCompactionAttempts)} / ${number(j.activeCompactionFailures)}`], [t('Compaction failures since Server start / interval','Server 啟動後 / 區間壓縮失敗數'), `${number(j.compactionFailuresSinceStart)} / ${deltaLabel(j.compactionFailuresSinceStart, previous?.journal.compactionFailuresSinceStart, zh)}`], [t('Oldest compaction age','最久壓縮等待時間'), number(j.oldestActiveCompactionAgeMs, ' ms')], [t('Append / compaction bytes','追加 / 壓縮位元組'), `${bytes(j.appendBytesWritten)} / ${bytes(j.compactionBytesWritten)}`], [t('Journal write amplification','日誌寫入放大率'), number(j.writeAmplification, '×')], [t('Listener dispatch failures','事件派送失敗數'), number(j.activeListenerDispatchFailures)], [t('Disk headroom','磁碟剩餘空間'), j.storageHeadroomLow ? t('Low','偏低') : bytes(j.storageUsableBytes)], [t('Terminal event cache jobs / weight','終止工作事件快取：工作數 / 大小'), `${number(j.cachedTerminalJobs)} / ${bytes(j.cachedTerminalWeightBytes)}`]] },
      { name: 'storage' as const, rows: [[t('File store used / usable / total','檔案系統已用 / 可用 / 總量'), `${bytes(s.fileStoreTotalBytes !== null && s.fileStoreUsableBytes !== null ? Math.max(0, s.fileStoreTotalBytes - s.fileStoreUsableBytes) : null)} / ${bytes(s.fileStoreUsableBytes)} / ${bytes(s.fileStoreTotalBytes)}`], [t('Job storage state / measurement','工作儲存狀態 / 測量'), `${s.state} / ${s.jobStorageMeasurementComplete === null ? t('Not available','無資料') : s.jobStorageMeasurementComplete ? t('Complete','完整') : t('Partial','部分')}`], [t('Job storage quota enabled','工作儲存配額已啟用'), String(s.jobQuotaEnabled)], [t('Job storage / quota','工作儲存 / 配額'), `${bytes(s.jobStorageBytes)} / ${bytes(s.jobQuotaBytes)}`], [t('Quota cleanup target / max single Job','配額清理目標 / 單一工作上限'), `${bytes(s.cleanupTargetBytes)} / ${bytes(s.maxSingleJobBytes)}`], [t('Eligible / protected / deleted Jobs','可清理 / 保護 / 已刪除工作'), `${number(s.eligibleJobs)} / ${number(s.protectedJobs)} / ${number(s.deletedJobs)}`], [t('Last sweep / reclaimed bytes','最近清理 / 回收位元組'), `${s.lastSweepAt || t('Not available','無資料')} / ${bytes(s.reclaimedBytes)}`], [t('Quota unmet','未達配額目標'), s.quotaUnmet === null ? t('Not available','無資料') : String(s.quotaUnmet)], [t('Archive writers active / queue / capacity','封存工作者執行中 / 佇列 / 容量'), `${number(s.archiveWritersActive)} / ${number(s.archiveQueueDepth)} / ${number(s.archiveQueueCapacity)}`], [t('Pending archive jobs','待處理封存工作'), number(s.pendingArchiveJobs)], [t('Archive failures / retry requests since start','啟動後封存失敗 / 重試請求'), `${number(s.archiveFailuresSinceStart)} / ${number(s.archiveRetriesSinceStart)}`], [t('Aggregate artifact bytes','產物總大小'), bytes(s.artifactBytes)]] },
      { name: 'inspection' as const, rows: [[t('Inspection enabled','檢查功能已啟用'), i.enabled ? t('Yes','是') : t('No','否')], [t('Inspection workers busy / configured / alive','檢查 Worker 忙碌 / 設定 / 存活'), `${number(i.busyWorkers)} / ${number(i.workerCount)} / ${number(i.liveProcesses)}`], [t('Inspection requests / duration','檢查請求 / 耗時'), `${number(i.requests)} / ${number(i.totalDurationMs, ' ms')}`], [t('Inspection failures / worker starts','檢查失敗 / Worker 啟動數'), `${number(i.failures)} / ${number(i.workerStarts)}`], [t('Index cache hits / misses','索引快取命中 / 未命中'), `${number(i.indexCacheHits)} / ${number(i.indexCacheMisses)}`], [t('Configuration cache hits / misses','設定快取命中 / 未命中'), `${number(i.configCacheHits)} / ${number(i.configCacheMisses)}`], [t('Inspector cache hits / misses','檢查器快取命中 / 未命中'), `${number(i.inspectorCacheHits)} / ${number(i.inspectorCacheMisses)}`], [t('Execution pool enabled','執行池已啟用'), ep.enabled ? t('Yes','是') : t('No','否')], [t('Prewarmed Workers ready / owned / max','預熱 Worker 就緒 / 管理中 / 上限'), `${number(ep.ready)} / ${number(ep.owned)} / ${number(ep.maxIdle)}`], [t('Prewarm starts / leases / cold fallbacks','預熱啟動 / 租用 / 冷啟動回退'), `${number(ep.starts)} / ${number(ep.leases)} / ${number(ep.coldFallbacks)}`], [t('Prewarm startup failures / recovery uncertain','預熱啟動失敗 / 復原未確定'), `${number(ep.startupFailures)} / ${ep.recoveryUncertain === null ? t('Not available','無資料') : String(ep.recoveryUncertain)}`], [t('Prewarm idle heap cap / CPU','預熱閒置 Heap 上限 / CPU'), `${bytes(ep.idleHeapCapBytes)} / ${ep.idleCpuMetricsAvailable === false ? t('Not available','無資料') : number(ep.idleCpuMs, ' ms')}`]] },
      { name: 'scheduledLoad' as const, rows: [[t('Scheduling','排程'), sl.enabled ? t('Enabled','已啟用') : t('Disabled','已停用')], [t('Schedules / active scheduled Loads / capacity','排程數 / 作用中排程負載 / 容量'), `${number(sl.scheduleCount)} / ${number(sl.activeLoads)} / ${number(sl.maxConcurrentRuns)}`], [t('Next due','下次執行'), sl.nextDueAt || t('Not scheduled','未排程')], [t('Recorded occurrences','已記錄排程次數'), sl.occurrences ? Object.entries(sl.occurrences).map(([key, value]) => `${key}: ${value}`).join(' · ') : t('Not available','無資料')]] },
    ];
  }, [snapshot, zh, previous, overallState]);
  const heapPoints = history.map(item => ({ at: Date.parse(item.capturedAt), value: item.process.heapUsedBytes }));
  const queuePoints = history.map(item => ({ at: Date.parse(item.capturedAt), value: item.workers.queued }));
  const gcPoints = history.slice(1).map((item,index) => ({ at: Date.parse(item.capturedAt), value: item.process.gcCount !== null && history[index].process.gcCount !== null && item.process.gcCount >= history[index].process.gcCount! ? item.process.gcCount-history[index].process.gcCount! : null }));
  const errorPoints = history.slice(1).map((item,index) => ({ at: Date.parse(item.capturedAt), value: item.http.serverErrors >= history[index].http.serverErrors ? item.http.serverErrors-history[index].http.serverErrors : null }));
  const compactionPoints = history.slice(1).map((item,index) => ({ at: Date.parse(item.capturedAt), value: item.journal.compactionFailuresSinceStart >= history[index].journal.compactionFailuresSinceStart ? item.journal.compactionFailuresSinceStart-history[index].journal.compactionFailuresSinceStart : null }));
  const journalPoints = history.map(item => ({ at: Date.parse(item.capturedAt), value: item.journal.activeJournalBytes }));
  const diskPoints = history.map(item => ({ at: Date.parse(item.capturedAt), value: item.storage.fileStoreUsableBytes }));

  return <main className="page-content att-monitoring" aria-label={zh ? '伺服器監控' : 'Server Monitoring'}>
    <header className="att-monitor-heading"><div><p className="eyebrow">ATT Server</p><h1>{zh ? '監控' : 'Monitoring'}</h1><p>{zh ? '唯讀伺服器健康、容量和活動快照。缺少的量測會標示為無資料。' : 'Read-only snapshots of Server health, capacity, and activity. Missing measurements stay unavailable.'}</p></div>
      <div className="att-monitor-controls"><label>{zh ? '更新間隔' : 'Refresh interval'} <select value={period} onChange={event => setPeriod(Number(event.target.value))}>{POLL_OPTIONS.map(value => <option key={value} value={value}>{value} s</option>)}</select></label><button type="button" onClick={() => setRefreshKey(value => value + 1)}>{zh ? '立即更新' : 'Refresh now'}</button></div>
    </header>
    <div className="att-monitor-freshness" role="status"><span>{loading ? (zh ? '正在取得快照…' : 'Loading snapshot…') : updatedAt ? (zh ? `最近更新：${new Date(updatedAt).toLocaleTimeString()}` : `Last updated ${new Date(updatedAt).toLocaleTimeString()}`) : (zh ? '尚無快照' : 'No snapshot yet')}</span><span>{snapshot ? `${zh ? '來源' : 'Origin'}: ${snapshot.origin} · ${zh ? '快照時間' : 'Snapshot time'}: ${new Date(snapshot.capturedAt).toLocaleString()}` : ''}</span></div>
    {error && <p className="att-monitor-error" role="alert">{zh ? `監控更新失敗：${error}` : `Monitoring refresh failed: ${error}`}{snapshot && <span>{zh ? ' 顯示最後一次成功快照。' : ' Showing the last successful snapshot.'}</span>}</p>}
    {(snapshot || unavailableStatus) && <section className={`att-monitor-alerts att-monitor-alerts-${stale ? 'stale' : unavailableStatus ? 'unavailable' : alertReasons.some(reason => reason.level === 'critical') ? 'critical' : alertReasons.length ? 'warning' : 'healthy'}`} role="status" aria-live="polite" aria-label={zh ? '監控狀態' : 'Monitoring status'}><h2>{zh ? `狀態：${overallState}` : `Status: ${overallState}`}</h2>{stale && snapshot && <p>{zh ? `上次成功快照已超過 ${period} 秒；最後已知 Server 狀態為 ${snapshot.health.status}。以下數值是歷史資料，Server 目前狀況未能確認。` : `The last successful snapshot is over ${period} seconds old. The last reported Server status was ${snapshot.health.status}; displayed readings are historical and current health is unknown.`}</p>}{unavailableStatus && <p>{zh ? '尚未取得成功快照；目前 Server 狀況無法確認。' : 'No successful snapshot is available, so current Server health is unknown.'}</p>}{snapshot && alertReasons.length > 0 && <>{stale && <h3>{zh ? '最後成功快照中的警示' : 'Alerts from the last successful snapshot'}</h3>}<ul>{alertReasons.map(reason => <li key={reason.message}>{reason.message}</li>)}</ul></>}</section>}
    {snapshot && <>
      <div className="att-monitor-group-grid">{sections.map(section => <section className="att-monitor-group" key={section.name} aria-label={titles[section.name]}>
        <h2>{titles[section.name]}</h2><dl>{section.rows.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}</dl>
        {section.name === 'health' && <small>{zh ? `版本化結構：${snapshot.schemaVersion}` : `Versioned schema: ${snapshot.schemaVersion}`}</small>}
        {section.name === 'storage' && <small>{zh ? '檔案系統使用量指 server.dataDir 所在的整個檔案系統。工作配額不包含 H2 資料庫或所有產物與暫存寫入；未量測的產物總大小會標示為無資料。' : 'File store usage covers the filesystem containing server.dataDir. The Job quota excludes the H2 database and some artifact or temporary writes; unmeasured aggregate artifact bytes stay unavailable.'}</small>}
        {section.name === 'inspection' && <details className="att-monitor-details"><summary>{zh ? '檢查階段與程序樣本' : 'Inspection stages and process samples'}</summary>
          <h3>{zh ? '伺服器檢查階段（累計）' : 'Server inspection stages (cumulative)'}</h3>
          <dl>{Object.entries(snapshot.inspection.serverStages).sort(([left],[right]) => left.localeCompare(right)).map(([stage,value]) => <div key={stage}><dt>{stage}</dt><dd>{number(value.count)} · {number(value.totalDurationMs,' ms')}</dd></div>)}</dl>
          <h3>{zh ? '隔離 Worker 樣本' : 'Isolated Worker samples'}</h3>
          <dl>{snapshot.inspection.workers.map(worker => <div key={worker.slot}><dt>{zh ? `槽 ${worker.slot}` : `Slot ${worker.slot}`}</dt><dd>{worker.busy ? (zh ? '忙碌' : 'Busy') : (zh ? '閒置' : 'Idle')} · {worker.processAlive ? (zh ? '程序存活' : 'Process alive') : (zh ? '無程序' : 'No process')} · {bytes(worker.heapUsedBytes)} · {number(worker.processCpuTimeMs,' ms CPU')}</dd></div>)}</dl>
        </details>}
      </section>)}</div>
      <section className="att-monitor-trends"><h2>{zh ? '本次工作階段趨勢' : 'Session trends'}</h2><p>{zh ? '最多保留最近 96 個快照；重新載入頁面後不會保留。計數器圖顯示每個取樣區間的增量。' : 'The latest 96 snapshots are kept in this page session and are cleared when it reloads. Counter charts show per-sample deltas.'}</p><div className="att-monitor-chart-grid">
        <Chart title={zh ? 'Heap 使用量（bytes）' : 'Heap used (bytes)'} points={heapPoints} emptyText={zh ? '正在收集快照；取得兩筆後會顯示趨勢。' : 'Collecting session data. A trend appears after two snapshots.'} />
        <Chart title={zh ? '工作佇列深度' : 'Work queue depth'} points={queuePoints} color="#0f766e" emptyText={zh ? '正在收集快照；取得兩筆後會顯示趨勢。' : 'Collecting session data. A trend appears after two snapshots.'} />
        <Chart title={zh ? 'GC 次數 / 區間' : 'GC count per interval'} points={gcPoints} color="#7c3aed" emptyText={zh ? '正在收集快照；取得兩筆後會顯示趨勢。' : 'Collecting session data. A trend appears after two snapshots.'} />
        <Chart title={zh ? 'HTTP 5xx / 區間' : 'HTTP 5xx per interval'} points={errorPoints} color="#be123c" emptyText={zh ? '正在收集快照；取得兩筆後會顯示趨勢。' : 'Collecting session data. A trend appears after two snapshots.'} />
        <Chart title={zh ? '日誌壓縮失敗 / 區間' : 'Journal compaction failures per interval'} points={compactionPoints} color="#c2410c" emptyText={zh ? '正在收集快照；取得兩筆後會顯示趨勢。' : 'Collecting session data. A trend appears after two snapshots.'} />
        <Chart title={zh ? '作用中工作日誌大小（bytes）' : 'Active Job journal bytes'} points={journalPoints} color="#0f766e" emptyText={zh ? '正在收集快照；取得兩筆後會顯示趨勢。' : 'Collecting session data. A trend appears after two snapshots.'} />
        <Chart title={zh ? '檔案系統可用空間（bytes）' : 'File store usable bytes'} points={diskPoints} color="#0369a1" emptyText={zh ? '正在收集快照；取得兩筆後會顯示趨勢。' : 'Collecting session data. A trend appears after two snapshots.'} />
      </div></section>
    </>}
    <section className="att-monitor-job-journal"><h2>{zh ? '單一工作日誌' : 'Per-Job journal'}</h2><p>{zh ? '輸入你有權查看的工作 ID，以讀取其日誌摘要。已快取的即時指標可立即檢視；Monitoring 不會為此掃描大型日誌檔案。' : 'Enter a Job ID you are authorized to view. Cached live metrics are shown; Monitoring does not scan large journal files for this view.'}</p>
      <form onSubmit={event => { event.preventDefault(); void requestJobJournal(); }}><label htmlFor="monitoring-job-id">{zh ? '工作 ID' : 'Job ID'}</label><input id="monitoring-job-id" value={jobId} onChange={event => { jobController.current?.abort(); setJobBusy(false); setJobJournal(null); setJobError(''); setJobId(event.target.value.toUpperCase()); }} maxLength={17} placeholder="J0123456789ABCDEF" /><button type="submit" disabled={jobBusy}>{jobBusy ? (zh ? '讀取中…' : 'Loading…') : (zh ? '檢視日誌' : 'View journal')}</button></form>
      {jobError && <p role="alert">{jobError}</p>}
      {jobJournal && <><a href={`#/jobs/${encodeURIComponent(jobJournal.jobId)}?tab=events`}>{zh ? '開啟工作事件 / 即時輸出' : 'Open Job events and live output'}</a>{!jobJournal.metricsAvailable ? <p>{zh ? '此工作日誌指標目前未快取；為保持查詢有界，請從工作頁面開啟事件檢視。' : 'Journal metrics are not currently cached. To keep this query bounded, open the Job Events page for its event view.'}</p> : <dl className="att-monitor-job-result"><div><dt>{zh ? '持久狀態' : 'Persistence'}</dt><dd>{jobJournal.persistence}</dd></div><div><dt>{zh ? '日誌大小 / 上限' : 'Journal bytes / cap'}</dt><dd>{bytes(jobJournal.journalBytes)} / {bytes(jobJournal.maxJournalBytes)}</dd></div><div><dt>{zh ? '保留事件 / 位元組' : 'Retained events / bytes'}</dt><dd>{number(jobJournal.retainedEvents)} / {bytes(jobJournal.retainedBytes)}</dd></div><div><dt>{zh ? '保留記憶體 / 壓縮積壓' : 'Retained memory / compaction backlog'}</dt><dd>{bytes(jobJournal.retainedMemoryBytes)} / {bytes(jobJournal.compactionBacklogBytes)}</dd></div><div><dt>{zh ? '壓縮次數 / 失敗' : 'Compaction attempts / failures'}</dt><dd>{number(jobJournal.compactionAttempts)} / {number(jobJournal.compactionFailures)}</dd></div><div><dt>{zh ? '派送失敗 / 寫入放大率' : 'Dispatch failures / write amplification'}</dt><dd>{number(jobJournal.listenerDispatchFailures)} / {number(jobJournal.writeAmplification,'×')}</dd></div><div><dt>{zh ? '最近壓縮時間 / 耗時 / 經過時間' : 'Last compaction at / duration / age'}</dt><dd>{`${jobJournal.lastCompactionAt || (zh ? '無資料' : 'Not available')} / ${number(jobJournal.lastCompactionDurationMs,' ms')} / ${number(jobJournal.lastCompactionAgeMs,' ms')}`}</dd></div><div><dt>{zh ? '歷史截斷 / 序號缺口' : 'History truncated / sequence gap'}</dt><dd>{number(jobJournal.historyTruncated === null ? null : Number(jobJournal.historyTruncated))} / {number(jobJournal.sequenceGapDetected === null ? null : Number(jobJournal.sequenceGapDetected))}</dd></div></dl>}</>}
    </section>
  </main>;
}
