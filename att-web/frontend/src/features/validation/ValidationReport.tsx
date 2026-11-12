import { useMemo, useState } from 'react';
import type { ResourceRoute, ValidationScope } from '../../router';
import type { UiLocale } from '../../uiLocale';
import type { ValidationDiagnostic, ValidationReportData, ValidationSection } from './types';
import './validation.css';

function text(en: string, zh: string, locale: UiLocale): string { return locale === 'zh-Hant' ? zh : en; }
function field(value: unknown): string { return typeof value === 'string' ? value : typeof value === 'number' ? String(value) : ''; }
function resourceOf(diagnostic: ValidationDiagnostic): string {
  return field(diagnostic.resourceId) || field(diagnostic.target) || field(diagnostic.resource);
}
function sourceOf(diagnostic: ValidationDiagnostic): string { return field(diagnostic.source) || field(diagnostic.path); }
function safeDiagnosticText(value: string): string {
  return value.slice(0, 4000)
    .replace(/\b(password|secret|token|credential|authorization|api[_-]?key)(\s*[:=]\s*)([^\s,;]+)/gi, '$1$2[REDACTED]')
    .replace(/(?:[A-Za-z]:[\\/]|\/(?:Users|home|private|tmp|var)\/)[^\s"'<>]*/g, '[REDACTED_PATH]');
}

function safeDiagnostic(diagnostic: ValidationDiagnostic): Record<string, string> {
  const result: Record<string, string> = {};
  for (const key of ['code', 'severity', 'message', 'source', 'field', 'suggestion', 'resourceType', 'resourceId', 'target'] as const) {
    const value = field(diagnostic[key]);
    if (value) result[key] = safeDiagnosticText(value);
  }
  return result;
}

function safeSection(section: ValidationSection): Record<string, unknown> {
  return {
    name: safeDiagnosticText(section.name),
    ...(section.source ? { source: safeDiagnosticText(section.source) } : {}),
    status: section.status,
    ...(section.diagnostics?.length ? { diagnostics: section.diagnostics.map(safeDiagnostic) } : {}),
    ...(section.sections?.length ? { sections: section.sections.map(safeSection) } : {}),
  };
}

/** Copy only the public report contract and context fields that are safe to identify as Server supplied. */
export function safeValidationReportCopy(report: ValidationReportData): string {
  const allowedContext = new Set(['packageId', 'environment', 'revision', 'packageRevision', 'manifestRevision', 'sourceRevision', 'runtimeConfigRevision', 'configurationRevision']);
  const context = Object.fromEntries(Object.entries(report.context || {})
    .filter(([key, value]) => allowedContext.has(key) && ['string', 'number', 'boolean'].includes(typeof value))
    .map(([key, value]) => [key, typeof value === 'string' ? safeDiagnosticText(value) : value]));
  return JSON.stringify({
    schemaVersion: report.schemaVersion,
    scope: report.scope,
    backend: report.backend,
    sections: report.sections.map(safeSection),
    diagnostics: report.diagnostics.map(safeDiagnostic),
    counts: report.counts,
    overallStatus: report.overallStatus,
    complete: report.complete,
    exitCode: report.exitCode,
    ...(Object.keys(context).length ? { context } : {}),
    ...(report.requestId ? { requestId: safeDiagnosticText(report.requestId) } : {}),
  }, null, 2);
}

function filteredSection(section: ValidationSection, matches: (diagnostic: ValidationDiagnostic) => boolean): ValidationSection | null {
  const diagnostics = (section.diagnostics || []).filter(matches);
  const children = (section.sections || []).map(child => filteredSection(child, matches)).filter((item): item is ValidationSection => item !== null);
  if (!diagnostics.length && !children.length) return null;
  return { ...section, diagnostics, sections: children };
}

function SectionTree({ sections, locale }: { sections: ValidationSection[]; locale: UiLocale }) {
  return <ul className="att-validation-section-tree">{sections.map((section, index) => <li key={`${section.name}:${index}`}>
    <details open>
      <summary><span className={`att-validation-section-status is-${section.status.toLowerCase()}`}>{section.status}</span>
        <strong>{safeDiagnosticText(section.name)}</strong>{section.source && <code>{safeDiagnosticText(section.source)}</code>}</summary>
      {section.diagnostics?.length ? <ul className="att-validation-section-diagnostics">{section.diagnostics.map((diagnostic, diagnosticIndex) => <li key={`${diagnostic.code}:${diagnosticIndex}`}>
        <strong>{safeDiagnosticText(diagnostic.severity)} · {safeDiagnosticText(diagnostic.code)}</strong><span>{safeDiagnosticText(diagnostic.message)}</span>
        {diagnostic.field && <small>{text('Field', '欄位', locale)}: {safeDiagnosticText(diagnostic.field)}</small>}
        {sourceOf(diagnostic) && <small>{text('Source', '來源', locale)}: {safeDiagnosticText(sourceOf(diagnostic))}</small>}
        {diagnostic.suggestion && <small>{text('Suggestion', '建議', locale)}: {safeDiagnosticText(diagnostic.suggestion)}</small>}
      </li>)}</ul> : null}
      {section.sections?.length ? <SectionTree sections={section.sections} locale={locale} /> : null}
    </details>
  </li>)}</ul>;
}

export function ValidationReport({ report, scope, packageId, environment, effectiveEnvironment, target, resourceRoute, onOpenResource, onRevalidate, locale }: {
  report: ValidationReportData;
  scope: ValidationScope;
  packageId?: string;
  environment?: string;
  effectiveEnvironment?: string;
  target?: string;
  resourceRoute?: ResourceRoute;
  onOpenResource?: () => void;
  onRevalidate(): void;
  locale: UiLocale;
}) {
  const [severity, setSeverity] = useState('all');
  const [code, setCode] = useState('');
  const [source, setSource] = useState('');
  const [resource, setResource] = useState('');
  const [search, setSearch] = useState('');
  const [copyNotice, setCopyNotice] = useState('');
  const [copyError, setCopyError] = useState('');
  const pass = report.overallStatus === 'PASS' && report.complete && report.exitCode === 0;
  const status = report.overallStatus === 'INVALID' || report.exitCode === 2 ? 'INVALID' : pass ? 'PASS' : 'PARTIAL';
  const diagnostics = report.diagnostics || [];
  const matches = (item: ValidationDiagnostic) => {
    const query = search.trim().toLocaleLowerCase();
    return (severity === 'all' || item.severity.toUpperCase() === severity)
      && (!code.trim() || field(item.code).toLocaleLowerCase().includes(code.trim().toLocaleLowerCase()))
      && (!source.trim() || sourceOf(item).toLocaleLowerCase().includes(source.trim().toLocaleLowerCase()))
      && (!resource.trim() || resourceOf(item).toLocaleLowerCase().includes(resource.trim().toLocaleLowerCase()))
      && (!query || [item.code, item.severity, item.message, item.field, item.suggestion, sourceOf(item), resourceOf(item)]
        .some(value => field(value).toLocaleLowerCase().includes(query)));
  };
  const filtered = useMemo(() => diagnostics.filter(matches), [report, severity, code, source, resource, search]);
  const activeFilters = Boolean(severity !== 'all' || code || source || resource || search);
  const visibleSections = activeFilters
    ? report.sections.map(section => filteredSection(section, matches)).filter((item): item is ValidationSection => item !== null)
    : report.sections;
  const resourceDiagnostic = resourceRoute && diagnostics.some(item => {
    const candidate = resourceOf(item);
    return candidate && (candidate === resourceRoute.resourceId || candidate === resourceRoute.logicalId);
  });
  const serverContext = Object.entries(report.context || {}).filter(([key, value]) =>
    ['packageId', 'environment', 'revision', 'packageRevision', 'manifestRevision', 'sourceRevision', 'runtimeConfigRevision', 'configurationRevision'].includes(key)
    && ['string', 'number'].includes(typeof value));

  async function copyReport() {
    setCopyNotice(''); setCopyError('');
    try {
      await navigator.clipboard.writeText(safeValidationReportCopy(report));
      setCopyNotice(text('Safe report copied.', '已複製安全報告。', locale));
    } catch {
      setCopyError(text('Clipboard access is unavailable.', '無法使用剪貼簿。', locale));
    }
  }

  return <section className="att-validation-report" aria-labelledby="att-validation-report-heading">
    <header className="att-validation-report-heading">
      <div><p className="att-eyebrow">{text('Result of last validation', '最近一次驗證結果', locale)}</p>
        <h2 id="att-validation-report-heading">{text('Validation report', '驗證報告', locale)}</h2></div>
      <div className="att-validation-report-actions">
        {resourceRoute && resourceDiagnostic && onOpenResource && <button className="button button-quiet" type="button" onClick={onOpenResource}>{text('Open matching resource', '開啟相符資源', locale)}</button>}
        <button className="button button-quiet" type="button" onClick={() => void copyReport()}>{text('Copy safe report', '複製安全報告', locale)}</button>
        <button className="button button-primary" type="button" onClick={onRevalidate}>{text('Revalidate', '重新驗證', locale)}</button>
      </div>
    </header>
    <div className={`att-validation-status is-${status.toLowerCase()}`} role="status" aria-live="polite">
      <strong>{status}</strong>
      <span>{report.complete ? text('Complete report', '完整報告', locale) : text('Incomplete report — review skipped or truncated checks', '報告不完整 — 請檢查略過或截斷的檢查', locale)}</span>
    </div>
    <dl className="att-validation-summary">
      <div><dt>{text('Scope', '範圍', locale)}</dt><dd>{scope}</dd></div>
      <div><dt>{text('Package', '套件', locale)}</dt><dd>{packageId || text('Global Server Runtime Config', '全域 Server 執行階段設定', locale)}</dd></div>
      {packageId && <div><dt>{text('Environment', '環境', locale)}</dt><dd>{environment || `${text('Package default', '套件預設值', locale)}${effectiveEnvironment ? ` (${effectiveEnvironment})` : ''}`}</dd></div>}
      {target && <div><dt>{text('Target', '目標', locale)}</dt><dd>{target}</dd></div>}
      <div><dt>{text('Server request ID', 'Server 請求 ID', locale)}</dt><dd>{report.requestId || text('Not supplied', '未提供', locale)}</dd></div>
      <div><dt>{text('Sections: pass / fail / warn / skip', '區段：通過 / 失敗 / 警告 / 略過', locale)}</dt><dd>{`${report.counts.pass} / ${report.counts.fail} / ${report.counts.warn} / ${report.counts.skip}`}</dd></div>
      <div><dt>{text('Diagnostics: errors / warnings', '診斷：錯誤 / 警告', locale)}</dt><dd>{`${report.counts.errors} / ${report.counts.warnings}`}</dd></div>
      {serverContext.map(([key, value]) => <div key={key}><dt>{text('Server context', 'Server context', locale)} · {key}</dt><dd>{safeDiagnosticText(String(value))}</dd></div>)}
    </dl>
    {!report.complete && <p className="att-validation-incomplete" role="alert">{text('This report is PARTIAL or incomplete and must not be treated as a valid result.', '此報告為 PARTIAL 或不完整，不可視為驗證通過。', locale)}</p>}
    {copyNotice && <p role="status">{copyNotice}</p>}{copyError && <p role="alert">{copyError}</p>}
    <section className="att-validation-sections" aria-label={text('Hierarchical validation sections', '階層式驗證區段', locale)}>
      <h3>{text('Checks', '檢查項目', locale)}</h3>
      {visibleSections.length ? <SectionTree sections={visibleSections} locale={locale} /> : <p>{text('No sections match these filters.', '沒有符合篩選條件的區段。', locale)}</p>}
    </section>
    <section className="att-validation-diagnostics" aria-label={text('Validation diagnostics', '驗證診斷', locale)}>
      <h3>{text('Diagnostics', '診斷', locale)} <small>({filtered.length})</small></h3>
      <div className="att-validation-filters">
        <label>{text('Severity', '嚴重程度', locale)}<select aria-label={text('Filter diagnostics by severity', '依嚴重程度篩選診斷', locale)} value={severity} onChange={event => setSeverity(event.currentTarget.value)}>
          <option value="all">{text('All severities', '所有嚴重程度', locale)}</option><option value="ERROR">ERROR</option><option value="WARN">WARN</option><option value="WARNING">WARNING</option><option value="INFO">INFO</option>
        </select></label>
        <label>{text('Code', '代碼', locale)}<input aria-label={text('Filter diagnostics by code', '依代碼篩選診斷', locale)} value={code} onChange={event => setCode(event.currentTarget.value)} /></label>
        <label>{text('Source', '來源', locale)}<input aria-label={text('Filter diagnostics by source', '依來源篩選診斷', locale)} value={source} onChange={event => setSource(event.currentTarget.value)} /></label>
        <label>{text('Resource', '資源', locale)}<input aria-label={text('Filter diagnostics by resource', '依資源篩選診斷', locale)} value={resource} onChange={event => setResource(event.currentTarget.value)} /></label>
        <label>{text('Search diagnostics', '搜尋診斷', locale)}<input type="search" aria-label={text('Search diagnostics', '搜尋診斷', locale)} value={search} onChange={event => setSearch(event.currentTarget.value)} /></label>
      </div>
      {filtered.length ? <ul className="att-validation-diagnostic-list">{filtered.map((diagnostic, index) => <li key={`${diagnostic.code}:${index}`}>
        <strong>{safeDiagnosticText(diagnostic.severity)} · {safeDiagnosticText(diagnostic.code)}</strong><p>{safeDiagnosticText(diagnostic.message)}</p>
        {diagnostic.field && <small>{text('Field', '欄位', locale)}: {safeDiagnosticText(diagnostic.field)}</small>}
        {sourceOf(diagnostic) && <small>{text('Source', '來源', locale)}: {safeDiagnosticText(sourceOf(diagnostic))}</small>}
        {resourceOf(diagnostic) && <small>{text('Resource', '資源', locale)}: {safeDiagnosticText(resourceOf(diagnostic))}</small>}
        {diagnostic.suggestion && <small>{text('Suggestion', '建議', locale)}: {safeDiagnosticText(diagnostic.suggestion)}</small>}
      </li>)}</ul> : <p>{text('No diagnostics match these filters.', '沒有符合篩選條件的診斷。', locale)}</p>}
    </section>
  </section>;
}
